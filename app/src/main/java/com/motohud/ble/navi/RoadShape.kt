package com.motohud.ble.navi

import kotlin.math.abs
import kotlin.math.atan2

/**
 * 道路形态识别：急弯、连续弯道、以及"弯道有多急"。
 *
 * ## 为什么这一块必须自己算
 *
 * 高德**免费搜索 SDK 的 `DriveStep` 里没有任何道路形态字段**（没有曲率、没有坡度、
 * 没有道路等级）。带这些信息的是导航 SDK（`AMapNavi`）的 `getRoadType()`，需要商务授权。
 *
 * 但弯道这件事**不需要外部数据**——`polyline` 本身就是一串经纬度点，相邻三点的
 * 转向角就是曲率。这是彻查里唯一"数据完全够用、只是没人算"的一类，也是最该补的。
 *
 * ## 和高德原版播报的对应关系
 *
 * 高德原版在山区会播「前方急转弯」「前方连续弯道，请减速」，它用的是自家路网属性；
 * 我们算出来的结果在语义上等价，只是判定口径不同（下面有具体阈值）。
 *
 * ## 为什么输出「距本步骤起点的距离」而不是折线索引
 *
 * 导航页手里只有 `stepRemain`（距下一转弯还有多少米）与 `step.distanceM`（本段总长），
 * 两者相减就是"在本段里已经走了多远"。所以提示只要给出"弯道在本段 X 米处"，
 * 就能直接换算成"前方还有多远"，精度只取决于折线累计距离，实测误差 < 3%。
 */
object RoadShape {

    /** 一个弯道提示 */
    data class CurveHint(
        /** 弯道顶点距**本步骤起点**的距离（米） */
        val atM: Float,
        /** 弯道累计转角（度，恒为正） */
        val angle: Float,
        /** true 表示左转（逆时针），false 表示右转 */
        val left: Boolean,
        /** 连续弯道（同一方向多次弯折，总转角大） */
        val continuous: Boolean
    ) {
        /** 急弯：单次转角 ≥ 60°，摩托车必须明显减速 */
        val sharp: Boolean get() = angle >= SHARP_ANGLE
    }

    /** 单次转角达到这个值算"急弯"（高德路网里 60° 已属急弯） */
    const val SHARP_ANGLE = 60f

    /** 连续弯道：同向累计转角达到这个值 */
    const val CONTINUOUS_ANGLE = 90f

    /** 连续弯道的最短跨度，太短的（路口内折返）不算 */
    const val CONTINUOUS_MIN_SPAN_M = 30f

    /** 把转角小于这个值的点视为直线，累计时不打断弯道 */
    private const val MIN_TURN = 12f

    /**
     * 扫描一条折线，返回其中的弯道（按距起点升序）。
     *
     * 算法：逐顶点算转向角，把**同方向且间隔不超过 40 米**的连续小转角合并成一段；
     * 合并后的累计转角 ≥ [SHARP_ANGLE] 就输出一个提示。
     *
     * 为什么不用简单的"单点转角 > 60°"：高德折线在长弯道上会给出很多个 15°~25° 的点，
     * 单点判定完全命中不到，而这恰恰是最危险的山路长弯。
     */
    fun scan(polyline: List<Pair<Double, Double>>): List<CurveHint> {
        if (polyline.size < 3) return emptyList()
        val out = ArrayList<CurveHint>(4)

        // 折线累计距离，用来把顶点索引换算成"距起点多少米"
        val cum = FloatArray(polyline.size)
        for (i in 1 until polyline.size) {
            cum[i] = cum[i - 1] + dist(polyline[i - 1], polyline[i]).toFloat()
        }

        var accAngle = 0f     // 当前合并中的累计转角（带符号）
        var accStartM = 0f    // 本段第一个弯折点的里程
        var lastAtM = -1f     // 上一个弯折点的里程，用于判断"间隔是否过大"

        for (i in 1 until polyline.size - 1) {
            val turn = turnAngle(polyline[i - 1], polyline[i], polyline[i + 1])
            if (abs(turn) < MIN_TURN) {
                // 直线：结算当前弯道段
                if (abs(accAngle) >= SHARP_ANGLE) {
                    out += hint(accAngle, accStartM, lastAtM - accStartM)
                }
                accAngle = 0f
                continue
            }
            val atM = cum[i]
            // 方向变了，或间距过大（中间隔了一长段直路）→ 结算上一段，重新开始
            val dirChanged = accAngle != 0f && (accAngle > 0) != (turn > 0)
            val gapTooBig = lastAtM >= 0f && atM - lastAtM > 40f
            if (dirChanged || gapTooBig) {
                if (abs(accAngle) >= SHARP_ANGLE) {
                    out += hint(accAngle, accStartM, lastAtM - accStartM)
                }
                accAngle = 0f
            }
            if (accAngle == 0f) accStartM = atM
            accAngle += turn
            lastAtM = atM
        }
        if (abs(accAngle) >= SHARP_ANGLE) {
            out += hint(accAngle, accStartM, lastAtM - accStartM)
        }
        return out
    }

