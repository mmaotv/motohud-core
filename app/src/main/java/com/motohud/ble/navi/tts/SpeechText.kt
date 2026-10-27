package com.motohud.ble.navi.tts

/**
 * 播报文本规范化。
 *
 * ## 为什么需要这一层
 *
 * 高德 `DriveStep.instruction` 是给**人看**的文案（"沿黄桷坪正街行驶 500 米后右转"），
 * 直接喂给 TTS 有三个坑：
 *
 * 1. **多音字地名**：模型的 lexicon 是单字音素表，"弹子石"的「弹」会被读成 dàn
 *    （正确是 tán）。表外的词只能靠同音字替换绕过 —— 播报是听觉，把字换成
 *    同音的安全字，读音对了、听感不受影响。
 * 2. **括号/特殊符号**：TTS 遇到 `（）「」·` 会停顿或吞字。
 * 3. **连续空格/换行**：会让合成出现奇怪的断句。
 *
 * 数字（"500 米"）不用自己处理 —— 模型自带 `number.fst` / `date.fst`
 * 做文本规范化，交给它比我们手写规则准。
 */
object SpeechText {

    /**
     * 多音字地名 → 同音安全字。
     *
     * 只收**默认读音会错**的词。加新词条前先在模型 lexicon.txt 里查那个字的
     * 默认音素，确认读音确实不对再进来 —— 无谓的替换只会增加播报延迟。
     *
     * 以重庆为例（本项目主要使用场景）：
     *  - 弹子石：弹 = tán（模型默认 dàn）
     *  - 黄桷坪：桷 = jué
     *  - 牛角沱 / 李家沱 / 唐家沱：沱 = tuó
     *  - 溉澜溪：溉 = gài
     *  - 曾家岩：曾 = zēng（模型默认 céng）
     *  - 石桥铺：铺 = pù（模型默认 pū）
     */
    private val HOMOPHONE: Map<String, String> = linkedMapOf(
        "弹子石" to "谈子石",
        "黄桷坪" to "黄决坪",
        "牛角沱" to "牛角驼",
        "李家沱" to "李家驼",
        "唐家沱" to "唐家驼",
        "溉澜溪" to "盖澜溪",
        "曾家岩" to "增家岩",
        "石桥铺" to "石桥瀑"
    )

    /** 需要剥掉的符号：TTS 遇到会停顿/吞字 */
    private val STRIP = Regex("[（）()\\[\\]「」『』《》\"“”·•…、，,]")

    private val SPACES = Regex("\\s+")

    /**
     * 规范化一句播报文案。返回空串表示"这句话不值得合成"
     * （调用方应退回预录片段，那种情况片段反而更快更自然）。
     */
    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw
        // 先替换词，再剥符号 —— 反过来会把"（黄桷坪）"这种带括号的漏掉
        HOMOPHONE.forEach { (from, to) -> s = s.replace(from, to) }
        s = STRIP.replace(s, " ")
        s = s.replace('\n', ' ').replace('\r', ' ')
        s = SPACES.replace(s, " ").trim()
        // 太短的句子（"右转"两个字）合成反而比片段慢，交给片段
        if (s.length < MIN_LEN) return ""
        return s
    }

    private const val MIN_LEN = 5
}
