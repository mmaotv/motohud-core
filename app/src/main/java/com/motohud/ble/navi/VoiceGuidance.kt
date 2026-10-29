package com.motohud.ble.navi

/**
 * 播报叙事层：决定「什么时候该说哪一类话」，文本由 [SpeechScript] 拼。
 *
 * ## 三段式：照着 Valhalla 的 ODIN 叙事模型搬的
 *
 * Valhalla 每条 maneuver 输出三句（见其 turn-by-turn API 的 `verbal_*` 字段）：
 *  - `verbal_transition_alert_instruction`：**提前提醒**——远处先给一次预警；
 *  - `verbal_pre_transition_instruction`：**临门指令**——快到路口时再说一遍；
 *  - `verbal_post_transition_instruction`：**过渡后确认**——"Continue on U.S. 2 22
 *    for 3.9 miles"，刚拐完立刻告诉你**现在这条路要骑多远、不用再动方向**。
 *
 * 我们以前只有前两段（阈值 1km/500/…/50 都在说同一件事：路口怎么转），
 * **完全没有第三段**。这正是用户反馈"每经过一个路口不知道要往哪走、容易走错"
 * 的根因：拐完弯的那一刻是最需要确认"我在对的路上了"的时刻，而以前这时候
 * 已经直接跳到播下一个路口了。
 *
 * 所以 [entry] 补上 post 段：
 *  - 长段：「继续沿南滨路行驶 2 公里」——不用再管方向；
 *  - 短段：「继续沿南滨路行驶 300 米，**然后**右转进入烟雨路」——一次说清两件事。
 *
 * ## 链式合并（multi-cue）
 *
 * Valhalla 用 `verbal_multi_cue` 标记"本条指令已把下一条拼进去"，OSRM Text
 * Instructions 对应的是 `then` 模板：「In 500 meters turn right onto A, then turn
 * left onto B」。两个紧邻的路口如果分开播，第二句往往等到来不及才说。
 * [pre] 与 [entry] 都按这个思路合并：**下一段够短就把下一句带上**。
 *
 * ## 为什么不直接把 OSRM / Valhalla 当依赖
 *
 * - `osrm-text-instructions.java`（BSD-2）只有 2017 年两次提交，官方 Android 版
 *   靠 J2V8 跑 JS 包，引入一个 JS 引擎为了一句播报不划算；
 * - Valhalla 是 C++ 路由引擎，要自带 OSM 瓦片（上百 MB），我们算路走的是高德
 *   在线服务 + 车机投屏用的是高德 IconType 协议，换底座等于推倒重来。
 *
 * 所以取它们的**叙事设计**，落到本项目的数据模型上——这才是"不重复造轮子"
 * 的正确姿势：借用被验证过的规则，而不是把别人的整套运行时塞进来。
 */
object VoiceGuidance {

    /** 本段短于该米数 → 进段时把下一句转弯一起说（"行驶 300 米，然后右转"） */
    const val MULTI_CUE_M = 500f

    /** 本段短于该米数 → 进段时不单独报"继续沿 X 行驶"，直接交给阈值播报 */
    const val POST_MIN_M = 250

    /** 距路口近于该米数才允许链式「然后…」：远处预告下一句会让人误以为马上要转 */
    const val CHAIN_AT_M = 250

    /**
     * 连续路口压缩：总跨度超过该米数就不再往后并段。
     *
     * 高德 `compress.remind.initMaxDist = 800` —— 两个路口相距 800 米以上就
     * 不算"连续路口"了，各说各的；挤在一起说反而让人以为马上要连着转两次。
     */
    const val COMPRESS_MAX_TOTAL_M = 800f

    /**
     * 最多往后并几段。
     *
     * 高德分三级（间隔 500~700 / 350~500 / 250~350），级数越高说明路口越密、
     * 越该并成一句。但人一次能记住的转向不超过三个，所以**封顶两段**：
     * 当前动作 + 两个后续动作，再多就是念经了。
     */
    const val COMPRESS_MAX_TAIL = 2

