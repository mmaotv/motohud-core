package com.motohud.ble.navi

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * 定位轨迹滤波器（解决「定位漂移」的核心）。
 *
 * 手机 GPS 在以下几种场景会给出明显错误的值，直接拿来导航就会出现"人在地图上乱跳"：
 *  1. **坐标系错误**：系统 LocationManager 给的是 WGS84，国内地图/路线是 GCJ02，
 *     两者在华东/华北差 300~700 米 —— 这不是抖动，是恒定偏移，必须先纠偏；
 *  2. **精度差的点**：室内、高楼间、隧道口，accuracy 会到 100 米以上；
 *  3. **跳点（spike）**：某一帧突然飞出几公里再回来；
 *  4. **静止抖动**：停在原地时坐标仍在几米范围内来回跳，界面上表现为"车在抖"。
 *
 * 处理顺序：精度门限 → 跳点抑制（自适应门限）→ 指数平滑（速度自适应）。所有距离
 * 计算在米级平面近似下完成，短距离（<5km）误差可忽略。
 */
class LocationFilter(
    /** 允许的最大水平精度（米），超过直接丢弃 */
    private val maxAccuracyM: Float = 60f,
    /** 基础平滑系数（0~1，越小越平滑但越滞后） */
    private val baseAlpha: Float = 0.45f,
    /** 静止判定速度（m/s） */
    private val stillSpeed: Float = 0.6f,
    /** 可能的最大移动速度（m/s）：60 ≈ 216km/h，超过即视为错点 */
    private val maxPlausibleSpeedMps: Float = 60f
) {

    /** 滤波输出 */
    data class Output(
        val lat: Double,
        val lng: Double,
        val bearing: Float,
        val speedMps: Float,
        val accuracy: Float,
        /** 是否为首帧（首帧不平滑，直接采用） */
        val first: Boolean
    )

    private var hasFix = false
    private var lat = 0.0
    private var lng = 0.0
    private var bearing = 0f
    private var speed = 0f
    private var accuracy = 0f
    private var lastMs = 0L

    /** 连续被跳点门限拒收的次数：连续 2 次说明是真实的大幅移动，放行 */
    private var rejected = 0

    fun reset() {
        hasFix = false
        rejected = 0
        lastMs = 0L
    }

    /**
     * 用已知起点给滤波器打底（导航开始时调用）。
     *
     * 不打底的话第一帧会被无条件接受 —— 而第一帧很可能来自高德定位的
     * **网络/基站定位**（室内、冷启动常见），误差可达几百米到几十公里，
     * 直接把车标甩到别的城市（地图上看就是"白屏/路线不见了"）。
     * 打底之后，第一帧同样要过跳点校验，位移不可能就直接丢掉。
     */
    fun seed(lat: Double, lng: Double, nowMs: Long) {
        hasFix = true
        this.lat = lat
        this.lng = lng
        bearing = 0f
        speed = 0f
        accuracy = 0f
        lastMs = nowMs
        rejected = 0
    }

    /**
     * 输入一帧原始定位（必须已转换到 GCJ02）。
     * @return 通过滤波的输出；被判为无效时返回 null
     */
    fun onFix(
        rawLat: Double,
        rawLng: Double,
        rawAccuracy: Float,
        rawBearing: Float,
        rawSpeedMps: Float,
        nowMs: Long
    ): Output? {
        if (rawLat == 0.0 && rawLng == 0.0) return null
        if (rawLat.isNaN() || rawLng.isNaN()) return null
        // 1) 精度门限
        if (rawAccuracy > maxAccuracyM) return null

        if (!hasFix) {
            hasFix = true
            lat = rawLat
            lng = rawLng
            bearing = rawBearing
            speed = rawSpeedMps
            accuracy = rawAccuracy
            lastMs = nowMs
            rejected = 0
            return Output(lat, lng, bearing, speed, accuracy, first = true)
        }

        val dt = if (lastMs > 0) max(0.001f, (nowMs - lastMs) / 1000f) else 1f
        val jump = distanceM(lat, lng, rawLat, rawLng)

        // 有些机型/提供者不给速度字段（hasSpeed=false），此时若仍按 0 处理，
        // 慢速行驶会被判成"静止"而用 0.12 的强平滑，表现为车标越拖越远（滞后漂移）。
        // 没有速度就用位移/时间估算。
        val effSpeed = if (rawSpeedMps > 0.1f) rawSpeedMps else (jump / dt).coerceIn(0f, 60f)

        // 2) 物理不可能位移：按 216km/h 上限推算，超出的一律判为错点。
        //    只有连续 3 帧且精度足够好（≤20m，基本是真正的卫星定位）
        //    才允许"重新定上位"，这样网络定位永远无法把车标甩走。
        if (jump > maxPlausibleSpeedMps * dt + 150f) {
            rejected++
            if (rejected < 3 || rawAccuracy > 20f) return null
            rejected = 0
            lat = rawLat
            lng = rawLng
            bearing = if (rawBearing.isFinite()) rawBearing else bearing
            speed = rawSpeedMps
            accuracy = rawAccuracy
            lastMs = nowMs
            return Output(lat, lng, bearing, speed, accuracy, first = false)
        }

        // 3) 跳点抑制：允许的最大位移 = 按速度推算的距离 ×3 + 精度余量
        val allowed = max(45f, (max(speed, effSpeed) * dt * 3f) + 40f)
        if (jump > allowed && rawAccuracy > 20f) {
            rejected++
            if (rejected < 2) return null
            // 连续两帧都指向远处：接受（真实的快速移动/重新定上位）
            rejected = 0
            lat = rawLat
            lng = rawLng
            bearing = rawBearing
            speed = rawSpeedMps
            accuracy = rawAccuracy
            lastMs = nowMs
            return Output(lat, lng, bearing, speed, accuracy, first = false)
        }
        rejected = 0

        // 3) 速度自适应平滑：移动越快越相信新值；静止时强平滑，压掉"原地抖"
        val alpha = when {
            jump > 30f -> 0.85f                        // 明显位移，几乎直接采用
            effSpeed < stillSpeed && jump < 4f -> 0.12f  // 静止微抖 → 强力压制
            effSpeed < stillSpeed -> 0.25f
            else -> baseAlpha
        }

        lat = lat + (rawLat - lat) * alpha
        lng = lng + (rawLng - lng) * alpha

        // 航向：低速时不采信（GPS 航向在低速下极不可靠，会乱转）
        if (rawBearing.isFinite() && effSpeed > 1.5f) {
            bearing = blendAngle(bearing, rawBearing, 0.4f)
        }
        speed = speed + (effSpeed - speed) * 0.5f
        accuracy = accuracy + (rawAccuracy - accuracy) * 0.3f
        lastMs = nowMs

        return Output(lat, lng, bearing, speed, accuracy, first = false)
    }

    companion object {

        /**
         * 两点间距离（米）。
         *
         * 公式**只允许有一份**，在 [Geo.distanceM]（优先走官方 `AMapUtils`，
         * JVM 单测下退回 haversine）。这里保留 Float 签名只是为了不动既有调用方。
         */
        fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float =
            Geo.distanceM(lat1, lng1, lat2, lng2).toFloat()

        /** 角度插值（处理 359° → 1° 的跨界） */
        fun blendAngle(from: Float, to: Float, t: Float): Float {
            var diff = ((to - from) % 360f + 540f) % 360f - 180f
            if (abs(diff) < 0.01f) return to
            return ((from + diff * t) % 360f + 360f) % 360f
        }
    }
}
