package com.motohud.ble.navi.bridge

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.motohud.ble.navi.IconMapper
import com.motohud.ble.navi.IconType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * 第三方导航桥接的数据层。
 *
 * ## 为什么单独建一套结构，而不复用 NaviEngine.NaviState
 *
 * [com.motohud.ble.navi.NaviEngine.NaviState] 是**本 App 自有路线引擎**的快照，字段全是
 * 非空且必须自洽（经纬度、航向、ETA、轨迹吸附……）。外部导航源只能拿到三件事：
 * 转向图标、下一路口距离、目的地剩余距离，其余一概没有。硬塞进 NaviState 会让
 * "默认值"看起来像真数据（`pathRemain = 0` 会被当成"已到终点"）。
 *
 * 所以这里每个字段都可空，**null = 没拿到 = 不发**。这是需求里
 * "不能把下一路口距离误当成目的地剩余距离" 的落地点。
 */

/** 数据来源。广播通道字段结构化、可信；通知通道只能靠文本推断。 */
enum class BridgeSource(val label: String, val fromBroadcast: Boolean) {
    AMAP_BROADCAST("高德广播", true),
    BAIDU_BROADCAST("百度广播", true),
    AMAP_NOTIFICATION("高德通知", false),
    BAIDU_NOTIFICATION("百度通知", false)
}

/** 统一导航状态（三个业务字段全部可空） */
data class BridgeNaviState(
    val source: BridgeSource,
    val running: Boolean = false,
    val arrived: Boolean = false,
    /** 高德 IconType（百度已换算成同一套数值） */
    val iconType: Int? = null,
    /** 下一路口距离（米） */
    val stepRemainM: Int? = null,
    /** 目的地剩余距离（米） */
    val pathRemainM: Int? = null,
    /** 道路名，仅用于日志与前台通知，不下发车机 */
    val roadName: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** 三项核心字段一个都没有时，这一帧没有转发价值 */
    val hasPayload: Boolean
        get() = iconType != null || stepRemainM != null || pathRemainM != null
}

/**
 * 进程级汇合点。
 *
 * 广播接收器在 [com.motohud.ble.service.NaviBridgeService] 里注册，通知解析在
 * [com.motohud.ble.service.NotificationForwardService] 里做，两者都不持有对方引用，
 * 只往这里投递数据。
 *
 * **优先级：广播 > 通知。** 广播超过 [BROADCAST_SILENCE_MS] 没动静才接受通知数据，
 * 广播一恢复立刻接管 —— 避免两路数据来回跳、距离忽大忽小。
 */
object NaviBridgeHub {

    /** 广播静默多久算"这条路不通"，此后允许通知通道接管 */
    const val BROADCAST_SILENCE_MS = 5_000L

    private val _state = MutableStateFlow<BridgeNaviState?>(null)
    val state: StateFlow<BridgeNaviState?> = _state.asStateFlow()

    /** 最近一次收到广播数据的时间（0 = 本次会话从没收到） */
    @Volatile
    var lastBroadcastAt: Long = 0L
        private set

    /** 转发开关是否打开。服务与通知监听共用同一个判断 */
    @Volatile
    var enabled: Boolean = false

    /**
     * 广播通道投递。
     *
     * 高德一条广播带全部字段；百度是「一条广播只带一项」，所以要按字段合并：
     * 新值优先，缺失的沿用上一帧，否则百度会出现"收到距离就把图标冲成空"。
     */
    fun offerBroadcast(s: BridgeNaviState) {
        lastBroadcastAt = System.currentTimeMillis()
        val prev = _state.value
        _state.value = if (prev != null && prev.source == s.source) {
            prev.copy(
                running = s.running || s.hasPayload,
                arrived = s.arrived,
                iconType = s.iconType ?: prev.iconType,
                stepRemainM = s.stepRemainM ?: prev.stepRemainM,
                pathRemainM = s.pathRemainM ?: prev.pathRemainM,
                roadName = s.roadName.ifBlank { prev.roadName },
                updatedAt = s.updatedAt
            )
        } else {
            s.copy(running = s.running || s.hasPayload)
        }
    }

