package com.motohud.ble.navi.tts

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 离线 TTS 模型的本地仓库（下载 / 校验 / 解压 / 删除）。
 *
 * ## 为什么模型不进 APK
 *
 * 中文 VITS 模型 `model.onnx` 约 29MB。塞进 APK 会让**所有**用户为"可能用不到的
 * 功能"付流量，而且换模型必须重新发版。改为按需下载后：
 *  - 包体只含引擎（sherpa-onnx AAR 的 so）；
 *  - 换模型 / 改进读音表只需换下载包，不用发新版；
 *  - 模型没下载时自动退回内置语音包，功能不残缺。
 *
 * ## 模型来源
 *
 * `vits-icefall-zh-aishell3`（**Apache-2.0**，可商用）。
 * 注意：网上常被推荐的 `matcha-icefall-zh-baker` 训练数据是 DataBaker
 * **非商用**授权，打进商用 App 有法律风险，所以没用它。
 *
 * 我们重新打了 zip（官方发的是 tar.bz2，Android 没有内置 bzip2 解压）：
 * 只保留 model.onnx / lexicon.txt / tokens.txt 和 4 个小的文本规范化 fst，
 * **剔除 172MB 的 rule.far** —— 那是完整规则库，导航播报用不到。
 */
object TtsModelStore {

    /** 模型版本号：换了下载包就 +1，用来触发"需要重新下载" */
    const val VERSION = 1

    private const val DIR = "tts"

    private const val URL =
        "https://github.com/mmaotv/motohud-core/releases/download/tts-model-v1/tts-zh-aishell3.zip"

    /** 缺一个都不能合成 */
    private val REQUIRED = listOf("model.onnx", "lexicon.txt", "tokens.txt")

    /** 文本规范化规则（数字/日期/多音字），有就带上，没有也能跑 */
    private val OPTIONAL_FST = listOf("date.fst", "number.fst", "new_heteronym.fst", "phone.fst")

    /** zip 合理下限，用来挡"下载到一半的坏包" */
    private const val MIN_ZIP_BYTES = 20L * 1024 * 1024

    fun root(context: Context): File = File(context.filesDir, DIR)

    fun file(context: Context, name: String): File = File(root(context), name)

    fun isInstalled(context: Context): Boolean =
        REQUIRED.all { file(context, it).let { f -> f.isFile && f.length() > 0 } }

    /** 已占用空间（设置页显示用） */
    fun installedBytes(context: Context): Long =
        runCatching { root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)

    fun delete(context: Context): Boolean {
        // 先把引擎里的模型引用放掉，再删文件：否则进程里还攥着一个指向
        // 已删除路径的引擎，下次播报只会合成失败
        SherpaSpeaker.resetEngine()
        return runCatching { root(context).deleteRecursively() }.getOrDefault(false)
    }

    /** 交给引擎的 fst 规则列表（绝对路径，逗号分隔） */
    fun ruleFsts(context: Context): String =
        OPTIONAL_FST.map { file(context, it) }
            .filter { it.isFile }
            .joinToString(",") { it.absolutePath }

    /**
     * 下载并安装模型。
     *
     * @param onProgress (已下载字节, 总字节)；总字节未知时为 -1
     */
    suspend fun install(
        context: Context,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = root(context)
            if (!dir.exists() && !dir.mkdirs()) error("无法创建模型目录")
            // 先下到临时文件：中途失败/取消都不会留下半个"看起来已安装"的目录
            val tmp = File(context.cacheDir, "tts_model.zip")
            tmp.delete()

            val conn = (URL(URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MotoHud")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) error("服务器返回 $code")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            // 节流：每 256KB 才回调一次，避免刷爆 UI
                            if (done - lastEmit >= 256 * 1024) {
                                lastEmit = done
                                onProgress(done, total)
                            }
                        }
                        onProgress(done, total)
                    }
                }
            } finally {
                runCatching { conn.disconnect() }
            }

            if (tmp.length() < MIN_ZIP_BYTES) {
                tmp.delete()
                error("下载的模型文件不完整")
            }

            // 解压：直接平铺到 tts 目录（zip 内就是平铺的）
            ZipInputStream(tmp.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.substringAfterLast('/')
                    if (name.isBlank()) continue
                    FileOutputStream(file(context, name)).use { out -> zin.copyTo(out) }
                }
            }
            tmp.delete()

            if (!isInstalled(context)) error("模型文件缺失，请重试")
            Unit
        }
    }
}