    /**
     * 距路口小于该米数 → **只报动作**，不再报距离和路名。
     *
     * 这是播报阶梯最后一档的**兜底值**，实际距离由 [closeDistOf] 按车速给出。
     * 到跟前了，骑手只关心"左还是右、第几个出口"，一句「右转」远比
     * 「前方 50 米右转进入烟雨路」有效——后者还没念完人已经过路口了。
     */
    const val CLOSE_M = 50

    /**
     * 临门播报距离：按当前车速折算「约 3 秒的提前量」。
     *
     * ## 为什么不是固定 50 米
     *
     * 固定值两头都不合适：
     *  - 70 km/h 时 50 米只有 **2.6 秒**，「前方 50 米右转」还没念完已经过路口；
     *  - 20 km/h 时 50 米有 **9 秒**，干等一句「右转」反而干扰判断。
     *
     * ## 依据
     *
     * 高德内置的播报调度表（`libamaptbt.so` 里的 `skilledMode.confirmDist`）
     * 是**按道路等级**给的：高速 80 米 / 快速路 70 / 主干道 50 / 县道 25 / 小路 30。
     * 我们拿不到道路等级（`DriveStep` 没有该字段，只有 instruction/road/distance/duration），
     * 改用**车速**近似——效果等价：快车早报、慢车晚报。
     *
     * 分档取整到 15 的倍数，让同一车速区间落到同一档，避免在档位边界来回抖动。
     */
    fun closeDistOf(speedKmh: Float): Int = when {
        speedKmh >= 65f -> 80   // 高速 / 高架：80 米 ≈ 4.4 秒 @65km/h
        speedKmh >= 45f -> 60   // 快速路 / 郊区：60 米 ≈ 4.8 秒 @45km/h
        speedKmh >= 25f -> 45   // 市区正常：45 米 ≈ 6.5 秒 @25km/h
        else -> 30              // 拥堵 / 慢行：30 米 ≈ 5.4 秒 @20km/h
    }

    /**
     * 过渡后确认（进段播报）：刚通过路口/刚进入新路段时说。
     *
     * @param current 当前所在路段（`steps[i]`）
     * @param icon    当前路段**末端的转向图标**（即路口动作）
     * @param remainM 距本段结束（下一个路口）还有多远；进段时就是整段长度
     * @return 空串表示这段太短/无可用信息，不值得开口
     */
    fun entry(current: RouteStep, icon: Int, remainM: Float): String {
        val d = remainM.toInt()
        if (d < POST_MIN_M) return ""
        val span = SpeechScript.span(d)
        if (span.isEmpty()) return ""
        val road = current.road.trim().takeIf { SpeechScript.isUsableRoad(it) }
        // 同一个句式听一路会腻，所以按 route 原文取一个**稳定**的变体号：
        // 用 instruction 的 hash 而不是随机数，保证同一段路每次算出来都是同一句，
        // 否则预合成的文本和运行时说的文本对不上，缓存直接失效。
        // 只有 road 存在时才有变体；内置语音包把 road 清空了，两边都走「继续直行 X」，
        // 不生成新文本，语音包那 153 条照样命中。
        val variant = if (road == null) 0 else (current.instruction.hashCode() ushr 4) and 1
        val head = when {
            road == null -> "继续直行 $span"
            variant == 1 -> "接下来 $span 沿${road}行驶"
            else -> "继续沿${road}行驶 $span"
        }
        // 短段：一次说完"走多远 + 接着怎么转"，避免第二句等到来不及才说
        if (d <= MULTI_CUE_M) {
            var act = SpeechScript.maneuverShort(icon, current.road, current.instruction)
            // 转弯目标就是当前这条路（高德写辅路/绕行时会出现）→ 只报动作，别复读路名，
            // 否则会听到"继续沿烟雨路行驶 300 米，然后右转进入烟雨路"
            if (road != null && act.endsWith(road)) act = SpeechScript.action(icon)
            val merged = chain(head, act)
            if (merged != head) return merged
        }
        return head
    }

