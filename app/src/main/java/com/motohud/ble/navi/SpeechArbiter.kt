package com.motohud.ble.navi

/**
 * 播报仲裁：照高德的优先级表范式，解决"两条播报同时触发该说哪条"。
 *
 * ## 为什么需要
 *
 * 现在 `say()` 是直通的：谁调用谁出声，`interrupt = true` 就抢播。
 * 于是出现两种事故：
 *  - 「前方 200 米右转」正念到一半，被「语音播报已开启」这种系统提示切成半句；
 *  - 偏航重算（最重要的那句）刚开口，被下一 tick 的转弯提醒顶掉。
 *
 * 高德在 `libamaptbt.so` 里带了一张 CSV 优先级表，形如：
 * ```
 * 1310,支付,支付宝收费站播报,50,100
 * 3031,车道级,车道级导航挂接播报,50,100
 * 81,路况,交通红绿灯起步播报提醒,100,100
 * 格式 = ID,分类,名称,优先级,权重
 * ```
 * 它给每条播报一个**优先级**（谁压谁）和一个**权重**（同级之间谁更该先播）。
 * 这里照同样的结构建模，但只收本项目真会说的那几类，不做无用的全量搬运。
 *
 * ## 仲裁规则
 *
 * 1. 当前没有播报 → 直接播；
 * 2. 新播报优先级 **高于** 正在播的 → 打断（安全类永远抢得过转弯）；
 * 3. **低于** → 丢弃（宁可不播，也不切断更重要的话）；
 * 4. 同级 → 比权重，权重高者替换；同权则新压旧（导航信息旧的本来就过期了）。
 *
 * ## 时长怎么算
 *
 * 没有回调告诉你"说完了没有"，所以按字数估：中文 TTS 约 4.5 字/秒，
 * 即每字 220ms。估长了会让后续播报被误丢，估短了会叠音 —— 220ms 是
 * 实测（离线 sherpa + 内置语音包）偏保守的取值。
 */
object SpeechArbiter {

    /** 一条播报的定义（对应高德 CSV 的一行） */
    data class Cue(
        val id: Int,
        val category: String,
        val name: String,
        /** 优先级：谁压谁。安全 > 转弯 > 路况 > 系统提示 */
        val priority: Int,
        /** 权重：同优先级之间谁更该先播 */
        val weight: Int,
    )

    // ---------- 表（ID 四位数，前两位是分类，照高德的编号习惯） ----------

    /** 偏航重算：最高优先级，任何播报都该给它让路 */
    val REROUTE = Cue(1201, "安全", "偏航重算", 90, 100)

    /** 临门动作：「右转」「进入环岛，第 3 个出口」——到跟前了，最要紧 */
    val CLOSE_ACTION = Cue(1002, "转弯", "临门动作", 80, 100)

    /** 到达目的地 */
    val ARRIVED = Cue(1302, "系统", "到达播报", 70, 80)

    /** 阈值提醒：「前方 500 米右转进入南滨路」 */
    val APPROACH = Cue(1001, "转弯", "路口预警", 60, 100)

    /** 更优路线提示 */
    val BETTER_ROUTE = Cue(1102, "路况", "更优路线", 55, 90)

    /** 拥堵/路况变化 */
    val TRAFFIC = Cue(1101, "路况", "路况提示", 50, 80)

    /** 进段确认：「继续沿南滨路行驶 2 公里」—— 不抢话，接在后面说 */
    val STEP_ENTRY = Cue(1003, "转弯", "进段确认", 40, 60)

    /**
     * 沿途道路要素：「前方进入隧道，请开灯」「前方上桥，注意横风」。
     *
     * 优先级压在转向类之下：它是"背景信息"，不能顶掉「前方 200 米右转」。
     * 但比操作确认（语音开关之类）高，因为错过隧道开灯提示是有实际后果的。
     */
    val ROAD_FEATURE = Cue(1401, "道路要素", "要素提示", 45, 70)

    /**
     * 弯道预警：「前方 300 米急转弯向左，请减速」。
     *
     * 比道路要素高、比路口预警低：弯道是**有安全后果**的，但优先级仍要让给
     * 「前方 100 米右转」这种必须立刻执行的动作。
     */
    val CURVE = Cue(1202, "安全", "弯道预警", 58, 85)

    /**
     * 坡度提示：「连续 6% 下坡，注意控制车速」。
     *
     * 长下坡是摩托车最容易出事的路段（刹车热衰减），所以排在道路要素之上；
     * 但它是"当前状态"而非"即将执行"，压在转弯类之下。
     */
    val TERRAIN = Cue(1203, "安全", "坡度提示", 52, 75)

    /**
     * 驾驶行为反馈：「急刹车，注意后方车辆」。
     *
     * 优先级最低的一档：事情已经发生了，说了也改不了，
     * 绝不能顶掉任何一条前瞻性提示。默认还关着（见 DriveBehaviorMonitor）。
     */
    val HARSH_DRIVE = Cue(1402, "行为", "驾驶行为", 15, 20)

    /** 开始导航开场白 */
    val START = Cue(1301, "系统", "开始导航", 30, 50)

    /** 语音开关、模式切换这类操作确认 */
    val UI_CONFIRM = Cue(1303, "系统", "操作确认", 20, 30)

    /** 按旧的 [SpeechPriority] 推导默认 Cue（存量调用点不用改） */
    fun cueOf(priority: SpeechPriority): Cue = when (priority) {
        SpeechPriority.SAFETY -> REROUTE
        SpeechPriority.TURN -> APPROACH
        SpeechPriority.INFO -> UI_CONFIRM
    }

    // ---------- 运行态 ----------

    /** 正在播的那条；null 表示静着 */
    private var current: Cue? = null
    private var busyUntilMs = 0L

    /** 一段播报预计要说多久（毫秒） */
    fun estimateMs(text: String): Long {
        val n = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        return n * 220L + 250L  // 末尾留一点余量，避免最后半个字被切
    }

    /** 仲裁结果 */
    enum class Verdict {
        /** 立刻播（必要时打断） */
        PLAY,

        /** 丢弃：有更重要的话正在说 */
        DROP,
    }

    /**
     * 提交一条播报，返回该不该播。
     *
     * @param force true = 无视仲裁强制播（用户手动点「重播」这类场景）
     */
    fun submit(cue: Cue, text: String, nowMs: Long, force: Boolean = false): Verdict {
        if (force) {
            current = cue
            busyUntilMs = nowMs + estimateMs(text)
            return Verdict.PLAY
        }
        val busy = nowMs < busyUntilMs
        if (!busy) {
            current = cue
            busyUntilMs = nowMs + estimateMs(text)
            return Verdict.PLAY
        }
        val cur = current
        val wins = when {
            cur == null -> true
            cue.priority > cur.priority -> true
            cue.priority < cur.priority -> false
            cue.weight > cur.weight -> true
            cue.weight < cur.weight -> false
            else -> true  // 同级同权：新的覆盖旧的，旧的导航信息已经过期
        }
        if (!wins) return Verdict.DROP
        current = cue
        busyUntilMs = nowMs + estimateMs(text)
        return Verdict.PLAY
    }

    /** 导航结束/页面销毁时清空，避免下次进来第一条被误丢 */
    fun reset() {
        current = null
        busyUntilMs = 0L
    }
}
