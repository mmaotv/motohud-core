package com.motohud.ble.navi.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.navi.SilentSpeaker
import com.motohud.ble.navi.Speaker
import com.motohud.ble.navi.SpeechPriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 离线神经 TTS 播报器（sherpa-onnx + 中文 VITS 模型）。
 *
 * 它是现在**唯一**的播报实现：模型装好后能念任意文本（含路名），
 * 完全离线、不依赖系统任何组件。原来的预录语音包已移除——它念不出路名、
 * 内容也太受限，且与合成引擎会抢着出声。
 *
 * ## 三条必须守住的底线
 *
 * 1. **不能卡 UI**：模型加载要几百毫秒、合成长句要几百毫秒，全部在后台单线程
 *    完成，主线程只负责播放。
 * 2. **不能叠音**：只有一个播放通道（AudioTrack），抢播就先掐掉自己，
 *    不抢播就等前一句播完再播（见 [awaitChannels]）。不存在第二套声音来抢频道。
 * 3. **不能重复播**：一次 `speak` 一个序号，新的播报来了旧的就作废，
 *    合成结果只对得上当前序号才播。
 *
 * ## 延迟是怎么压下去的
 *
 * 靠 [prewarm]：导航引擎知道"下一步是什么"，在距触发点十几秒时就先把那句话
 * 合成好放进 [cache]（按文本做 key）。真正播报时 90% 命中缓存 → 首字延迟≈0。
 * 未命中才走"异步合成"那条慢路径（首句可能慢半拍，但一定会播出来）。
 *
 * ## 没有模型怎么办
 *
 * 模型需要用户在点「下载语音」后才有。没装模型时本类引擎为 null，
 * 所有播报静默跳过（由 [com.motohud.ble.navi.SpeechRouter] 直接返回
 * [SilentSpeaker]，根本不会进到这里）。所以导航不会崩，只是没声音——
 * 提示用户去下载即可。
 */
class SherpaSpeaker(context: Context) : Speaker {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /**
     * 合成串行化：sherpa 的 OfflineTts 实例不是线程安全的，必须单线程访问。
     *
     * 用**进程级**单线程（见 [synthWorker]）：实例级的话，"路线页预合成"和
     * "导航页播报"会用两个线程同时进 generate，直接崩在 native 层。
     */
    private val worker get() = synthWorker

    /**
     * 已合成音频缓存（key = 规范化后的文本 + 音色 + 语速），按字节估算大小。
     *
     * 放进程级：同一句话下次导航还会用到（"前方 200 米右转"这种短句复用率很高），
     * 没必要每次进导航都重新合成一遍。
     */
    private val cache get() = sharedCache

    /**
     * 引擎也是进程级单例。
     *
     * 加载一次要 3 秒左右（29MB 模型 + 图优化），如果跟着 Activity 走，
     * 每次进导航的前几秒都只能退回静默 —— 表现就是"路名时有时无"。
     */
    private val engine: OfflineTts? get() = sharedEngine

    @Volatile private var muted = false
    @Volatile private var released = false

    /** 播报序号：每次 speak 自增，用来丢弃"已被新播报取代"的合成结果 */
    private var seq = 0

    private var track: AudioTrack? = null

    /** 当前这段合成音频预计播完的时刻（uptimeMillis） */
    private var ttsEndsAt = 0L

    init {
        loadEngine()
    }

    // ---------- Speaker ----------

    override fun speak(
        text: String,
        priority: SpeechPriority,
        interrupt: Boolean
    ) {
        if (released || muted) {
            // 这两条静默路径以前没有任何日志，出问题时根本查不到"为什么没声音"
            Log.w(TAG, "跳过播报（released=$released muted=$muted）：$text")
            return
        }
        val normalized = SpeechText.normalize(text)
        // 文本太短或为空：合成出来也没意义，直接跳过（已无离线语音包兜底）
        if (normalized.isEmpty()) {
            Log.w(TAG, "文本规范化后为空，跳过播报：$text")
            return
        }
        if (interrupt) {
            halt()
        } else {
            seq++
        }
        val mySeq = seq
        if (interrupt) {
            startTts(normalized, mySeq)
        } else {
            // 「接在后面播」：AudioTrack 单通道，合不进一个队列，只能等前一句说完
            awaitChannels(mySeq) { startTts(normalized, mySeq) }
        }
    }

