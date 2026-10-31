package com.motohud.ble.navi

import android.graphics.Bitmap

/**
 * 导航 SDK 提供的「沿途提示」数据模型。
 *
 * 为什么单独抽一层，而不是把 `com.amap.api.navi.model.*` 直接往 UI 传：
 *  1. UI/车机投屏不该认识 SDK 的类 —— 换 SDK 或加离线兜底时不用改一片代码；
 *  2. SDK 的回调对象是**可变**的（同一个 AMapNaviCameraInfo 会被复用改写），
 *     直接存引用会导致"提示一出现就不断变化"。这里全部转成不可变值。
 *
 * 这些数据在接入导航 SDK 之前**完全拿不到**：搜索 SDK 的 DriveStep 只有 TMC 路况、
 * 红绿灯数、收费、限行，没有电子眼、没有限速、没有车道线、没有路口放大图。
 */
object CameraKind {
    // 与 com.amap.api.navi.enums.CameraType 的数值一致（javap 核实：
    // SPEED=0 SURVEILLANCE=1 TRAFFICLIGHT=2 BREAKRULE=3 BUSWAY=4 EMERGENCY=5
    // BICYCLE=6 INTERVALVELOCITYSTART=8 INTERVALVELOCITYEND=9 FLOWSPEED=10 ETC=11）
    const val SPEED = 0
    const val SURVEILLANCE = 1
    const val TRAFFIC_LIGHT = 2
    const val BREAK_RULE = 3
    const val BUS_WAY = 4
    const val EMERGENCY = 5
    const val BICYCLE = 6
    const val INTERVAL_START = 8
    const val INTERVAL_END = 9
    const val FLOW_SPEED = 10
    const val ETC = 11

    /**
     * 播报名（字符串资源）。
     *
     * 返回资源 id 而不是字符串：项目约定所有中文进 `res/values/strings.xml`，
     * 播报词库（[SpeechScript]）是历史例外，新代码不再往 Kotlin 里写中文。
     */
    @androidx.annotation.StringRes
    fun labelRes(type: Int): Int = when (type) {
        SPEED -> com.motohud.ble.R.string.camera_speed
        SURVEILLANCE -> com.motohud.ble.R.string.camera_surveillance
        TRAFFIC_LIGHT -> com.motohud.ble.R.string.camera_traffic_light
        BREAK_RULE -> com.motohud.ble.R.string.camera_break_rule
        BUS_WAY -> com.motohud.ble.R.string.camera_bus_way
        EMERGENCY -> com.motohud.ble.R.string.camera_emergency
        BICYCLE -> com.motohud.ble.R.string.camera_bicycle
        INTERVAL_START -> com.motohud.ble.R.string.camera_interval_start
        INTERVAL_END -> com.motohud.ble.R.string.camera_interval_end
        FLOW_SPEED -> com.motohud.ble.R.string.camera_flow_speed
        ETC -> com.motohud.ble.R.string.camera_etc
        else -> com.motohud.ble.R.string.camera_generic
    }

    /** 是否值得打断播报（拍照类才提，流量检测/ETC 太吵就不提了） */
    fun worthSpeaking(type: Int): Boolean = when (type) {
        SPEED, SURVEILLANCE, TRAFFIC_LIGHT, BREAK_RULE,
        BUS_WAY, EMERGENCY, BICYCLE, INTERVAL_START, INTERVAL_END -> true
        else -> false
    }
}

/**
 * 电子眼 / 区间测速提示。
 *
 * @param type [CameraKind] 之一
 * @param distanceM 距该电子眼的米数（SDK 已按当前车速与路况算好播报距离）
 * @param speedKmh 该点限速（0 = 未知）。高德把限速随电子眼一起下发，是免费能力。
 * @param interval 是否区间测速
 * @param intervalRemainM 区间测速剩余里程（仅区间测速有意义）
 */
data class CameraAlert(
    val type: Int,
    val distanceM: Int,
    val speedKmh: Int = 0,
    val interval: Boolean = false,
    val intervalRemainM: Int = 0
) {
    /** 去重键：同一个电子眼只会念一次（距离按 400 米分桶，允许"600米/200米"各报一次） */
    fun speechKey(): String = "$type|$speedKmh|${distanceM / 400}"
}

