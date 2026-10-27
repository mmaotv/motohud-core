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
 * ## 为什么 speak 同时要 text 和 clips
 *
 * 两个实现需要的东西不一样：
 *  - [ClipSpeaker]（现有语音包）只能播预录片段 → 要 `clips`；
 *  - [com.motohud.ble.navi.tts.SherpaSpeaker]（离线合成）能念任意文本 → 要 `text`，
 *    而且**只有它能念出路名**（高德 `DriveStep.instruction` 本来就是含路名的整句）。
 *
 * 所以两个都传：能合成的用 text，合成不了就退回 clips。这样任何设备上
 * 都保证「至少有声音」，不会出现导航全程哑巴。
 */
interface Speaker {

    /**
     * 播一句话。
     *
     * @param text 含路名的完整播报文本（如「沿黄桷坪正街行驶 500 米后右转」）。
     *             实现方可以不支持文本，此时忽略它。
     * @param clips 预录片段（`R.raw.vp_*`），**兜底用**。空表示没有可用片段。
     * @param priority 见 [SpeechPriority]
     * @param interrupt true = 抢播（清空正在播的）；false = 接在后面播
     */
    fun speak(
        text: String,
        clips: List<Int>,
        priority: SpeechPriority = SpeechPriority.TURN,
        interrupt: Boolean = true
    )

    /**
     * 提前合成（预热）。
     *
     * 导航引擎知道「下一步是什么」，在距触发点还有十几秒时就可以把这句
     * 合成好放缓存，真正播报时首字延迟几乎为 0。纯片段播放器实现里是空操作。
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
 * 用内置语音包实现的 [Speaker]。
 *
 * **行为与改造前完全一致** —— 只播 `clips`，忽略 `text`
 * （语音包是预录片段，念不出任意文本，这是它设计上的已知代价）。
 * 改造这一层的目的只是给后面的离线合成留出入口，不改变任何听感。
 */
class ClipSpeaker(context: Context) : Speaker {

    private val player = VoicePackPlayer(context)

    override fun speak(
        text: String,
        clips: List<Int>,
        priority: SpeechPriority,
        interrupt: Boolean
    ) {
        if (clips.isEmpty()) return
        player.speak(clips, interrupt)
    }

    /** 预录片段没有"合成"这一步 */
    override fun prewarm(text: String) = Unit

    override fun stop() = player.stop()

    override fun setMuted(muted: Boolean) = player.setMuted(muted)

    override fun release() = player.release()
}