    /**
     * 通知通道投递。广播还活着就直接丢弃 —— 广播字段更准，不能被文本猜测覆盖。
     */
    fun offerNotification(s: BridgeNaviState) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (lastBroadcastAt > 0 && now - lastBroadcastAt < BROADCAST_SILENCE_MS) return
        _state.value = s.copy(updatedAt = now, running = true)
    }

    /** 广播通道最近是否活跃 */
    fun broadcastAlive(): Boolean =
        lastBroadcastAt > 0 && System.currentTimeMillis() - lastBroadcastAt < BROADCAST_SILENCE_MS

    fun reset() {
        lastBroadcastAt = 0L
        _state.value = null
    }
}

// ---------------------------------------------------------------------------
// 通道一：高德标准广播
// ---------------------------------------------------------------------------

/**
 * 高德状态广播解析（《AmapAuto 标准广播协议_20180813》）。
 *
 * 手机版（com.autonavi.minimap）与车机版（com.autonavi.amapauto）用同一套 action：
 *  - `KEY_TYPE=10001` 引导信息：`ICON` / `NEW_ICON` / `SEG_REMAIN_DIS` / `ROUTE_REMAIN_DIS`
 *  - `KEY_TYPE=10019` 运行状态：`EXTRA_STATE` 8 开始导航 / 9 结束导航 / 39 到达目的地
 *
 * 关键点：`ICON` 给的就是**高德 IconType 数值**，与车机协议同源，
 * 不需要任何文本解析，也就不会把两个距离搞混。
 */
object AmapBroadcast {

    const val ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"

    const val KEY_TYPE = "KEY_TYPE"
    const val TYPE_GUIDE = 10001
    const val TYPE_STATE = 10019

    const val EXTRA_STATE = "EXTRA_STATE"
    const val STATE_START_NAVI = 8
    const val STATE_END_NAVI = 9
    const val STATE_ARRIVED = 39

    const val ICON = "ICON"
    const val NEW_ICON = "NEW_ICON"
    const val SEG_REMAIN_DIS = "SEG_REMAIN_DIS"
    const val ROUTE_REMAIN_DIS = "ROUTE_REMAIN_DIS"
    const val CUR_ROAD_NAME = "CUR_ROAD_NAME"
    const val NEXT_ROAD_NAME = "NEXT_ROAD_NAME"

    /** 解析一帧；不是本协议 / 不是关心的类型时返回 null */
    fun parse(intent: Intent): BridgeNaviState? {
        val b = intent.extras ?: return null
        return when (b.intOf(KEY_TYPE)) {
            TYPE_GUIDE -> {
                // NEW_ICON 比 ICON 新；0 表示"无图标"，不能当成直行下发
                val icon = (b.intOf(NEW_ICON) ?: b.intOf(ICON))?.takeIf { it in 1..70 }
                val step = b.intOf(SEG_REMAIN_DIS)?.takeIf { it >= 0 }
                val path = b.intOf(ROUTE_REMAIN_DIS)?.takeIf { it >= 0 }
                val road = b.strOf(NEXT_ROAD_NAME).orEmpty()
                    .ifBlank { b.strOf(CUR_ROAD_NAME).orEmpty() }
                // 一条引导广播三个字段全空 = 没有可用内容，别拿它覆盖上一帧
                if (icon == null && step == null && path == null) return null
                BridgeNaviState(
                    source = BridgeSource.AMAP_BROADCAST,
                    running = true,
                    iconType = icon,
                    stepRemainM = step,
                    pathRemainM = path,
                    roadName = road
                )
            }

            TYPE_STATE -> when (b.intOf(EXTRA_STATE)) {
                STATE_START_NAVI -> BridgeNaviState(BridgeSource.AMAP_BROADCAST, running = true)
                STATE_ARRIVED -> BridgeNaviState(
                    BridgeSource.AMAP_BROADCAST,
                    running = false,
                    arrived = true,
                    iconType = IconType.ARRIVED_DESTINATION
                )
                STATE_END_NAVI -> BridgeNaviState(BridgeSource.AMAP_BROADCAST, running = false)
                else -> null
            }

            else -> null
        }
    }
}

// ---------------------------------------------------------------------------
// 通道二：百度车机版广播
// ---------------------------------------------------------------------------

