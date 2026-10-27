package com.motohud.ble.navi

import android.content.Context
import com.motohud.ble.navi.tts.SherpaSpeaker
import com.motohud.ble.navi.tts.TtsModelStore

/**
 * 播报引擎选择器。
 *
 * 现在只有一种声音：离线神经 TTS（[SherpaSpeaker]）。模型装好它就能念任意文本
 * （含路名），完全离线。
 *
 * - 模型已下载 → 返回 [SherpaSpeaker]（内部在后台线程加载引擎，就绪前静默）；
 * - 模型未下载 → 返回 [SilentSpeaker]，此路没有离线语音包可兜底，唯一选择是
 *   提示用户去「语音设置」里下载模型。
 *
 * 注意：`create()` 在主线程被 `initVoice()` 调用，所以 **不能**在这里同步
 * 加载模型（30MB onnx 加载要几百毫秒，会把导航页卡住）。做法是先返回一个对象，
 * 引擎在后台线程加载；加载完成前播报静默，加载完成后自动有声音。
 */
object SpeechRouter {

    /**
     * 创建播报器。
     *
     * 返回的对象在模型未就绪时静默（不报错、不挑设备），就绪后自动播报 ——
     * 调用方（GuidanceActivity）无需关心切换时机。
     */
    fun create(context: Context): Speaker {
        val app = context.applicationContext
        // 没装模型就直接静默，连后台线程都不开（没有离线包可兜底了）
        if (!TtsModelStore.isInstalled(app)) {
            android.util.Log.w("SpeechRouter", "未安装语音模型 → 静默播报器")
            return SilentSpeaker
        }
        return runCatching { SherpaSpeaker(app) }
            .onFailure { android.util.Log.e("SpeechRouter", "语音引擎创建失败 → 静默", it) }
            .getOrElse { SilentSpeaker }
            .also { android.util.Log.i("SpeechRouter", "播报器：${it.javaClass.simpleName}") }
    }
}
