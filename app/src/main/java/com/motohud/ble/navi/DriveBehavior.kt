package com.motohud.ble.navi

import kotlin.math.abs

/**
 * 驾驶行为感知：急刹车 / 急加速 / 急转弯。
 *
 * ## 先说清楚一件事：这不是"道路要素"
 *
 * 用户提到的「前方长陡坡、上坡下坡、急刹车、急转弯」其实是**两类完全不同的东西**，
 * 混在一起实现会做歪：
 *
 * | 说法 | 本质 | 数据来源 | 能否提前播 |
 * |---|---|---|---|
 * | 前方急转弯、连续弯道 | **道路形态** | `polyline` 曲率自算（见 [RoadShape]） | 能，提前 300m |
 * | 前方长下坡、上坡 | **道路形态** | 只有气压计能测（见 [TerrainSense]） | 不能，只能"当前" |
 * | 急刹车、急加速 | **驾驶行为** | 本类，GPS 速度/航向自算 | 不能，只能事后 |
 *
 * 高德原版的播报里**没有"急刹车"这一句** —— 它播的是"前方急下坡""前方急转弯"，
 * 都是路的事；"你刚急刹了"是对骑手行为的评价，导航软件不会说（谁刹车谁知道）。
 *
 * 所以这里的态度是：
 *  - **默认不播报**（[com.motohud.ble.data.NaviPrefs.harshDriveVoice] 默认关）——
 *    骑行中被提醒"你刚急刹了"纯属干扰；
 *  - 但**照常计数**，供行程结束后回顾（"这趟急刹 3 次，都在那个路口"），
 *    也能当摔车风险的早期信号；
 *  - 用户在设置里主动打开才播。
 *
 * ## 阈值是怎么定的
 *
 * 摩托车常规制动减速度 0.2~0.35g，紧急制动可达 0.6~0.9g。取 **0.3g（2.94 m/s²）**
 * 作为"急刹"门限，1.2 秒内速度掉 3.5 m/s（约 12.6 km/h）才认 —— 双条件缺一不可，
 * 因为 GPS 速度的瞬时噪声（±1 m/s）单靠减速度会频繁误报。
 *
 * 急转弯取 **18°/s 且车速 ≥ 6 m/s（21.6 km/h）**：低速原地掉头航向变化也很快，
 * 那不算急转弯。
 */
class DriveBehaviorMonitor {

    enum class Kind {
        /** 急刹车 */
        HARD_BRAKE,

        /** 急加速 */
        HARD_ACCEL,

        /** 急转弯 */
        HARD_TURN
    }

    data class Event(val kind: Kind, /** 触发时的车速 km/h */ val speedKmh: Float)

    // ---------- 采样环形缓冲 ----------
    // 只保留最近 ~3 秒：判定窗口最长 1.2 秒，再长没意义还占内存。
    private val cap = 40
    private val ts = LongArray(cap)
    private val vs = FloatArray(cap)      // m/s
    private val bs = FloatArray(cap)      // bearing 度
    private var n = 0
    private var head = 0

    private var lastBrakeAt = 0L
    private var lastAccelAt = 0L
    private var lastTurnAt = 0L

    /** 本次行程累计次数（供结束页 / 未来统计用） */
    var brakeCount = 0
        private set
    var accelCount = 0
        private set
    var turnCount = 0
        private set

    fun reset() {
        n = 0
        head = 0
        lastBrakeAt = 0L
        lastAccelAt = 0L
        lastTurnAt = 0L
        brakeCount = 0
        accelCount = 0
        turnCount = 0
    }

    /**
     * 喂一帧定位。
     *
     * @param speedMps GPS 速度，必须 ≥ 0；无速度时不要调用（否则全是误报）
     * @param bearing  航向角（度）。低速时 GPS 航向不可靠，内部已按速度过滤
     * @param accuracy 定位精度（米）。> 30m 直接丢弃，网络定位算不出加减速
     */
    fun onFix(nowMs: Long, speedMps: Float, bearing: Float, accuracy: Float): Event? {
        if (accuracy > ACC_MAX_M || speedMps < 0f || !bearing.isFinite()) return null

        ts[head] = nowMs
        vs[head] = speedMps
        bs[head] = bearing
        head = (head + 1) % cap
        if (n < cap) n++

        // 需要至少两帧、且跨度够判定窗口才动手
        val spanMs = nowMs - ts[(head - n + cap) % cap]
        if (n < 3 || spanMs < WINDOW_MS) return null

        // 取窗口起点那一帧做对比（不逐点积分，直接端点差分最稳）
        val firstIdx = (head - n + cap) % cap
        val dt = (nowMs - ts[firstIdx]) / 1000f
        if (dt < 0.5f) return null
        val dv = speedMps - vs[firstIdx]
        val accel = dv / dt                       // m/s²，正 = 加速，负 = 减速

        // ---- 急刹车 / 急加速 ----
        if (accel <= -BRAKE_MPS2 && dv <= -BRAKE_DV && nowMs - lastBrakeAt > COOLDOWN_MS) {
            lastBrakeAt = nowMs
            brakeCount++
            return Event(Kind.HARD_BRAKE, speedMps * 3.6f)
        }
        if (accel >= ACCEL_MPS2 && dv >= ACCEL_DV && nowMs - lastAccelAt > COOLDOWN_MS) {
            lastAccelAt = nowMs
            accelCount++
            return Event(Kind.HARD_ACCEL, speedMps * 3.6f)
        }

        // ---- 急转弯 ----
        if (speedMps >= TURN_MIN_SPEED) {
            val turnRate = abs(angleDiff(bs[firstIdx], bearing)) / dt
            if (turnRate >= TURN_DPS && nowMs - lastTurnAt > COOLDOWN_MS) {
                lastTurnAt = nowMs
                turnCount++
                return Event(Kind.HARD_TURN, speedMps * 3.6f)
            }
        }
        return null
    }

    /** 角度差归一化到 [-180, 180]，否则 359°→1° 会被算成 -358° */
    private fun angleDiff(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    companion object {
        /** 判定窗口：1.2 秒 */
        const val WINDOW_MS = 1_200L
        /** 急刹：减速度门限 0.3g */
        const val BRAKE_MPS2 = 2.94f
        /** 急刹：窗口内速度至少降这么多，压住 GPS 噪声 */
        const val BRAKE_DV = 3.5f
        /** 急加速：0.25g */
        const val ACCEL_MPS2 = 2.45f
        const val ACCEL_DV = 3.0f
        /** 急转弯：转向率门限（度/秒） */
        const val TURN_DPS = 18f
        /** 急转弯：低于这个车速不算（原地掉头不算） */
        const val TURN_MIN_SPEED = 6f
        /** 同类事件最小间隔，避免一次动作刷屏 */
        const val COOLDOWN_MS = 6_000L
        /** 超过这个精度的定位不采信 */
        const val ACC_MAX_M = 30f
    }
}

/** 驾驶行为提示文案（双链路两套） */
object DriveBehaviorNarrator {

    /** 内置语音包固定句式，与 gen_voice_pack.py 的 BEHAVIOR 逐字一致 */
    fun fixedText(kind: DriveBehaviorMonitor.Kind): String = when (kind) {
        DriveBehaviorMonitor.Kind.HARD_BRAKE -> "急刹车，注意后方车辆"
        DriveBehaviorMonitor.Kind.HARD_ACCEL -> "加速过猛，注意前方路况"
        DriveBehaviorMonitor.Kind.HARD_TURN -> "转弯过快，注意车速"
    }

    fun prewarmTexts(): List<String> = DriveBehaviorMonitor.Kind.values()
        .map { fixedText(it) }
}
