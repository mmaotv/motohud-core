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
    // 7 是空缺 —— 官方枚举里没有这一号，别自己编含义。
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

    // ------------------------------------------------- 细化类型（≥ F_FIRST）

    /**
     * 这些**不在 SDK 枚举里**，是从官方播报文本里解析出来的（见 [CameraText]）。
     *
     * 起点取 100，与 `CameraType` 的 0~11 彻底隔开：任何 `when` 漏了细化类型
     * 都会落到 `else`，不会被误判成某个真实枚举值的含义。
     */
    const val F_FIRST = 100

    const val F_PRESS_LINE = 100        // 压线拍照
    const val F_SEATBELT = 101          // 不系安全带拍照
    const val F_PHONE = 102             // 接打电话拍照
    const val F_ILLEGAL = 103           // 违法 / 违章拍照（官方兜底词）
    const val F_RED_LIGHT = 104         // 闯红灯拍照
    const val F_SPEED = 105             // 超速 / 测速拍照
    const val F_OCCUPY_EMERGENCY = 106  // 占用应急车道
    const val F_OCCUPY_BUS = 107        // 占用公交道
    const val F_WRONG_WAY = 108         // 逆行
    const val F_PARKING = 109           // 违停
    const val F_GENERIC = 110           // 词表外的类型（只显示"电子眼"）

    fun isFine(type: Int): Boolean = type >= F_FIRST

    // ---------------------------------------------------------------- 分类

    /**
     * 播报分组。**按「该不该打断骑手」分组，不按 SDK 枚举分组**。
     *
     * 为什么不能一个类型一组：真机实测（重庆观音桥 1.3km，2026-10-31）15 条电子眼提示里
     * 14 条是 `BREAK_RULE`。它们本身没有更细的语义（SDK 只有这 11 类，压线/逆行/
     * 不礼让行人在高德 App 上有、在这个 SDK 里全落进 `BREAK_RULE`），
     * 一个点位念一遍就是"每 6 秒一句同样的废话"。
     * 所以 `BREAK_RULE`/`SURVEILLANCE` 归为**只上屏不念**：信息在屏幕上，
     * 耳朵留给真正有动作含义的三类。
     */
    const val G_SPEED = 0      // 测速 / 区间测速：唯一带限速的组，最该说
    const val G_SIGNAL = 1     // 闯红灯：跟路口绑定，说一次够
    const val G_LANE = 2       // 占道类：只有正好走那条道才有意义
    const val G_VIOLATION = 3  // 通用违章：兜底大类，只上屏不念
    const val G_NONE = 4       // 车流量检测 / ETC：不是"违法风险"，不上屏也不念

    fun group(type: Int): Int = when (type) {
        SPEED, INTERVAL_START, INTERVAL_END, F_SPEED -> G_SPEED
        TRAFFIC_LIGHT, F_RED_LIGHT -> G_SIGNAL
        BUS_WAY, EMERGENCY, BICYCLE, F_PRESS_LINE, F_OCCUPY_EMERGENCY, F_OCCUPY_BUS,
        F_WRONG_WAY -> G_LANE
        // 「不系安全带」「接打电话」「违停」对骑摩托的人没有可执行的动作：
        // 安全带跟你无关、手机放兜里、违停不是你的诉求 —— 只上屏，别打断播报。
        BREAK_RULE, SURVEILLANCE, F_SEATBELT, F_PHONE, F_ILLEGAL, F_PARKING, F_GENERIC ->
            G_VIOLATION
        else -> G_NONE
    }

    /**
     * 播报距离档（米，**由远到近**）。空数组 = 不念。
     *
     * 档位值必须落在 [SpeechScript.span] 能念出来的那几个词上
     * （50/100/200/300/500/800/1 公里）—— 内置语音包是预录片段，
     * 录一个"150 米"这种档位就要多一条音频，且永远只可能用到一次。
     *
     * 测速给两档（远程预警 + 临门确认），另外两组只一档：
     * 闯红灯/占道的应对动作是"别闯、别占"，说两遍不会让人更守法，只会更吵。
     */
    fun tiersOf(type: Int): IntArray = when {
        // 细化类型来自官方文本，官方一次就给到"三百米内/一公里内"，只念一次就够：
        // 这类情报的价值在"提前知道有压线"，不在于到跟前再确认一遍。
        isFine(type) -> intArrayOf(300)
        group(type) == G_SPEED -> intArrayOf(500, 200)
        group(type) == G_SIGNAL -> intArrayOf(200)
        group(type) == G_LANE -> intArrayOf(100)
        else -> IntArray(0)
    }

    /**
     * 串内代表类型的优先级：同一串（见 `CAMERA_CLUSTER_M`）里挑最重要的一个当代表。
     * 一串"测速 + 三个违章拍照"要说的是测速，不是三个违章。
     */
    fun rank(type: Int): Int = when (group(type)) {
        G_SPEED -> 3
        G_SIGNAL -> 2
        G_LANE -> 1
        G_VIOLATION -> 0
        else -> -1
    }

    /** 是否上屏（G_NONE 连界面都不占） */
    fun worthShowing(type: Int): Boolean = group(type) != G_NONE

    /** 是否值得打断播报：只有测速/闯红灯/占道三类 */
    fun worthSpeaking(type: Int): Boolean = group(type) <= G_LANE

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
        F_PRESS_LINE -> com.motohud.ble.R.string.camera_press_line
        F_SEATBELT -> com.motohud.ble.R.string.camera_seatbelt
        F_PHONE -> com.motohud.ble.R.string.camera_phone
        F_ILLEGAL -> com.motohud.ble.R.string.camera_illegal
        F_RED_LIGHT -> com.motohud.ble.R.string.camera_traffic_light
        F_SPEED -> com.motohud.ble.R.string.camera_speed
        F_OCCUPY_EMERGENCY -> com.motohud.ble.R.string.camera_emergency
        F_OCCUPY_BUS -> com.motohud.ble.R.string.camera_bus_way
        F_WRONG_WAY -> com.motohud.ble.R.string.camera_wrong_way
        F_PARKING -> com.motohud.ble.R.string.camera_parking
        else -> com.motohud.ble.R.string.camera_generic
    }

    /**
     * 界面色标（P3）：远看一眼就知道是"要减速"还是"别闯灯"还是"随便看看"。
     *
     * 红灯色给测速（最需要立刻反应）、橙黄给信号灯、黄色给占道、灰白给通用违章。
     * 不用图标：横屏侧栏这一行只有一行字高，塞图标会把文字挤到省略。
     */
    @androidx.annotation.ColorRes
    fun colorRes(type: Int): Int = when (group(type)) {
        G_SPEED -> com.motohud.ble.R.color.red
        G_SIGNAL -> com.motohud.ble.R.color.orange
        G_LANE -> com.motohud.ble.R.color.yellow
        else -> com.motohud.ble.R.color.text_secondary
    }
}

