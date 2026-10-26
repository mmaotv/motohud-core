package com.motohud.ble.ui.vehicle

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.data.device.DevicePrefs
import com.motohud.ble.ui.scan.ScanActivity
import kotlinx.coroutines.launch

/**
 * 车况页（二级页）。
 *
 * ## 为什么不再显示水温 / 转速 / 油量
 *
 * 实测确认 **这台车机没有遥测上行通道**（BLE 侧上行帧恒为 0），
 * 仪表、体检、故障码这些数据一行都拿不到。以前这一页放着转速/水温/油量一排格子，
 * 结果永远是 "--"，看起来像 App 坏了 —— 比不放还糟。
 *
 * 所以这一页现在只显示**真实存在、且用户用得着**的东西：
 *  1. 现在连没连上、连的是哪台、连了多久；
 *  2. 这台车机能干什么、干不了什么（干不了的直接写原因，而不是留个空格子）。
 */
class VehicleActivity : AppCompatActivity() {

    private val ble get() = (application as MotoHudApp).bleManager

    private lateinit var dot: View
    private lateinit var stateText: TextView
    private lateinit var stateSub: TextView
    private lateinit var action: TextView
    private lateinit var deviceCard: LinearLayout
    private lateinit var fieldName: TextView
    private lateinit var fieldMac: TextView
    private lateinit var fieldSession: TextView
    private lateinit var fieldLast: TextView

    /** 连接成功后开始计时，用于「本次连接」 */
    private var connectedAt = 0L
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = Runnable { renderSession() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val topBar = findViewById<View>(R.id.veh_top_bar)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(8))
            insets
        }

        dot = findViewById(R.id.veh_dot)
        stateText = findViewById(R.id.veh_state)
        stateSub = findViewById(R.id.veh_state_sub)
        action = findViewById(R.id.veh_action)
        deviceCard = findViewById(R.id.veh_device_card)
        fieldName = findViewById(R.id.veh_field_name)
        fieldMac = findViewById(R.id.veh_field_mac)
        fieldSession = findViewById(R.id.veh_field_session)
        fieldLast = findViewById(R.id.veh_field_last)

        findViewById<View>(R.id.veh_btn_back).setOnClickListener { finish() }
        action.setOnClickListener { onAction() }
        // 「更换车机」与连接页是同一件事：重新扫描 + 选另一台
        findViewById<View>(R.id.veh_change).setOnClickListener { ScanActivity.start(this) }

        observeState()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ble.state.collect { st ->
                    render(st)
                    if (st == BleConnectionManager.State.CONNECTED) {
                        if (connectedAt == 0L) {
                            connectedAt = System.currentTimeMillis()
                            startTicker()
                        }
                    } else {
                        connectedAt = 0L
                        stopTicker()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DevicePrefs.lastConnectedAt.collect { renderLast(it) }
            }
        }
        render(ble.state.value)
    }

    private fun render(st: BleConnectionManager.State) {
        val connected = st == BleConnectionManager.State.CONNECTED
        when (st) {
            BleConnectionManager.State.CONNECTED -> {
                dot.setBackgroundResource(R.drawable.dot_green)
                stateText.setText(R.string.status_connected)
                stateSub.text = ble.connectedDeviceName ?: getString(R.string.vehicle_unknown_device)
                action.setText(R.string.device_card_disconnect)
            }
            BleConnectionManager.State.CONNECTING -> {
                dot.setBackgroundResource(R.drawable.dot_orange)
                stateText.setText(R.string.status_connecting)
                stateSub.setText(R.string.vehicle_state_connecting)
                action.setText(R.string.cancel)
            }
            BleConnectionManager.State.RECONNECTING -> {
                dot.setBackgroundResource(R.drawable.dot_orange)
                stateText.setText(R.string.status_reconnecting)
                stateSub.setText(R.string.vehicle_state_reconnecting)
                action.setText(R.string.cancel)
            }
            BleConnectionManager.State.DISCONNECTED -> {
                dot.setBackgroundResource(R.drawable.dot_gray)
                stateText.setText(R.string.status_disconnected)
                val saved = DevicePrefs.last(this)
                stateSub.text = if (saved != null) saved.second.ifBlank { saved.first }
                else getString(R.string.vehicle_no_device)
                action.setText(R.string.device_card_connect)
            }
        }
        deviceCard.visibility = if (connected) View.VISIBLE else View.GONE
        if (connected) {
            fieldName.text = ble.connectedDeviceName ?: getString(R.string.garage_value_empty)
            fieldMac.text = ble.connectedAddress ?: getString(R.string.garage_value_empty)
            renderSession()
        }
    }

    private fun startTicker() {
        ticker.removeCallbacks(tick)
        ticker.postDelayed(tick, 1_000L)
    }

    private fun stopTicker() {
        ticker.removeCallbacks(tick)
    }

    /** 「本次连接」时长，每秒走一次 */
    private fun renderSession() {
        if (connectedAt == 0L) return
        fieldSession.text = formatDuration(System.currentTimeMillis() - connectedAt)
        startTicker()
    }

    private fun renderLast(ts: Long) {
        fieldLast.text = if (ts <= 0L) {
            getString(R.string.device_card_never)
        } else {
            relativeTime(ts)
        }
    }

    private fun onAction() {
        when (ble.state.value) {
            BleConnectionManager.State.CONNECTED -> ble.disconnect()
            BleConnectionManager.State.CONNECTING,
            BleConnectionManager.State.RECONNECTING -> ble.disconnect()
            BleConnectionManager.State.DISCONNECTED -> {
                val saved = DevicePrefs.last(this)
                if (saved != null) {
                    ble.connectSavedOrScan(saved.first, saved.second) { ok ->
                        if (!ok) runOnUiThread { ScanActivity.start(this) }
                    }
                } else {
                    ScanActivity.start(this)
                }
            }
        }
    }

    private fun relativeTime(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        val min = diff / 60_000L
        return when {
            min < 1 -> getString(R.string.device_card_just_now)
            min < 60 -> getString(R.string.device_card_min_ago, min)
            min < 1440 -> getString(R.string.device_card_hour_ago, min / 60)
            else -> getString(R.string.device_card_day_ago, min / 1440)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stopTicker()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, VehicleActivity::class.java))
        }
    }
}