/**
 * 百度状态广播解析（百度开放平台 vehicle-opencontrol，官网版本 2.0+）。
 *
 *  - `com.baidu.map.auto.NOTIFY.ACTION_NAVI_INDUCUD` 诱导信息
 *    （`TURN_ICONINFO` / `NEXT_TURN_ICON_DISTANCE` / `LEFT_DISTANCE` / `NEXT_ROAD_NAME` …）
 *  - `com.baidu.map.auto.NOTIFY.ACTION_NAVI_STATUE` 导航开始 / 结束（`NAVI_START` / `NAVI_END`）
 *
 * 百度和高德最大的不同：**一条广播通常只带一个透传项**（action + method + value），
 * 所以这里按"能拿到几个算几个"解析，缺的字段留 null，由 [NaviBridgeHub] 按字段合并。
 *
 * ⚠️ 官方文档只写"bdmap 车机版"。手机版是否发这套广播**未经实测确认**，
 * 所以手机版百度仍然依赖通知兜底。
 */
object BaiduBroadcast {

    const val ACTION_INDUCED = "com.baidu.map.auto.NOTIFY.ACTION_NAVI_INDUCUD"
    const val ACTION_STATE = "com.baidu.map.auto.NOTIFY.ACTION_NAVI_STATUE"

    const val TURN_ICONINFO = "TURN_ICONINFO"
    const val NEXT_TURN_ICON_DISTANCE = "NEXT_TURN_ICON_DISTANCE"
    const val LEFT_DISTANCE = "LEFT_DISTANCE"
    const val CUR_ROAD_NAME = "CUR_ROAD_NAME"
    const val NEXT_ROAD_NAME = "NEXT_ROAD_NAME"

    const val METHOD_NAVI_START = "NAVI_START"
    const val METHOD_NAVI_END = "NAVI_END"

    /**
     * 百度图标名 → 高德 IconType。
     *
     * 百度官方对照表有 40 多个名字，这里只列**高频且语义明确**的；
     * 其余走 [iconOf] 里的关键字兜底。靠左/靠右映射到 65/66 会超出车机图标表，
     * 发送前由 [com.motohud.ble.ble.NaviCommand] 统一裁成直行。
     */
    private val ICON_MAP = mapOf(
        "turn_back" to IconType.LEFT_TURN_AROUND,
        "turn_front" to IconType.STRAIGHT,
        "turn_left" to IconType.LEFT,
        "turn_right" to IconType.RIGHT,
        "turn_left_front" to IconType.LEFT_FRONT,
        "turn_right_front" to IconType.RIGHT_FRONT,
        "turn_left_back" to IconType.LEFT_BACK,
        "turn_right_back" to IconType.RIGHT_BACK,
        "turn_left_side" to IconType.MERGE_LEFT,
        "turn_right_side" to IconType.MERGE_RIGHT,
        "turn_ring" to IconType.ENTER_ROUNDABOUT,
        "turn_ring_front" to IconType.ENTER_ROUNDABOUT,
        "turn_dest" to IconType.ARRIVED_DESTINATION,
        "turn_via_1" to IconType.ARRIVED_WAYPOINT
    )

    fun iconOf(name: String?): Int? {
        val n = name?.trim()?.lowercase().orEmpty()
        if (n.isEmpty()) return null
        ICON_MAP[n]?.let { return it }
        // 兜底按关键字猜；顺序有讲究：先具体后笼统
        return when {
            n.contains("ring") -> IconType.ENTER_ROUNDABOUT
            n.contains("dest") -> IconType.ARRIVED_DESTINATION
            n.contains("via") -> IconType.ARRIVED_WAYPOINT
            n.contains("back") ->
                if (n.contains("left")) IconType.LEFT_BACK else IconType.RIGHT_BACK
            n.contains("left_front") || n.contains("lf_") -> IconType.LEFT_FRONT
            n.contains("right_front") || n.contains("rf_") -> IconType.RIGHT_FRONT
            n.contains("left_side") -> IconType.MERGE_LEFT
            n.contains("right_side") -> IconType.MERGE_RIGHT
            n.contains("left") -> IconType.LEFT
            n.contains("right") -> IconType.RIGHT
            n.contains("front") -> IconType.STRAIGHT
            else -> null
        }
    }

