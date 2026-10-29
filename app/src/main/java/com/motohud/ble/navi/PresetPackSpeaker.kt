package com.motohud.ble.navi

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 内置语音包播报器（**不需要下载任何模型**）。
 *
 * ## 为什么还要它
 *
 * 现在的导航语音只有一条路：下载 30MB 离线模型 → sherpa-onnx 现场合成。
 * 好处是能念路名，坏处是：没下模型的人**全程没有声音**，而"要不要下 30MB"
 * 不该是"能不能听见导航"的前提。
 *
 * 所以把最常说的 150 多条短句在构建期预合成好塞进 assets
 * （`tools/gen_voice_pack.py`，音色沿用 0.0.5 第一版：edge-tts Xiaoxiao +8%），
 * 装完即有声音、零延迟、零下载。
 *
 * ## 一句话是怎么拼出来的
 *
 * 播报文本形如「前方 200 米右转进入烟雨路」，其中**路名无法预置**
 * （任意组合），所以：
 *  1. 整句在包里 → 直接播这一段；
 *  2. 否则从左到右贪心匹配**最长的已知片段**：「前方 200 米」+「右转」，
 *     匹配不上的字（路名）跳过。
 *
 * 代价是内置包**不念路名**（"前方 200 米右转"）。这是刻意的取舍：
 * 方向/距离/第几个路口/第几个出口这些决定走不走错的信息一个不少，
 * 要路名就下载模型，两者不冲突。
 *
 * ## 和神经 TTS 的关系
 *
 * 互斥，由 [SpeechRouter] 决定：模型装了走 sherpa（能念路名），
 * 没装 / 创建失败走这里。两套同时出声就是叠音（0.0.7 的老问题）。
 */
class PresetPackSpeaker(context: Context) : Speaker {

    companion object {
        private const val TAG = "PresetPack"
        private const val DIR = "voicepack"
        private const val INDEX = "index.txt"

        /** 贪心匹配时单个片段的最大字符数（最长条目「进入环岛，走第 3 个出口」13 字） */
        private const val MAX_CLIP_CHARS = 16
    }

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val player = MediaPlayer()

    /** 文本 → assets 内文件名 */
    private val index = HashMap<String, String>(256)

    /** 待播片段（assets 相对路径），按入队顺序播 */
    private val queue = ArrayDeque<String>()

    private var playing = false
    private var muted = false

    init {
        loadIndex()
        // 必须声明成「导航语音」，否则系统把它当未知用途：
        // 音乐/播报同时响时不会做闪避，车机/蓝牙也可能是另一路音量。
        // （sherpa 那条链路用的就是 USAGE_ASSISTANCE_NAVIGATION_GUIDANCE）
        runCatching {
            player.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        player.setOnCompletionListener { handler.post { playNext() } }
        player.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "播放出错 what=$what extra=$extra，跳过该段")
            handler.post { playNext() }
            true
        }
        Log.i(TAG, "内置语音包就绪：${index.size} 句")
    }

    private fun loadIndex() {
        runCatching {
            app.assets.open("$DIR/$INDEX").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val tab = line.lastIndexOf('\t')
                    if (tab > 0) {
                        index[line.substring(0, tab)] = line.substring(tab + 1)
                    }
                }
            }
        }.onFailure { Log.e(TAG, "语音包索引读取失败", it) }
    }

    override fun speak(text: String, priority: SpeechPriority, interrupt: Boolean) {
        // MediaPlayer 必须在创建它的线程（主线程）上用，业务侧偶尔从回调线程调
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { speak(text, priority, interrupt) }
            return
        }
        if (muted || text.isBlank()) return
        val clips = clipsFor(text)
        if (clips.isEmpty()) {
            Log.w(TAG, "语音包里没有可用片段，跳过：$text")
            return
        }
        Log.i(TAG, "播报：$text → ${clips.size} 段")
        if (interrupt) {
            queue.clear()
            halt()
        }
        queue.addAll(clips)
        if (!playing) playNext()
    }

    /** 内置包已全部在 assets 里，没有"合成"这一步 */
    override fun prewarm(text: String) = Unit

    override fun stop() {
        queue.clear()
        halt()
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        if (muted) stop()
    }

    override fun release() {
        stop()
        runCatching { player.release() }
    }

    // ---------- 文本 → 片段 ----------

    /**
     * 把一句播报切成可播放的片段序列。
     *
     * 先试整句；不行就贪心取最长前缀，跳过匹配不上的字（路名、标点）。
     */
    private fun clipsFor(text: String): List<String> {
        index[text]?.let { return listOf(it) }
        val out = ArrayList<String>(4)
        var i = 0
        val n = text.length
        while (i < n) {
            var len = minOf(MAX_CLIP_CHARS, n - i)
            var hit: String? = null
            while (len >= 1) {
                val cand = text.substring(i, i + len)
                if (index.containsKey(cand) && !isNumberTail(text, i, cand)) {
                    hit = cand
                    break
                }
                len--
            }
            if (hit != null) {
                index[hit]?.let { out.add(it) }
                i += hit.length
            } else {
                i++
            }
        }
        return out
    }

    /**
     * 这一段是不是从一个更长的数字里切出来的。
     *
     * 「开始导航，全程约 **10.1 公里**」里，贪心匹配会在 "10" 处失败（库里没有），
     * 一路退到 "1 公里" 命中 —— 播出去就成了"一公里"，与实际差了 9 公里。
     * 数字片段前面紧挨数字或小数点的一律不要。
     */
    private fun isNumberTail(text: String, start: Int, cand: String): Boolean {
        if (start == 0 || cand.isEmpty() || !cand[0].isDigit()) return false
        val prev = text[start - 1]
        return prev.isDigit() || prev == '.' || prev == '．'
    }

    // ---------- 播放 ----------

    private fun playNext() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            playing = false
            return
        }
        playing = true
        runCatching {
            player.reset()
            // mp3 在 assets 里不压缩，可以拿 fd 直接播，不用整段读进内存
            app.assets.openFd("$DIR/$next").use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.prepare()
            player.start()
        }.onFailure {
            Log.w(TAG, "片段播放失败：$next", it)
            handler.post { playNext() }
        }
    }

    private fun halt() {
        playing = false
        // isPlaying 在 Idle 状态会抛，两段分开兜，保证 reset 一定执行
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.reset() }
    }
}
