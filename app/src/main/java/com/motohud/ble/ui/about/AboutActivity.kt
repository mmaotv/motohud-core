package com.motohud.ble.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.motohud.ble.BuildConfig
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.ble.BleConstants
import com.motohud.ble.data.UpdateChecker
import com.motohud.ble.ui.MainViewModel
import com.motohud.ble.ui.diagnostic.DiagnosticActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 关于页。
 *
 * 三块内容：
 *  1. 本机版本 + 检查更新（GitHub Releases 最新版与更新日志，可直接下载安装）；
 *  2. 车机信息 —— 骑行中最需要随时确认的几项：连接状态、设备名、协议、
 *     电压、水温、油量、总里程。数据来自 BLE 实时帧，未连接时显示 "--"；
 *  3. 开源仓库入口。
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var vm: MainViewModel
    private lateinit var app: MotoHudApp

    private lateinit var cardUpdate: View
    private lateinit var tvUpdateTitle: TextView
    private lateinit var tvUpdateLog: TextView
    private lateinit var btnDownload: TextView
    private lateinit var pbDownload: ProgressBar
    private lateinit var btnCheck: TextView

    private var latest: UpdateChecker.ReleaseInfo? = null
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        app = application as MotoHudApp
        vm = ViewModelProvider(this, MainViewModel.Factory(app))[MainViewModel::class.java]

        val topBar = findViewById<View>(R.id.about_top_bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(8))
            insets
        }

        cardUpdate = findViewById(R.id.card_update)
        tvUpdateTitle = findViewById(R.id.tv_update_title)
        tvUpdateLog = findViewById(R.id.tv_update_log)
        btnDownload = findViewById(R.id.btn_download)
        pbDownload = findViewById(R.id.pb_download)
        btnCheck = findViewById(R.id.btn_check_update)

        findViewById<TextView>(R.id.tv_version).text = getString(
            R.string.about_version
        ) + " " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"

        findViewById<View>(R.id.about_btn_back).setOnClickListener { finish() }
        btnCheck.setOnClickListener { checkUpdate() }
        btnDownload.setOnClickListener { downloadAndInstall() }
        findViewById<TextView>(R.id.tv_repo).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.REPO_URL)))
            }
        }
        // 诊断页隐藏入口：连点版本号 5 次。
        // 从设置菜单移出来——普通用户不需要它，但排查 BLE 问题时必须够得着。
        findViewById<View>(R.id.tv_version).setOnClickListener { onVersionTap() }

        observeDevice()
        // 进页面自动检查一次（静默，不打扰）
        checkUpdate(silent = true)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- 诊断页隐藏入口 ----------

    private var versionTaps = 0
    private var lastTapAt = 0L

    private fun onVersionTap() {
        val now = System.currentTimeMillis()
        // 两次点击间隔超过 1.5s 就重新计数，避免误触累积
        versionTaps = if (now - lastTapAt > 1500L) 1 else versionTaps + 1
        lastTapAt = now
        val left = 5 - versionTaps
        when {
            left > 2 -> Unit
            left in 1..2 -> Toast.makeText(
                this, getString(R.string.about_debug_hint, left), Toast.LENGTH_SHORT
            ).show()
            else -> {
                versionTaps = 0
                startActivity(Intent(this, DiagnosticActivity::class.java))
            }
        }
    }

    // ---------- 版本更新 ----------

    private fun checkUpdate(silent: Boolean = false) {
        if (downloading) return
        btnCheck.text = getString(R.string.about_checking)
        btnCheck.isEnabled = false
        lifecycleScope.launch {
            val info = UpdateChecker.fetchLatest()
            btnCheck.text = getString(R.string.about_check_update)
            btnCheck.isEnabled = true

            if (info == null) {
                if (!silent) {
                    Toast.makeText(this@AboutActivity, R.string.about_check_failed, Toast.LENGTH_SHORT)
                        .show()
                }
                return@launch
            }
            latest = info
            if (!UpdateChecker.isNewer(info.version, BuildConfig.VERSION_NAME)) {
                cardUpdate.visibility = View.GONE
                if (!silent) {
                    Toast.makeText(this@AboutActivity, R.string.about_up_to_date, Toast.LENGTH_SHORT)
                        .show()
                }
                return@launch
            }
            cardUpdate.visibility = View.VISIBLE
            tvUpdateTitle.text = getString(R.string.about_new_version, info.version)
            tvUpdateLog.text = info.changelog.ifBlank { getString(R.string.about_no_release) }
            btnDownload.visibility = if (info.apkUrl != null) View.VISIBLE else View.GONE
        }
    }

    private fun downloadAndInstall() {
        val info = latest ?: return
        val url = info.apkUrl ?: return
        if (downloading) return

        // Android 8+ 需要「安装未知应用」权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.about_install_permission, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }

        downloading = true
        pbDownload.visibility = View.VISIBLE
        pbDownload.progress = 0
        btnDownload.isEnabled = false
        btnDownload.text = getString(R.string.about_downloading, 0)

        lifecycleScope.launch {
            val apk = withContext(Dispatchers.IO) { downloadApk(url, info.version) }
            downloading = false
            pbDownload.visibility = View.GONE
            btnDownload.isEnabled = true
            btnDownload.setText(R.string.about_download)

            if (apk == null || !apk.exists()) {
                Toast.makeText(this@AboutActivity, R.string.about_download_failed, Toast.LENGTH_LONG)
                    .show()
                return@launch
            }
            installApk(apk)
        }
    }

    /** 下载到 cache/updates，返回文件；进度回调回主线程 */
    private fun downloadApk(url: String, version: String): File? {
        return runCatching {
            val dir = File(cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "motohud-$version.apk")
            if (out.exists() && out.length() > 1024 * 1024) return out

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MotoHud-App")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        var lastPct = -1
                        while (input.read(buf).also { read = it } > 0) {
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    runOnUiThread {
                                        pbDownload.progress = pct
                                        btnDownload.text =
                                            getString(R.string.about_downloading, pct)
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            out.takeIf { it.exists() && it.length() > 0 }
        }.getOrNull()
    }

    private fun installApk(apk: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", apk
            )
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            Toast.makeText(this, R.string.about_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 车机信息 ----------

    private fun observeDevice() {
        val tvState = findViewById<TextView>(R.id.tv_dev_state)
        val tvName = findViewById<TextView>(R.id.tv_dev_name)
        val tvProtocol = findViewById<TextView>(R.id.tv_dev_protocol)
        val tvBattery = findViewById<TextView>(R.id.tv_dev_battery)
        val tvWater = findViewById<TextView>(R.id.tv_dev_water)
        val tvOil = findViewById<TextView>(R.id.tv_dev_oil)
        val tvMileage = findViewById<TextView>(R.id.tv_dev_mileage)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.bleManager.state.collect { st ->
                    val connected = st == BleConnectionManager.State.CONNECTED
                    tvState.setText(
                        if (connected) R.string.about_connected else R.string.about_disconnected
                    )
                    tvName.text = app.bleManager.connectedDeviceName
                        ?: getString(R.string.about_value_unknown)
                    tvProtocol.text = if (connected) {
                        protocolOf(app.bleManager.connectedDeviceName)
                    } else {
                        getString(R.string.about_value_unknown)
                    }
                }
            }
        }

        vm.telemetry.observe(this) { t ->
            tvBattery.text = t.battery?.let { String.format(Locale.US, "%.1f V", it) }
                ?: getString(R.string.about_value_unknown)
            tvWater.text = when (t.waterTemp) {
                null -> getString(R.string.about_value_unknown)
                0 -> "<45℃"
                else -> "${t.waterTemp}℃"
            }
            tvOil.text = t.oil?.let { "$it%" } ?: getString(R.string.about_value_unknown)
            tvMileage.text = t.totalMileage?.let { "$it km" }
                ?: getString(R.string.about_value_unknown)
        }
    }

    /** 由设备名推断通信协议族（与原车机 App 的两套协议一致） */
    private fun protocolOf(deviceName: String?): String {
        return when (BleConstants.deviceTypeOf(deviceName)) {
            BleConstants.DeviceType.SR -> "SR 系列（REM / NEXT / NAVI）"
            BleConstants.DeviceType.BYQ -> "BYQ 系列（NA / RE / NE）"
            else -> "通用协议"
        }
    }
}
