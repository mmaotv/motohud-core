package com.motohud.ble.navi.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.navi.ClipSpeaker
import com.motohud.ble.navi.Speaker
import com.motohud.ble.navi.SpeechPriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 离线神经 TTS 播报器（sherpa-onnx + 中文 VITS 模型）。
 *
 * ## 它解决了什么
 *
 * 内置语音包是预录片段拼装（"前方"+"300米"+"右转"），**念不出路名** ——
 * 这是它设计上的已知代价。这条路用真正的合成引擎，能把
 * 「沿黄桷坪正街行驶 500 米后右转」整句念出来，路口名不再缺失。
 *
 * ## 三条必须守住的底线
 *
 * 1. **不能卡 UI**：模型加载要几百毫秒、合成长句要几百毫秒，全部在后台单线程
 *    完成，主线程只负责播放。
 * 2. **不能没有声音**：合成失败 / 超时 / 模型没装 → 一律退回 [ClipSpeaker]。
 *    宁可播"前方 200 米右转"，也不能让导航变哑巴。
 * 3. **不能重复播**：看门狗（[FALLBACK_MS]）先播了片段之后，合成结果就不再播了，
 *    否则同一句话会被念两遍。
 *
 * ## 延迟是怎么压下去的
 *
 * 靠 [prewarm]：导航引擎知道"下一步是什么"，在距触发点十几秒时就先把那句话
 * 合成好放进 [cache]（按文本做 key）。真正播报时 90% 命中缓存 → 首字延迟≈0。
 * 未命中才走"异步合成 + 看门狗"那条慢路径。
 */
class SherpaSpeaker(context: Context) : Speaker {

    private val app = context.applicationContext
    private val fallback = ClipSpeaker(app)
    private val main = Handler(Looper.getMainLooper())

    /**
     * 合成串行化：sherpa 的 OfflineTts 实例不是线程安全的，必须单线程访问。
     *
     * 用**进程级**单线程（见 [synthWorker]）：实例级的话，"路线页预合成"和
     * "导航页播报"会用两个线程同时进 generate，直接崩在 native 层。
     */
    private val worker get() = synthWorker

    /**
     * 已合成音频缓存（key = 规范化后的文本），按字节估算大小。
     *
     * 放进程级：同一句话下次导航还会用到（"前方 200 米右转"这种短句复用率很高），
     * 没必要每次进导航都重新合成一遍。
     */
    private val cache get() = sharedCache

    /**
     * 引擎也是进程级单例。
     *
     * 加载一次要 3 秒左右（29MB 模型 + 图优化），如果跟着 Activity 走，
     * 每次进导航的前几秒都只能退回语音包 —— 表现就是"路名时有时无"。
     */
    private val engine: OfflineTts? get() = sharedEngine

    @Volatile private var muted = false
    @Volatile private var released = false

    /** 播报序号：每次 speak 自增，用来丢弃"已被新播报取代"的合成结果 */
    private var seq = 0

    private var track: AudioTrack? = null

    /** 看门狗：合成太慢时先让片段出声 */
    private var watchdog: Runnable? = null

    /** 本次播报是否已经用片段兜过底（兜过就不再播合成结果，避免重复） */
    private var fallbackUsed = false

    init {
        loadEngine()
    }

    // ---------- Speaker ----------

