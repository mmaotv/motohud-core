package com.motohud.ble.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.motohud.ble.MotoHudApp
import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.ble.BleConstants
import com.motohud.ble.ble.BleProtocol
import com.motohud.ble.data.FaultRepository
import com.motohud.ble.data.SearchEngine
import com.motohud.ble.obd.ObdClient
import com.motohud.ble.obd.ObdPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 全局共享 ViewModel：BLE 状态 + 遥测数据 + 搜索。
 */
class MainViewModel(app: MotoHudApp) : ViewModel() {

    val ble: BleConnectionManager = app.bleManager
    val searchEngine: SearchEngine = app.searchEngine

    /** OBD 诊断头（第二条 BLE 链路，见 [ObdClient]） */
    val obd: ObdClient = app.obdClient
    private val appCtx: android.content.Context = app

    // ---------- 扫描 ----------
    private val _scanDevices = MutableStateFlow<List<BleConnectionManager.ScanItem>>(emptyList())
    val scanDevices = _scanDevices.asLiveData(timeoutInMs = 500L)

    // ---------- 连接状态 ----------
    val connectionState = ble.state.asLiveData(timeoutInMs = 500L)

    // ---------- 实时遥测（CMD_B/C/D） ----------
    data class Telemetry(
        val speed: Int? = null,
        val rpm: Int? = null,
        val fuel: Double? = null,          // 瞬时油耗
        val waterTemp: Int? = null,        // 0 表示 <45
        val roomTemp: String? = null,
        val oil: Int? = null,              // %
        val battery: Float? = null,        // V
        val totalMileage: Int? = null,
        val maxSpeed: Int? = null,
        val avgSpeed: Int? = null
    )

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry = _telemetry.asLiveData(timeoutInMs = 500L)

    // ---------- 体检（CMD_A 开关 + CMD_E/F 故障 + OBD 实码） ----------
    data class SwitchItem(val name: String, val on: Boolean)
    data class Examination(
        val switches: List<SwitchItem> = emptyList(),
        val ecuFault: FaultRepository.FaultItem? = null,
        val obdFault: FaultRepository.FaultItem? = null,
        /** OBD 实读故障码（模式 03/07/0A）。车机侧 CMD_F 只有 49 个索引位，永远读不到真码。 */
        val dtcs: List<FaultRepository.FaultItem> = emptyList(),
        /** OBD 是否读到过（区分"没读"和"读了没故障"） */
        val dtcScanned: Boolean = false
    )

    private val _examination = MutableStateFlow(Examination())
    val examination = _examination.asLiveData(timeoutInMs = 500L)

    // ---------- 消息提示 ----------
    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asLiveData(timeoutInMs = 500L)

    init {
        viewModelScope.launch {
            ble.events.collect { event ->
                when (event) {
                    is BleConnectionManager.Event.ScanUpdate ->
                        _scanDevices.value = event.devices.sortedByDescending { it.rssi }

                    is BleConnectionManager.Event.ConnectionState -> {
                        if (event.state == BleConnectionManager.State.DISCONNECTED) {
                            _telemetry.value = Telemetry()
                            _examination.value = Examination()
                            // 断开后旧读数不应被记进下一条导航历史
                            com.motohud.ble.data.BikeSnapshot.clear()
                        }
                        if (event.state == BleConnectionManager.State.CONNECTED) {
                            com.motohud.ble.data.BikeSnapshot.deviceName = ble.connectedDeviceName
                            // 连上之后才记存档：用户点错设备不会被当成"我的车"
                            val addr = ble.connectedAddress
                            if (!addr.isNullOrBlank()) {
                                com.motohud.ble.data.device.DevicePrefs.save(
                                    app, addr, ble.connectedDeviceName
                                )
                            }
                        }
                    }

                    // 原厂在 CCCD 写入成功（0x1234 通知打开）后才发 HELLO 握手，
                    // 提前发会因写特征未就绪而丢失（NormalMotorFragment.initBleController）
                    is BleConnectionManager.Event.NotifyReady -> sendHello()

                    is BleConnectionManager.Event.NotifyData -> handleFrame(event.frame)

                    is BleConnectionManager.Event.WriteResult ->
                        _toast.value = if (event.success) "指令已发送" else "指令发送失败"

                    is BleConnectionManager.Event.Error -> _toast.value = event.message
                    else -> Unit
                }
            }
        }

        // OBD 实时数据 → 并入同一份 Telemetry。
        // 车机（BYQ）上行帧实测恒为 0，转速/水温/电压只有 OBD 这条链路才有源。
        viewModelScope.launch {
            obd.live.collect { live -> if (live.hasData) mergeObd(live) }
        }
        viewModelScope.launch {
            obd.dtcs.collect { list ->
                _examination.value = _examination.value.copy(
                    dtcs = list.map { toFaultItem(it) },
                    dtcScanned = _examination.value.dtcScanned || list.isNotEmpty()
                )
            }
        }
        viewModelScope.launch {
            obd.message.collect { msg ->
                if (msg != null) {
                    _toast.value = msg
                    obd.consumeMessage()
                }
            }
        }
    }

