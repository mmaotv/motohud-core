package com.motohud.ble.navi

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.utils.SpatialRelationUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * 导航引擎接口：产生与高德导航 SDK NaviInfo 等价的状态快照，供手机 UI 与
 * 车机投屏（NaviCommand → BLE）共用。
 */
interface NaviEngine {

    /** 导航状态快照（等价 NaviInfo + 位置信息） */
    data class NaviState(
        val running: Boolean = false,
        val arrived: Boolean = false,
        /** 当前位置（GCJ02） */
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        /** 目的地 */
        val destName: String = "",
        val destLatitude: Double = 0.0,
        val destLongitude: Double = 0.0,
        /** 全程剩余（米） */
        val pathRemain: Int = 0,
        /** 下一转弯剩余（米） */
        val stepRemain: Int = 0,
        /** 当前步骤索引（无路线时为 -1） */
        val stepIndex: Int = -1,
        /**
         * 即将执行的转向所在步骤索引（无路线或已到终点为 -1）。
         *
         * AMap 把「直行引道」与「转弯」拆成相邻两步：steps[stepIndex] 是正在走的路段，
         * 真正的转弯动作在 steps[stepIndex+1]；若当前段本身就是一个转弯（短转弯段），
         * 则等于 stepIndex。播报「前方 X 米右转」、banner 图标/文字都该读这一段，
         * 读 stepIndex 会把整段直行引道当成直行，导致提前预告全部消失。
         */
        val maneuverIndex: Int = -1,
        /** 即将转向的引导语（如"右转进入望京路"）；无转弯时退回当前段引导语 */
        val stepInstruction: String = "",
        /** 高德 IconType 数值 */
        val iconType: Int = IconType.STRAIGHT,
        /** 当前航向（度，0=正北，沿路线方向） */
        val bearing: Float = 0f,
        /** 实时速度 km/h（GPS 优先，无 GPS 时为模拟速度） */
        val speedKmh: Float = DEFAULT_SPEED_KMH,
        /** 预计剩余秒数 */
        val etaSeconds: Int = 0,
        /** GPS 是否活跃（false=沿路线推算模式） */
        val gpsActive: Boolean = false,
        /** 已行驶距离（米） */
        val traveled: Int = 0,
        /** 是否基于真实规划路线（false=直线演示模式） */
        val onRoute: Boolean = false,
        /** 当前定位水平精度（米，0=未知） */
        val accuracy: Float = 0f,
        /** 定位来源类型（高德 locationType：1=GPS 6=网络 8=融合 0=推算） */
        val locType: Int = 0,
        /** 本帧是否来自卫星定位（决定"信号弱"提示） */
        val fromSatellite: Boolean = false,
        /**
         * GPS 信号质量（高德定位 SDK 判定）：1=好 0=差 -1=未知。
         *
         * 高德的判断综合了卫星数、信噪比与速度一致性，比单看 accuracy 更靠谱 ——
         * 网络定位经常报"±5 米"其实是基站粗定位。系统 GPS 兜底给不出这个值，固定 -1。
         */
        val gpsAccuracyStatus: Int = -1,
        /** 前方剩余路段上的拥堵里程（米，TMC 状态≥3 拥堵/严重拥堵累加） */
        val congestionAheadM: Float = 0f
    )

    val state: StateFlow<NaviState>

    /**
     * 开始导航。
     * @param route 真实规划路线（高德路线规划结果）；null 时退化为直线演示模式
     */
    fun start(
        destName: String,
        destLat: Double,
        destLng: Double,
        startHint: Pair<Double, Double>? = null,
        route: RouteData? = null
    )

    /** 手动指定转向图标（HUD 演示/测试按钮），立即生效一帧 */
    fun overrideIcon(iconType: Int)

    fun setSpeed(kmh: Float)

    /** 停止导航（不发 FINISH；调用方决定发 FINISH 还是 NO） */
    fun stop()

    fun release()

    /** 步骤切换回调（TTS 播报） */
    var onStepChanged: ((String) -> Unit)?

    /** 偏航回调：开错路、持续偏离路线时触发，携带当前坐标供上层重新规划 */
    var onRerouteRequest: ((Double, Double) -> Unit)?