    /**
     * 临门指令（阈值播报）：距路口 remainM 米时说。
     *
     * 两段式：
     *  - **最后一档（≤ [CLOSE_M] 米）**：只报动作——「右转」「向右前方行驶」
     *    「进入环岛，第 3 个出口」。到跟前再报距离和路名只会拖慢，来不及。
     *  - 更远的档位：距离 + 动作 + 目标路名；直行路口走 [SpeechScript.straight]，
     *    其余走 [SpeechScript.turn]。
     *
     * 链式「然后…」只在 **距路口 ≤ [CHAIN_AT_M] 米** 时才补：1 公里/500 米处
     * 说「然后左转进入福红路」，骑手会以为马上要左转，而那是八百米以后的事。
     *
     * @param next 下一段（`steps[i+1]`），为 null 表示本段是最后一段
     * @param closeDist 最后一档的距离（[closeDistOf] 按车速算），小于等于它就只报动作
     */
    fun pre(
        current: RouteStep,
        icon: Int,
        remainM: Int,
        next: RouteStep?,
        closeDist: Int = CLOSE_M
    ): String {
        val tails = if (next == null) emptyList() else tailsFrom(listOf(next), remainM)
        return preWithTails(current, icon, remainM, tails, closeDist)
    }

    /**
     * 带路线上下文的临门指令：会自动把后面 **连续多个** 短路口的动作并进来。
     *
     * 城区骑行最难受的一段是「右转、马上左转、马上再右转」——按老逻辑每段各播一次，
     * 第一句还没念完人已经到第二个路口了。高德的 `compress` 就是干这个的：
     * 两路口间隔落在 500~700 / 350~500 / 250~350 三级区间时，把几句压成一句。
     *
     * 这里按总跨度（[COMPRESS_MAX_TOTAL_M]，高德的 initMaxDist=800）往后并，
     * 最多并 [COMPRESS_MAX_TAIL] 段，输出形如：
     * 「前方 200 米右转进入A路，然后左转进入B路，然后右转进入C路」。
     *
     * @param steps 整条路线的步骤表
     * @param index 当前所在段的下标
     */
    fun pre(
        steps: List<RouteStep>,
        index: Int,
        remainM: Int,
        closeDist: Int = CLOSE_M
    ): String {
        val current = steps.getOrNull(index) ?: return ""
        val icon = IconMapper.of(current.action, current.orientation, current.instruction)
        return preWithTails(current, icon, remainM, compressTail(steps, index, remainM), closeDist)
    }

    /**
     * 往后能并进来的后续动作（不含距离词）。
     *
     * 三个终止条件，任一命中就停：
     *  - 距当前路口还远（> [CHAIN_AT_M]）—— 远处预告下一句会被误当成"马上要转"；
     *  - 累计跨度超过 [COMPRESS_MAX_TOTAL_M]；
     *  - 已经并了 [COMPRESS_MAX_TAIL] 段。
     */
    fun compressTail(steps: List<RouteStep>, index: Int, remainM: Int): List<String> {
        if (remainM > CHAIN_AT_M) return emptyList()
        // 当前段的动作也算"上一句"：高德常把同一句写两遍（进辅路、绕行），
        // 「前方 200 米右转进入A路，然后右转进入A路」听着像卡带
        val cur = steps.getOrNull(index)
        val curAct = if (cur == null) "" else {
            val icon = IconMapper.of(cur.action, cur.orientation, cur.instruction)
            SpeechScript.maneuverShort(icon, cur.road, cur.instruction)
        }
        return tailsFrom(steps.drop(index + 1), remainM, curAct)
    }

    private fun tailsFrom(
        following: List<RouteStep>,
        remainM: Int,
        exclude: String = ""
    ): List<String> {
        if (remainM > CHAIN_AT_M) return emptyList()
        val out = ArrayList<String>(COMPRESS_MAX_TAIL)
        var acc = 0f
        var last = exclude
        for (s in following) {
            if (out.size >= COMPRESS_MAX_TAIL) break
            val d = s.distanceM
            if (d <= 0f) break
            if (acc + d > COMPRESS_MAX_TOTAL_M) break
            val icon = IconMapper.of(s.action, s.orientation, s.instruction)
            val act = SpeechScript.maneuverShort(icon, s.road, s.instruction)
            if (act.isEmpty()) break
            if (act != last) {
                out.add(act)
                last = act
            }
            acc += d
        }
        return out
    }

