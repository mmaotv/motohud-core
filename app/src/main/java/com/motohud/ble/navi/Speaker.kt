package com.motohud.ble.navi

import android.content.Context

/**
 * 导航播报的统一出口。
 *
 * ## 为什么要这一层
 *
 * 以前 `GuidanceActivity` 直接 `new VoicePackPlayer()` 并传 `R.raw` 资源 id，
 * 换引擎就得改业务代码。抽成接口后，业务只说「我想说这句话」，
 * 由具体实现决定怎么说。
 *
 * ## 现在只有一种实现
 *
 * [com.motohud.ble.navi.tts.SherpaSpeaker]（离线神经 TTS）是唯一的播报器：
 * 模型装好后能念任意文本（含路名），完全离线、不依赖系统组件。
 * 没有模型 / 语音关闭时由 [SilentSpeaker] 顶上，保证调用方不会空指针。
 *
 * 原来的预录语音包（[VoicePack] 片段拼装）已移除：它念不出路名、内容也太受限，
 * 且与合成引擎会抢着出声。删除后导航只有一种声音，信息更完整、也不会叠音。
 */
interface Speaker {

    /**
     * 播一句话。
     *
     * @param text 含路名的完整播报文本（如「沿黄桷坪正街行驶 500 米后右转」）。
     * @param priority 见 [SpeechPriority]
     * @param interrupt true = 抢播（清空正在播的）；false = 接在后面播
     */
    fun speak(
        text: String,
        priority: SpeechPriority = SpeechPriority.TURN,
        interrupt: Boolean = true
    )

    /**
     * 提前合成（预热）。
     *
     * 导航引擎知道「下一步是什么」，在距触发点还有十几秒时就可以把这句
     * 合成好放缓存，真正播报时首字延迟几乎为 0。
     */
    fun prewarm(text: String)

    fun stop()

    fun setMuted(muted: Boolean)

    fun release()
}

/**
 * 播报优先级。
 *
 * 现在只有「转弯抢播 / 常规排队」两档，但必须先把等级留出来：
 * 将来加上限行/安全提示时，它**不能被转弯播报顶掉**（现在 `interrupt=true`
 * 一刀切会有这个隐患）。
 */
enum class SpeechPriority {
    /** 安全类：限行、逆行提醒等，永不被打断 */
    SAFETY,

    /** 转弯提醒：时效性强，可以抢播 */
    TURN,

    /** 常规信息：开始导航、到达、语音开关确认，可排队 */
    INFO
}

/**
 * 无语音实现：模型没下载 / 语音开关关闭时使用。
 *
 * 所有方法都是空操作，仅保证调用方拿到的 [Speaker] 永远非空、不会崩。
 * 业务侧（[com.motohud.ble.ui.navi.GuidanceActivity]）在调用前也会先判断
 * `NaviPrefs.voice`，所以这里静默即可。
 */
object SilentSpeaker : Speaker {
    override fun speak(text: String, priority: SpeechPriority, interrupt: Boolean) = Unit
    override fun prewarm(text: String) = Unit
    override fun stop() = Unit
    override fun setMuted(muted: Boolean) = Unit
    override fun release() = Unit
}
