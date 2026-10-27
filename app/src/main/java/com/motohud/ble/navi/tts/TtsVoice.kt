package com.motohud.ble.navi.tts

/**
 * 可选音色。
 *
 * AISHELL-3 是 **174 个说话人**的多说话人模型，`speakers.txt` 里就是
 * SSB0005、SSB0009 … 这串编号，`generate(text, speakerId, speed)` 的
 * 第二个参数就是它的下标。
 *
 * ## 为什么只列 8 个而不是 174 个
 *
 * 174 个挨个试听没人受得了，而且相邻编号的听感常常几乎一样。
 * 这里挑的是一组**跨度大、差别明显**的编号，配合「点一下就能听」
 * 的试听，用户听两条基本就能定下来。想换口味随时回来换。
 *
 * ## 为什么不标注"男声/女声"
 *
 * 说话人文件里只有编号，没有性别；硬标性别标错了比不标更让人困惑。
 * 所以统一叫「音色 N」，让试听说话。
 */
object TtsVoice {

    /** 预置音色（值 = speakerId） */
    val ALL = listOf(0, 12, 25, 40, 58, 77, 96, 120)

    fun indexOf(speakerId: Int): Int = ALL.indexOf(speakerId).takeIf { it >= 0 } ?: 0

    fun label(index: Int): String = "音色 ${index + 1}"
}