    override fun speak(
        text: String,
        clips: List<Int>,
        priority: SpeechPriority,
        interrupt: Boolean
    ) {
        if (released || muted) return
        val normalized = SpeechText.normalize(text)
        // 文本太短或为空：片段更快更自然
        if (normalized.isEmpty()) {
            fallback.speak("", clips, priority, interrupt)
            return
        }
        if (interrupt) {
            stop()
        }
        val mySeq = ++seq
        fallbackUsed = false
        cancelWatchdog()
        // 音色/语速是用户随时会改的，所以缓存 key 必须带上它们 ——
        // 否则换了音色还在播旧音色的缓存，用户会觉得"选项没用"
        val spk = NaviPrefs.ttsSpeaker.value
        val spd = speedOr()
        val key = cacheKey(normalized, spk, spd)

        // 缓存命中：直接播，零延迟
        cache.get(key)?.let {
            playAudio(it)
            return
        }

        // 引擎还没就绪（首次进入导航的头一两秒）：先用片段顶上，别让用户等
        val tts = engine
        if (tts == null) {
            fallback.speak("", clips, priority, interrupt)
            return
        }

        // 看门狗：合成超过 FALLBACK_MS 还没出声，就先播片段。
        // **没有片段可播时不能启动** —— 试听就是这种情况，兜底等于播了个空，
        // 还会把稍后才合成好的结果一起丢掉，表现就是"点了试听没声音"。
        if (clips.isNotEmpty()) {
            val wd = Runnable {
                if (mySeq == seq && !released && !fallbackUsed) {
                    fallbackUsed = true
                    Log.w(TAG, "合成超过 ${FALLBACK_MS}ms 未完成，先播片段：$normalized")
                    fallback.speak("", clips, priority, true)
                }
            }
            watchdog = wd
            main.postDelayed(wd, FALLBACK_MS)
        }

        worker.execute {
            val t0 = System.currentTimeMillis()
            val audio = runCatching { tts.generate(normalized, spk, spd) }.getOrNull()
            Log.i(TAG, "合成耗时 ${System.currentTimeMillis() - t0}ms（音色 $spk 语速 $spd）：$normalized")
            if (audio == null || audio.samples.isEmpty()) {
                Log.w(TAG, "合成失败或为空：$normalized")
            } else {
                cache.put(key, audio)
            }
            main.post {
                if (released || mySeq != seq) return@post
                cancelWatchdog()
                when {
                    // 看门狗已经播过片段 → 不再重复
                    fallbackUsed -> Unit
                    audio != null && audio.samples.isNotEmpty() -> playAudio(audio)
                    // 合成失败且还没出声 → 片段兜底，保证有声音
                    else -> fallback.speak("", clips, priority, false)
                }
            }
        }
    }

    /**
     * 预合成。
     *
     * 调用方（导航渲染循环）在距路口还有十几秒时就调它，届时 [speak] 命中缓存。
     * 重复调用同一句会被 [cache] 自然去重（命中即跳过）。
     */
    override fun prewarm(text: String) {
        if (released || muted) return
        val normalized = SpeechText.normalize(text)
        if (normalized.isEmpty()) return
        val spk = NaviPrefs.ttsSpeaker.value
        val spd = speedOr()
        val key = cacheKey(normalized, spk, spd)
        if (cache.get(key) != null) return
        val tts = engine ?: return
        worker.execute {
            if (released) return@execute
            if (cache.get(key) != null) return@execute
            runCatching { tts.generate(normalized, spk, spd) }
                .getOrNull()
                ?.takeIf { it.samples.isNotEmpty() }
                ?.let { cache.put(key, it) }
        }
    }

