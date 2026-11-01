package com.motohud.ble.navi

/**
 * 解析导航 SDK **官方播报文本**里的电子眼提示。
 *
 * ## 为什么需要它
 *
 * `AMapNaviCameraInfo.cameraType` 只有 11 类（0 测速 / 1 监控 / 2 闯红灯 / 3 违章拍照 /
 * 4 公交道 / 5 应急道 / 6 非机动车道 / 8-9 区间 / 10 车流 / 11 ETC），
 * 高德 App 上常见的「压线」「不系安全带」「接打电话」全被塞进 `BREAK_RULE`。
 * 换句话说：**光靠 cameraType 永远区分不出压线**。
 *
 * 但 SDK 自己的语音通道（`onGetNavigationText`）会下发细得多的原文，实测（2026-11-01，机场高速）：
 *
 * ```
 * 三百米内,有多个压线拍照
 * 一公里内,有多个不系安全带拍照
 * 九百米内,有多个接打电话拍照
 * 四百米内,有多个违法拍照,请按交规行驶
 * ```
 *
 * 这三件事 cameraType 都没有：**细分类型 / 距离 / 是不是"多个"**。
 *
 * ## 为什么现在才能用
 *
 * 以前这些原文只是被转发给 TTS 直接念（`setUseInnerVoice(false)` + 非转向文本放行）。
 * 内置语音包是预录片段，念不出「有多个压线拍照」这种任意文本，`clipsFor` 只能
 * 整句跳过 —— 实测日志里就是 `语音包里没有可用片段，跳过：三百米内,有多个压线拍照`。
 * 所以默认配置下这段情报**一直是丢的**。
 *
 * 现在把它解析成 [CameraKind] 的细化类型，交给自己的播报链路念（片段可控），
 * 原文则吞掉不再转发 —— 否则模型链路会把同一件事说两遍。
 */
object CameraText {

    /**
     * 解析结果。
     *
     * @param type [CameraKind] 的细化类型（≥ [CameraKind.F_FIRST]）
     * @param distanceM 官方给的距离（米）；解析不出时为 0
     * @param multi 原文是否说「多个」（同一路段连排探头）
     */
    data class Parsed(val type: Int, val distanceM: Int, val multi: Boolean)

    /**
     * 距离 + 「有」+ 类型 + 拍照。
     *
     * 距离部分限制成「数字/中文数字 + 米|公里」，别写成 `[^，,]{1,8}`——
     * 那样「前方路口有违章拍照」会被当成距离为 0 的合法匹配，把正常转向提示吃掉。
     */
    private val RE = Regex(
        "(?:前方)?\\s*([0-9一二两三四五六七八九十百千]+(?:米|公里))\\s*(?:内)?[，,]?\\s*有\\s*" +
            "(多个)?\\s*([^，,]{1,10}?)拍照"
    )

    /** 词表 → 细化类型。键是官方原文里的类型词（`...拍照` 之前那一段）。 */
    private val WORDS = mapOf(
        "压线" to CameraKind.F_PRESS_LINE,
        "不系安全带" to CameraKind.F_SEATBELT,
        "接打电话" to CameraKind.F_PHONE,
        "违法" to CameraKind.F_ILLEGAL,
        "违章" to CameraKind.F_ILLEGAL,
        "闯红灯" to CameraKind.F_RED_LIGHT,
        "超速" to CameraKind.F_SPEED,
        "测速" to CameraKind.F_SPEED,
        "占用应急车道" to CameraKind.F_OCCUPY_EMERGENCY,
        "走应急车道" to CameraKind.F_OCCUPY_EMERGENCY,
        "占用公交" to CameraKind.F_OCCUPY_BUS,
        "公交车道" to CameraKind.F_OCCUPY_BUS,
        "逆行" to CameraKind.F_WRONG_WAY,
        "逆向行驶" to CameraKind.F_WRONG_WAY,
        "违停" to CameraKind.F_PARKING,
        "违法停车" to CameraKind.F_PARKING,
    )

    /**
     * 是不是电子眼播报文本；是就返回解析结果，不是返回 null。
     *
     * 只认「…有…拍照」这个句式 —— 官方其它提示（服务区、施工、逐车道指引）
     * 一个都不该被这里吃掉。
     */
    fun parse(text: String): Parsed? {
        val m = RE.find(text) ?: return null
        val dist = distanceOf(m.groupValues[1]) ?: return null
        if (dist <= 0) return null
        val word = m.groupValues[3].trim()
        // 词表里没有的类型仍算电子眼：宁可显示"电子眼"，也别把这条情报扔了
        val type = WORDS[word] ?: WORDS.entries.firstOrNull { word.contains(it.key) }?.value
            ?: CameraKind.F_GENERIC
        return Parsed(type, dist, m.groupValues[2].isNotEmpty())
    }

    /** 「三百米」「一公里」「300米」→ 米。解析不出返回 null。 */
    fun distanceOf(raw: String): Int? {
        val s = raw.trim()
        val km = s.endsWith("公里")
        val body = s.removeSuffix("米").removeSuffix("公里").trim()
        if (body.isEmpty()) return null
        val n = body.toIntOrNull() ?: cnNumber(body) ?: return null
        return if (km) n * 1000 else n
    }

    /**
     * 中文数字 → 整数（只处理导航文本里会出现的形态：三百 / 九百 / 一公里 / 十五）。
     * 支持"百/千/十"三个量级；"一点七公里"这种小数不在电子眼句式里，不处理。
     */
    private fun cnNumber(s: String): Int? {
        var total = 0
        var cur = 0
        for (ch in s) {
            when (ch) {
                '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
                '千' -> { total += (if (cur == 0) 1 else cur) * 1000; cur = 0 }
                '零' -> Unit
                else -> {
                    val d = digit(ch) ?: return null
                    cur = d
                }
            }
        }
        return total + cur
    }

    private fun digit(ch: Char): Int? = when (ch) {
        '一' -> 1; '二', '两' -> 2; '三' -> 3; '四' -> 4; '五' -> 5
        '六' -> 6; '七' -> 7; '八' -> 8; '九' -> 9
        else -> ch.digitToIntOrNull()
    }
}
