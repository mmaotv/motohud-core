package com.motohud.ble.navi

/**
 * 播报文本的**原子层**：把「距离 / 动作 / 路名 / 路口次序 / 环岛出口」拼成短语。
 *
 * 上层叙事（过路口后说什么、要不要带"然后"）在 [VoiceGuidance] 里组合本类。
 *
 * ## 为什么不能直接念高德的 `instruction`
 *
 * 高德的 `DriveStep.instruction` 是给人看的原文：
 *  - 长路段是整句「沿黄桷坪正街行驶 500 米后右转进入南滨路」——
 *    可我们是在**距路口 200 米**时播报，念「行驶 500 米后」是错的；
 *  - 简单的路口只给「右转」两个字，连路名都没有。
 *
 * 所以要文本自己拼：**距离 + 动作 + 目标路名**，合成出来的信息才齐。
 *
 * ## 关键信息不能丢（2026-10-28 补）
 *
 * 高德原文里有两类**决定走不走错**的信息，以前被整个丢掉了：
 *  - 「在第 **2** 个路口左转」——同一个路口连着好几个门面/辅路，不报次序很容易拐错；
 *  - 「在第 **3** 个出口驶出」——环岛不报第几个出口等于没指路。
 * 现在由 [ordinalOf] / [roundaboutExitOf] 抽出来，并入 [turn]。
 * 这两项在开源实现里也是独立字段：Valhalla 的 `roundabout_exit_count`、
 * OSRM Text Instructions 的 `ordinal` / `exit` 提示。
 *
 * ## 距离为什么分档
 *
 * 分档不是为了省字，是为了**命中合成缓存**。缓存 key 是文本本身：
 *  - 精确到米 → 「前方 197 米」和「前方 203 米」是两句，每次都要重新合成；
 *  - 分档后「前方 200 米」是一句固定文本，一次合成、整程复用。
 */
object SpeechScript {

    /**
     * 转向动作词。
     *
     * 覆盖导航引擎认得的全部转向图标，保证每个图标都有对应的中文播报词。
     * 返回空串表示"这个图标没有可播的动作"。
     */
    fun action(iconType: Int): String = when (iconType) {
        IconType.LEFT -> "左转"
        IconType.RIGHT -> "右转"
        IconType.LEFT_FRONT -> "向左前方行驶"
        IconType.RIGHT_FRONT -> "向右前方行驶"
        IconType.LEFT_BACK -> "向左后方行驶"
        IconType.RIGHT_BACK -> "向右后方行驶"
        IconType.LEFT_TURN_AROUND, IconType.U_TURN_RIGHT -> "掉头"
        IconType.STRAIGHT -> "直行"
        IconType.ENTER_ROUNDABOUT -> "进入环岛"
        IconType.ENTRY_RING_LEFT -> "靠左进入环岛"
        IconType.OUT_ROUNDABOUT -> "驶出环岛"
        IconType.MERGE_LEFT -> "向左并线"
        IconType.MERGE_RIGHT -> "向右并线"
        IconType.ARRIVED_WAYPOINT -> "到达途经点"
        IconType.ARRIVED_DESTINATION -> "到达目的地"
        else -> ""
    }

    /**
     * 距离词（带「前方」）。
     *
     * 分档一是为了让同一距离上的文本固定、命中合成缓存；二是让**播报阈值**
     * （[com.motohud.ble.ui.navi.GuidanceActivity] 里的 1km/500/300/200/100/50）
     * 和念出来的字面对得上：阈值 1000 时这里必须给「前方 1 公里」，
     * 所以 800 米这一档的上界收到 900，别把 950 米念成「800 米」。
     *
     * 这套「多远一档、临到路口加密」的距离模型参考自开源的
     * OSRM Text Instructions（BSD-2）与 Valhalla 的语音提示设计。
     */
    fun distance(remainM: Int): String = when {
        remainM <= 0 -> ""
        remainM <= 75 -> "前方 50 米"
        remainM <= 150 -> "前方 100 米"
        remainM <= 250 -> "前方 200 米"
        remainM <= 400 -> "前方 300 米"
        remainM <= 650 -> "前方 500 米"
        remainM <= 900 -> "前方 800 米"
        remainM <= 1_500 -> "前方 1 公里"
        remainM <= 3_000 -> "前方 2 公里"
        remainM <= 7_000 -> "前方 5 公里"
        else -> "前方 10 公里"
    }

