package com.motohud.ble.data

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 崩溃捕获与落盘。
 *
 * ## 为什么必须有这个
 *
 * 之前整个项目没有任何崩溃捕获：`Thread.setDefaultUncaughtExceptionHandler` 一处都没有。
 * 用户反馈「闪退了」时，能拿到的只有 [BleDiagnostic] 里那份 BLE 链路日志 ——
 * 那是**蓝牙通道**的流水，跟 Java 异常栈毫无关系。等于出了问题完全无从下手。
 *
 * ## 几个不能踩的坑
 *
 * 1. **必须转发给原 handler**。只接管不转发的话进程不会被系统正常杀掉，
 *    表现是「崩了但卡在原地」，次数多了被判 ANR，比直接崩还糟。
 * 2. **写文件要限时**。崩溃可能发生在任意线程，包括主线程。主线程里同步做 IO
 *    一旦卡住（磁盘满、SELinux、厂商魔改），系统会先判 ANR 而不是崩溃，
 *    用户看到的是「卡死」而不是「闪退」，日志还会丢。所以另起线程写并 join 限时。
 * 3. **报告本身不能抛异常**。整个写入过程包在 runCatching 里 ——
 *    写报告失败把原始崩溃盖掉，是最难查的一类问题。
 * 4. **只留最近 10 份**。崩溃循环（一启动就崩）会在几秒内刷出上百个文件，
 *    把用户存储塞满，反过来让 App 更起不来。
 *
 * ## 文件位置
 *
 * `filesDir/crash/crash-<时间戳>.txt`。放在 filesDir 而不是 cacheDir：
 * 系统清缓存时不会连带删掉，用户过几天想反馈时记录还在。
 * 导出时复制到 `external-files/diagnostic/`（已有 FileProvider 授权），走系统分享面板。
 */
object CrashHandler {

    private const val DIR = "crash"
    private const val PREFIX = "crash-"
    private const val SUFFIX = ".txt"

    /** 最多保留几份，超出按时间删最旧的 */
    private const val MAX_FILES = 10

    /** 写盘最长等待：超过就放弃，宁可丢报告也不能让进程卡成 ANR */
    private const val WRITE_TIMEOUT_MS = 2500L

    /** 摘要里附带的诊断日志尾部字节数（完整 diag.log 可能有 1MB，全塞进去没人看得完） */
    private const val DIAG_TAIL_BYTES = 8 * 1024

