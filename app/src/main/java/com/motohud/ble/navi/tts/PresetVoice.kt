package com.motohud.ble.navi.tts

/**
 * 内置语音包（[com.motohud.ble.navi.PresetPackSpeaker]）的音色。
 *
 * ## 为什么单独一套，不复用 [TtsStyle]
 *
 * [TtsStyle] 是给离线神经 TTS 用的：一个风格 = 模型说话人 + 语速 + 音调，
 * 八个风格全靠在播放端重采样和换 speakerId 造出来，**不需要额外资源**。
 *
 * 内置语音包正好相反：每条都是**预先录好的 mp3**，音色是录的时候定死的。
 * 想换音色只能**重录一整套**（这里每套约 2MB、180 条）。
 * 所以：
 *  - 只做 3 套（再多，APK 体积吃不消，而且导航语音换来换去意义不大）；
 *  - 语速/音调对内置包**无效**（mp3 已定型），界面上要如实说明，
 *    不能让用户以为"调了没反应是坏了"。
 *
 * ## 目录约定
 *
 * 第一套直接放在 `assets/voicepack` 根目录（0.0.5 以来的既有位置，不动它就零迁移风险），
 * 其余放 `assets/voicepack/<dir>/`。[dir] 为空字符串表示根目录。
 *
 * 改这里的 id 或目录名，必须同步 `tools/gen_voice_pack.py` 顶部的 `VOICES`。
 */
object PresetVoice {

    data class Voice(
        /** 持久化标识 */
        val id: String,
        /** 界面显示名 */
        val name: String,
        /** assets/voicepack 下的子目录，空 = 根目录 */
        val dir: String
    )

    val ALL = listOf(
        Voice("xiao", "晓晓 · 女声", ""),
        Voice("yun", "云健 · 男声", "yun"),
        Voice("yi", "晓伊 · 女声", "yi")
    )

    val DEFAULT: Voice = ALL.first()

    fun of(id: String?): Voice = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /** assets 里的包根目录，如 `voicepack` / `voicepack/yun` */
    fun assetsDir(id: String?): String {
        val d = of(id).dir
        return if (d.isEmpty()) "voicepack" else "voicepack/$d"
    }
}