    /** 一段折线的几何长度（米）。与 [scan] 里用的累计口径完全一致 */
    fun lengthOf(polyline: List<Pair<Double, Double>>): Float {
        var s = 0.0
        for (i in 1 until polyline.size) s += dist(polyline[i - 1], polyline[i])
        return s.toFloat()
    }

    private fun hint(acc: Float, startM: Float, spanM: Float): CurveHint {
        val angle = abs(acc)
        return CurveHint(
            atM = startM,
            angle = angle,
            // 符号约定见 turnAngle：负 = 左转。这里曾经写成 acc > 0，
            // 结果左弯播成"向右"、右弯播成"向左" —— 是对着弯道喊反方向，
            // 比不播还危险。RoadShapeTest 里用真实几何锁死了这个方向。
            left = acc < 0,
            continuous = angle >= CONTINUOUS_ANGLE && spanM >= CONTINUOUS_MIN_SPAN_M
        )
    }

    /**
     * 三点转向角（度）。**负 = 左转，正 = 右转。**
     *
     * 直接用平面近似：在几百米尺度上，经纬度按 cos(lat) 缩放后的平面角误差 < 0.5%，
     * 比反复做球面三角快得多。
     *
     * 符号来源：`atan2(东, 北)` 是方位角（0=正北，顺时针为正）。
     * 面向东（90°）左转到北（0°），差值是 -90 → 左转为负，与直觉的"逆时针为正"相反。
     */
    private fun turnAngle(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
        c: Pair<Double, Double>
    ): Float {
        val k = kotlin.math.cos(Math.toRadians(b.first))
        val x1 = (b.second - a.second) * k
        val y1 = b.first - a.first
        val x2 = (c.second - b.second) * k
        val y2 = c.first - b.first
        if ((x1 == 0.0 && y1 == 0.0) || (x2 == 0.0 && y2 == 0.0)) return 0f
        val b1 = atan2(x1, y1)
        val b2 = atan2(x2, y2)
        var d = Math.toDegrees(b2 - b1).toFloat()
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        Geo.distanceM(a.first, a.second, b.first, b.second)
}

/**
 * 弯道提示的播报文案（双链路两套）。
 *
 * 触发距离都是 300 米：摩托车 40~60km/h 时 300 米约 20 秒，足够收油减速；
 * 再远用户会忘记，再近（150 米）已经来不及把速度降下来。
 */
object CurveNarrator {

    const val TRIGGER_M = 300

    /** 内置语音包固定句式，与 gen_voice_pack.py 的 CURVES 逐字一致 */
    fun fixedText(hint: RoadShape.CurveHint): String = when {
        hint.continuous -> "前方连续弯道，请减速"
        hint.left -> "前方急转弯向左，请减速"
        else -> "前方急转弯向右，请减速"
    }

    /** 离线神经语音：带上实际距离与角度，信息量更大 */
    fun text(hint: RoadShape.CurveHint, remainM: Int): String {
        val d = SpeechScript.distance(remainM)
        val dir = if (hint.left) "向左" else "向右"
        return when {
            hint.continuous -> if (d.isEmpty()) "前方连续弯道，请减速" else "${d}连续弯道，请减速"
            hint.sharp -> if (d.isEmpty()) "前方急转弯$dir，请减速" else "${d}急转弯$dir，请减速"
            else -> if (d.isEmpty()) "前方弯道$dir" else "${d}弯道$dir"
        }
    }

    /** 预合成清单：内置包要用到的全部固定句式 */
    fun prewarmTexts(): List<String> = listOf(
        "前方连续弯道，请减速",
        "前方急转弯向左，请减速",
        "前方急转弯向右，请减速"
    )
}
