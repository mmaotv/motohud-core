package com.motohud.ble.ui.diagnostic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.motohud.ble.R
import com.motohud.ble.data.BleDiagnostic
import com.motohud.ble.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 蓝牙连接诊断页：把 BleDiagnostic 累积的链路状态实时呈现，
 * 完整日志已落盘（不丢帧），支持一键导出 .txt 文件通过系统分享面板发出。
 */
class DiagnosticActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLastFrame: TextView
    private lateinit var tvDetail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        /*
         * 日 / 夜主题必须在 super 之前定下来。
         * `?attr/mtXxx` 是在布局 inflate 那一刻从主题里取值的，晚一步设置
         * 只能影响之后新建的 View，已经铺好的界面一个字都不会变。
         */
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic)
        tvStatus = findViewById(R.id.tv_status)
        tvLastFrame = findViewById(R.id.tv_last_frame)
        tvDetail = findViewById(R.id.tv_detail)

        findViewById<Button>(R.id.btn_copy).setOnClickListener { copy() }
        findViewById<Button>(R.id.btn_export).setOnClickListener { export() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            BleDiagnostic.reset()
            Toast.makeText(this, R.string.diagnostic_cleared, Toast.LENGTH_SHORT).show()
            refresh()
        }

        // log 会在每一个关键事件时更新，因此观察它即可驱动整页刷新
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BleDiagnostic.log.collect { refresh() }
            }
        }
    }

    private fun refresh() {
        tvStatus.text = buildString {
            append("状态: ${BleDiagnostic.state.value}\n")
            append("设备: ${BleDiagnostic.deviceName.value ?: "无"}\n")
            append("通知通道: ${if (BleDiagnostic.notifyReady.value) "已开启" else "未开启"}\n")
            append("HELLO 握手: ${if (BleDiagnostic.helloSent.value) "已发送" else "未发送"}\n")
            append("收到帧数: ${BleDiagnostic.frameCount.value}")
        }
        tvLastFrame.text = buildString {
            append("最近原始帧: ${BleDiagnostic.lastRaw.value}\n")
            append("最近帧解析: ${BleDiagnostic.lastSummary.value}")
        }
        // 显示完整落盘日志（不丢帧），而非仅内存最近 N 行
        tvDetail.text = BleDiagnostic.loadPersisted().ifEmpty { BleDiagnostic.exportText() }
    }

    private fun copy() {
        val text = BleDiagnostic.loadPersisted().ifEmpty { BleDiagnostic.exportText() }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ble_diagnostic", text))
        Toast.makeText(this, R.string.diagnostic_copied, Toast.LENGTH_SHORT).show()
    }

    private fun export() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { BleDiagnostic.export(this@DiagnosticActivity) }
            val uri: Uri = FileProvider.getUriForFile(
                this@DiagnosticActivity,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.diagnostic_export)))
            } catch (_: Exception) {
                Toast.makeText(this@DiagnosticActivity, R.string.diagnostic_export_fail, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
