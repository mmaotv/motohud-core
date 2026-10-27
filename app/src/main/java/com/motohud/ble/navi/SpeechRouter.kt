package com.motohud.ble.navi

import android.content.Context
import com.motohud.ble.navi.tts.SherpaSpeaker
import com.motohud.ble.navi.tts.TtsModelStore

/**
 * 播报引擎选择器。
 *
 * ## 降级链
 *
 * ```
 * ① 离线合成（SherpaSpeaker + 本地模型）   → 能念路名、不依赖系统组件
 *        ↓ 模型没下载 / 加载失败 / 合成超时
 * ② 内置语音包（ClipSpeaker）              → 永远有声音
 * ```
 *
 * 这个顺序是刻意的：**语音包是底线，不是主选**。它不出错、不挑设备，
 * 但念不出路名；只要本地模型可用，就应该用能念路名的那条。
 *
 * 注意：`create()` 在主线程被 `initVoice()` 调用，所以 **不能**在这里同步
 * 加载模型（30MB onnx 加载要几百毫秒，会把导航页卡住）。做法是：
 * 先返回一个「等模型就绪后自动切换」的实现，模型在后台线程加载。
 */
object SpeechRouter {

    /**
     * 创建播报器。
     *
     * 返回的对象在模型未就绪时行为与 [ClipSpeaker] 完全一致，
     * 就绪后自动改用合成 —— 调用方（GuidanceActivity）无需关心切换时机。
     */
    fun create(context: Context): Speaker {
        val app = context.applicationContext
        // 没有模型就直接用语音包，连后台线程都不开
        if (!TtsModelStore.isInstalled(app)) return ClipSpeaker(app)
        return runCatching { SherpaSpeaker(app) }
            .getOrElse { ClipSpeaker(app) }
    }
}
