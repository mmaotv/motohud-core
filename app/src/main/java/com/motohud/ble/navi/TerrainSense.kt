package com.motohud.ble.navi

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * 坡度感知：长上坡 / 长下坡。
 *
 * ## 先说结论：高德给不了坡度
 *
 * 彻查过 SDK 的每一个相关入口，全部确认无坡度数据：
 *  - `com.amap.api.maps.model.LatLng` 只有 `latitude` / `longitude` 两个字段，**没有海拔**；
 *  - `com.amap.api.services.core.LatLonPoint` 同样只有经纬度（`javap` 已核实）；
 *  - `DriveStep` 的全部 getter 里没有任何高程/坡度/道路等级字段；
 *  - 地图 SDK 里有 `AMap.getTerrainApprovalNumber()` 与 `addContourLineOverlay()`，
 *    但 `ContourLineOptions`（混淆名 `col.3sl.z5`）**没有公开构造器**，只接受 Parcel，
 *    应用层造不出来；`getTerrainApprovalNumber()` 只是地形图服务的合规编号回填。
 *
 * 所以「前方长陡坡」这类播报**不可能**从高德数据里拿到。要么接第三方高程 API
 * （离线不可用、有配额、骑行中弱网），要么换数据源。
 *
 * ## 这里采用的办法：气压计
 *
 * 手机气压计（`Sensor.TYPE_PRESSURE`）分辨率约 0.01 hPa ≈ 8 cm 高度，比 GPS 高程
 * （误差 10~30 m）精确两个数量级。用「气压换算的相对高度变化 ÷ GPS 累计水平距离」
 * 就能得到真实坡度百分比，全程离线、零流量。
 *
 * ### 三个必须做的工程处理
 *
 * 1. **只能用相对高度**：绝对海拔需要当地海平面气压，拿不到；但算坡度只需要 Δh，
 *    所以拿第一个采样当基准就够。
 * 2. **风压干扰**：骑行时迎面气流会把静压拉低（伯努利效应），20 m/s 时约 2.4 hPa
 *    ≈ 20 m 的假高度。用 **20 秒滑动窗口 + 线性回归**把这种短时扰动平均掉；
 *    匀速时风压恒定，不影响 Δh。
 * 3. **必须"持续"才提示**：单帧算出的坡度没有任何意义。要求窗口内**整段**都满足
 *    |坡度| ≥ 4%（山区国道常见 4~8%，城市立交匝道 max 8%），才认为是长坡。
 *
 * ### 播报口径为什么是「当前」不是「前方」
 *
 * 坡度只能测已经走过的路，没法预知前方。20 秒窗口意味着提示会比坡道起点晚 20 秒左右，
 * 对动辄一两公里的长下坡完全可接受；但如果说成"前方下坡"就是撒谎，所以文案用
 * 「连续下坡，注意控制车速」。
 *
 * 设备没有气压计时 [isAvailable] 返回 false，整套逻辑静默关闭 —— 不降级、不误报。
 */
class TerrainSense(context: Context?) {

    /** 一次坡度判定结果 */
    data class Verdict(
        /** 坡度百分比，正 = 上坡，负 = 下坡 */
        val gradePercent: Float,
        /** true = 长下坡 */
        val downhill: Boolean,
        /** 窗口内累计水平距离（米），用于文案里的"连续多长" */
        val spanM: Float
    )

    private val manager: SensorManager? =
        context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    /** 设备有无气压计 —— 没有就整套关掉 */
    fun isAvailable(): Boolean = sensor != null

    // ---------- 采样缓冲 ----------
    // 每个采样存 (时间戳ms, 气压hPa)。20 秒窗口按 5Hz 采样即 100 个点，留余量。
    private val times = LongArray(WINDOW_MAX)
    private val pressures = FloatArray(WINDOW_MAX)
    private var count = 0
    private var head = 0

    /** 窗口内 GPS 水平距离累计（米），由 [addDistance] 喂入 */
    private var windowDistM = 0f

    private var running = false
    private var lastVerdict: Verdict? = null