    override fun stop() {
        seq++
        cancelWatchdog()
        runCatching {
            track?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                it.flush()
            }
        }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        if (muted) stop()
        fallback.setMuted(muted)
    }

    override fun release() {
        released = true
        stop()
        fallback.release()
        releaseTrack()
        // 引擎、缓存、合成线程都是共享的，这里**不能**释放 —— 下一个导航页还要接着用。
        // 真要释放（删模型、改模型）走 [resetEngine]。
    }

    // ---------- 引擎 ----------

    /**
     * 等引擎就绪（供"试听"这类需要一个确定结果的场景用）。
     *
     * 导航播报**不该**调它 —— 那边有看门狗兜底，等反而会制造静音。
     * 但试听时如果引擎还没加载完就 speak，会直接掉到"先播片段"那条路，
     * 用户听不出任何变化，会以为模型没生效。
     *
     * @return 超时前就绪返回 true
     */
    suspend fun awaitReady(timeoutMs: Long = 10_000): Boolean =
        withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (engine != null) return@withContext true
                if (released) return@withContext false
                delay(80)
            }
            engine != null
        }

    /**
     * 后台加载模型（进程内只加载一次，见 [sharedEngine]）。
     *
     * 模型放在 `filesDir`（用户点下载才有的），配置里全部用绝对路径，
     * 所以构造 `OfflineTts` 时 AssetManager 必须传 `null`。
     */
    private fun loadEngine() {
        if (!markLoading()) return
        worker.execute {
            val started = System.currentTimeMillis()
            runCatching { buildEngine(app) }
                .onSuccess {
                    sharedEngine = it
                    Log.i(TAG, "TTS 引擎就绪，用时 ${System.currentTimeMillis() - started}ms，" +
                        "采样率 ${it.sampleRate()}Hz")
                }
                .onFailure { Log.w(TAG, "TTS 引擎加载失败，将使用内置语音包", it) }
            synchronized(LOAD_LOCK) { sharedLoading = false }
        }
    }

    // ---------- 播放 ----------

    /**
     * 播放一段已合成的 PCM。
     *
     * 用 `MODE_STATIC` 一次性写入再播：导航播报都是 1~4 秒的短句，
     * static 模式不用维护写入线程，也不会因为缓冲不足产生断音。
     */
    private fun playAudio(audio: GeneratedAudio) {
        if (released || muted) return
        releaseTrack()
        val samples = audio.samples
        if (samples.isEmpty()) return
        val rate = audio.sampleRate.coerceIn(8_000, 48_000)
        val at = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: run {
            Log.w(TAG, "AudioTrack 创建失败，退回语音包")
            return
        }
        val written = runCatching {
            at.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        }.getOrDefault(-1)
        if (written <= 0) {
            runCatching { at.release() }
            return
        }
        track = at
        runCatching { at.play() }
        Log.i(
            TAG,
            "播报 ${written * 1000L / rate}ms（${rate}Hz，${samples.size} 点）"
        )
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        runCatching {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.release()
        }
    }

    private fun cancelWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
    }

    companion object {
        private const val TAG = "SherpaSpeaker"

        /**
         * 合成超时阈值。超过就先播片段。
         *
         * 实测这台 arm64 设备合成长句要 700~1100ms，所以 800ms 会让大部分
         * 句子都被片段顶掉（路名又没了）。1200ms 的依据：市区 40km/h 时
         * 1.2s 走 13 米，对"前方 200 米右转"这种提醒仍然来得及。
         */
        private const val FALLBACK_MS = 1200L

        /** 多人模型，默认音色；实际取值见 [NaviPrefs.ttsSpeaker] */
        private const val SPEAKER_ID = 0

        /** 语速边界。低于 0.6 像慢放，高于 1.6 已经听不清字 */
        private const val SPEED_MIN = 0.6f
        private const val SPEED_MAX = 1.6f

        /** 缓存上限。一句 3 秒 16kHz float ≈ 190KB，24MB 能存约 120 句，够一个路口循环 */
        private const val CACHE_BYTES = 24 * 1024 * 1024

        private val LOAD_LOCK = Any()

        /**
         * 进程级合成线程。
         *
         * 必须唯一：`OfflineTts` 实例有可变内部状态，两个线程同时 generate
         * 会在 native 层崩（不是抛异常，是 SIGSEGV）。
         */
        private val synthWorker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "tts-synth").apply { priority = Thread.NORM_PRIORITY - 1 }
        }

        /** 进程级引擎实例（加载一次约 3 秒，不能跟着 Activity 走） */
        @Volatile private var sharedEngine: OfflineTts? = null

        /** 是否正在加载：防止两个 speaker 同时加载把内存吃爆 */
        @Volatile private var sharedLoading = false

        private val sharedCache = object : LruCache<String, GeneratedAudio>(CACHE_BYTES) {
            override fun sizeOf(key: String, value: GeneratedAudio): Int =
                value.samples.size * 4
        }

        /**
         * 释放共享引擎。
         *
         * 只有「删除语音模型」才需要调：模型文件已经没了，引擎指着的是不存在的路径，
         * 不重置的话下一个导航页会拿它合成失败。
         */
        fun resetEngine() {
            synchronized(LOAD_LOCK) {
                runCatching { sharedEngine?.release() }
                sharedEngine = null
                sharedLoading = false
                sharedCache.evictAll()
            }
        }

        /** 引擎是否已就绪（给设置页显示状态用） */
        fun isEngineReady(): Boolean = sharedEngine != null

        /**
         * 预合成一批文本（路线页算完路就调）。
         *
         * 为什么要在路线页做：算完路线到用户点「开始导航」之间有几秒，
         * 这几秒正好够合成前两句。等进了导航页再合成，第一句永远赶不上
         * —— 第一次播报和"开始导航"只隔 100ms，必然触发看门狗退回片段。
         */
        fun prefetch(context: Context, texts: List<String>) {
            val app = context.applicationContext
            if (!TtsModelStore.isInstalled(app)) return
            val spk = NaviPrefs.ttsSpeaker.value
            val spd = speedOr()
            val todo = texts.map { SpeechText.normalize(it) to it }
                .filter { it.first.isNotEmpty() }
                .map { cacheKey(it.first, spk, spd) to it.first }
                .filter { sharedCache.get(it.first) == null }
                .distinctBy { it.first }
            if (todo.isEmpty()) return
            synthWorker.execute {
                val tts = sharedEngine ?: return@execute
                todo.forEach { (key, t) ->
                    if (sharedCache.get(key) != null) return@forEach
                    runCatching { tts.generate(t, spk, spd) }
                        .getOrNull()
                        ?.takeIf { it.samples.isNotEmpty() }
                        ?.let { sharedCache.put(key, it) }
                }
            }
        }

        /** 当前音色下的语速，夹到安全区间 */
        private fun speedOr(): Float =
            NaviPrefs.ttsSpeed.value.coerceIn(SPEED_MIN, SPEED_MAX)

        /** 缓存键：文本 + 音色 + 语速，三者任一变化都不复用 */
        private fun cacheKey(text: String, speakerId: Int, speed: Float): String =
            "$speakerId|$speed|$text"

        /**
         * App 启动时预热引擎。
         *
         * 不预热的话，进导航的头 3 秒只能退回语音包 —— 用户听到的就是
         * "这一句有路名、下一句没有"。放到后台线程做，启动速度不受影响。
         */
        fun warmUp(context: Context) {
            val app = context.applicationContext
            if (!TtsModelStore.isInstalled(app)) return
            if (!markLoading()) return
            Thread({
                val started = System.currentTimeMillis()
                runCatching { buildEngine(app) }
                    .onSuccess {
                        sharedEngine = it
                        Log.i(TAG, "TTS 引擎预加载完成，用时 ${System.currentTimeMillis() - started}ms")
                    }
                    .onFailure { Log.w(TAG, "TTS 引擎预加载失败", it) }
                synchronized(LOAD_LOCK) { sharedLoading = false }
            }, "tts-preload").start()
        }

        /** 抢加载权：已经有引擎或正在加载就返回 false */
        private fun markLoading(): Boolean = synchronized(LOAD_LOCK) {
            if (sharedEngine != null) return false
            if (sharedLoading) return false
            sharedLoading = true
            true
        }

        /** 组装引擎。构造 OfflineTts 必须传 null 的 AssetManager，理由见调用处注释 */
        private fun buildEngine(app: Context): OfflineTts {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = TtsModelStore.file(app, "model.onnx").absolutePath,
                        lexicon = TtsModelStore.file(app, "lexicon.txt").absolutePath,
                        tokens = TtsModelStore.file(app, "tokens.txt").absolutePath,
                        // 不传 dataDir/dictDir：这个模型是纯中文 lexicon，
                        // 不需要 espeak-ng 的字典数据
                        dataDir = "",
                        dictDir = "",
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        // 语音包里一句话约 1.0；这里略快一点，导航播报更利落
                        lengthScale = 0.95f
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                // 数字/日期/多音字规范化交给模型自带的 fst，比手写规则准
                ruleFsts = TtsModelStore.ruleFsts(app),
                ruleFars = "",
                // 导航播报都是短句，一句一次合成；多了反而增加首字延迟
                maxNumSentences = 1,
                silenceScale = 0.2f
            )
            // AssetManager 必须传 null：sherpa 用「有没有 assetManager」决定按哪种方式
            // 打开文件。传了它就以为路径是 assets 内的相对路径，遇到绝对路径直接
            // fatal（ReadFile failed → 进程 abort），见 sherpa-onnx#2562。
            val tts = OfflineTts(null, config)
            // 预热一句短的：首次 generate 会触发图优化，留到播报时才做会明显卡顿
            runCatching { tts.generate("准备出发", SPEAKER_ID, 1.0f) }
            return tts
        }
    }
}