    /**
     * 距离词（不带「前方」），给「沿 X 路行驶 **300 米**」这类句式用。
     *
     * 分档与 [distance] 完全一致——同一个距离在两种句式里必须落在同一档，
     * 否则缓存会成倍膨胀（每个距离出现两份文本）。注意这里不能复用
     * [distance] 去切字符串：将来改文案时切出来的会错位。
     */
    fun span(remainM: Int): String = when {
        remainM <= 0 -> ""
        remainM <= 75 -> "50 米"
        remainM <= 150 -> "100 米"
        remainM <= 250 -> "200 米"
        remainM <= 400 -> "300 米"
        remainM <= 650 -> "500 米"
        remainM <= 900 -> "800 米"
        remainM <= 1_500 -> "1 公里"
        remainM <= 3_000 -> "2 公里"
        remainM <= 7_000 -> "5 公里"
        else -> "10 公里"
    }

    /**
     * 路口直行播报：「前方 200 米，路口直行进入南滨路」。
     *
     * 转弯有 [turn]，但很多路口是**直行穿过**——不提醒的话骑手到路口容易
     * 跟着车流拐错。这里明确说「直行」，并把接下来的路名带上。
     */
    fun straight(remainM: Int, instruction: String): String {
        val d = distance(remainM)
        if (d.isEmpty()) return ""
        // 只有原文明确写了「进入 X 路」才报路名。直行段的 road 往往是**当前所在的
        // 那条路**，拿它来说「直行进入」会把"继续沿本路"说成"进入本路"，反而误导。
        val target = ENTER_ROAD.find(instruction)?.groupValues?.getOrNull(1)?.trim()
        return if (!target.isNullOrEmpty() && target.length <= 12) {
            "$d，路口直行进入$target"
        } else {
            "$d，路口直行"
        }
    }

    /**
     * 转弯播报：「前方 200 米右转进入南滨路」。
     *
     * 有路口次序时补上「第 2 个路口」；是环岛则改成「走第 3 个出口」。
     * 这两项只在高德原文里出现过才加，没有就与旧版逐字一致（不回退信息量）。
     *
     * @param road 本段道路名（高德 `DriveStep.getRoad()`）
     * @param instruction 高德原文，用于提取"进入哪条路"、"第几个路口/出口"
     * @return 空串表示没有可播的动作（该图标不在 [action] 覆盖范围内）
     */
    fun turn(iconType: Int, remainM: Int, road: String, instruction: String): String {
        val act = action(iconType)
        if (act.isEmpty()) return ""
        // 「到达目的地 / 途经点」这类没有"进入某条路"的说法
        val terminal = iconType == IconType.ARRIVED_DESTINATION ||
            iconType == IconType.ARRIVED_WAYPOINT
        val target = if (terminal) "" else targetRoad(instruction, road)
        val d = distance(remainM)
        if (d.isEmpty()) return ""

        // 环岛：出口序号比「进入环岛」有用得多（Valhalla 的 roundabout_exit_count）
        val exit = roundaboutExitOf(instruction)
        if (exit in 2..8 && isRoundabout(iconType)) {
            val head = "$d，进入环岛，走第 $exit 个出口"
            return if (target.isNotEmpty()) "$head，驶入$target" else head
        }

        // 「第 1 个路口左转」等同于左转，报出来是噪音；只有 ≥2 才有区分度
        val ord = ordinalOf(instruction)
        return buildString {
            append(d)
            if (ord in 2..9) {
                append("，第 ")
                append(ord)
                append(" 个路口")
            }
            append(act)
            if (target.isNotEmpty()) {
                append("进入")
                append(target)
            }
        }
    }

    /**
     * 只含动作与路名的短语（不带距离、不带「前方」）。
     *
     * 给「…，然后 **右转进入南滨路**」这种链式预告用：第二段不能再带一次距离，
     * 否则车里会听到「前方 200 米右转，然后前方 300 米左转」这种别扭句子。
     *
     * 环岛特殊：目标是「走第几个出口」，不是"进入环岛"（[targetRoad] 对环岛
     * 不做退回，否则会拼出"驶出环岛进入环岛"这种语病）。
     */
    fun maneuverShort(iconType: Int, road: String, instruction: String): String {
        val act = action(iconType)
        if (act.isEmpty()) return ""
        if (iconType == IconType.ARRIVED_DESTINATION || iconType == IconType.ARRIVED_WAYPOINT) return act
        val exit = roundaboutExitOf(instruction)
        if (isRoundabout(iconType)) {
            return if (exit in 2..8) "进入环岛，走第 $exit 个出口" else act
        }
        val target = targetRoad(instruction, road)
        return if (target.isNotEmpty()) "${act}进入$target" else act
    }