    private fun handleFrame(frame: BleProtocol.Frame?) {
        when (frame) {
            is BleProtocol.Frame.CmdA -> {
                val switches = BleConstants.SWITCH_NAMES.mapIndexed { i, (cn) ->
                    SwitchItem(cn, frame.switches.getOrNull(i) == 1)
                }
                _examination.value = _examination.value.copy(switches = switches)
            }

            is BleProtocol.Frame.CmdB -> _telemetry.value = _telemetry.value.copy(
                speed = if (obdFresh()) _telemetry.value.speed else frame.speed,
                rpm = if (obdFresh()) _telemetry.value.rpm else frame.rotatingSpeed,
                fuel = frame.instantaneousFuel
            )

            is BleProtocol.Frame.CmdC -> {
                // 水温/电压同理：OBD 有新鲜数据时不让车机的 0 覆盖掉
                val fresh = obdFresh()
                _telemetry.value = _telemetry.value.copy(
                    waterTemp = if (fresh) _telemetry.value.waterTemp else frame.waterTemperature,
                    roomTemp = frame.roomTemperature,
                    oil = if (fresh) _telemetry.value.oil else frame.oilQuantity,
                    battery = if (fresh) _telemetry.value.battery else frame.batteryVoltage
                )
                // 同步给全局快照，导航历史结束时才能记下"当时的车况"
                if (!fresh) {
                    com.motohud.ble.data.BikeSnapshot.waterTemp = frame.waterTemperature
                    com.motohud.ble.data.BikeSnapshot.oil = frame.oilQuantity
                    com.motohud.ble.data.BikeSnapshot.battery = frame.batteryVoltage
                }
            }

            is BleProtocol.Frame.CmdD -> {
                _telemetry.value = _telemetry.value.copy(
                    totalMileage = frame.totalMileage,
                    maxSpeed = frame.maxSpeed,
                    avgSpeed = frame.averageSpeed
                )
                com.motohud.ble.data.BikeSnapshot.mileage = frame.totalMileage
            }

            is BleProtocol.Frame.CmdE -> {
                val fault = FaultRepository.byIndex(BleConstants.CMD_E, frame.malfunctionIndex)
                _examination.value = _examination.value.copy(ecuFault = fault)
            }

            is BleProtocol.Frame.CmdF -> {
                val fault = FaultRepository.byIndex(BleConstants.CMD_F, frame.malfunctionIndex)
                _examination.value = _examination.value.copy(obdFault = fault)
            }

            else -> Unit
        }
    }

    // ---------- 指令 ----------

    /** 握手（BaseNaviActivity.java:255 / NormalMotorFragment.java:1850） */
    fun sendHello() {
        com.motohud.ble.data.BleDiagnostic.markHelloSent()
        ble.sendCommand(BleConstants.cmdHello(android.os.Build.BRAND))
    }

