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

    /** 下一段短于该米数 → 临门指令后面补一句"然后…" */
    const val CHAIN_M = 400f

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
        val head = if (road != null) "继续沿${road}行驶 $span" else "继续直行 $span"
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
     * 直行路口走 [SpeechScript.straight]（"路口直行进入 X 路"）；
     * 其余走 [SpeechScript.turn]。下一段够短就补「，然后…」（OSRM 的 `then`）。
     *
     * @param next 下一段（`steps[i+1]`），为 null 表示本段是最后一段
     */
    fun pre(current: RouteStep, icon: Int, remainM: Int, next: RouteStep?): String {
        val base = if (icon == IconType.STRAIGHT) {
            SpeechScript.straight(remainM, current.instruction)
        } else {
            SpeechScript.turn(icon, remainM, current.road, current.instruction)
        }
        if (base.isEmpty() || next == null) return base
        if (next.distanceM > CHAIN_M) return base
        val nextIcon = IconMapper.of(next.action, next.orientation, next.instruction)
        val nextAct = SpeechScript.maneuverShort(nextIcon, next.road, next.instruction)
        return chain(base, nextAct)
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

        // 阈值播报：与 GuidanceActivity 的阶梯一致；带/不带"然后"都要备，
        // 因为下一段长度是固定的，但播报点在哪个阈值上不固定
        for (probe in intArrayOf(1_000, 500, 300, 200, 100, 50)) {
            if (probe > total) continue
            pre(step, icon, probe, next).takeIf { it.isNotEmpty() }?.let { out.add(it) }
            pre(step, icon, probe, null).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        // 进段那一刻整段长度上的临门指令（短段会被当成"前方 X 米转弯"播一次）
        pre(step, icon, total, next).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        pre(step, icon, total, null).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out.distinct()
    }
}