    // 注：进段确认 / 阈值临门指令 / 预合成文本均由叙事层 VoiceGuidance 组合，
    // 本类只提供不带叙事的原子短语。

    // ---------- 结构化事实抽取 ----------

    /** 是否环岛类图标 */
    fun isRoundabout(iconType: Int): Boolean = iconType == IconType.ENTER_ROUNDABOUT ||
        iconType == IconType.ENTRY_RING_LEFT ||
        iconType == IconType.OUT_ROUNDABOUT

    /**
     * 「在第 2 个路口左转」→ 2；没说次序返回 0。
     *
     * 只认 1~9：路口次序超过 9 个基本是高德把长距离描述塞进来了，报了反而干扰。
     */
    fun ordinalOf(instruction: String): Int {
        val raw = ORDINAL.find(instruction)?.groupValues?.getOrNull(1) ?: return 0
        return cnNum(raw).takeIf { it in 1..9 } ?: 0
    }

    /** 「在第 3 个出口驶出」→ 3；没说返回 0 */
    fun roundaboutExitOf(instruction: String): Int {
        val raw = EXIT.find(instruction)?.groupValues?.getOrNull(1) ?: return 0
        return cnNum(raw).takeIf { it in 1..9 } ?: 0
    }

    /**
     * 这个字符串能不能当路名念出来。
     *
     * 高德会用「当前道路 / 无名道路 / 环岛」这类词占位，念出来是噪音：
     * 「继续沿环岛行驶」——环岛不是一条路，应该说「继续直行」。
     */
    fun isUsableRoad(road: String): Boolean {
        val r = road.trim()
        if (r.isEmpty() || r.length > 12) return false
        return r !in PLACEHOLDER_ROAD
    }

    /**
     * 从高德原文里抠出「进入某某路」。
     *
     * 原文形如「…后右转进入南滨路」，`进入` 后面的就是目标路名。
     * 抠不到才退回 [road]（高德这一项给的是本段道路名，只在没有明确目标路时
     * 当补充信息用 —— 有明确目标路还用它，会把"进入 A 路"说成当前所在的路）。
     */
    private fun targetRoad(instruction: String, road: String): String {
        ENTER_ROAD.find(instruction)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() && it !in PLACEHOLDER_ROAD }
            ?.let { return it }
        return road.trim().takeIf { isUsableRoad(it) } ?: ""
    }

    /** 高德会用这些词占位，它们不是真实路名 */
    private val PLACEHOLDER_ROAD = setOf(
        "当前道路", "无名道路", "未知道路", "道路", "环岛", "-", "无"
    )

    /**
     * 阿拉伯数字与中文小写数字 → Int（只处理 1~99，够路口/出口用了）。
     *
     * 高德两种写法都会出现：「第2个路口」和「第二个路口」，所以不能只匹配 `\d`。
     */
    private fun cnNum(s: String): Int {
        val t = s.trim()
        if (t.isEmpty()) return 0
        t.toIntOrNull()?.let { return it }
        val digits = "零一二三四五六七八九"
        if (t.length == 1) {
            val i = digits.indexOf(t)
            if (i >= 0) return i
            if (t == "两") return 2
            return 0
        }
        // 十几 / 二十几 / 几十
        val ten = t.indexOf('十')
        if (ten < 0) return 0
        val hi = if (ten == 0) 1 else digits.indexOf(t[0]).takeIf { it > 0 } ?: 0
        val lo = if (ten == t.length - 1) 0 else digits.indexOf(t[ten + 1]).takeIf { it > 0 } ?: 0
        return hi * 10 + lo
    }

    /** `进入` 之后到标点/空白为止的路名，最长 12 字（再长基本不是路名） */
    private val ENTER_ROAD = Regex("进入([^，。；,;、\\s]{2,12})")

    /** 「第 2 个路口」/「第二个路口」 */
    private val ORDINAL = Regex("第\\s*([0-9两一二三四五六七八九十]{1,3})\\s*个?\\s*路口")

    /** 「第 3 个出口」/「第3出口」 */
    private val EXIT = Regex("第\\s*([0-9两一二三四五六七八九十]{1,3})\\s*个?\\s*出口")
}
