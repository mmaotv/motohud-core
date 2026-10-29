package com.motohud.ble.navi

import android.content.Context
import com.motohud.ble.navi.tts.SherpaSpeaker
import com.motohud.ble.navi.tts.TtsModelStore

/**
 * 播报引擎选择器。
 *
 * 两种声音，**互斥**（两套同时出声就是叠音，0.0.7 踩过）：
 *
 * - **内置语音包** [PresetPackSpeaker]：随 APK 一起装好，150+ 条预合成短句，
 *   零下载零延迟。代价是**念不出路名**（"前方 200 米右转"）。
 *   没下模型的用户至少能听见方向、距离、第几个路口/出口。
 * - **离线神经 TTS** [SherpaSpeaker]：能念任意文本（含路名），但要先下载 30MB 模型。
 *
 * 选择规则：
 *  模型已装 → sherpa（信息最全）；没装 → 内置包（有声音）；
 *  sherpa 创建失败（模型损坏 / so 缺失）→ 退回内置包，而不是静默。
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
        if (!TtsModelStore.isInstalled(app)) {
            // 模型没装：走内置包。没有它这里就只能返回静默（老行为）
            return newPreset(app, "未安装语音模型")
        }
        return runCatching { SherpaSpeaker(app) }
            .onFailure { android.util.Log.e("SpeechRouter", "语音引擎创建失败 → 退内置语音包", it) }
            .getOrElse { newPreset(app, "神经引擎创建失败") }
            .also { android.util.Log.i("SpeechRouter", "播报器：${it.javaClass.simpleName}") }
    }

    private fun newPreset(app: Context, why: String): Speaker {
        android.util.Log.i("SpeechRouter", "$why → 使用内置语音包")
        return runCatching { PresetPackSpeaker(app) }
            .onFailure { android.util.Log.e("SpeechRouter", "内置语音包初始化失败 → 静默", it) }
            .getOrElse { SilentSpeaker }
    }
}