    /** 最近一次判定（供界面显示坡度徽章），无数据为 null */
    fun lastVerdictOrNull(): Verdict? = lastVerdict

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running) return
            val now = System.currentTimeMillis()
            // 5Hz 足够（气压变化本来就慢），再高频只是徒增噪声
            if (count > 0 && now - times[(head - 1 + WINDOW_MAX) % WINDOW_MAX] < 180L) return
            times[head] = now
            pressures[head] = event.values[0]
            head = (head + 1) % WINDOW_MAX
            if (count < WINDOW_MAX) count++
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (running || sensor == null) return
        count = 0
        head = 0
        windowDistM = 0f
        lastVerdict = null
        running = true
        runCatching { manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager?.unregisterListener(listener) }
        count = 0
        head = 0
        windowDistM = 0f
    }

    /**
     * 由导航 tick 喂入上一帧到这一帧走过的水平距离（米）。
     *
     * 用**路线上的**行驶距离而不是 GPS 直线位移：GPS 抖动会产生正负抵消不了的假位移，
     * 而路线累计距离是单调的，更稳。
     */
    fun addDistance(m: Float) {
        if (!running) return
        if (m > 0f) windowDistM += m
    }

    /**
     * 尝试做一次判定。调用方每秒调一次即可，内部会检查窗口是否够长。
     *
     * @return 达到"长坡"标准时返回判定结果，否则 null
     */
    fun evaluate(nowMs: Long = System.currentTimeMillis()): Verdict? {
        if (!running || count < MIN_SAMPLES) return null
        // 窗口必须真的覆盖足够长的时间，否则 5 个点也能算出离谱的坡度
        val oldest = times[(head - count + WINDOW_MAX) % WINDOW_MAX]
        val spanMs = nowMs - oldest
        if (spanMs < WINDOW_MS) return null
        if (windowDistM < MIN_DIST_M) return null

        // 线性回归求气压对时间的斜率（hPa/s）：等间隔采样下用平均值公式即可，
        // 比逐点最小二乘省一半开销，结果一致。
        var sumP = 0.0
        var sumTP = 0.0
        var sumT = 0.0
        var sumTT = 0.0
        val t0 = oldest
        for (i in 0 until count) {
            val idx = (head - count + i + WINDOW_MAX) % WINDOW_MAX
            val t = (times[idx] - t0) / 1000.0
            val p = pressures[idx].toDouble()
            sumP += p
            sumT += t
            sumTP += t * p
            sumTT += t * t
        }
        val n = count.toDouble()
        val denom = n * sumTT - sumT * sumT
        if (abs(denom) < 1e-6) return null
        val dPdt = (n * sumTP - sumT * sumP) / denom   // hPa/s

        // hPa → 米：近地面 1 hPa ≈ 8.3 m（标准大气，20~30℃ 范围内误差 < 5%）
        val dhdt = -dPdt * M_PER_HPA               // m/s，气压降 = 上升
        val v = (windowDistM / (spanMs / 1000f))    // 平均水平速度 m/s
        if (v < 1.0f) return null                  // 走走停停算不出坡度
        val grade = (dhdt / v * 100.0).toFloat()

        // 噪声门限：回归残差太小说明气压根本没动（或传感器卡死），不做判定
        if (abs(grade) < MIN_GRADE) {
            lastVerdict = null
            return null
        }
        val verdict = Verdict(
            gradePercent = grade,
            downhill = grade < 0f,
            spanM = windowDistM
        )
        lastVerdict = verdict
        return verdict
    }

    companion object {
        /** 滑动窗口长度：20 秒。再短压不住风压，再长提示延迟太明显 */
        const val WINDOW_MS = 20_000L
        /** 至少要有这么多采样才可信（5Hz × 20s = 100，留一半余量） */
        private const val MIN_SAMPLES = 50
        /** 判定为"长坡"的最小坡度（百分比） */
        const val MIN_GRADE = 4f
        /** 窗口内至少要走这么远，否则 20 秒只挪了 10 米也算不出坡 */
        private const val MIN_DIST_M = 120f
        private const val WINDOW_MAX = 160
        private const val M_PER_HPA = 8.3
    }
}

/** 坡度播报文案（双链路两套） */
object TerrainNarrator {

    /** 内置语音包固定句式，与 gen_voice_pack.py 的 TERRAIN 逐字一致 */
    fun fixedText(downhill: Boolean): String =
        if (downhill) "连续下坡，注意控制车速" else "连续上坡，注意动力"

    /** 离线神经语音：带上坡度与长度，信息更具体 */
    fun text(v: TerrainSense.Verdict): String {
        val g = abs(v.gradePercent).toInt().coerceIn(4, 15)
        val km = (v.spanM / 1000f)
        val span = if (km >= 0.95f) "%.1f 公里".format(km) else "${v.spanM.toInt()} 米"
        return if (v.downhill) {
            "连续 $g% 下坡，已持续 $span，注意控制车速"
        } else {
            "连续 $g% 上坡，已持续 $span，注意动力"
        }
    }

    fun prewarmTexts(): List<String> = listOf(
        "连续下坡，注意控制车速",
        "连续上坡，注意动力"
    )
}
