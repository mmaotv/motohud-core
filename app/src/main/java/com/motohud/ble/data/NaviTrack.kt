package com.motohud.ble.data

import com.motohud.ble.MotoHudApp
import com.motohud.ble.navi.Geo
import com.motohud.ble.navi.LocationFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 导航轨迹记录器。
 *
 * ## 为什么需要
 * 历史记录原先只有「起点坐标 / 终点坐标 / 距离」，没有任何中间点，
 * 所以点开一条历史除了看文字什么都画不出来。要做出「高德那种轨迹回放」，
 * 必须有真实的行驶路径 —— 这个类就是那条路径的来源。
 *
 * ## 采样策略（性能与精度平衡）
 *  - 数据源：导航引擎每秒一帧的 [com.motohud.ble.navi.NaviEngine.NaviState]，不额外开定位；
 *  - 落点门槛：距上一点 ≥ 10m **或** 距上一点 ≥ 5s（停车时不灌点）；
 *  - 批量写库：内存攒 10 个点一次性 `insertAll`，避免 1Hz 单条写放大；
 *  - 结束处理：Douglas-Peucker 简化（ε=5m）→ 编码成 `lat,lng;…` → 写回
 *    [NaviHistoryEntity.trackPolyline] → 删除原始点。
 *
 * ## 兜底
 * 进程被杀时最多丢当前缓冲（10 个点）。[NaviHistory.finish] 是同步拿 id，
 * 这里同样用 `runBlocking` 收尾，保证"导航一结束，详情页立刻能看"。
 */
object NaviTrack {

    /** 落点最小位移（米） */
    private const val MIN_STEP_M = 10f

    /** 落点最大时间间隔（毫秒）：慢速/静止时也留一点时间维度 */
    private const val MAX_GAP_MS = 5_000L

    /** Douglas-Peucker 简化阈值（米） */
    private const val SIMPLIFY_EPSILON_M = 5.0

    /** 单次导航最多保留点数（防异常长时间导航把内存撑爆） */
    private const val MAX_POINTS = 4000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val buffer = ArrayList<NaviTrackPointEntity>(16)
    private var historyId = 0L
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var lastTs = 0L
    private var total = 0
    private var app: MotoHudApp? = null

    /** 开始记录一条轨迹 */
    fun begin(app: MotoHudApp, id: Long) {
        this.app = app
        synchronized(buffer) {
            buffer.clear()
            historyId = id
            lastLat = 0.0
            lastLng = 0.0
            lastTs = 0L
            total = 0
        }
    }

    /**
     * 喂一个定位点。调用方可以 1Hz 无脑调用，门槛判断在这里做。
     */
    fun feed(id: Long, lat: Double, lng: Double, speedKmh: Float, ts: Long) {
        if (id <= 0) return
        if (lat == 0.0 && lng == 0.0) return
        val toFlush: List<NaviTrackPointEntity>?
        synchronized(buffer) {
            if (historyId != id) return
            if (total >= MAX_POINTS) return
            if (lastLat != 0.0 || lastLng != 0.0) {
                val moved = LocationFilter.distanceM(lastLat, lastLng, lat, lng)
                val elapsed = ts - lastTs
                if (moved < MIN_STEP_M && elapsed < MAX_GAP_MS) return
            }
            lastLat = lat
            lastLng = lng
            lastTs = ts
            total++
            buffer.add(
                NaviTrackPointEntity(
                    historyId = id,
                    lat = lat,
                    lng = lng,
                    ts = ts,
                    speedKmh = if (speedKmh.isFinite() && speedKmh > 0f) speedKmh else 0f
                )
            )
            toFlush = if (buffer.size >= 10) ArrayList(buffer).also { buffer.clear() } else null
        }
        if (toFlush != null) {
            val dao = app?.naviTrackPointDao
            if (dao != null) scope.launch { runCatching { dao.insertAll(toFlush) } }
        }
    }

    /** 结束并压缩写回 */
    fun finish(app: MotoHudApp, id: Long?) {
        if (id == null || id <= 0) return
        val pending: List<NaviTrackPointEntity>
        synchronized(buffer) {
            pending = ArrayList(buffer)
            buffer.clear()
            if (historyId == id) {
                historyId = 0L
                lastLat = 0.0
                lastLng = 0.0
                lastTs = 0L
            }
        }
        runCatching {
            runBlocking {
                withTimeoutOrNull(2500L) {
                    val dao = app.naviTrackPointDao
                    if (pending.isNotEmpty()) dao.insertAll(pending)
                    val raw = dao.listByHistory(id)
                    val cleaned = denoise(raw)
                    val points = cleaned.map { it.lat to it.lng }
                    val simplified = if (points.size > 2) {
                        douglasPeucker(points, SIMPLIFY_EPSILON_M)
                    } else {
                        points
                    }
                    val moving = raw.filter { it.speedKmh >= 2f }
                    val avg = if (moving.isNotEmpty()) moving.map { it.speedKmh.toDouble() }.average() else null
                    val max = raw.maxOfOrNull { it.speedKmh }?.toDouble()
                    app.naviHistoryDao.updateTrack(
                        id = id,
                        polyline = if (simplified.isEmpty()) null else encode(simplified),
                        avgSpeed = avg,
                        maxSpeed = max
                    )
                    dao.deleteByHistory(id)
                    dao.deleteOrphans()
                }
            }
        }
    }

