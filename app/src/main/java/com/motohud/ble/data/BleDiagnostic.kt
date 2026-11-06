package com.motohud.ble.data

import android.content.Context
import com.motohud.ble.ble.BleProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 蓝牙连接诊断记录器（单例）。
 *
 * 在 BleConnectionManager / MainViewModel 的关键节点调用，持续记录整条链路：
 *   连接状态 → 服务发现 → CCCD 通知开启 → HELLO 握手 → 收帧(原始+解码) → 发送指令结果
 *
 * 持久化：每条日志同时异步追加到 filesDir/ble_diagnostic/diag.log（单线程写、1MB 自动轮转），
 * 因此即便一次测试产生成千上万帧，也不会像内存那样被截断；诊断页可随时读取完整文件，
 * 并导出一个 .txt 文件通过系统分享面板发出（不依赖电脑）。
 */
object BleDiagnostic {

    private val _state = MutableStateFlow("DISCONNECTED")
    val state = _state.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName = _deviceName.asStateFlow()

    private val _notifyReady = MutableStateFlow(false)
    val notifyReady = _notifyReady.asStateFlow()

    private val _helloSent = MutableStateFlow(false)
    val helloSent = _helloSent.asStateFlow()

    private val _frameCount = MutableStateFlow(0)
    val frameCount = _frameCount.asStateFlow()

    private val _lastRaw = MutableStateFlow("")
    val lastRaw = _lastRaw.asStateFlow()

    private val _lastSummary = MutableStateFlow("")
    val lastSummary = _lastSummary.asStateFlow()

    private val _sent = MutableStateFlow<List<SentLine>>(emptyList())
    val sent = _sent.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log = _log.asStateFlow()

    data class SentLine(val text: String, val ok: Boolean?)
    data class LogLine(val time: String, val msg: String)

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var logFile: File? = null
    private var writer: BufferedWriter? = null
    private val writerBusy = AtomicBoolean(false)
    private const val MAX_FILE_BYTES = 1024 * 1024L // 1MB 后轮转

    /**
     * 导航关键事件单独一个文件（`navi_trace.log`），**不跟着 BLE 帧一起轮转**。
     *
     * 为什么要分开：`diag.log` 满 1MB 就轮转，而导航中 BLE 每秒写几十帧，
     * 实车八九分钟就把整个文件推掉一遍。偏航重算这种事一次骑行只出现一两次，
     * 写在 diag.log 里，等骑完回来看早就被帧日志挤没了 —— 现场根本没法查。
     * 这里一次骑行也就几十行，128KB 够存几十趟。
     */
    private var traceFile: File? = null
    private var traceWriter: BufferedWriter? = null
    private const val TRACE_MAX_BYTES = 128 * 1024L