    companion object {
        const val DEFAULT_SPEED_KMH = 25f
        const val MIN_SPEED_KMH = 5f
        const val MAX_SPEED_KMH = 120f
        const val TICK_MS = 1000L
        const val ARRIVE_THRESHOLD_M = 25.0
        const val GPS_STALE_MS = 3_000L

        /** 垂足投影的搜索窗口（点数，前后各这么多）。与最近点索引的窗口保持一致 */
        const val PROJECT_WINDOW = 200

        /** 偏航判定：偏移超过该米数（吸附前原始偏移）才视为开错路 */
        const val OFFROUTE_M = 90.0
        /** 偏航判定：定位精度需优于该米数，避免噪点触发重算 */
        const val OFFROUTE_ACC_M = 60f
        /** 偏航需持续该毫秒数才重算，过滤瞬时 GPS 跳变 */
        const val OFFROUTE_SUSTAIN_MS = 8_000L
        /** 两次偏航重算的最小间隔，避免反复重算 */
        const val REROUTE_COOLDOWN_MS = 25_000L

        /** 默认起点（无定位权限时用于室内演示） */
        val DEFAULT_START = 39.9087 to 116.3975
    }
}

/** 路线单步骤（自高德搜索 SDK DriveStep 转换，GCJ02 坐标） */
data class RouteStep(
    /** 完整引导语，如"沿望京路行驶 500 米后右转" */
    val instruction: String,
    /** 动作文本，如"右转" */
    val action: String,
    /**
     * 辅助动作（高德 `DriveStep.getAssistantAction()`）。
     *
     * 高德把一些**不影响转向但需要提醒**的信息放在这里，例如「进入匝道」
     * 「通过人行横道」。长期没被读取，导致这类提示一条都没播过。
     */
    val assistantAction: String = "",
    /** 方向文本，如"右" */
    val orientation: String,
    /** 道路名 */
    val road: String,
    /** 本步骤距离（米） */
    val distanceM: Float,
    /** 本步骤几何（lat, lng） */
    val polyline: List<Pair<Double, Double>>,
    /** 本步骤拥堵里程（米，TMC 状态≥3）；用于"前方拥堵"统计 */
    val congestedM: Float = 0f
)

/** 规划路线（自高德搜索 SDK DrivePath 转换） */
data class RouteData(
    val distanceM: Float,
    val durationS: Float,
    val steps: List<RouteStep>,
    /** 规划模式描述，如「不走高速·避拥堵」 */
    val modeLabel: String = "",
    /** 高德返回的方案名，如「速度最快」；界面上的候选路线标题 */
    val label: String = "",
    /** 过路费（元） */
    val tolls: Float = 0f,
    /** 红绿灯数量 */
    val trafficLights: Int = 0,
    /**
     * 限行标记（高德 `DrivePath.getRestriction()`）。
     *
     * 0 = 不限行；非 0 表示这条路线**会经过限行区域**（外地牌照、尾号限行、货车限行等）。
     * 这个字段一直被忽略 —— 于是外地游客骑进限行区也收不到任何提示。
     * 具体含义由高德服务端判定，SDK 没有给出枚举常量，所以这里只区分"有 / 无"。
     */
    val restriction: Int = 0,
    /** 收费路段里程（米） */
    val tollDistanceM: Float = 0f
)

/** 高德 IconType 常用值（com.amap.api.navi.enums.IconType） */
object IconType {
    const val NONE = 0
    const val LEFT = 2
    const val RIGHT = 3
    const val LEFT_FRONT = 4
    const val RIGHT_FRONT = 5
    const val LEFT_BACK = 6
    const val RIGHT_BACK = 7
    const val LEFT_TURN_AROUND = 8
    const val STRAIGHT = 9
    const val ARRIVED_WAYPOINT = 10
    const val ENTER_ROUNDABOUT = 11
    const val OUT_ROUNDABOUT = 12
    const val ARRIVED_DESTINATION = 15
    const val U_TURN_RIGHT = 19
    const val ENTRY_RING_LEFT = 21
    const val MERGE_LEFT = 65
    const val MERGE_RIGHT = 66
}

/** 路线累计点：位置 + 距起点累计距离 */
private data class CumPoint(val lat: Double, val lng: Double, val dist: Double)

/**
 * 路线引导引擎。
 *
 * 复刻原 App 投屏导航数据流（原 App 由高德导航 SDK onNaviInfoUpdate 驱动）：
 *   1. 每秒 tick，优先采用真实 GPS 位置；GPS 失效（室内/隧道/测试）时沿路线航位推算；
 *   2. 将 GPS 位置投影到路线折线，计算 已行驶 / 全程剩余 / 下一转弯剩余；
 *   3. 转向图标由当前步骤动作经 [IconMapper] 映射为高德 IconType（与车机协议一致）；
 *   4. 到达终点（< 25m）触发 arrived，上层发 FINISH :::；
 *   5. 无规划路线时退化为直线演示模式（测试/HUD 演示用）。
 */