    private const val PREFS = "crash_prefs"
    private const val KEY_PENDING = "pending_count"

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * 安装全局捕获。
     *
     * @param extras 由 Application 提供，用于补充「崩溃那一刻 App 在干什么」。
     *   例如导航是否在跑、连的是哪台车机。**必须自己保证不抛异常**，
     *   这里再包一层 runCatching 兜底。
     */
    fun install(app: Application, extras: (() -> String)? = null) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // ① 尽力写盘，绝不让自己抛出去
            runCatching { writeReportBlocking(app, thread, error, extras) }
            // ② 交还给系统（或下一个 handler），保证进程被正常终止
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                exitProcess(10)
            }
        }
    }

    // ---------- 写入 ----------

    private fun writeReportBlocking(
        app: Application,
        thread: Thread,
        error: Throwable,
        extras: (() -> String)?
    ) {
        val body = buildReport(app, thread, error, extras)
        val dir = dir(app)
        // 独立线程 + join 限时：主线程崩溃时既不会卡死，也能在进程被杀前尽量写完
        val writer = Thread({
            runCatching {
                dir.mkdirs()
                val f = File(dir, PREFIX + fileStamp.format(Date()) + SUFFIX)
                f.writeText(body)
                prune(dir)
            }
        }, "crash-writer")
        writer.isDaemon = true
        writer.start()
        runCatching { writer.join(WRITE_TIMEOUT_MS) }
        bumpPending(app)
    }

    private fun buildReport(
        app: Application,
        thread: Thread,
        error: Throwable,
        extras: (() -> String)?
    ): String {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        return buildString {
            appendLine("===== Mozheng BLE 崩溃报告 =====")
            appendLine("时间: ${stamp.format(Date())}")
            appendLine("版本: ${appVersion(app)}")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("进程: ${app.packageName} (pid ${android.os.Process.myPid()})")
            appendLine("线程: ${thread.name} (id=${thread.id}, 状态=${thread.state})")
            appendLine("内存: ${memoryLine()}")
            appendLine()
            appendLine("----- 附加状态 -----")
            appendLine(runCatching { extras?.invoke() }.getOrElse { "(采集失败: ${it.javaClass.simpleName})" })
            appendLine()
            appendLine("----- 异常栈 -----")
            appendLine(sw.toString())
            appendLine("----- BLE 诊断日志尾部 -----")
            appendLine(diagTail())
        }
    }

    private fun appVersion(app: Application): String = runCatching {
        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    }.getOrDefault("未知")

    private fun memoryLine(): String = runCatching {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val maxMb = rt.maxMemory() / 1024 / 1024
        "java heap ${usedMb}/${maxMb} MB"
    }.getOrDefault("未知")

    private fun diagTail(): String = runCatching {
        BleDiagnostic.loadPersisted().takeLast(DIAG_TAIL_BYTES)
    }.getOrElse { "(读取失败: ${it.javaClass.simpleName})" }

    /** 只留最近 [MAX_FILES] 份 */
    private fun prune(dir: File) {
        val all = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: return
        if (all.size <= MAX_FILES) return
        all.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    // ---------- 读 ----------

    private fun dir(context: Context): File = File(context.filesDir, DIR)

    /** 全部崩溃报告，最新的在前 */
    fun list(context: Context): List<File> = runCatching {
        (dir(context).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(PREFIX) }
            .sortedByDescending { it.name }
    }.getOrDefault(emptyList())

    /** 最近一次崩溃报告，没有则 null */
    fun latest(context: Context): File? = list(context).firstOrNull()

    /** 最近一次崩溃的摘要（时间 + 异常首行），用于设置面板与启动提示 */
    fun latestSummary(context: Context): String? {
        val f = latest(context) ?: return null
        return runCatching {
            val text = f.readText()
            val time = Regex("时间: (.+)").find(text)?.groupValues?.getOrNull(1)?.trim() ?: "未知时间"
            // 异常栈里第一行非空、非「异常栈」标题的行就是异常类型与消息
            val exception = text.substringAfter("----- 异常栈 -----", "")
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: "未知异常"
            "$time\n$exception"
        }.getOrNull()
    }

    // ---------- 待处理计数（下一次启动提示用） ----------

    /**
     * 崩溃次数。下次启动时如果 > 0，就提示用户「上次异常退出过，是否导出报告」。
     * 用计数而不是布尔：连续崩两次说明问题稳定复现，值得优先处理。
     */
    fun pendingCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PENDING, 0)

    /** 用户已经看过/导出过，清零 */
    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PENDING, 0).apply()
    }

    private fun bumpPending(context: Context) {
        runCatching {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.edit().putInt(KEY_PENDING, p.getInt(KEY_PENDING, 0) + 1).apply()
        }
    }

    // ---------- 导出 ----------

    /**
     * 把全部崩溃报告合并成一个 .txt，落在 `external-files/diagnostic/`，
     * 供 FileProvider + ACTION_SEND 分享出去。
     */
    fun export(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val target = File(dir, "diagnostic").apply { mkdirs() }
        val out = File(target, "crash-report.txt")
        val sb = StringBuilder()
        val files = list(context).reversed() // 导出时按时间正序，便于顺着看
        if (files.isEmpty()) {
            sb.appendLine("没有崩溃记录。")
        } else {
            sb.appendLine("共 ${files.size} 份崩溃报告（按时间正序）")
            files.forEachIndexed { i, f ->
                sb.appendLine()
                sb.appendLine("########## ${i + 1}/${files.size} ##########")
                sb.appendLine(runCatching { f.readText() }.getOrElse { "(读取失败: ${it.message})" })
            }
        }
        out.writeText(sb.toString())
        return out
    }

    /** 清空全部崩溃记录（连同待处理计数） */
    fun clear(context: Context) {
        list(context).forEach { runCatching { it.delete() } }
        clearPending(context)
    }
}
