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

    // ---------- 体检（CMD_A 开关 + CMD_E/F 故障） ----------
    data class SwitchItem(val name: String, val on: Boolean)
    data class Examination(
        val switches: List<SwitchItem> = emptyList(),
        val ecuFault: FaultRepository.FaultItem? = null,
        val obdFault: FaultRepository.FaultItem? = null
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
                speed = frame.speed,
                rpm = frame.rotatingSpeed,
                fuel = frame.instantaneousFuel
            )

            is BleProtocol.Frame.CmdC -> {
                _telemetry.value = _telemetry.value.copy(
                    waterTemp = frame.waterTemperature,
                    roomTemp = frame.roomTemperature,
                    oil = frame.oilQuantity,
                    battery = frame.batteryVoltage
                )
                // 同步给全局快照，导航历史结束时才能记下"当时的车况"
                com.motohud.ble.data.BikeSnapshot.waterTemp = frame.waterTemperature
                com.motohud.ble.data.BikeSnapshot.oil = frame.oilQuantity
                com.motohud.ble.data.BikeSnapshot.battery = frame.batteryVoltage
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