/**
 * 电子眼 / 区间测速提示。
 *
 * @param id 引擎给这一个电子眼分配的流水号（同一次经过始终不变）。
 *   播报去重必须按它来，不能按"类型+限速"——一条路上连着两个同型号测速头，
 *   按类型去重会把第二个整个吞掉，用户以为漏报。
 * @param type [CameraKind] 之一
 * @param distanceM 距该电子眼的米数（引擎按车速逐秒递减，不是回调时的快照）
 * @param speedKmh 该点限速（0 = 未知）。高德把限速随电子眼一起下发，是免费能力。
 * @param interval 是否区间测速
 * @param intervalRemainM 区间测速剩余里程（仅区间测速有意义）
 * @param clusterCount 这一串（相邻 ≤`CAMERA_CLUSTER_M` 米）里有几个电子眼。
 *   `1` = 孤零零一个。引擎算好带下来，播报按"串"只说一次、界面显示 ×N。
 */
data class CameraAlert(
    val type: Int,
    val distanceM: Int,
    val speedKmh: Int = 0,
    val interval: Boolean = false,
    val intervalRemainM: Int = 0,
    val id: Int = 0,
    val clusterCount: Int = 1
) {
    /**
     * 去重键：**同一个点位**在**同一个距离档**里只播一次。
     *
     * 用档位值（200/500…）而不是 `distanceM / 400` 这种机械分桶：
     * 机械分桶的边界（400/800）和念出来的距离词（300/500/800）对不上，
     * 会出现"420 米说 500 米、390 米说 300 米"这种前后矛盾。
     *
     * @param tier 命中的档位值；`0` 表示"点位出现得太晚，档位已经过去"，
     *   这时按实际距离兜底念一句（见 GuidanceActivity.pickCameraTier）。
     * @param exact true = 兜底那句（按实际距离念），与正常跨档要分开记，
     *   否则"90 米兜底"和"200 米跨档"会撞同一个键、第二句被吞。
     */
    fun speechKey(tier: Int, exact: Boolean = false): String =
        if (exact) "$id|$tier|e" else "$id|$tier"

    companion object {
        /**
         * [clusterCount] 的特殊值：官方文本说「有多个」，但没给具体数量。
         * 界面显示「（多处）」，不编数字。
         */
        const val MULTI = -1
    }
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
/**
 * 路口放大图。
 *
 * 有**两种来源**，字段跟着两种走（见 [VectorCrossRenderer] 的说明）：
 *  - 栅格图：`showCross(AMapNaviCross)`，SDK 已解码好的位图 → [bitmap] 直接可用
 *  - 矢量图：`showModeCross(AMapModelCross)`，给的是**自定义二进制**（不是 PNG）
 *    → 只填 [vector]，由导航页交给 `AMapModeCrossOverlay` 渲染成位图后回灌
 *
 * @param seq 流水号，每次收到新的放大图回调 +1。
 *
 * **不能靠 bitmap 引用判断要不要刷新界面**：SDK 每次回调很可能复用**同一个 Bitmap
 * 实例**（把像素重新写进去），引用相等但内容已经变了。UI 用 `tag !== bitmap` 判重
 * 就会认定"没变化"、不重新 setImageBitmap，结果整程只看到第一张路口图 ——
 * 用户反馈的"只展示第一张，后面再也不展示"就是这个原因。
 * 现在以 seq 为准：seq 变了就重新设一次。
 */
data class CrossImage(
    val bitmap: Bitmap? = null,
    val seq: Int = 0,
    /** 矢量数据，非空表示「还没渲染」。渲染完由引擎替换成带 bitmap 的同一个 seq */
    val vector: ByteArray? = null
) {
    val valid: Boolean get() = bitmap != null && !bitmap.isRecycled

    /** 需要导航页用 GL 渲染（矢量图还没出位图） */
    val needsRender: Boolean get() = bitmap == null && vector != null
}

/** 前方服务区（SDK `onServiceAreaUpdate`） */
data class ServiceAreaAlert(val name: String, val distanceM: Int)

/** 沿途交通事件（SDK `onNaviRouteNotify` / `getTrafficIncidentInfo`） */
data class RouteIncident(val title: String, val type: Int)
