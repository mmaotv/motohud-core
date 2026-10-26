package com.motohud.ble.ui.scan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.motohud.ble.R
import com.motohud.ble.data.device.DevicePrefs
import com.motohud.ble.ui.common.MotoDialog

/**
 * 连接车机（二级页）。
 *
 * 从底部页签降级而来：一个用户只有一台车机，连接页不该占页签，
 * 但"第一次连接 / 换车机 / 忘记设备"仍然需要它，所以保留为二级页。
 *
 * 页面结构：顶栏（返回 + 标题 + 说明）+ [ScanFragment]（原扫描逻辑整体复用）+ 忘记设备。
 */
class ScanActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val topBar = findViewById<View>(R.id.scan_top_bar)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(10))
            insets
        }

        findViewById<View>(R.id.scan_btn_back).setOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.scan_container, ScanFragment())
                .commit()
        }

        val forget = findViewById<android.widget.TextView>(R.id.scan_forget)
        renderForget(forget)
        forget.setOnClickListener {
            val saved = DevicePrefs.last(this) ?: return@setOnClickListener
            MotoDialog.builder(this)
                .setTitle(R.string.scan_forget_title)
                .setMessage(getString(R.string.scan_forget_message, saved.second.ifBlank { saved.first }))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.scan_forget_confirm) { _, _ ->
                    DevicePrefs.forget(this)
                    finish()
                }
                .show()
        }
    }

    private fun renderForget(v: View) {
        val saved = DevicePrefs.last(this)
        v.visibility = if (saved == null) View.GONE else View.VISIBLE
        if (saved != null) {
            (v as android.widget.TextView).text = getString(
                R.string.scan_forget_device_named, saved.second.ifBlank { saved.first }
            )
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, ScanActivity::class.java))
        }
    }
}