/**
 * 车道动作码。
 *
 * 数值与 `com.amap.api.navi.enums.LaneAction` 完全一致（对照反编译包核实）。
 * 为什么不用 SDK 的常量而自己抄一份：`LaneAction` 只是 class + static int，
 * 引用它会把 UI 层绑死在 SDK 上；而枚举值本身就是固化的协议，抄一份更稳。
 */
object LaneAction {
    const val AHEAD = 0
    const val LEFT = 1
    const val AHEAD_LEFT = 2
    const val RIGHT = 3
    const val AHEAD_RIGHT = 4
    const val LEFT_U_TURN = 5
    const val LEFT_RIGHT = 6
    const val AHEAD_LEFT_RIGHT = 7
    const val RIGHT_U_TURN = 8
    const val AHEAD_LEFT_U_TURN = 9
    const val AHEAD_RIGHT_U_TURN = 10
    const val LEFT_LEFT_U_TURN = 11
    const val RIGHT_RIGHT_U_TURN = 12
    const val LEFT_IN_AHEAD = 13
    const val LEFT_IN_LEFT_U_TURN = 14

    /** 终止符：车道数组以它结尾（`gn.a(byte[])` 数到它就停），也代表"该车道不可走" */
    const val RESERVED = 15
    const val AHEAD_LEFT_LEFT_U_TURN = 16
    const val RIGHT_RIGHT_U_TURN_EX = 17
    const val LEFT_RIGHT_U_TURN = 18
    const val AHEAD_RIGHT_RIGHT_U_TURN = 19
    const val LEFT_LEFT_U_TURN_EX = 20
    const val BUS = 21
    const val EMPTY = 22
    const val VARIABLE = 23
    const val NULL = 255
}

/** 绘制用的箭头原语（一个车道格子里可能并排画多个） */
object Arrow {
    const val STRAIGHT = 0
    const val LEFT = 1
    const val RIGHT = 2
    const val UTURN_LEFT = 3
    const val UTURN_RIGHT = 4
    const val BUS = 5
}

/**
 * 车道线提示（SDK `showLaneInfo`）。
 *
 * ## 编码规则（从反编译的 `com.amap.api.col.3nsl.gn` 反推，官方文档没写）
 *
 * `showLaneInfo(laneInfos, laneBackgrounds, laneFronts)` 里：
 * - `laneBackgrounds[i]` = 第 i 条车道的**动作码**（[LaneAction]），数组**以 15 结尾**，
 *   数到 15 就停 —— `count = indexOf(15)`；
 * - `laneFronts[i]` = 该车道能不能走到下一段：**15 = 不可走**，其余 = 可走
 *   （对应 `AMapLaneInfo.isRecommended()` = "十六进制串不以 F 结尾"）；
 * - `laneInfos[i].laneTypeIdArray` = 2 位十六进制：**高 4 位是动作码，低 4 位是可用性**。
 *
 * 之前按"`laneTypeIdArray` 是每车道类型字符（l/r/s）"来解析，是错的 ——
 * 那两个字符其实是十六进制的动作码和可用性位，所以界面上永远只显示得出第一个车道的形状。
 *
 * @param count 车道数
 * @param actions 每条车道的动作码（长度 ≥ count）
 * @param usable 每条车道是否可走（false = 该车道不导向下一步，界面上置灰）
 */
