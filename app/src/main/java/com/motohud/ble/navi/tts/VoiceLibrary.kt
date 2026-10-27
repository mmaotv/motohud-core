package com.motohud.ble.navi.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 离线语音库（新一代"语音包"）。
 *
 * ## 和老的预录语音包有什么不同
 *
 * 老的是 48 个 `res/raw/vp_*.mp3`，靠拼接说话，**念不出路名**，
 * 这就是"内容太少"的根源。
 * 新一代反着来：用离线神经引擎**预先把常用播报语合成好存在手机里**。
 * 既完全离线，又能念任意文本（含路名），条数也从 48 涨到 150+。
 *
 * ## 两层内容
 *
 * 1. **预置短语**（[phrases]，约 150 条）：距离 × 转向动作 + 系统提示语。
 *    装完模型一键生成后，这些句子**永远零延迟**——不用等合成。
 * 2. **增量沉淀**：每次实时合成成功的话也存进来（[put]）。
 *    跑过的路线、念过的路名会越攒越多，下次开机直接命中。
 *
 * 所以它既是"语音包"，也是一份跨重启的持久化缓存：
 * 内存 LruCache 进程一退就空，磁盘这份才是真正让首句不慢半拍的东西。
 *
 * ## 存储格式
 *
 * `filesDir/voice_lib/<风格>/<md5>.pcm`，4 字节采样率 + 16bit 单声道 PCM。
 * 16bit 而非 float：模型本身只有 8kHz，float 只是白白翻倍体积。
 */
object VoiceLibrary {

    private const val TAG = "VoiceLibrary"
    private const val DIR = "voice_lib"

    /** 增量沉淀的上限：再多就删最久没用过的，避免把用户存储吃干 */
    private const val MAX_FILES = 400
    private const val MAX_BYTES = 48L * 1024 * 1024

    // ---------- 预置短语 ----------

    /** 转向动作词，与 [SpeechScript.action] 保持一致 */
    private val ACTIONS = listOf(
        "左转", "右转", "向左前方行驶", "向右前方行驶",
        "向左后方行驶", "向右后方行驶", "掉头", "直行",
        "进入环岛", "靠左进入环岛", "驶出环岛",
        "向左并线", "向右并线"
    )

    /** 距离词，与 [SpeechScript.distance] 的分档完全一致——不一致就命中不了 */
    private val DISTANCES = listOf(
        "前方 50 米", "前方 100 米", "前方 200 米", "前方 300 米",
        "前方 500 米", "前方 800 米", "前方 1 公里", "前方 2 公里",
        "前方 5 公里", "前方 10 公里"
    )

    /** 系统提示语：这些是导航里一定会说、且不含路名的话 */
    private val NOTICES = listOf(
        "开始导航",
        "即将到达目的地",
        "已到达目的地",
        "已到达途经点",
        "您已偏离路线，正在重新规划",
        "已为您重新规划路线",
        "前方道路拥堵，正在寻找更快路线",
        "已为您切换到更快路线",
        "语音播报已开启",
        "语音播报已关闭",
        "正在搜索卫星信号",
        "卫星信号弱，请注意定位偏差",
        "请注意安全驾驶",
        "前方路口请减速慢行",
        "请保持直行",
        "请注意避让行人"
    )

    /**
     * 预置短语全集（约 150 条）。
     *
     * 距离 × 动作是全组合：骑行中真实遇到的组合不可预测，
     * 少一条就意味着那一句要临时合成，慢半拍才出声。
     */
    fun phrases(): List<String> {
        val out = ArrayList<String>(160)
        for (act in ACTIONS) {
            for (d in DISTANCES) out.add("$d$act")
        }
        out.addAll(NOTICES)
        return out.distinct()
    }

    // ---------- 读写 ----------

    private fun dir(app: Context, styleId: String): File =
        File(app.filesDir, "$DIR/$styleId").apply { mkdirs() }

    private fun file(app: Context, styleId: String, text: String): File =
        File(dir(app, styleId), md5(text) + ".pcm")

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** 读一条已生成的语音；不存在或损坏返回 null */
    fun get(app: Context, styleId: String, text: String): GeneratedAudio? {
        val f = file(app, styleId, text)
        if (!f.exists() || f.length() < 8) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { din ->
                val rate = din.readInt()
                val n = ((f.length() - 4) / 2).toInt()
                if (n <= 0) return null
                val samples = FloatArray(n)
                for (i in 0 until n) samples[i] = din.readShort() / 32767f
                GeneratedAudio(samples, rate)
            }
        }.getOrElse {
            Log.w(TAG, "语音库条目损坏，已忽略：$text")
            runCatching { f.delete() }
            null
        }
    }

    /** 存一条。每次实时合成成功都调，让语音库自己越攒越全 */
    fun put(app: Context, styleId: String, text: String, audio: GeneratedAudio) {
        if (audio.samples.isEmpty()) return
        val d = dir(app, styleId)
        runCatching {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file(app, styleId, text)))).use {
                it.writeInt(audio.sampleRate)
                for (s in audio.samples) {
                    it.writeShort((s.coerceIn(-1f, 1f) * 32767f).toInt())
                }
            }
            trim(d)
        }.onFailure { Log.w(TAG, "写入语音库失败", it) }
    }

    /** 已生成条数 */
    fun count(app: Context, styleId: String): Int =
        dir(app, styleId).listFiles()?.count { it.isFile && it.extension == "pcm" } ?: 0

    /** 占用空间（字节） */
    fun bytes(app: Context, styleId: String): Long =
        dir(app, styleId).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** 清空某个风格（换风格不删，删模型才删） */
    fun clear(app: Context, styleId: String) {
        runCatching { dir(app, styleId).deleteRecursively() }
    }

    fun clearAll(app: Context) {
        runCatching { File(app.filesDir, DIR).deleteRecursively() }
    }

    /**
     * 超限就删最久没用过的。
     *
     * 按最后修改时间而不是访问时间：Android 上 lastModified 是可靠的，
     * 而"最近用过"应该由调用方在 [get] 命中时更新。
     */
    private fun trim(d: File) {
        val files = d.listFiles()?.filter { it.isFile && it.extension == "pcm" } ?: return
        val total = files.sumOf { it.length() }
        if (files.size <= MAX_FILES && total <= MAX_BYTES) return
        files.sortedBy { it.lastModified() }
            .take((files.size - MAX_FILES + 1).coerceAtLeast(1))
            .forEach { runCatching { it.delete() } }
    }

    /** 命中时刷新时间戳，让 trim 知道这条还在用 */
    fun touch(app: Context, styleId: String, text: String) {
        runCatching {
            val f = file(app, styleId, text)
            if (f.exists()) f.setLastModified(System.currentTimeMillis())
        }
    }
}