    /** 解析一帧；不是本协议的 action 时返回 null */
    fun parse(intent: Intent): BridgeNaviState? {
        val b = intent.extras ?: return null
        return when (intent.action) {
            ACTION_STATE -> when (b.strOf("method")?.uppercase().orEmpty()) {
                METHOD_NAVI_START -> BridgeNaviState(BridgeSource.BAIDU_BROADCAST, running = true)
                METHOD_NAVI_END -> BridgeNaviState(BridgeSource.BAIDU_BROADCAST, running = false)
                else -> null
            }

            ACTION_INDUCED -> {
                val f = flatten(b)
                val iconName = f[TURN_ICONINFO]
                val step = f[NEXT_TURN_ICON_DISTANCE]?.toIntOrNull()?.takeIf { it >= 0 }
                val path = f[LEFT_DISTANCE]?.toIntOrNull()?.takeIf { it >= 0 }
                val road = f[NEXT_ROAD_NAME].orEmpty().ifBlank { f[CUR_ROAD_NAME].orEmpty() }
                val icon = iconOf(iconName)
                if (icon == null && step == null && path == null) return null
                BridgeNaviState(
                    source = BridgeSource.BAIDU_BROADCAST,
                    running = true,
                    iconType = icon,
                    stepRemainM = step,
                    pathRemainM = path,
                    roadName = road
                )
            }

            else -> null
        }
    }

    /**
     * 把一帧广播摊平成 字段名 → 字符串。
     *
     * 百度不同版本有两种投递形态，两种都要吃得下：
     *  ① 直接把字段当 extra 塞进来（`putExtra("TURN_ICONINFO", "turn_right")`）
     *  ② `action + method + value`，method 才是字段名
     *  ③ 整包 JSON 塞在 `content` 里
     */
    private fun flatten(b: Bundle): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (k in b.keySet()) {
            val v = b.get(k) ?: continue
            val s = when (v) {
                is String -> v
                is Number -> v.toString()
                is Boolean -> v.toString()
                else -> null
            }
            if (!s.isNullOrBlank()) out[k] = s
        }
        // 形态 ②
        val method = b.strOf("method")
        val value = b.strOf("value") ?: b.strOf("result")
        if (!method.isNullOrBlank() && !value.isNullOrBlank()) out[method] = value
        // 形态 ③：从 content 里扒 "KEY":"VALUE"
        b.strOf("content")?.let { json ->
            for (m in JSON_PAIR.findAll(json)) {
                val k = m.groupValues[1]
                val v = m.groupValues[2].trim().trim('"')
                if (!out.containsKey(k)) out[k] = v
            }
        }
        return out
    }

    private val JSON_PAIR = Regex("\"([A-Za-z_]+)\"\\s*:\\s*\"?([^\"',}]+)\"?")
}

// ---------------------------------------------------------------------------
// 通道三：通知文本兜底
// ---------------------------------------------------------------------------

/**
 * 通知文本兜底解析。
 *
 * 只在广播静默时启用，规则刻意保守 —— **宁可少发，也不猜**：
 *  1. 下一路口距离必须凑齐「数字 + 单位 (+后) + 转向动作」的完整句式；
 *  2. 目的地剩余距离必须带「剩余 / 距终点 / 距目的地」这类限定词，且先把上一句的片段扣掉；
 *  3. 转向动作只认白名单关键词，认不出就留 null。
 *
 * 三条任一不满足就返回 null，对应字段不发 —— 这正是需求里
 * "只有在数据确实可用时才发送" 的落点。
 */
object NotifyTextParser {

    const val PKG_AMAP = "com.autonavi.minimap"
    const val PKG_BAIDU = "com.baidu.BaiduMap"

    private const val NUM = "(\\d+(?:\\.\\d+)?)"
    private const val UNIT = "(公里|千米|km|KM|Km|米|m)"

    /** 转向动作白名单，长的写前面（正则交替按顺序匹配） */
    private const val ACTION =
        "(左前方行驶|右前方行驶|进入环岛|驶出环岛|左前方|右前方|左转|右转|掉头|靠左|靠右|直行)"