data class LaneGuide(
    val count: Int,
    val actions: IntArray,
    val usable: BooleanArray
) {
    // BooleanArray/IntArray 在 data class 里的 equals 是引用比较，
    // 手写一份按内容的，避免 StateFlow 因"内容没变但引用变了"重复下发整帧。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LaneGuide) return false
        return count == other.count &&
            actions.contentEquals(other.actions) &&
            usable.contentEquals(other.usable)
    }

    override fun hashCode(): Int =
        count * 31 + actions.contentHashCode() * 17 + usable.contentHashCode()

    fun actionAt(lane: Int): Int = actions.getOrElse(lane) { LaneAction.AHEAD }

    fun isUsable(lane: Int): Boolean = usable.getOrElse(lane) { true }

    /** 该车道要画的箭头（并排）。空数组 = 不画（空车道/不可用且无动作） */
    fun arrowsAt(lane: Int): IntArray = when (actionAt(lane)) {
        LaneAction.AHEAD -> intArrayOf(Arrow.STRAIGHT)
        LaneAction.LEFT -> intArrayOf(Arrow.LEFT)
        LaneAction.AHEAD_LEFT -> intArrayOf(Arrow.STRAIGHT, Arrow.LEFT)
        LaneAction.RIGHT -> intArrayOf(Arrow.RIGHT)
        LaneAction.AHEAD_RIGHT -> intArrayOf(Arrow.STRAIGHT, Arrow.RIGHT)
        LaneAction.LEFT_U_TURN -> intArrayOf(Arrow.UTURN_LEFT)
        LaneAction.LEFT_RIGHT -> intArrayOf(Arrow.LEFT, Arrow.RIGHT)
        LaneAction.AHEAD_LEFT_RIGHT -> intArrayOf(Arrow.STRAIGHT, Arrow.LEFT, Arrow.RIGHT)
        LaneAction.RIGHT_U_TURN -> intArrayOf(Arrow.UTURN_RIGHT)
        LaneAction.AHEAD_LEFT_U_TURN -> intArrayOf(Arrow.STRAIGHT, Arrow.UTURN_LEFT)
        LaneAction.AHEAD_RIGHT_U_TURN -> intArrayOf(Arrow.STRAIGHT, Arrow.UTURN_RIGHT)
        LaneAction.LEFT_LEFT_U_TURN, LaneAction.LEFT_IN_LEFT_U_TURN,
        LaneAction.LEFT_LEFT_U_TURN_EX -> intArrayOf(Arrow.LEFT, Arrow.UTURN_LEFT)
        LaneAction.RIGHT_RIGHT_U_TURN, LaneAction.RIGHT_RIGHT_U_TURN_EX ->
            intArrayOf(Arrow.RIGHT, Arrow.UTURN_RIGHT)
        // 直行中的左转：本质是"直行+可选左转"，画直行带左分叉
        LaneAction.LEFT_IN_AHEAD -> intArrayOf(Arrow.STRAIGHT, Arrow.LEFT)
        LaneAction.AHEAD_LEFT_LEFT_U_TURN -> intArrayOf(Arrow.STRAIGHT, Arrow.LEFT, Arrow.UTURN_LEFT)
        LaneAction.LEFT_RIGHT_U_TURN -> intArrayOf(Arrow.LEFT, Arrow.RIGHT, Arrow.UTURN_RIGHT)
        LaneAction.AHEAD_RIGHT_RIGHT_U_TURN -> intArrayOf(Arrow.STRAIGHT, Arrow.RIGHT, Arrow.UTURN_RIGHT)
        LaneAction.BUS -> intArrayOf(Arrow.BUS)
        LaneAction.EMPTY, LaneAction.NULL -> IntArray(0)
        // VARIABLE（可变车道）只能当直行画，别乱猜
        else -> intArrayOf(Arrow.STRAIGHT)
    }

    /** 无障碍/文字兜底用的说明（画不出箭头时，或读屏时） */
    @androidx.annotation.StringRes
    fun describeRes(lane: Int): Int = when (actionAt(lane)) {
        LaneAction.LEFT, LaneAction.LEFT_IN_AHEAD -> com.motohud.ble.R.string.lane_left
        LaneAction.RIGHT -> com.motohud.ble.R.string.lane_right
        LaneAction.LEFT_U_TURN -> com.motohud.ble.R.string.lane_uturn
        LaneAction.AHEAD_LEFT -> com.motohud.ble.R.string.lane_left_front
        LaneAction.AHEAD_RIGHT -> com.motohud.ble.R.string.lane_right_front
        LaneAction.LEFT_RIGHT, LaneAction.AHEAD_LEFT_RIGHT -> com.motohud.ble.R.string.lane_left_right
        LaneAction.BUS -> com.motohud.ble.R.string.lane_bus
        else -> com.motohud.ble.R.string.lane_straight
    }
}


/** 路口放大图（SDK `showCross` 给的位图，直接 setImageBitmap 即可） */
data class CrossImage(val bitmap: Bitmap?) {
    val valid: Boolean get() = bitmap != null && !bitmap.isRecycled
}

/** 前方服务区（SDK `onServiceAreaUpdate`） */
data class ServiceAreaAlert(val name: String, val distanceM: Int)

/** 沿途交通事件（SDK `onNaviRouteNotify` / `getTrafficIncidentInfo`） */
data class RouteIncident(val title: String, val type: Int)
