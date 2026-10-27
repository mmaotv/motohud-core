package com.motohud.ble.navi.tts

/**
 * 播报**风格**预设。
 *
 * ## 为什么要有"风格"这一层
 *
 * 底层模型（AISHELL-3）只给 174 个编号说话人，用户看编号根本选不出来，
 * 而且相邻编号听感常常几乎一样。用户要的是「御姐」「调皮」「林志玲」
 * 「小孩」「佩奇」「熊大熊二」这种**有性格的声音**，不是"音色 3"。
 *
 * 所以一个风格 = 三元组：**说话人 + 语速 + 音调**。
 * 前两项交给模型，第三项在播放端做重采样（见 [SherpaSpeaker]）：
 *
 *  - 音调 > 1 → 声音更尖、更快（小孩 / 佩奇 / 调皮）
 *  - 音调 < 1 → 声音更低、更慢（熊大熊二 / 沉稳大叔）
 *
 * 这不是"角色扮演"，是把同一批说话人拉到不同的音区，
 * 让用户在骑行中一眼能分辨、一听能记住。
 *
 * ## 变调为什么连带变速
 *
 * 重采样压缩样本数会同时改变音调和时长——而对角色音来说**这正是想要的**：
 * 佩奇本来就又快又尖，熊二本来就又慢又粗。
 * 真正的"变调不变速"要 PSOLA/WSOLA，代价大且对 8kHz 导航语音收益不明显。
 */
object TtsStyle {

    data class Style(
        /** 持久化用的标识，改它会导致老用户回到默认风格（可接受） */
        val id: String,
        /** 界面上显示的名字 */
        val name: String,
        /** 模型说话人下标 */
        val speakerId: Int,
        /** 语速倍率（模型 lengthScale，越大越快） */
        val speed: Float,
        /** 音调倍率（播放端重采样，>1 更尖更快） */
        val pitch: Float
    )

    val ALL = listOf(
        Style("standard", "标准女声", 0, 1.00f, 1.00f),
        Style("yujie", "御姐音", 12, 0.88f, 0.93f),
        Style("sweet", "温柔甜美", 25, 0.92f, 1.05f),
        Style("lively", "调皮活泼", 40, 1.14f, 1.12f),
        Style("bear", "憨厚大熊", 58, 0.85f, 0.78f),
        Style("child", "小女孩", 77, 1.16f, 1.28f),
        Style("teen", "清爽少年", 96, 1.04f, 1.09f),
        Style("uncle", "沉稳大叔", 120, 0.90f, 0.85f)
    )

    val DEFAULT: Style = ALL.first()

    fun of(id: String?): Style = ALL.firstOrNull { it.id == id } ?: DEFAULT

    fun indexOf(id: String?): Int = ALL.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
}