    private fun preWithTails(
        current: RouteStep,
        icon: Int,
        remainM: Int,
        tails: List<String>,
        closeDist: Int
    ): String {
        // 最后一档：短促的动作词，不带距离、不带"然后"
        if (remainM <= closeDist) return SpeechScript.actionOnly(icon, current.instruction)
        val base = if (icon == IconType.STRAIGHT) {
            SpeechScript.straight(remainM, current.instruction)
        } else {
            SpeechScript.turn(icon, remainM, current.road, current.instruction)
        }
        if (base.isEmpty() || tails.isEmpty()) return base
        return tails.fold(base) { acc, tail -> chain(acc, tail) }
    }

    /**
     * 拼接「，然后…」，**重复的链式短语直接丢掉**。
     *
     * 骑行实测会撞上两种情况：
     *  - 高德连续两段都写「右转进入烟雨路」（进辅路、绕行），不过滤就会听到
     *    「前方 200 米右转进入烟雨路，然后右转进入烟雨路」；
     *  - 环岛段的 road 就是「环岛」，不过滤会拼出「然后驶出环岛进入环岛」。
     *
     * 车里一句话复读，比少一句严重得多，所以这里宁可吞掉。
     */
    private fun chain(base: String, tail: String): String {
        if (tail.isEmpty()) return base
        if (base.contains(tail)) return base
        return "$base，然后$tail"
    }

    /**
     * 一段路可能要说到的全部文本（供预合成）。
     *
     * 只生成**真会被说出口**的组合，不做笛卡尔积：离线引擎单句合成
     * 0.5~1.5s，骑行中把 CPU 喂给用不到的句子会拖慢真正的播报。
     *
     * 覆盖范围：进段确认（含/不含"然后"）+ 各播报阈值上的临门指令（含/不含"然后"）；
     * 路口次序、环岛出口已经并进了文本本身，不需要额外维度。
     */
    fun prewarmTexts(steps: List<RouteStep>, index: Int): List<String> {
        val step = steps.getOrNull(index) ?: return emptyList()
        val icon = IconMapper.of(step.action, step.orientation, step.instruction)
        val next = steps.getOrNull(index + 1)
        val total = step.distanceM.toInt()
        if (total <= 0) return emptyList()
        val out = ArrayList<String>(10)

        // 进段播报：长段只有"继续沿 X 行驶 N"，短段带"然后…"，两种形态都备
        entry(step, icon, step.distanceM).takeIf { it.isNotEmpty() }?.let { out.add(it) }

        // 阈值播报：与 GuidanceActivity 的阶梯（[TURN_THRESHOLDS]）逐档一致；
        // 带/不带"然后"都要备，因为下一段长度是固定的，但播报点在哪个阈值上不固定。
        // 这里的 [pre] 传整条 steps，会按同一套 compress 规则把连续路口并进来 ——
        // 运行时 stepText 用的也是这个重载，两边文本必须逐字一致，否则缓存不命中。
        //
        // 最后一档的**距离**是动态的（[closeDistOf]，30~80 米），但它的**文本**
        // 只由 [SpeechScript.actionOnly] 决定、与距离无关，所以这里固定 probe 50
        // 就能覆盖全部情形 —— 运行时无论 closeDist 取 30 还是 80，说的都是同一句。
        for (probe in intArrayOf(1_100, 550, 50)) {
            if (probe > total) continue
            pre(steps, index, probe).takeIf { it.isNotEmpty() }?.let { out.add(it) }
            pre(step, icon, probe, null).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        // 进段那一刻整段长度上的临门指令（短段会被当成"前方 X 米转弯"播一次）
        pre(steps, index, total).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        pre(step, icon, total, null).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out.distinct()
    }
}