    /** 「500 米后右转」/「前方 300米 靠右」 */
    private val STEP_RE = Regex("$NUM\\s*$UNIT\\s*(?:后|以后|之后)?\\D{0,3}$ACTION")

    /** 「剩余 12.6 公里」/「距目的地还有 800 米」 */
    private val PATH_RE = Regex("(?:剩余|距终点|距目的地|距离目的地)\\D{0,6}$NUM\\s*$UNIT")

    private val KM_UNITS = setOf("公里", "千米", "km", "KM", "Km")

    /**
     * 转向动作白名单。
     *
     * **刻意不含「到达」**：通知正文里"预计 18:30 到达""到达目的地还有 5 公里"
     * 极其常见，一旦把它当动作就会在路中间点亮"到达目的地"图标。
     * 到达只认广播里的 `EXTRA_STATE=39`，不从文本猜。
     */
    private val ACTIONS = listOf(
        "掉头", "左前方行驶", "右前方行驶", "左前方", "右前方",
        "左转", "右转", "靠左", "靠右", "进入环岛", "驶出环岛", "直行"
    )

    fun sourceOf(pkg: String?): BridgeSource? = when (pkg) {
        PKG_AMAP -> BridgeSource.AMAP_NOTIFICATION
        PKG_BAIDU -> BridgeSource.BAIDU_NOTIFICATION
        else -> null
    }

    /**
     * 解析一条通知。拿不到任何确定字段时返回 null。
     */
    fun parse(sbn: StatusBarNotification): BridgeNaviState? {
        val source = sourceOf(sbn.packageName) ?: return null
        val n = sbn.notification ?: return null
        // 只认导航类通知：高德的营销推送也是同一个包名，不能一概而论
        if (n.category != Notification.CATEGORY_NAVIGATION && !sbn.isOngoing) return null
        val e = n.extras ?: return null

        val fields = listOf(
            e.getCharSequence(Notification.EXTRA_TITLE),
            e.getCharSequence(Notification.EXTRA_TEXT),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT),
            e.getCharSequence(Notification.EXTRA_SUB_TEXT)
        ).mapNotNull { cq -> cq?.toString()?.trim()?.takeIf { it.isNotEmpty() } }

        if (fields.isEmpty()) return null
        val all = fields.joinToString(" ")

        // 1) 先认「X 米 + 转向」，这是最具体的句式，命中后从文本里扣掉避免重复计数
        val stepMatch = STEP_RE.find(all)
        val stepM = stepMatch?.let {
            meters(it.groupValues[1], it.groupValues[2])
        }
        val residual = if (stepMatch != null) {
            all.removeRange(stepMatch.range)
        } else {
            all
        }

        // 2) 再认「剩余 X」，必须有限定词才认，否则宁可不发
        val pathM = PATH_RE.find(residual)?.let {
            meters(it.groupValues[1], it.groupValues[2])
        }

        // 3) 转向只认白名单关键词
        val icon = ACTIONS.firstOrNull { all.contains(it) }?.let { hit ->
            IconMapper.of(hit, null, all)
        }

        if (icon == null && stepM == null && pathM == null) return null
        return BridgeNaviState(
            source = source,
            running = true,
            iconType = icon,
            stepRemainM = stepM,
            pathRemainM = pathM,
            roadName = ""
        )
    }

    private fun meters(num: String, unit: String): Int? {
        val v = num.toDoubleOrNull() ?: return null
        val m = if (unit in KM_UNITS) v * 1000 else v
        return m.roundToInt().takeIf { it >= 0 }
    }
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/**
 * 广播 extras 的类型在不同版本/机型上并不稳定（int / long / String 都见过），
 * 直接 `getIntExtra` 会抛 ClassCastException，所以统一走宽松读取。
 */
private fun Bundle.intOf(key: String): Int? = when (val v = get(key)) {
    null -> null
    is Int -> v
    is Long -> v.toInt()
    is Short -> v.toInt()
    is Double -> v.toInt()
    is String -> v.trim().toIntOrNull()
    else -> null
}

private fun Bundle.strOf(key: String): String? =
    get(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