class RouteNaviEngine(
    private val context: android.content.Context?,
    private val locationManager: LocationManager?
) : NaviEngine {

    private val _state = MutableStateFlow(NaviEngine.NaviState())
    override val state: StateFlow<NaviEngine.NaviState> = _state.asStateFlow()

    override var onStepChanged: ((String) -> Unit)? = null

    override var onRerouteRequest: ((Double, Double) -> Unit)? = null

    /** 偏航检测：持续偏离路线的起始时刻与最近一次重算冷却 */
    private var offRouteSinceMs = 0L
    private var lastRerouteMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastGpsTime = 0L // 0 = 尚无 GPS 更新，首帧走推算
    private var manualIcon: Int? = null
    private var curLat = 0.0
    private var curLng = 0.0
    private var curBearing = 0f

    /** 高德连续定位（GCJ02 + 多源融合）；无 Key/未授权时为 null */
    private val locator = context?.let { com.motohud.ble.amap.AmapLocator(it) }

    /** 轨迹滤波：压掉精度差、跳点与静止抖动 */
    private val filter = LocationFilter()

    private var lastAccuracy = 0f
    private var lastLocType = 0
    private var lastFromSatellite = false
    private var lastGpsAccuracyStatus = -1
    private var locatorActive = false

    /** 最近一帧高德定位的时间：用于决定系统定位是否作为兜底 */
    private var lastAmapFixMs = 0L

    // ---------- 路线数据 ----------
    private var cumulative: List<CumPoint> = emptyList()
    private var stepEndDist: List<Double> = emptyList()   // 每步结束时的累计距离
    private var steps: List<RouteStep> = emptyList()
    private var totalDist = 0.0

    /** 与 [cumulative] 同序的投影用点列（[buildRoute] 建一次，供 SDK 求垂足） */
    private var routeLatLng: List<LatLng> = emptyList()

    /** 高德返回的这条路线规划总时长（秒），用于推算剩余时间 */
    private var routeDurationS = 0f
    private var lastIndex = 0

    /** 最近一次使用的规划路线（HUD/导航页共享引擎时用于重绘） */
    var lastRoute: RouteData? = null
        private set

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!running) return
            // 高德定位 5 秒内有输出时，系统定位只当兜底不用（避免两套定位打架）
            if (System.currentTimeMillis() - lastAmapFixMs < 5_000L) return
            // 关键：系统 LocationManager 返回 WGS84，而地图与规划路线都是 GCJ02。
            // 不做转换会形成 300~700m 的恒定偏移，正是"导航一直漂"的根因。
            // 纠偏走高德官方 CoordinateConverter（见 CoordFix），不再自算公式。
            val (gLat, gLng) = com.motohud.ble.amap.CoordFix.gpsToGcj(
                location.latitude, location.longitude
            )
            acceptFix(
                lat = gLat,
                lng = gLng,
                accuracy = if (location.hasAccuracy()) location.accuracy else 0f,
                bearing = if (location.hasBearing()) location.bearing else Float.NaN,
                speedMps = if (location.hasSpeed()) location.speed else 0f,
                locType = if (location.provider == LocationManager.GPS_PROVIDER) 1 else 6,
                fromSatellite = location.provider == LocationManager.GPS_PROVIDER
                // 系统定位没有高德的信号质量判定，保持默认 -1
            )
        }

        @Deprecated("旧版回调保留兼容")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * 统一的定位入口：无论来自高德定位还是系统 GPS，都先过 [LocationFilter]。
     * 被滤掉的帧不会刷新 lastGpsTime，于是引擎自动转入沿路线推算，界面不会乱跳。
     */
    private fun acceptFix(
        lat: Double,
        lng: Double,
        accuracy: Float,
        bearing: Float,
        speedMps: Float,
        locType: Int,
        fromSatellite: Boolean,
        gpsAccuracyStatus: Int = -1
    ) {
        if (!running) return
        val now = System.currentTimeMillis()
        // 网络/基站定位（locType=6）在城市里误差常达几百米，精度字段却只报几十米，
        // 一旦采信就会把车标甩到隔壁街区甚至隔壁城市。这里单独收紧门限。
        if (locType == 6 && accuracy > 35f) return
        val out = filter.onFix(
            rawLat = lat,
            rawLng = lng,
            rawAccuracy = accuracy,
            rawBearing = if (bearing.isFinite()) bearing else 0f,
            rawSpeedMps = speedMps,
            nowMs = now
        ) ?: return

        lastGpsTime = now
        curLat = out.lat
        curLng = out.lng
        // 低速时 GPS 航向不可靠，不采信（否则车头会一直转）
        if (out.speedMps > 1.2f && out.bearing.isFinite()) curBearing = out.bearing
        lastAccuracy = if (out.accuracy > 0f) out.accuracy else accuracy
        lastLocType = locType
        lastFromSatellite = fromSatellite
        lastGpsAccuracyStatus = gpsAccuracyStatus

        _state.value = _state.value.copy(
            latitude = curLat,
            longitude = curLng,
            bearing = curBearing,
            gpsActive = true,
            speedKmh = if (out.speedMps > 0.2f) out.speedMps * 3.6f else 0f,
            accuracy = lastAccuracy,
            locType = locType,
            fromSatellite = fromSatellite,
            gpsAccuracyStatus = gpsAccuracyStatus
        )
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            stepOnce()
            handler.postDelayed(this, NaviEngine.TICK_MS)
        }
    }

    // ---------- 启动 / 停止 ----------

    override fun start(
        destName: String,
        destLat: Double,
        destLng: Double,
        startHint: Pair<Double, Double>?,
        route: RouteData?
    ) {
        // 重规划（偏航/换线）会再次调用 start：先清掉上一条 tick 循环，否则会叠加成双倍推演
        handler.removeCallbacks(tick)
        stopLocationUpdates()
        manualIcon = null
        lastIndex = 0
        offRouteSinceMs = 0L
        lastRerouteMs = 0L
        // 新一次导航必须清空滤波与定位历史，否则会沿用上一次的平滑中心
        filter.reset()
        lastGpsTime = 0L
        lastAmapFixMs = 0L
        lastAccuracy = 0f
        lastLocType = 0
        lastFromSatellite = false
        lastGpsAccuracyStatus = -1

        val (sLat, sLng) = startHint ?: lastKnownLocation() ?: NaviEngine.DEFAULT_START
        curLat = sLat
        curLng = sLng
        // 用起点给滤波器打底：第一帧定位也要过跳点校验，
        // 否则冷启动时一帧网络定位就能把车标甩走几百公里。
        filter.seed(sLat, sLng, System.currentTimeMillis())

        // 记住高德给的规划总时长，ETA 按剩余里程等比推算。
        // 车机常拿不到 GPS 速度，以前用 5km/h 兜底，11 公里会算成"2 小时 17 分"。
        routeDurationS = route?.durationS?.takeIf { it > 30f } ?: 0f

        if (route != null && route.steps.isNotEmpty() &&
            route.steps.any { it.polyline.size >= 2 }
        ) {
            lastRoute = route
            buildRoute(route)
        } else {
            lastRoute = null
            cumulative = emptyList()
            routeLatLng = emptyList()
            stepEndDist = emptyList()
            steps = emptyList()
            totalDist = 0.0
        }

        val (brg, dist0) = bearingAndDistance(curLat, curLng, destLat, destLng)
        curBearing = brg
        // lastGpsTime 保持 0：首帧即进入推算，直到收到真实 GPS 更新
        running = true
        _state.value = NaviEngine.NaviState(
            running = true,
            latitude = curLat,
            longitude = curLng,
            destName = destName,
            destLatitude = destLat,
            destLongitude = destLng,
            bearing = curBearing,
            pathRemain = dist0.toInt(),
            stepRemain = dist0.toInt(),
            etaSeconds = etaSeconds(dist0),
            onRoute = cumulative.isNotEmpty()
        )
        requestLocationUpdates()
        handler.post(tick)
    }

    override fun overrideIcon(iconType: Int) {
        manualIcon = iconType
        _state.value = _state.value.copy(iconType = iconType)
    }

    override fun setSpeed(kmh: Float) {
        val v = kmh.coerceIn(NaviEngine.MIN_SPEED_KMH, NaviEngine.MAX_SPEED_KMH)
        _state.value = _state.value.copy(speedKmh = v)
    }

    override fun stop() {
        running = false
        manualIcon = null
        handler.removeCallbacks(tick)
        stopLocationUpdates()
        _state.value = _state.value.copy(running = false)
    }

    override fun release() {
        stop()
        onStepChanged = null
    }

    // ---------- 路线构建 ----------

    /** 展平所有步骤折线为累计点列，并记录每步结束的累计距离 */
    private fun buildRoute(route: RouteData) {
        steps = route.steps
        val points = ArrayList<CumPoint>(1024)
        var acc = 0.0
        var prevLat = Double.NaN
        var prevLng = Double.NaN
        val ends = ArrayList<Double>(route.steps.size)
        for (step in route.steps) {
            for ((lat, lng) in step.polyline) {
                if (lat == prevLat && lng == prevLng) continue
                if (!prevLat.isNaN()) {
                    val (_, d) = bearingAndDistance(prevLat, prevLng, lat, lng)
                    acc += d
                }
                points.add(CumPoint(lat, lng, acc))
                prevLat = lat
                prevLng = lng
            }
            ends.add(acc)
        }
        cumulative = points
        stepEndDist = ends
        // 投影用的 LatLng 视图：与 cumulative 同序，算路后建一次。
        // 每个 tick 都新建 List<LatLng> 会产生成百上千个小对象，GC 抖动直接反映成掉帧。
        routeLatLng = points.map { LatLng(it.lat, it.lng) }
        // 以路线几何距离校正总里程（折线累加通常略大于官方口径）
        totalDist = if (points.isNotEmpty()) points.last().dist else route.distanceM.toDouble()
    }

    // ---------- 每 tick 推演 ----------

    private fun stepOnce() {
        val s = _state.value
        if (!s.running) return

        val gpsFresh = System.currentTimeMillis() - lastGpsTime < NaviEngine.GPS_STALE_MS
        val stepM = s.speedKmh * 1000f / 3600f * (NaviEngine.TICK_MS / 1000f)

        if (cumulative.isNotEmpty()) {
            // ---------- 路线模式 ----------
            if (gpsFresh) {
                lastIndex = nearestIndex(curLat, curLng, lastIndex)
                // 车标吸附路线：导航软件一律把车标画在路上。
                // 高德算路会把起点吸到最近可行驶道路，而 GPS 原始点常在路侧几十米外，
                // 不吸附就会看到"车标在楼里 / 在路外"——这就是用户感知到的"漂移"。
                //
                // 吸附目标用**折线垂足**而不是最近顶点：算路的折线点间隔 10~50m 不等，
                // 取顶点会让车标在相邻顶点之间来回弹（视觉抖动），而且"到顶点的距离"
                // 恒大于等于"到路的距离"，长直路段上会把 8m 的偏移算成 20m，
                // 白白触发一次 0.75 的吸附。垂足由 SDK 的
                // SpatialRelationUtil.calShortestDistancePoint 给出（见 projectOnRoute）。
                val anchor = projectOnRoute(curLat, curLng, lastIndex) ?: vertexAnchor(lastIndex)
                val off = LocationFilter.distanceM(curLat, curLng, anchor.lat, anchor.lng)
                // 偏航判定必须在吸附前用原始偏移：吸附会掩盖偏移，否则开错路也发现不了。
                // 精度尚可且持续偏离 >90m 超过 8s，视为开错路，回调上层重算。
                val offRoute = off > NaviEngine.OFFROUTE_M && lastAccuracy <= NaviEngine.OFFROUTE_ACC_M
                var snap = 0f
                if (offRoute) {
                    trackOffRoute()
                } else {
                    offRouteSinceMs = 0L
                    snap = when {
                        off > 60f -> if (lastAccuracy > 25f) 0.65f else 0f // 偏离过大：精度差才拉回
                        off > 15f -> 0.75f
                        off > 4f -> 0.5f
                        else -> 0f
                    }
                    if (snap > 0f) {
                        curLat += (anchor.lat - curLat) * snap
                        curLng += (anchor.lng - curLng) * snap
                    }
                }
                if (com.motohud.ble.BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "NaviEngine",
                        "idx=$lastIndex off=${off.toInt()}m snap=$snap acc=${lastAccuracy.toInt()} " +
                            "anchor=${anchor.tag} cur=($curLat,$curLng) road=(${anchor.lat},${anchor.lng})"
                    )
                }
            } else {
                // GPS 失效：沿路线从当前投影点前进 stepM
                lastIndex = advanceAlongRoute(lastIndex, stepM.toDouble())
                val p = cumulative[lastIndex]
                curLat = p.lat
                curLng = p.lng
            }
            val traveled = cumulative[lastIndex].dist
            val remaining = (totalDist - traveled).coerceAtLeast(0.0)
            val arrived = remaining <= NaviEngine.ARRIVE_THRESHOLD_M

            val (curStep, stepRemain) = currentStep(traveled)
            curBearing = bearingAt(lastIndex)

            // 前方拥堵里程：当前步骤及之后的拥堵分段累加（TMC 状态≥3），用于提示与换线
            val congestionAhead = if (curStep >= 0) steps.drop(curStep).sumOf { it.congestedM.toDouble() }.toInt() else 0

            // 即将转向的那一步：AMap 把直行引道与转弯拆成相邻 step
            // （steps[i]=直行引道、steps[i+1]=转弯），所以正在走的直行段，其"即将转弯"
            // 在 steps[i+1]；若当前段本身就是转弯（短转弯段），就是它自己。
            // banner 图标/文字、提前预告阈值、车机图标都读这一步——之前错用当前段(直行)
            // 当转弯，导致直行引道整段被当直行、1 公里/500 米预告全消失，只剩到路口才报。
            val maneuverIdx = IconMapper.maneuverIndexOf(
                curStep,
                if (curStep >= 0) IconMapper.of(
                    steps[curStep].action, steps[curStep].orientation, steps[curStep].instruction
                ) else IconType.STRAIGHT,
                steps.size
            )
            val maneuverStep = if (maneuverIdx >= 0) steps.getOrNull(maneuverIdx) else null
            val icon = when {
                arrived -> IconType.ARRIVED_DESTINATION
                manualIcon != null -> manualIcon!!
                maneuverStep != null -> IconMapper.of(
                    maneuverStep.action, maneuverStep.orientation, maneuverStep.instruction
                )
                else -> IconType.STRAIGHT
            }

            // 步骤切换 → TTS 播报
            if (!arrived && curStep >= 0 && curStep != s.stepIndex) {
                val ins = steps[curStep].instruction.ifBlank { steps[curStep].action }
                if (ins.isNotBlank()) onStepChanged?.invoke(ins)
            }

            _state.value = s.copy(
                running = !arrived,
                arrived = arrived,
                latitude = curLat,
                longitude = curLng,
                pathRemain = remaining.toInt(),
                stepRemain = stepRemain.toInt(),
                stepIndex = curStep,
                maneuverIndex = maneuverIdx,
                stepInstruction = maneuverStep?.instruction
                    ?: (if (curStep >= 0) steps[curStep].instruction else ""),
                iconType = icon,
                bearing = curBearing,
                etaSeconds = if (arrived) 0 else etaSeconds(remaining),
                gpsActive = gpsFresh,
                traveled = traveled.toInt(),
                speedKmh = s.speedKmh,
                onRoute = true,
                congestionAheadM = congestionAhead.toFloat()
            )

            if (arrived) {
                running = false
                handler.removeCallbacks(tick)
                stopLocationUpdates()
            }
        } else {
            // ---------- 直线演示模式（无规划路线） ----------
            val (brg, dist) = bearingAndDistance(curLat, curLng, s.destLatitude, s.destLongitude)
            if (!gpsFresh && dist > stepM) {
                val (nLat, nLng) = destinationPoint(curLat, curLng, brg, stepM.toDouble())
                curLat = nLat
                curLng = nLng
                curBearing = brg
            }
            val (_, dist2) = bearingAndDistance(curLat, curLng, s.destLatitude, s.destLongitude)
            val arrived = dist2 <= NaviEngine.ARRIVE_THRESHOLD_M
            val icon = when {
                arrived -> IconType.ARRIVED_DESTINATION
                manualIcon != null -> manualIcon!!
                dist2 < 100 -> autoTurnIcon(brg, curBearing)
                else -> IconType.STRAIGHT
            }
            val newTraveled = s.traveled + if (gpsFresh) 0 else stepM.toInt()

            _state.value = s.copy(
                running = !arrived,
                arrived = arrived,
                latitude = curLat,
                longitude = curLng,
                pathRemain = dist2.toInt(),
                stepRemain = dist2.toInt(),
                stepIndex = -1,
                maneuverIndex = -1,
                stepInstruction = "",
                iconType = icon,
                bearing = curBearing,
                etaSeconds = if (arrived) 0 else etaSeconds(dist2),
                gpsActive = gpsFresh,
                traveled = newTraveled
            )
            if (arrived) {
                running = false
                handler.removeCallbacks(tick)
                stopLocationUpdates()
            }
        }
    }

    /** 偏航累计：持续偏离足够久且过了冷却，就向上层请求重新规划 */
    private fun trackOffRoute() {
        val now = System.currentTimeMillis()
        if (offRouteSinceMs == 0L) offRouteSinceMs = now
        else if (now - offRouteSinceMs > NaviEngine.OFFROUTE_SUSTAIN_MS &&
            now - lastRerouteMs > NaviEngine.REROUTE_COOLDOWN_MS
        ) {
            lastRerouteMs = now
            offRouteSinceMs = 0L
            onRerouteRequest?.invoke(curLat, curLng)
        }
    }

    // ---------- 路线几何 ----------

    /** 吸附锚点：折线垂足（`proj`）或最近顶点（`vertex`）。tag 只用于调试日志 */
    private data class Anchor(val lat: Double, val lng: Double, val tag: String)

    private fun vertexAnchor(idx: Int): Anchor {
        val p = cumulative[idx.coerceIn(cumulative.indices)]
        return Anchor(p.lat, p.lng, "vertex")
    }

    /**
     * 把当前位置投影到路线折线上，返回垂足（高德 `SpatialRelationUtil`）。
     *
     * 只在 [nearIdx] 前后各 [PROJECT_WINDOW] 个点里找：长路线能有两万个点，
     * 每秒全量扫一遍是几万次浮点运算，而 GPS 一秒最多挪几百米、跳不出窗口。
     *
     * 返回 null 就退回最近顶点 —— 只有 SDK 不可用（纯 JVM 单测）时会发生，
     * 真机不会。宁可吸附点差十几米，也不能因为一次异常让车标停住。
     */
    private fun projectOnRoute(lat: Double, lng: Double, nearIdx: Int): Anchor? {
        val pts = routeLatLng
        if (pts.size < 2) return null
        val lo = (nearIdx - NaviEngine.PROJECT_WINDOW).coerceAtLeast(0)
        val hi = (nearIdx + NaviEngine.PROJECT_WINDOW).coerceAtMost(pts.size - 1)
        if (hi - lo < 1) return null
        return runCatching {
            val pair = SpatialRelationUtil.calShortestDistancePoint(
                pts.subList(lo, hi + 1), LatLng(lat, lng)
            )
            val pt = pair?.second ?: return@runCatching null
            Anchor(pt.latitude, pt.longitude, "proj")
        }.getOrNull()
    }

    /** 最近点索引：优先在 lastIndex 附近窗口查找，偏离过远时全量扫描 */
    private fun nearestIndex(lat: Double, lng: Double, from: Int): Int {
        if (cumulative.isEmpty()) return 0
        val window = 200
        val lo = (from - window).coerceAtLeast(0)
        val hi = (from + window).coerceAtMost(cumulative.size - 1)
        var bestIdx = from.coerceIn(cumulative.indices)
        var bestD = Double.MAX_VALUE
        var i = lo
        while (i <= hi) {
            val p = cumulative[i]
            val (_, d) = bearingAndDistance(lat, lng, p.lat, p.lng)
            if (d < bestD) {
                bestD = d
                bestIdx = i
            }
            i++
        }
        // 窗口内最近点距离 > 80m：GPS 跳变，全量重定位
        if (bestD > 80.0) {
            bestD = Double.MAX_VALUE
            for (k in cumulative.indices) {
                val p = cumulative[k]
                val (_, d) = bearingAndDistance(lat, lng, p.lat, p.lng)
                if (d < bestD) {
                    bestD = d
                    bestIdx = k
                }
            }
        }
        return bestIdx
    }

    /** 沿路线前进 dist 米，返回新索引（线性插值更新位置） */
    private fun advanceAlongRoute(from: Int, dist: Double): Int {
        if (cumulative.isEmpty()) return 0
        val target = cumulative[from].dist + dist
        var i = from
        while (i < cumulative.size - 1 && cumulative[i + 1].dist < target) i++
        if (i < cumulative.size - 1) {
            val a = cumulative[i]
            val b = cumulative[i + 1]
            val seg = b.dist - a.dist
            if (seg > 0.01) {
                val t = ((target - a.dist) / seg).coerceIn(0.0, 1.0)
                curLat = a.lat + (b.lat - a.lat) * t
                curLng = a.lng + (b.lng - a.lng) * t
            }
        }
        return i
    }

    /** 当前所在步骤索引与距下一转弯剩余距离 */
    private fun currentStep(traveled: Double): Pair<Int, Double> {
        if (stepEndDist.isEmpty()) return -1 to 0.0
        for (i in stepEndDist.indices) {
            if (traveled < stepEndDist[i]) {
                return i to (stepEndDist[i] - traveled).coerceAtLeast(0.0)
            }
        }
        val last = stepEndDist.size - 1
        return last to 0.0
    }

    /** 路线在 idx 处的航向（朝向下一点） */
    private fun bearingAt(idx: Int): Float {
        if (cumulative.isEmpty()) return curBearing
        val a = cumulative[idx.coerceIn(cumulative.indices)]
        val b = cumulative[(idx + 1).coerceAtMost(cumulative.size - 1)]
        if (a.lat == b.lat && a.lng == b.lng) return curBearing
        val (brg, _) = bearingAndDistance(a.lat, a.lng, b.lat, b.lng)
        return brg
    }

    // ---------- 定位 ----------

    private fun requestLocationUpdates() {
        // 双路并行：
        //  - 高德连续定位优先（天然 GCJ02、多源融合、带速度与航向）；
        //  - 系统定位始终注册作为兜底 —— 高德定位在某些机型/无网络时可能长期
        //    不出点，只挂高德会退化成"永远在推算"，表现为导航不跟手、越走越偏。
        val loc = locator
        if (loc != null && runCatching { loc.isAvailable() }.getOrDefault(false)) {
            loc.start(
                onFix = { fix ->
                    lastAmapFixMs = System.currentTimeMillis()
                    acceptFix(
                        lat = fix.lat,
                        lng = fix.lng,
                        accuracy = fix.accuracy,
                        bearing = fix.bearing,
                        speedMps = fix.speedMps,
                        locType = fix.locationType,
                        fromSatellite = fix.fromSatellite,
                        gpsAccuracyStatus = fix.gpsAccuracyStatus
                    )
                }
            )
            locatorActive = true
        }
        // 系统定位（已做 WGS84→GCJ02 转换，高德有输出时自动让位）
        val lm = locationManager ?: return
        try {
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, NaviEngine.TICK_MS, 1f, locationListener)
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, NaviEngine.TICK_MS, 1f, locationListener)
        } catch (_: Exception) {
            // 无位置提供者时静默降级为航位推算
        }
    }

    private fun stopLocationUpdates() {
        if (locatorActive) {
            runCatching { locator?.stop() }
            locatorActive = false
        }
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {}
    }

    private fun lastKnownLocation(): Pair<Double, Double>? {
        val lm = locationManager ?: return null
        return try {
            @Suppress("MissingPermission")
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            // 同样必须纠偏到 GCJ02，否则起点会整体偏出去
            loc?.let { com.motohud.ble.amap.CoordFix.gpsToGcj(it.latitude, it.longitude) }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 直线模式辅助 ----------

    /** 距终点 <100m：按"目标方位 vs 当前航向"偏差推转向（演示模式） */
    private fun autoTurnIcon(targetBearing: Float, currentBearing: Float): Int {
        var diff = (targetBearing - currentBearing + 360f) % 360f
        if (diff > 180f) diff -= 360f
        return when {
            abs(diff) < 25f -> IconType.STRAIGHT
            diff >= 25f && diff < 60f -> IconType.RIGHT_FRONT
            diff > 120f -> IconType.LEFT_TURN_AROUND
            diff > 60f -> IconType.RIGHT
            diff <= -25f && diff > -60f -> IconType.LEFT_FRONT
            diff < -60f -> IconType.LEFT
            else -> IconType.STRAIGHT
        }
    }

    /**
     * 剩余时间。
     *
     * 优先按「规划总时长 × 剩余里程占比」推算 —— 高德的时长已经包含红绿灯、限速与
     * 拥堵，比用瞬时车速外推稳得多。只有在没有规划时长时，才退化成按车速估算，
     * 且兜底速度取 20km/h（原先 5km/h 会把 11 公里算成 2 小时 17 分）。
     */
    private fun etaSeconds(distM: Double): Int {
        if (routeDurationS > 30f && totalDist > 1.0) {
            val ratio = (distM / totalDist).coerceIn(0.0, 1.0)
            return (routeDurationS * ratio).toInt()
        }
        val v = _state.value.speedKmh.coerceAtLeast(20f)
        return (distM / (v * 1000 / 3600)).toInt()
    }

    // ---------- 球面几何（实现统一在 Geo，这里只留调用别名） ----------

    /** 返回 (方位角度, 距离米) */
    private fun bearingAndDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Pair<Float, Double> =
        Geo.bearing(lat1, lng1, lat2, lng2) to Geo.distanceM(lat1, lng1, lat2, lng2)

    /** 从 (lat,lng) 沿 bearing 前进 dist 米后的坐标（直线演示模式用） */
    private fun destinationPoint(lat: Double, lng: Double, bearingDeg: Float, distM: Double): Pair<Double, Double> =
        Geo.destination(lat, lng, bearingDeg, distM)
}