    // ---------- 编码 / 解码 ----------

    /** 编码：`lat,lng;lat,lng;…`（6 位小数，约 16 字符/点） */
    fun encode(points: List<Pair<Double, Double>>): String =
        points.joinToString(";") { (lat, lng) ->
            String.format(java.util.Locale.US, "%.6f,%.6f", lat, lng)
        }

    /** 解码；格式不合法时返回空列表（详情页走"未记录轨迹"兜底） */
    fun decode(raw: String?): List<Pair<Double, Double>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { seg ->
            val p = seg.split(',')
            if (p.size != 2) return@mapNotNull null
            val lat = p[0].toDoubleOrNull() ?: return@mapNotNull null
            val lng = p[1].toDoubleOrNull() ?: return@mapNotNull null
            lat to lng
        }
    }

    /**
     * 源头去噪：剔除明显 GPS 漂移的「瞬移」点。
     *
     * 规则：某点与前一点、与后一点间的瞬时速度都远超合理上限（如 70 m/s ≈ 252km/h），
     * 但「跳过该点、前后直连」的速度正常 → 判定为漂移，丢弃。
     * 只动明显异常点；正常快速行驶（直线、匀速）不会被误删。
     */
    private fun denoise(
        raw: List<NaviTrackPointEntity>,
        maxSpeedMps: Double = 70.0
    ): List<NaviTrackPointEntity> {
        if (raw.size <= 2) return raw
        val keep = BooleanArray(raw.size) { true }
        for (i in 1 until raw.size - 1) {
            val prev = raw[i - 1]
            val cur = raw[i]
            val next = raw[i + 1]
            val dtPrev = (cur.ts - prev.ts) / 1000.0
            val dtNext = (next.ts - cur.ts) / 1000.0
            if (dtPrev <= 0 || dtNext <= 0) continue
            val vPrev = Geo.distanceM(prev.lat, prev.lng, cur.lat, cur.lng) / dtPrev
            val vNext = Geo.distanceM(cur.lat, cur.lng, next.lat, next.lng) / dtNext
            if (vPrev > maxSpeedMps && vNext > maxSpeedMps) {
                val vDirect =
                    Geo.distanceM(prev.lat, prev.lng, next.lat, next.lng) / (dtPrev + dtNext)
                if (vDirect < maxSpeedMps) keep[i] = false
            }
        }
        return raw.filterIndexed { i, _ -> keep[i] }
    }

    // ---------- Douglas-Peucker ----------

    /**
     * 折线简化。轨迹动辄上千点，直接存会让详情页解码+绘制变慢，
     * 而 5m 阈值内的偏差点肉眼完全看不出来（骑行 GPS 精度本身就在 5~10m）。
     */
    fun douglasPeucker(
        points: List<Pair<Double, Double>>,
        epsilonM: Double
    ): List<Pair<Double, Double>> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            var maxDist = 0.0
            var index = -1
            for (i in start + 1 until end) {
                val d = perpendicularDistance(points[i], points[start], points[end])
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (index > 0 && maxDist > epsilonM) {
                keep[index] = true
                stack.addLast(intArrayOf(start, index))
                stack.addLast(intArrayOf(index, end))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** 点到线段距离（米）；用局部等距近似，几十公里内误差可忽略 */
    private fun perpendicularDistance(
        p: Pair<Double, Double>,
        a: Pair<Double, Double>,
        b: Pair<Double, Double>
    ): Double {
        val latScale = com.motohud.ble.navi.Geo.M_PER_DEG_LAT
        val lngScale = com.motohud.ble.navi.Geo.mPerDegLng(a.first)
        val px = (p.second - a.second) * lngScale
        val py = (p.first - a.first) * latScale
        val bx = (b.second - a.second) * lngScale
        val by = (b.first - a.first) * latScale
        val len2 = bx * bx + by * by
        if (len2 < 1e-6) return kotlin.math.sqrt(px * px + py * py)
        val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - bx * t
        val dy = py - by * t
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