    /** 导航指令：按设备类型选码表（NormalMotorFragment.java:1795-1827） */
    fun sendNavigation(iconType: Int, pathRemain: Int, stepRemain: Int) {
        val type = BleConstants.deviceTypeOf(ble.connectedDeviceName)
        when (type) {
            BleConstants.DeviceType.SR -> {
                ble.sendCommand(BleConstants.cmdRem(pathRemain))
                ble.sendCommand(BleConstants.cmdNext(stepRemain))
                ble.sendCommand(BleConstants.cmdNavi(iconType))
            }

            BleConstants.DeviceType.BYQ -> {
                ble.sendCommand(BleConstants.cmdNa(iconType))
                ble.sendCommand(BleConstants.cmdRe(pathRemain))
                ble.sendCommand(BleConstants.cmdNe(stepRemain))
            }

            BleConstants.DeviceType.UNKNOWN -> {
                // 未知设备两套都发（原 App 对 byq 大小写兼容的扩展）
                ble.sendCommand(BleConstants.cmdNa(iconType))
                ble.sendCommand(BleConstants.cmdRe(pathRemain))
                ble.sendCommand(BleConstants.cmdNe(stepRemain))
                ble.sendCommand(BleConstants.cmdRem(pathRemain))
                ble.sendCommand(BleConstants.cmdNext(stepRemain))
                ble.sendCommand(BleConstants.cmdNavi(iconType))
            }
        }
    }

    fun sendFinish() = ble.sendCommand(BleConstants.CMD_FINISH)

    /** 通知转发（TWNotifyService 调用） */
    fun sendMsg(type: String, count: Int) = ble.sendCommand(BleConstants.cmdMsg(type, count))

    fun sendRaw(text: String) = ble.sendCommand(text)

    fun consumeToast() {
        _toast.value = null
    }

    // ---------- OBD ----------

    /** OBD 最近 6 秒内有数据 → 认为它是当前有效数据源，车机的 0 不再覆盖它 */
    private fun obdFresh(): Boolean {
        val l = obd.live.value
        return l.hasData && System.currentTimeMillis() - l.updatedAt < 6_000L
    }

    private fun mergeObd(l: ObdClient.Live) {
        val t = _telemetry.value
        _telemetry.value = t.copy(
            speed = l.speed ?: t.speed,
            rpm = l.rpm ?: t.rpm,
            waterTemp = l.coolant ?: t.waterTemp,
            battery = l.voltage ?: t.battery,
            oil = l.fuelLevel?.toInt() ?: t.oil
        )
        l.coolant?.let { com.motohud.ble.data.BikeSnapshot.waterTemp = it }
        l.fuelLevel?.let { com.motohud.ble.data.BikeSnapshot.oil = it.toInt() }
        l.voltage?.let { com.motohud.ble.data.BikeSnapshot.battery = it }
    }

    /**
     * OBD 码 → 本地知识库。
     *
     * 命中 [FaultRepository] 的 49 个标准码就用现成中文说明；
     * 没命中（摩托车 ECU 的厂商自定义码很常见，P1xxx 尤其多）也照原样显示码值，
     * 不能因为"不认识"就吞掉 —— 用户拿去 4S 店就是要这串字符。
     */
    private fun toFaultItem(d: ObdClient.Dtc): FaultRepository.FaultItem {
        val known = FaultRepository.byCode(d.code)
        val table = "OBD·${d.kind.label}"
        return known?.copy(table = table) ?: FaultRepository.FaultItem(
            code = d.code,
            table = table,
            name = "标准 OBD 故障码（未收录具体说明）",
            cause = "该码不在本地 49 条常用码表内，多为车型自定义或子系统专用码",
            suggestion = "记录码值后按车型查询维修手册"
        )
    }

    fun connectObd(address: String, name: String?) {
        obd.connect(address, name)
        ObdPrefs.setEnabled(appCtx, true)
    }

    fun disconnectObd() {
        obd.disconnect()
        ObdPrefs.setEnabled(appCtx, false)
    }

    /** 读码：三条模式（03/07/0A）一起读，读完标记 dtcScanned，界面才能区分"没故障"和"没读过" */
    fun readObdDtcs() {
        viewModelScope.launch {
            val list = obd.readDtcs()
            _examination.value = _examination.value.copy(
                dtcs = list.map { toFaultItem(it) },
                dtcScanned = true
            )
            _toast.value = if (list.isEmpty()) "读取完成：没有故障码" else "读取到 ${list.size} 个故障码"
        }
    }

    fun clearObdDtcs() {
        viewModelScope.launch {
            val ok = obd.clearDtcs()
            _toast.value = if (ok) "故障码已清除" else "清除失败"
            if (ok) _examination.value = _examination.value.copy(dtcs = emptyList(), dtcScanned = true)
        }
    }

    // ---------- 生命周期 ----------

    override fun onCleared() {
        ble.release()
        super.onCleared()
    }

    class Factory(private val app: MotoHudApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
    }
}
