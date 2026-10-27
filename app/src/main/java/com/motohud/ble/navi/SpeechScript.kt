package com.motohud.ble.navi

/**
 * 把导航状态组装成**一句完整的话**，交给离线合成念出来。
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

    /** 距离词，分档是为了让同一距离上的文本固定、命中合成缓存 */
    fun distance(remainM: Int): String = when {
        remainM <= 0 -> ""
        remainM <= 75 -> "前方 50 米"
        remainM <= 150 -> "前方 100 米"
        remainM <= 250 -> "前方 200 米"
        remainM <= 400 -> "前方 300 米"
        remainM <= 650 -> "前方 500 米"
        remainM <= 1_000 -> "前方 800 米"
        remainM <= 1_500 -> "前方 1 公里"
        remainM <= 3_000 -> "前方 2 公里"
        remainM <= 7_000 -> "前方 5 公里"
        else -> "前方 10 公里"
    }

    /**
     * 转弯播报：「前方 200 米右转进入南滨路」。
     *
     * @param road 本段道路名（高德 `DriveStep.getRoad()`）
     * @param instruction 高德原文，用于从中提取"进入哪条路"
     * @return 空串表示没有可播的动作（该图标不在 [action] 覆盖范围内）
     */
    fun turn(iconType: Int, remainM: Int, road: String, instruction: String): String {
        val act = action(iconType)
        if (act.isEmpty()) return ""
        // 「到达目的地 / 途经点」这类没有"进入某条路"的说法
        val terminal = iconType == IconType.ARRIVED_DESTINATION ||
            iconType == IconType.ARRIVED_WAYPOINT
        val target = if (terminal) "" else targetRoad(instruction, road)
        return buildString {
            append(distance(remainM))
            append(act)
            if (target.isNotEmpty()) {
                append("进入")
                append(target)
            }
        }
    }

    /**
     * 进入某一段时要说的话（此时「距路口」就是整段长度）。
     *
     * 与 200 米提醒分开是因为**两句话文本不同**，缓存里各存一份才都能命中。
     */
    fun enterText(iconType: Int, stepDistanceM: Float, road: String, instruction: String): String =
        turn(iconType, stepDistanceM.toInt(), road, instruction)

    /**
     * 某一段可能要说到的全部文本（供预合成）。
     *
     * 一个路段最多播三次：进入时（整段距离）、详细档 500 米以内、200 米以内。
     * 三档距离分属不同的桶，所以要把几个桶的文本都提前合成好；
     * 否则真正该播时缓存没命中 → 临时合成慢半拍，那一句才迟迟出声。
     */
    fun variants(
        iconType: Int,
        stepDistanceM: Float,
        road: String,
        instruction: String
    ): List<String> {
        val total = stepDistanceM.toInt()
        // 档位要覆盖 [distance] 所有可能输出的距离词：50/100/200/300/500/800米。
        // 少一档就会缓存未命中 → 进导航后首句要临时合成，慢半拍才出声。
        val probes = listOf(total, 800, 500, 300, 200, 100, 50)
            .filter { it in 1..total }
        return probes
            .map { turn(iconType, it, road, instruction) }
            .filter { it.isNotEmpty() }
            .distinct()
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
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return road.trim().takeIf { it.isNotEmpty() && it.length <= 12 } ?: ""
    }

    /** `进入` 之后到标点/空白为止的路名，最长 12 字（再长基本不是路名） */
    private val ENTER_ROAD = Regex("进入([^，。；,;、\\s]{2,12})")
}
