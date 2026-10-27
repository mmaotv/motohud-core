package com.motohud.ble.navi

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * 语音包播放器：把一串片段（`R.raw.vp_*`）按顺序播出去。
 *
 * ## 为什么用 MediaPlayer 而不是 SoundPool
 *
 * SoundPool 延迟更低，但拿不到「这段播完了」的回调，做不了顺序拼接。
 * 这里每段只有 0.6~1.5 秒，`reset + setDataSource + prepare` 的几十毫秒
 * 恰好落在「前方……300米……右转」该有的自然停顿上，听感反而更像真人播报。
 *
 * ## 覆盖式排队
 *
 * 播报是**时效信息**：旧的一句没播完就来了新的一句（连续两个转弯），
 * 必须丢掉旧的直接播新的，否则会听到已经开过的路口。所以 [speak] 是
 * 清空队列后重来，不做"排队等前面播完"。
 *
 * 所有方法都必须在主线程调用（Activity 侧天然满足）。
 */
class VoicePackPlayer(context: Context) {

    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private val queue = ArrayDeque<Int>()
    private var muted = false
    private var released = false
    private var playing = false

    /**
     * 播报一串片段。
     *
     * [interrupt] = true（默认）：清空正在播的内容立刻播新的 —— 转弯提醒这类
     *   时效信息必须"说新的"，不能等旧句子播完（那时路口已经过了）。
     * [interrupt] = false：接在队列后面播 —— 用于"开始导航，全程 6 公里"之后
     *   紧跟的第一条路口播报，硬插进去会把人话说成半句。
     */
    fun speak(clips: List<Int>, interrupt: Boolean = true) {
        if (released) return
        if (interrupt) queue.clear()
        if (muted || clips.isEmpty()) {
            if (interrupt) stop()
            return
        }
        queue.addAll(clips)
        if (!playing) playNext()
    }

    fun stop() {
        queue.clear()
        playing = false
        runCatching {
            if (player?.isPlaying == true) player?.stop()
        }
    }

    /** 语音开关关掉时用它掐掉正在播的内容 */
    fun setMuted(value: Boolean) {
        muted = value
        if (value) stop()
    }

    fun release() {
        released = true
        queue.clear()
        runCatching { player?.release() }
        player = null
    }

    private fun playNext() {
        val res = queue.removeFirstOrNull()
        if (res == null) {
            playing = false
            return
        }
        val mp = ensurePlayer()
        if (mp == null) {
            // 播放器都建不起来（极少见），把整串丢掉，别无限重试
            queue.clear()
            playing = false
            return
        }
        try {
            mp.reset()
            appContext.resources.openRawResourceFd(res).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.prepare()
            mp.start()
            playing = true
            if (com.motohud.ble.BuildConfig.DEBUG) {
                // 「为什么没声音」时先看这条：有日志说明播报链路是通的，问题在音量/蓝牙
                Log.d(TAG, "play res=$res 剩余${queue.size}段")
            }
        } catch (e: Exception) {
            Log.w(TAG, "片段播放失败 res=$res，跳过", e)
            playNext()
        }
    }

    private fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setOnCompletionListener { playNext() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    true
                }
            }.also { player = it }
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer 创建失败", e)
            null
        }
    }

    private companion object {
        const val TAG = "VoicePackPlayer"
    }
}