    fun attach(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "ble_diagnostic")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, "diag.log")
        traceFile = File(dir, "navi_trace.log")
        openWriter()
    }

    private fun openWriter() {
        try {
            writer?.close()
            writer = BufferedWriter(FileWriter(logFile, true))
        } catch (_: Exception) {
            writer = null
        }
    }

    /** 一次新的连接尝试开始时调用，清空会话级计数（文件日志保留历史，不清空） */
    fun onNewConnection(name: String?) {
        _deviceName.value = name
        _state.value = "CONNECTING"
        _notifyReady.value = false
        _helloSent.value = false
        _frameCount.value = 0
        _lastRaw.value = ""
        _lastSummary.value = ""
        log("开始连接: ${name ?: "未知设备"}")
    }

    fun setState(s: String) {
        _state.value = s
        if (s == "DISCONNECTED") _notifyReady.value = false
        log("状态 → $s")
    }

    fun setNotifyReady(v: Boolean) {
        _notifyReady.value = v
        if (v) log("通知通道已开启 (CCCD 写入成功)")
    }

    fun markHelloSent() {
        if (!_helloSent.value) {
            _helloSent.value = true
            log("已发送 HELLO 握手")
        }
    }

    fun onNotify(hex: String, channel: String = "A") {
        _frameCount.value = _frameCount.value + 1
        _lastRaw.value = hex
        _lastSummary.value = summarize(hex)
        log("收帧[$channel] #${_frameCount.value}: $hex")
    }

    fun onSend(text: String) {
        _sent.value = (_sent.value + SentLine(text, null)).takeLast(40)
        log("发送: $text")
    }

    fun onWriteResult(success: Boolean, hex: String) {
        val list = _sent.value.toMutableList()
        for (i in list.indices.reversed()) {
            if (list[i].ok == null) {
                list[i] = list[i].copy(ok = success)
                break
            }
        }
        _sent.value = list
        log("写入结果: ${if (success) "成功" else "失败"} ($hex)")
    }

    fun reset() {
        _state.value = "DISCONNECTED"
        _deviceName.value = null
        _notifyReady.value = false
        _helloSent.value = false
        _frameCount.value = 0
        _lastRaw.value = ""
        _lastSummary.value = ""
        _sent.value = emptyList()
        _log.value = emptyList()
        // 清空落盘日志
        ioExecutor.execute {
            try {
                writer?.close()
                logFile?.delete()
                openWriter()
            } catch (_: Exception) {
            }
        }
    }

    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _log.value = (_log.value + LogLine(t, msg)).takeLast(300)
        // 落盘带**日期**：diag.log 是 1MB 轮转的循环历史，只写 HH:mm:ss 时
        // 跨天的新旧内容混在一起根本分不清（实车排障时反复踩到）。
        // 内存快照仍用 HH:mm:ss，诊断页展示不受影响。
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        appendToFile("[$stamp] $msg")
    }

    /**
     * 记录导航关键事件（重算、路线切换、播报决策等），落到不会被 BLE 帧挤掉的
     * `navi_trace.log`。实车抓不到 logcat 时就靠它复盘。
     */
    fun trace(msg: String) {
        val t = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        _log.value = (_log.value + LogLine(t, msg)).takeLast(300)
        val line = "[$t] $msg"
        ioExecutor.execute {
            try {
                var f = traceFile ?: return@execute
                if (f.exists() && f.length() > TRACE_MAX_BYTES) {
                    traceWriter?.close()
                    val rotated = File(f.parent, "navi_trace.log.1")
                    if (rotated.exists()) rotated.delete()
                    f.renameTo(rotated)
                    traceWriter = BufferedWriter(FileWriter(f, true))
                }
                if (traceWriter == null) traceWriter = BufferedWriter(FileWriter(f, true))
                traceWriter?.appendLine(line)
                traceWriter?.flush()
            } catch (_: Exception) {
                traceWriter = null
            }
        }
    }

    private fun appendToFile(line: String) {
        ioExecutor.execute {
            try {
                val f = logFile ?: return@execute
                if (f.exists() && f.length() > MAX_FILE_BYTES) {
                    // 轮转：旧文件改名 .1，重新开新文件
                    writer?.close()
                    val rotated = File(f.parent, "diag.log.1")
                    if (rotated.exists()) rotated.delete()
                    f.renameTo(rotated)
                    openWriter()
                }
                writer?.appendLine(line)
                writer?.flush()
            } catch (e: Exception) {
                writer = null
            }
        }
    }

    /** 读取完整落盘日志（最多返回末尾 80KB，避免一次性载入过大） */
    fun loadPersisted(): String {
        val f = logFile ?: return ""
        if (!f.exists()) return ""
        return try {
            val bytes = f.length()
            if (bytes <= 80 * 1024) f.readText()
            else {
                val raf = f.inputStream().bufferedReader()
                // 跳过前面，取末尾 80KB
                val skip = (bytes - 80 * 1024).coerceAtLeast(0)
                raf.skip(skip)
                val tail = raf.readText()
                "...(更早的日志已省略)...\n$tail"
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** 导出为 .txt 文件到 external-files/diagnostic/，供系统分享面板发出 */
    suspend fun export(context: Context): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "diagnostic")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val out = File(dir, "ble_diagnostic_$stamp.txt")
        // 导航事件单独一段带上：它才是"偏航有没有重算、线有没有重画"的证据，
        // 而 diag.log 早就被 BLE 帧轮转光了
        val body = buildString {
            append(loadPersisted().ifEmpty { exportText() })
            val tr = traceFile
            if (tr != null && tr.exists() && tr.length() > 0) {
                append("\n\n===== 导航事件（navi_trace.log） =====\n")
                append(tr.readText())
            }
        }
        out.writeText(body)
        out
    }

    private fun summarize(hex: String): String {
        val f = BleProtocol.parseFrame(hex) ?: return "无法解析 (${hex.length} 字符)"
        return when (f) {
            is BleProtocol.Frame.CmdA ->
                "CmdA 开关位域 " + f.switches.joinToString("") { if (it == 1) "1" else "0" }
            is BleProtocol.Frame.CmdB ->
                "CmdB 速度=${f.speed} 转速=${f.rotatingSpeed} 油耗=${f.instantaneousFuel}"
            is BleProtocol.Frame.CmdC ->
                "CmdC 水温=${f.waterTemperature} 环境温度=${f.roomTemperature} 油量=${f.oilQuantity}% 电压=${f.batteryVoltage}V"
            is BleProtocol.Frame.CmdD ->
                "CmdD 总里程=${f.totalMileage} 极速=${f.maxSpeed} 均速=${f.averageSpeed}"
            is BleProtocol.Frame.CmdE ->
                "CmdE 故障索引=${f.malfunctionIndex} ${f.malfunctionCode ?: ""}"
            is BleProtocol.Frame.CmdF ->
                "CmdF 故障索引=${f.malfunctionIndex} ${f.malfunctionCode ?: ""}"
            is BleProtocol.Frame.Unknown -> "未知命令 cmd=${f.cmd}"
        }
    }

    fun exportText(): String = buildString {
        append("=== 蓝牙连接诊断（内存快照） ===\n")
        append("状态: ${_state.value}\n")
        append("设备: ${_deviceName.value ?: "无"}\n")
        append("通知通道: ${if (_notifyReady.value) "已开启" else "未开启"}\n")
        append("HELLO 握手: ${if (_helloSent.value) "已发送" else "未发送"}\n")
        append("收到帧数: ${_frameCount.value}\n")
        append("最近原始帧: ${_lastRaw.value}\n")
        append("最近帧解析: ${_lastSummary.value}\n\n")
        append("--- 最近发送指令 ---\n")
        _sent.value.reversed().forEach { line ->
            val mark = if (line.ok == true) "OK" else if (line.ok == false) "FAIL" else "..."
            append("[$mark] ${line.text}\n")
        }
        append("\n--- 事件日志 ---\n")
        _log.value.forEach { append("[${it.time}] ${it.msg}\n") }
    }
}
