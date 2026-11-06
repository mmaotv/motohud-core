package com.motohud.ble.ui.vehicle

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import com.motohud.ble.obd.ObdClient
import com.motohud.ble.obd.ObdPrefs
import com.motohud.ble.ui.common.MotoDialog
import com.motohud.ble.ui.scan.ScanActivity
import com.motohud.ble.ui.theme.AppTheme
import kotlinx.coroutines.delay
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
    private val obd get() = (application as MotoHudApp).obdClient

    private lateinit var dot: View
    private lateinit var stateText: TextView
    private lateinit var stateSub: TextView
    private lateinit var action: TextView
    private lateinit var deviceCard: LinearLayout
    private lateinit var fieldName: TextView
    private lateinit var fieldMac: TextView
    private lateinit var fieldSession: TextView
    private lateinit var fieldLast: TextView

    // OBD 卡片
    private lateinit var obdDot: View
    private lateinit var obdState: TextView
    private lateinit var obdSub: TextView
    private lateinit var obdAction: TextView
    private lateinit var obdGrid: View
    private lateinit var obdRpm: TextView
    private lateinit var obdSpeed: TextView
    private lateinit var obdCoolant: TextView
    private lateinit var obdVoltage: TextView
    private lateinit var obdDtcRow: View
    private lateinit var obdDtcList: TextView

    /** 用户点过"读故障码"才算扫过，否则空列表分不清是"没故障"还是"没读过" */
    private var dtcScanned = false

    /** 连接成功后开始计时，用于「本次连接」 */
    private var connectedAt = 0L
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = Runnable { renderSession() }

    override fun onCreate(savedInstanceState: Bundle?) {
        /*
         * 日 / 夜主题必须在 super 之前定下来。
         * `?attr/mtXxx` 是在布局 inflate 那一刻从主题里取值的，晚一步设置
         * 只能影响之后新建的 View，已经铺好的界面一个字都不会变。
         */
        AppTheme.apply(this)
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

        bindObd()
        observeState()
        handleDebugIntent()
    }

    /**
     * debug 包专用：`DebugVehicleAlias` 带 extra 直接触发扫描/连接。
     *
     * 测试机 168 不接受 `adb shell input tap`（INJECT_EVENTS 被拒），
     * 想验证 ELM327 握手只能靠这层入口；release 包里没人会传这些 extra，
     * 传了也只是走正常的 connect 路径，没有额外权限。
     */
    private fun handleDebugIntent() {
        val i = intent ?: return
        when {
            !i.getStringExtra("obd_addr").isNullOrBlank() -> {
                obd.connect(i.getStringExtra("obd_addr")!!, i.getStringExtra("obd_name"))
            }
            i.getBooleanExtra("obd_scan", false) -> obd.startScan()
        }
    }

    private fun bindObd() {
        obdDot = findViewById(R.id.obd_dot)
        obdState = findViewById(R.id.obd_state)
        obdSub = findViewById(R.id.obd_sub)
        obdAction = findViewById(R.id.obd_action)
        obdGrid = findViewById(R.id.obd_grid)
        obdRpm = findViewById(R.id.obd_rpm)
        obdSpeed = findViewById(R.id.obd_speed)
        obdCoolant = findViewById(R.id.obd_coolant)
        obdVoltage = findViewById(R.id.obd_voltage)
        obdDtcRow = findViewById(R.id.obd_dtc_row)
        obdDtcList = findViewById(R.id.obd_dtc_list)

        obdAction.setOnClickListener { onObdAction() }
        findViewById<View>(R.id.obd_btn_read).setOnClickListener { readDtcs() }
        findViewById<View>(R.id.obd_btn_clear).setOnClickListener { confirmClearDtcs() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    obd.phase.collect { renderObd(it) }
                }
                launch {
                    obd.live.collect { renderObdLive(it) }
                }
                launch {
                    obd.dtcs.collect { renderDtcs() }
                }
                launch {
                    obd.connectedName.collect { obdSub.text = it ?: obdSub.text }
                }
            }
        }
        renderObd(obd.phase.value)
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

    // ---------- OBD ----------

    private fun renderObd(p: ObdClient.Phase) {
        val ready = p == ObdClient.Phase.READY
        when (p) {
            ObdClient.Phase.IDLE, ObdClient.Phase.ERROR -> {
                obdDot.setBackgroundResource(R.drawable.dot_gray)
                obdState.setText(
                    if (p == ObdClient.Phase.ERROR) R.string.obd_state_error else R.string.obd_state_off
                )
                // 失败原因要留在页面上：Toast 一闪而过，用户没法照着排查
                if (p == ObdClient.Phase.ERROR) {
                    obdSub.text = obd.detail.value ?: getString(R.string.obd_state_error)
                } else if (!obd.live.value.hasData) {
                    obdSub.setText(R.string.obd_state_off_sub)
                }
                obdAction.setText(R.string.obd_connect)
            }
            ObdClient.Phase.SCANNING -> {
                obdDot.setBackgroundResource(R.drawable.dot_orange)
                obdState.setText(R.string.obd_state_scanning)
                obdSub.setText(R.string.obd_state_scanning_sub)
                obdAction.setText(R.string.obd_cancel)
            }
            ObdClient.Phase.CONNECTING -> {
                obdDot.setBackgroundResource(R.drawable.dot_orange)
                obdState.setText(R.string.obd_state_connecting)
                obdAction.setText(R.string.obd_cancel)
            }
            ObdClient.Phase.INITIALIZING -> {
                obdDot.setBackgroundResource(R.drawable.dot_orange)
                obdState.setText(R.string.obd_state_init)
                obdSub.setText(R.string.obd_state_init_sub)
                obdAction.setText(R.string.obd_cancel)
            }
            ObdClient.Phase.READY -> {
                obdDot.setBackgroundResource(R.drawable.dot_green)
                obdState.setText(R.string.obd_state_ready)
                val name = obd.connectedName.value ?: getString(R.string.vehicle_unknown_device)
                val ver = obd.adapterId.value
                obdSub.text = if (ver.isNullOrBlank()) name else "$name · $ver"
                obdAction.setText(R.string.obd_disconnect)
                // 连上并初始化成功才记存档，点错设备不会被当成"我的诊断头"
                val addr = obd.connectedAddress.value
                if (!addr.isNullOrBlank()) ObdPrefs.save(this, addr, obd.connectedName.value)
            }
        }
        obdGrid.visibility = if (ready) View.VISIBLE else View.GONE
        obdDtcRow.visibility = if (ready) View.VISIBLE else View.GONE
        obdDtcList.visibility = if (ready && dtcScanned) View.VISIBLE else View.GONE
    }

    private fun renderObdLive(l: ObdClient.Live) {
        obdRpm.text = l.rpm?.toString() ?: getString(R.string.garage_value_empty)
        obdSpeed.text = l.speed?.toString() ?: getString(R.string.garage_value_empty)
        obdCoolant.text = l.coolant?.toString() ?: getString(R.string.garage_value_empty)
        obdVoltage.text = l.voltage?.let { String.format("%.1f", it) }
            ?: getString(R.string.garage_value_empty)
    }

    private fun renderDtcs() {
        val list = obd.dtcs.value
        if (!dtcScanned) return
        obdDtcList.visibility = View.VISIBLE
        obdDtcList.text = if (list.isEmpty()) {
            getString(R.string.obd_dtc_none)
        } else {
            list.joinToString("\n") { "${it.code}  ${it.kind.label}" }
        }
    }

    private fun onObdAction() {
        when (obd.phase.value) {
            ObdClient.Phase.READY,
            ObdClient.Phase.CONNECTING,
            ObdClient.Phase.INITIALIZING,
            ObdClient.Phase.SCANNING -> {
                obd.stopScan()
                obd.disconnect()
                dtcScanned = false
                renderObd(ObdClient.Phase.IDLE)
                renderObdLive(ObdClient.Live())
            }
            else -> showDevicePicker()
        }
    }

    /**
     * 先扫再选。
     *
     * 为什么不是"扫到就自动连"：手机上同时存在车机、耳机、手环好几个 BLE 设备，
     * 自动连错一次要等十几秒超时才恢复；让用户点一下，代价小得多。
     * 原 App 也是扫到后弹「是否连接该设备」。
     */
    private fun showDevicePicker() {
        obd.startScan()
        lifecycleScope.launch {
            val saved = ObdPrefs.saved.value
            // 有存档就只扫 2.5s（用户多半就是连它）；没有才等满 9s 找新设备。
            // 无限等会让「诊断头没插」变成一次十秒的干等。
            val deadline = System.currentTimeMillis() + if (saved == null) 9_000L else 2_500L
            var list: List<ObdClient.Device> = emptyList()
            while (System.currentTimeMillis() < deadline) {
                delay(500)
                list = obd.devices.value
                if (saved == null && list.isNotEmpty()) break
            }
            // 存档置顶：点火没开时诊断头不广播，但用户就是要连它
            val merged = ArrayList<ObdClient.Device>(list.size + 1)
            if (saved != null && list.none { it.address.equals(saved.first, true) }) {
                merged += ObdClient.Device(
                    saved.second.ifBlank { saved.first },
                    saved.first,
                    Int.MIN_VALUE
                )
            }
            merged += list
            if (merged.isEmpty()) {
                obd.stopScan()
                Toast.makeText(this@VehicleActivity, R.string.obd_no_device, Toast.LENGTH_SHORT).show()
                renderObd(ObdClient.Phase.IDLE)
                return@launch
            }
            val labels = merged.map { d ->
                val tag = if (d.rssi == Int.MIN_VALUE) " · ${getString(R.string.obd_last_used)}" else ""
                "${d.name}$tag\n${d.address}"
            }.toTypedArray()
            MotoDialog.builder(this@VehicleActivity)
                .setTitle(R.string.obd_pick_title)
                .setItems(labels) { _, i ->
                    obd.stopScan()
                    obd.connect(merged[i].address, merged[i].name)
                }
                .setNegativeButton(R.string.obd_cancel, null)
                .setOnDismissListener { obd.stopScan() }
                .show()
        }
    }

    private fun readDtcs() {
        lifecycleScope.launch {
            Toast.makeText(this@VehicleActivity, R.string.obd_reading, Toast.LENGTH_SHORT).show()
            obd.readDtcs()
            dtcScanned = true
            renderDtcs()
            renderObd(obd.phase.value)
        }
    }

    private fun confirmClearDtcs() {
        MotoDialog.builder(this)
            .setTitle(R.string.obd_clear_confirm_title)
            .setMessage(R.string.obd_clear_confirm_msg)
            .setPositiveButton(R.string.obd_clear_dtc) { _, _ ->
                lifecycleScope.launch {
                    val ok = obd.clearDtcs()
                    Toast.makeText(
                        this@VehicleActivity,
                        if (ok) R.string.obd_cleared else R.string.obd_clear_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                    renderDtcs()
                }
            }
            .setNegativeButton(R.string.obd_cancel, null)
            .show()
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