    /** 走合成通道（语音库命中 → 立即播；未命中 → 后台合成并回写语音库） */
    private fun startTts(normalized: String, mySeq: Int) {
        val style = styleOr()
        val spd = speedOr()
        val key = cacheKey(normalized, style, spd)

        // ① 内存缓存：命中零延迟
        cache.get(key)?.let {
            playAudio(it, style.pitch)
            return
        }

        // ② 离线语音库（磁盘）：跨重启都在，这是首句不再慢半拍的关键
        VoiceLibrary.get(app, style.id, normalized)?.let {
            VoiceLibrary.touch(app, style.id, normalized)
            cache.put(key, it)
            playAudio(it, style.pitch)
            return
        }

        // 引擎还没就绪（模型没装 / 正在加载）：静默跳过，不制造噪音也不崩
        val tts = engine ?: run {
            Log.w(TAG, "引擎未就绪，跳过播报：$normalized")
            return
        }

        worker.execute {
            livePending.incrementAndGet()
            val t0 = System.currentTimeMillis()
            val audio = runCatching { tts.generate(normalized, style.speakerId, spd) }.getOrNull()
            Log.i(
                TAG,
                "合成耗时 ${System.currentTimeMillis() - t0}ms（${style.name} 语速 $spd）：$normalized"
            )
            if (audio == null || audio.samples.isEmpty()) {
                Log.w(TAG, "合成失败或为空：$normalized")
            } else {
                cache.put(key, audio)
                // 沉淀进语音库：念过的路名下次开机直接命中
                VoiceLibrary.put(app, style.id, normalized, audio)
            }
            main.post {
                livePending.decrementAndGet()
                if (released || mySeq != seq) return@post
                if (audio != null && audio.samples.isNotEmpty()) playAudio(audio, style.pitch)
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
        val style = styleOr()
        val spd = speedOr()
        val key = cacheKey(normalized, style, spd)
        if (cache.get(key) != null) return
        // 语音库里已经有了就不用再合成一遍
        if (VoiceLibrary.get(app, style.id, normalized) != null) return
        val tts = engine ?: return
        worker.execute {
            if (released) return@execute
            if (cache.get(key) != null) return@execute
            runCatching { tts.generate(normalized, style.speakerId, spd) }
                .getOrNull()
                ?.takeIf { it.samples.isNotEmpty() }
                ?.let {
                    cache.put(key, it)
                    VoiceLibrary.put(app, style.id, normalized, it)
                }
        }
    }

    override fun stop() = halt()

    /**
     * 掐掉当前正在播的 TTS。
     *
     * 现在已经只有一个通道，不存在"两套声音互不知情叠一起"的问题——这正是
     * 删除离线语音包后彻底根治叠音的原因。
     */
    private fun halt() {
        seq++
        stopTts()
    }

    private fun stopTts() {
        ttsEndsAt = 0L
        runCatching {
            track?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                it.flush()
            }
        }
    }

    /** TTS 通道是否正在出声 */
    private fun ttsSpeaking(): Boolean = SystemClock.uptimeMillis() < ttsEndsAt

    /**
     * 等 TTS 通道空出来再执行。
     *
     * `interrupt=false` 的语义是"接在前面那句后面播"，但 AudioTrack 是单通道队列，
     * 只能轮询等它播完。等超过 [QUEUE_WAIT_MS] 就**硬切**：宁可切掉半句，
     * 也绝不让播报无限排队积压。
     */
    private fun awaitChannels(mySeq: Int, action: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + QUEUE_WAIT_MS
        fun tick() {
            if (released || muted || mySeq != seq) return
            if (!ttsSpeaking()) return action()
            if (SystemClock.uptimeMillis() >= deadline) {
                Log.w(TAG, "上一句等不到结束，强制切换")
                stopTts()
                return action()
            }
            main.postDelayed({ tick() }, QUEUE_POLL_MS)
        }
        tick()
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        if (muted) stop()
    }

    override fun release() {
        released = true
        stop()
        releaseTrack()
        // 引擎、缓存、合成线程都是共享的，这里**不能**释放 —— 下一个导航页还要接着用。
        // 真要释放（删模型、改模型）走 [resetEngine]。
    }

    // ---------- 引擎 ----------

    /**
     * 等引擎就绪（供"试听"这类需要一个确定结果的场景用）。
     *
     * 导航播报**不该**调它 —— 那边有预合成兜底，等反而会制造静音。
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
                    Log.i(
                        TAG,
                        "TTS 引擎就绪，用时 ${System.currentTimeMillis() - started}ms，" +
                            "采样率 ${it.sampleRate()}Hz"
                    )
                }
                .onFailure { Log.w(TAG, "TTS 引擎加载失败（模型未下载？），播报将静默", it) }
            synchronized(LOAD_LOCK) { sharedLoading = false }
        }
    }

    // ---------- 播放 ----------

    /**
     * 播放一段已合成的 PCM。
     *
     * 用 `MODE_STATIC` 一次性写入再播：导航播报都是 1~4 秒的短句，
     * static 模式不用维护写入线程，也不会因为缓冲不足产生断音。
     *
     * 播出前会先按音量档位放大振幅——实测 sherpa 的 VITS 输出整体偏小，
     * 直接播会显得"声音太小"。放大后更清晰，峰值做夹断保护避免破音。
     */
    /**
     * 播放一段已合成的 PCM。
     *
     * 用 `MODE_STATIC` 一次性写入再播：导航播报都是 1~4 秒的短句，
     * static 模式不用维护写入线程，也不会因为缓冲不足产生断音。
     *
     * 播出前做两件事：
     *  1. 按 [pitch] 重采样变调（风格的一部分，见 [TtsStyle]）；
     *  2. 按音量档位放大振幅——sherpa 的 VITS 输出整体偏小，不放大就是"声音太小"。
     */
    private fun playAudio(audio: GeneratedAudio, pitch: Float = 1f) {
        if (released || muted) return
        releaseTrack()
        val samples = audio.samples
        if (samples.isEmpty()) return
        val rate = audio.sampleRate.coerceIn(8_000, 48_000)
        // 变调 + 放大（钳位到 ±1，避免削波破音）
        val gain = gainOr()
        val shaped = resample(samples, pitch)
        val out = FloatArray(shaped.size) { (shaped[it] * gain).coerceIn(-1f, 1f) }
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
                .setBufferSizeInBytes(out.size * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: run {
            Log.w(TAG, "AudioTrack 创建失败，跳过播报")
            return
        }
        val written = runCatching {
            at.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
        }.getOrDefault(-1)
        if (written <= 0) {
            runCatching { at.release() }
            return
        }
        track = at
        runCatching { at.play() }
        // MODE_STATIC 播完不会回调，只能按时长推算"什么时候说完"，
        // 用来回答「这个通道还在响吗」（见 [awaitChannels]）
        ttsEndsAt = SystemClock.uptimeMillis() + written * 1000L / rate + 150
        Log.i(
            TAG,
            "播报 ${written * 1000L / rate}ms（${rate}Hz，${out.size} 点，" +
                "增益 ${gain}x，音调 ${pitch}x）"
        )
    }

    /**
     * 线性插值重采样，用来做**变调**。
     *
     * @param factor > 1：样本被压缩 → 音调更高、语速更快（小女孩 / 佩奇 / 调皮）
     *              < 1：样本被拉伸 → 音调更低、语速更慢（熊大熊二 / 沉稳大叔）
     *
     * 角色音本来就自带"又快又尖""又慢又粗"的特征，所以这里让变调连带变速
     * 是**故意的**：真做"变调不变速"（PSOLA）代价大，对 8kHz 导航语音收益也不明显。
     */
    private fun resample(src: FloatArray, factor: Float): FloatArray {
        if (factor <= 0f || (factor - 1f) in -0.01f..0.01f) return src
        val outLen = (src.size / factor).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        var pos = 0f
        for (i in 0 until outLen) {
            val i0 = pos.toInt()
            if (i0 >= src.size - 1) {
                out[i] = src[src.size - 1]
                pos += factor
                continue
            }
            val frac = pos - i0
            out[i] = src[i0] + (src[i0 + 1] - src[i0]) * frac
            pos += factor
        }
        return out
    }

    private fun releaseTrack() {
        ttsEndsAt = 0L
        val t = track ?: return
        track = null
        runCatching {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.release()
        }
    }

    companion object {
        private const val TAG = "SherpaSpeaker"

        /**
         * 输出增益，用户可调（默认 2.0×）。
         *
         * sherpa VITS 原始振幅只有 ±0.3 左右，摩托车风噪下基本听不清，必须放大。
         * 钳位 ±1，多出来的部分削掉——轻微削波也比"听不见"好。
         */
        private fun gainOr(): Float = NaviPrefs.ttsVolume.value.coerceIn(1.0f, 3.0f)

        /**
         * 排队等待中的**实时播报**数量。
         *
         * 引擎是单线程且非线程安全的，后台生成语音库会把它占满——
         * 实测后果是"进导航后头几句要等两分钟才出声"。
         * 所以生成任务每念一条前先看这里：有人排队就立刻让位。
         */
        private val livePending = java.util.concurrent.atomic.AtomicInteger()

        /** 语音库生成前最多等引擎多久（毫秒）：App 启动时引擎还在预热 */
        private const val ENGINE_WAIT_MS = 60_000L

        /**
         * 「接在后面播」最多等多久（毫秒）。
         *
         * 导航播报一句一般 2~4 秒，5 秒还没说完说明是长句或通道卡住了；
         * 到点就硬切，宁可切掉半句也不能无限排队。
         */
        private const val QUEUE_WAIT_MS = 5_000L

        /** 轮询间隔：100~200ms 足够灵敏，又不会在主线程上空转 */
        private const val QUEUE_POLL_MS = 150L

        /** 多人模型，预热用的说话人；实际取值见 [TtsStyle] */
        private const val SPEAKER_ID = 0

        /**
         * 最终语速边界。
         *
         * 最终语速 = 风格基准语速 × 用户滑杆倍率，所以区间要比单看滑杆更宽：
         * 「小女孩」基准 1.16 再乘滑杆 1.4 就是 1.62，仍应合法。
         */
        private const val SPEED_MIN = 0.4f
        private const val SPEED_MAX = 2.0f

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
         * —— 第一次播报和"开始导航"只隔 100ms，必然慢半拍。
         */
        fun prefetch(context: Context, texts: List<String>) {
            val app = context.applicationContext
            if (!TtsModelStore.isInstalled(app)) return
            val style = styleOr()
            val spd = speedOr()
            val todo = texts.map { SpeechText.normalize(it) }
                .filter { it.isNotEmpty() }
                .map { cacheKey(it, style, spd) to it }
                .filter { sharedCache.get(it.first) == null }
                .distinctBy { it.first }
            if (todo.isEmpty()) return
            synthWorker.execute {
                val tts = sharedEngine ?: return@execute
                todo.forEach { (key, t) ->
                    if (sharedCache.get(key) != null) return@forEach
                    if (VoiceLibrary.get(app, style.id, t) != null) return@forEach
                    runCatching { tts.generate(t, style.speakerId, spd) }
                        .getOrNull()
                        ?.takeIf { it.samples.isNotEmpty() }
                        ?.let {
                            sharedCache.put(key, it)
                            VoiceLibrary.put(app, style.id, t, it)
                        }
                }
            }
        }

        // ---------- 离线语音库 ----------

        /**
         * 生成离线语音库（约 150 条常用播报语）。
         *
         * 这是新一代"语音包"：[VoiceLibrary.phrases] 里的句子**提前合成好存在手机里**，
         * 之后这些句子永远零延迟出声，且跨重启都在（内存缓存一退进程就空了）。
         *
         * 一条约 0.5~1s，150 条约 2~3 分钟，所以必须在后台跑 + 给进度，
         * 不能让用户干等一个转圈。生成中途退出也没事，已生成的都在，下次继续补。
         *
         * @param onProgress (已完成, 总数)，在主线程回调
         */
        fun buildVoiceLibrary(
            context: Context,
            onProgress: (done: Int, total: Int) -> Unit
        ) {
            val app = context.applicationContext
            if (!TtsModelStore.isInstalled(app)) return
            val style = styleOr()
            val spd = speedOr()
            val all = VoiceLibrary.phrases().map { SpeechText.normalize(it) }
                .filter { it.isNotEmpty() }
                .distinct()
            val total = all.size
            val main = Handler(Looper.getMainLooper())
            var done = 0
            synthWorker.execute {
                // 引擎加载是另一条线程在做（App 启动时预热），这里要等它就绪。
                // 等的是 synthWorker 自己，不阻塞引擎加载，也不会拖慢 UI。
                var tts = sharedEngine
                if (tts == null) {
                    val deadline = System.currentTimeMillis() + ENGINE_WAIT_MS
                    while (tts == null && System.currentTimeMillis() < deadline) {
                        Thread.sleep(200)
                        tts = sharedEngine
                    }
                }
                if (tts == null) {
                    Log.w(TAG, "引擎未就绪，语音库生成跳过")
                    main.post { onProgress(total, total) }
                    return@execute
                }
                all.forEach { t ->
                    // **实时播报永远优先**：语音库是"锦上添花"，可以下次再补；
                    // 但让导航等 2 分钟才出声是不能接受的。发现有排队中的实时合成就立刻让位。
                    if (livePending.get() > 0) {
                        Log.i(TAG, "语音库生成让位给实时播报，已生成 ${VoiceLibrary.count(app, style.id)} 条")
                        return@execute
                    }
                    if (VoiceLibrary.get(app, style.id, t) == null) {
                        runCatching { tts.generate(t, style.speakerId, spd) }
                            .getOrNull()
                            ?.takeIf { it.samples.isNotEmpty() }
                            ?.let {
                                VoiceLibrary.put(app, style.id, t, it)
                                sharedCache.put(cacheKey(t, style, spd), it)
                            }
                    }
                    done++
                    val d = done
                    main.post { onProgress(d, total) }
                }
                Log.i(TAG, "离线语音库生成完毕：${VoiceLibrary.count(app, style.id)} 条")
            }
        }

        /** 语音库已生成条数（给设置页显示用） */
        fun libraryCount(context: Context): Int =
            VoiceLibrary.count(context.applicationContext, styleOr().id)

        /** 语音库占用空间（字节） */
        fun libraryBytes(context: Context): Long =
            VoiceLibrary.bytes(context.applicationContext, styleOr().id)

        /** 语音库还差多少条没生成（>0 就说明值得在后台接着补） */
        fun libraryMissing(context: Context): Int =
            (phrasesCount() - libraryCount(context)).coerceAtLeast(0)

        private fun phrasesCount(): Int = VoiceLibrary.phrases().size

        /** 当前播报风格（决定说话人 + 基准语速 + 音调） */
        private fun styleOr(): TtsStyle.Style = TtsStyle.of(NaviPrefs.ttsStyle.value)

        /**
         * 最终语速 = 风格基准 × 用户滑杆倍率。
         *
         * 风格自带基准语速（御姐慢、小孩快），滑杆是在此之上的整体微调，
         * 两者叠加而不是互相覆盖 —— 否则调了滑杆就把风格调没了。
         */
        private fun speedOr(): Float =
            (styleOr().speed * NaviPrefs.ttsSpeed.value).coerceIn(SPEED_MIN, SPEED_MAX)

        /** 缓存键：文本 + 风格 + 最终语速，三者任一变化都不复用 */
        private fun cacheKey(text: String, style: TtsStyle.Style, speed: Float): String =
            "${style.id}|$speed|$text"

        /**
         * App 启动时预热引擎。
         *
         * 不预热的话，进导航的头 3 秒只能静默 —— 用户听到的就是
         * "这一句有、下一句没有"。放到后台线程做，启动速度不受影响。
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
                        Log.i(
                            TAG,
                            "TTS 引擎预加载完成，用时 ${System.currentTimeMillis() - started}ms"
                        )
                    }
                    .onFailure { Log.w(TAG, "TTS 引擎预加载失败（模型未下载？）", it) }
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
                        // 1.0 = 模型自然语速；之前 0.95 偏快、声调发尖，
                        // 调回自然值让导航播报更稳更清楚
                        lengthScale = 1.0f
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
            // 预热一句**与真实播报同长度结构**的话：sherpa 首次 generate 会按输入长度触发
            // 图优化，热身句太短（如"准备出发"）时，第一个真实导航句仍要再付一次 ~4s 优化，
            // 表现就是"进导航头一句慢半拍"。用"前方 200 米右转进入南滨路"把开销提前到 App 启动时。
            runCatching { tts.generate("前方 200 米右转进入南滨路", SPEAKER_ID, 1.0f) }
            return tts
        }
    }
}
