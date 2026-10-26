package com.motohud.ble.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.motohud.ble.data.BleDiagnostic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE 连接管理器 —— 原 App FastBLE 栈（BleDealer + ScanMainActivity + BleManager）的
 * 原生 GATT 等价实现，零第三方依赖。
 *
 * 复刻行为：
 *  - 扫描 10s 超时，名称前缀 BYQ/SR 过滤（ScanMainActivity.java:208-313）
 *  - autoConnect 连接，服务发现后使能 00001234 通知（CCCD 00002902）
 *  - 掉线自动重连：10 次 × 500ms（ScanMainActivity.java:395-398 / BleManager setReConnectCount）
 *  - 写指令经 BleProtocol.encodeCommand() 编码
 *  - 通知数据 → hex → BleProtocol.parseFrame() → [events] SharedFlow
 */
class BleConnectionManager(private val context: Context) {

    sealed interface Event {
        data class ScanUpdate(val devices: List<ScanItem>) : Event
        data class ScanFinished(val devices: List<ScanItem>) : Event
        data class ConnectionState(val state: State) : Event
        /** CCCD 写入成功：0x1234 通知已打开，可以开始收发（原厂此时发 HELLO 握手） */
        data object NotifyReady : Event
        data class NotifyData(val hex: String, val frame: BleProtocol.Frame?) : Event
        data class WriteResult(val success: Boolean, val hex: String) : Event
        data class Error(val message: String) : Event
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    data class ScanItem(
        val name: String,
        val address: String,
        val rssi: Int,
        val deviceType: BleConstants.DeviceType,
        /** true = legacy ECU（860745020*），只有二进制遥测通道，没有 3719 文本写通道 */
        val isLegacy: Boolean = false
    )

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var reconnectAttempts = 0
    private var scanning = false
    private var notifyReadyEmitted = false

    /** Android BLE 一次只能挂一个写操作，原厂 FastBle 内部有队列，这里等价实现 */
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeBusy = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 记录 CCCD 写入属于哪条通道（A=文本/握手，B=纯遥测），onDescriptorWrite 回调里按顺序取回 */
    private val pendingCccdChannels = ArrayDeque<String>()

    /** GATT 探测：连上后逐个读取可读特征，看车机是否把数据放在 READ 特征里（而不是 notify） */
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var readBusy = false
    private var readProbed = false
    private val readWatchdog = Runnable {
        if (readBusy) {
            Log.w(TAG, "read watchdog fired")
            BleDiagnostic.log("读取超时，跳过")
            synchronized(readQueue) { readBusy = false }
            drainReadQueue()
        }
    }

    /** 写看门狗：NO_RESPONSE 回调偶发不触发时仍能推进队列，避免整条链路卡死 */
    private val writeWatchdog = Runnable {
        if (writeBusy) {
            Log.w(TAG, "write watchdog fired, releasing busy")
            writeBusy = false
            drainWriteQueue()
        }
    }

    val scanResults = mutableMapOf<String, ScanItem>()

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    val connectedDeviceName: String?
        get() = _connectedName
    private var _connectedName: String? = null

    /** 已记录过的扫描名，诊断日志里每个名字只记一次，避免刷屏 */
    private val seenScanNames = mutableSetOf<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { d ->
                val name = d.name ?: return
                // 诊断用：把附近所有出现的设备名记下来，便于确认车边到底有没有 ECU（860745020*）
                if (seenScanNames.add(name)) BleDiagnostic.log("扫描到设备: $name (${d.address})")

                val legacy = BleConstants.isLegacyDevice(name)
                // 放行两类：FastBLE 仪表(BYQ/SR) 与 legacy ECU(860745020*)
                if (!BleConstants.isTargetDevice(name) && !legacy) return
                scanResults[d.address] = ScanItem(
                    name = name,
                    address = d.address,
                    rssi = result.rssi,
                    deviceType = BleConstants.deviceTypeOf(name),
                    isLegacy = legacy
                )
                _events.tryEmit(Event.ScanUpdate(scanResults.values.toList()))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _events.tryEmit(Event.Error("扫描失败 code=$errorCode"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = State.DISCONNECTED
                _events.tryEmit(Event.ConnectionState(State.DISCONNECTED))
                scheduleReconnect()
                return
            }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    _state.value = State.CONNECTED
                    reconnectAttempts = 0
                    BleDiagnostic.setState("CONNECTED")
                    _events.tryEmit(Event.ConnectionState(State.CONNECTED))
                    g.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    _state.value = State.DISCONNECTED
                    notifyCharacteristic = null
                    BleDiagnostic.setState("DISCONNECTED")
                    _events.tryEmit(Event.ConnectionState(State.DISCONNECTED))
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                BleDiagnostic.log("服务发现失败 status=$status")
                _events.tryEmit(Event.Error("服务发现失败 status=$status"))
                return
            }
            // 通道 A（FastBLE 主栈，文本指令）：00003719 / 00001234
            val serviceA = g.getService(UUID.fromString(BleConstants.UUID_SERVICE))
            val chA = serviceA?.getCharacteristic(UUID.fromString(BleConstants.UUID_NOTIFY))

            // 通道 B（legacy 二进制遥测栈）：0000ff33 / 0000ff02
            // 原厂 CMD A~F 遥测（车速/转速/油耗/水温/油量/电压/里程/故障码）走这条；
            // 4.9.0 启动入口虽变死代码，但车机固件仍可能在此下发，必须也开通知，否则"读不到数据"。
            val chB = g.getService(UUID.fromString(BleConstants.UUID_SERVICE_LEGACY))
                ?.getCharacteristic(UUID.fromString(BleConstants.UUID_CHART_LEGACY))

            if (chA == null && chB == null) {
                BleDiagnostic.log("未找到通道 A 与通道 B")
                _events.tryEmit(
                    Event.Error(
                        "未找到 ${BleConstants.UUID_SERVICE} 或 ${BleConstants.UUID_SERVICE_LEGACY} 服务，该设备可能不是车机"
                    )
                )
                return
            }

            if (chA != null) {
                BleDiagnostic.log("服务发现成功，准备开启通知 (通道 A)")
                notifyCharacteristic = chA
                enableNotify(g, chA, "A")
            } else {
                // 纯 legacy ECU 设备：只有遥测、没有文本写通道，属正常情况
                BleDiagnostic.log("纯遥测设备：无通道 A(3719)，仅开通道 B")
            }

            if (chB != null) {
                BleDiagnostic.log("发现 legacy 遥测通道 0000ff02，开启通知 (通道 B)")
                enableNotify(g, chB, "B")
            } else {
                BleDiagnostic.log("未发现 legacy 遥测通道 0000ff02（部分车机仅用通道 A）")
            }

            // 诊断增强：把整张 GATT 表（服务/特征/属性）打到日志（纯本地枚举，不占 GATT 通道）。
            // 读取探测要等 CCCD 写完成后再做，否则会和开通知抢同一个 GATT 操作。
            dumpGatt(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == UUID.fromString(BleConstants.UUID_CCCD)) {
                val channel = synchronized(pendingCccdChannels) {
                    pendingCccdChannels.removeFirstOrNull()
                } ?: "A"
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (channel == "A") {
                        Log.d(TAG, "CCCD(A) written, notify ready")
                        BleDiagnostic.setNotifyReady(true)
                        emitNotifyReady()
                        // 通知就绪后再做"可读特征"探测，避免与 CCCD 写抢 GATT 操作
                        if (!readProbed) {
                            readProbed = true
                            gatt?.let { startReadAll(it) }
                        }
                    } else {
                        // 通道 B 只是遥测，不需要触发 HELLO
                        BleDiagnostic.log("CCCD(B) 已写入，遥测通道就绪")
                    }
                } else {
                    BleDiagnostic.log("打开通知失败 channel=$channel status=$status")
                    if (channel == "A") _events.tryEmit(Event.Error("打开通知失败 status=$status"))
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = ch.value ?: return
            val channel = if (ch.uuid == UUID.fromString(BleConstants.UUID_NOTIFY)) "A" else "B"
            emitNotify(value, channel)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val channel = if (ch.uuid == UUID.fromString(BleConstants.UUID_NOTIFY)) "A" else "B"
            emitNotify(value, channel)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            @Suppress("DEPRECATION") val v = ch.value
            handleRead(ch, status, v)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleRead(ch, status, value)
        }

        private fun handleRead(ch: BluetoothGattCharacteristic, status: Int, value: ByteArray?) {
            mainHandler.removeCallbacks(readWatchdog)
            if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.isNotEmpty()) {
                BleDiagnostic.log("读取 ${ch.uuid} = ${value.toHexString()} (${value.size}B)")
                Log.d(TAG, "read ${ch.uuid} = ${value.toHexString()}")
            } else {
                BleDiagnostic.log("读取 ${ch.uuid} 无数据 status=$status")
            }
            synchronized(readQueue) { readBusy = false }
            drainReadQueue()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            mainHandler.removeCallbacks(writeWatchdog)
            BleDiagnostic.onWriteResult(status == BluetoothGatt.GATT_SUCCESS, ch.value.toHexString())
            _events.tryEmit(Event.WriteResult(status == BluetoothGatt.GATT_SUCCESS, ch.value.toHexString()))
            writeBusy = false
            drainWriteQueue()
        }

        private fun emitNotify(value: ByteArray, channel: String) {
            val hex = value.toHexString()
            val frame = BleProtocol.parseFrame(hex)
            Log.d(TAG, "onNotify[$channel]: $hex")
            BleDiagnostic.onNotify(hex, channel)
            _events.tryEmit(Event.NotifyData(hex, frame))
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { String.format("%02X", it) }

    /** 通知就绪：CCCD 写成功后发出；带兜底定时器防止回调未触发时握手卡住 */
    private fun emitNotifyReady() {
        if (notifyReadyEmitted) return
        notifyReadyEmitted = true
        _events.tryEmit(Event.NotifyReady)
    }

    private fun scheduleNotifyReadyFallback() {
        mainHandler.removeCallbacks(notifyReadyFallback)
        mainHandler.postDelayed(notifyReadyFallback, 1500L)
    }

    private val notifyReadyFallback = Runnable {
        if (!notifyReadyEmitted) {
            Log.w(TAG, "CCCD callback missed, emitting NotifyReady by fallback")
            emitNotifyReady()
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter?.isEnabled != true) {
            _events.tryEmit(Event.Error("蓝牙未开启"))
            return
        }
        scanResults.clear()
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        _events.tryEmit(Event.ScanUpdate(emptyList()))
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _events.tryEmit(Event.ScanFinished(scanResults.values.toList()))
    }

    fun isScanning() = scanning

    @SuppressLint("MissingPermission")
    fun connect(address: String, fromReconnect: Boolean = false) {
        if (adapter?.isEnabled != true) {
            _events.tryEmit(Event.Error("蓝牙未开启"))
            return
        }
        stopScan()
        val attempts = reconnectAttempts
        disconnect()
        reconnectAttempts = if (fromReconnect) attempts else 0
        _state.value = State.CONNECTING
        _events.tryEmit(Event.ConnectionState(State.CONNECTING))
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        _connectedName = device.name
        BleDiagnostic.onNewConnection(device.name)
        BleDiagnostic.log("设备地址=${device.address} 名称=${device.name} 绑定状态=${device.bondState}")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, true, gattCallback)
        }
    }

    /** 把整张 GATT 表（服务/特征/属性/权限）写进诊断日志 */
    private fun dumpGatt(g: BluetoothGatt) {
        try {
            BleDiagnostic.log("=== GATT 服务列表 ===")
            g.services?.forEach { svc ->
                BleDiagnostic.log("服务 ${svc.uuid}")
                svc.characteristics?.forEach { ch ->
                    BleDiagnostic.log("  特征 ${ch.uuid} [${propsOf(ch)}] perms=0x${Integer.toHexString(ch.permissions)}")
                }
            }
            BleDiagnostic.log("=== GATT 结束 ===")
        } catch (e: Exception) {
            BleDiagnostic.log("GATT 枚举异常: ${e.message}")
        }
    }

    /** 特征属性 → 可读文字 */
    private fun propsOf(ch: BluetoothGattCharacteristic): String {
        val p = ch.properties
        val list = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) list += "READ"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) list += "WRITE"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) list += "WRITE_NR"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) list += "NOTIFY"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) list += "INDICATE"
        if (p and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) list += "BROADCAST"
        return if (list.isEmpty()) "NONE" else list.joinToString("|")
    }

    /** 收集所有"可读且不需加密"的特征，逐个读一次（只读探测，不改变车机状态） */
    @SuppressLint("MissingPermission")
    private fun startReadAll(g: BluetoothGatt) {
        try {
            val readable = g.services.orEmpty().flatMap { it.characteristics.orEmpty() }
                .filter {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 &&
                        it.permissions and BluetoothGattCharacteristic.PERMISSION_READ != 0 &&
                        it.permissions and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED == 0
                }
            if (readable.isEmpty()) {
                BleDiagnostic.log("无可直接读取的特征（数据只能靠 notify/indicate 推送）")
                return
            }
            BleDiagnostic.log("开始读取 ${readable.size} 个可读特征…")
            synchronized(readQueue) {
                readQueue.clear()
                readQueue.addAll(readable)
                readBusy = false
            }
            drainReadQueue()
        } catch (e: Exception) {
            BleDiagnostic.log("读取探测异常: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainReadQueue() {
        val g = gatt ?: return
        val ch = synchronized(readQueue) {
            if (readBusy || readQueue.isEmpty()) null else {
                readBusy = true
                readQueue.removeFirst()
            }
        } ?: return
        mainHandler.removeCallbacks(readWatchdog)
        mainHandler.postDelayed(readWatchdog, 1500L)
        val ok = try { g.readCharacteristic(ch) } catch (e: Exception) { false }
        if (!ok) {
            mainHandler.removeCallbacks(readWatchdog)
            synchronized(readQueue) { readBusy = false }
            mainHandler.postDelayed({ drainReadQueue() }, 120L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic, channel: String) {
        // 仅通道 A 需要触发"通知就绪 → 发 HELLO"；通道 B 是只读遥测，不重置该标志
        if (channel == "A") notifyReadyEmitted = false
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(UUID.fromString(BleConstants.UUID_CCCD))
        if (cccd == null) {
            if (channel == "A") _events.tryEmit(Event.Error("缺少 CCCD 描述符"))
            return
        }
        // 按特征属性选 CCCD 值：只有 NOTIFY 写 0x0001，只有 INDICATE 必须写 0x0002，
        // 否则车机收到"开通知"但不会真正推送（这是"连上了却收不到数据"的常见原因之一）。
        val props = ch.properties
        val useIndicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
            props and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
        cccd.value = if (useIndicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        BleDiagnostic.log("开启通道 $channel 通知: ${if (useIndicate) "INDICATE" else "NOTIFY"} (${propsOf(ch)})")
        if (channel == "A") scheduleNotifyReadyFallback()
        synchronized(pendingCccdChannels) { pendingCccdChannels.addLast(channel) }
        g.writeDescriptor(cccd)
    }

    /** 发送文本指令（自动经 0D..0D / 1E 封装，排队串行写出） */
    @SuppressLint("MissingPermission")
    fun sendCommand(text: String) {
        val ch = notifyCharacteristic?.let {
            gatt?.getService(UUID.fromString(BleConstants.UUID_SERVICE))
                ?.getCharacteristic(UUID.fromString(BleConstants.UUID_WRITE))
        }
        if (ch == null || _state.value != State.CONNECTED) {
            _events.tryEmit(Event.WriteResult(false, ""))
            BleDiagnostic.onWriteResult(false, "")
            return
        }
        val data = BleProtocol.encodeCommand(text)
        BleDiagnostic.onSend(text)
        synchronized(writeQueue) {
            writeQueue.addLast(data)
        }
        drainWriteQueue()
        Log.d(TAG, "sendCommand: $text -> ${data.toHexString()}")
    }

    private fun drainWriteQueue() {
        val ch = notifyCharacteristic?.let {
            gatt?.getService(UUID.fromString(BleConstants.UUID_SERVICE))
                ?.getCharacteristic(UUID.fromString(BleConstants.UUID_WRITE))
        }
        if (ch == null) {
            synchronized(writeQueue) { writeQueue.clear() }
            return
        }
        val data = synchronized(writeQueue) {
            if (writeBusy || writeQueue.isEmpty()) null else {
                writeBusy = true
                writeQueue.removeFirst()
            }
        } ?: return
        // 原厂 0x2345 是 WRITE NO RESPONSE（nRF 实测），按特征属性选类型
        ch.writeType = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        ch.value = data
        mainHandler.removeCallbacks(writeWatchdog)
        mainHandler.postDelayed(writeWatchdog, 2000L)
        @Suppress("DEPRECATION") val ok = gatt?.writeCharacteristic(ch) == true
        if (!ok) {
            mainHandler.removeCallbacks(writeWatchdog)
            writeBusy = false
            _events.tryEmit(Event.WriteResult(false, data.toHexString()))
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        reconnectAttempts = BleConstants.RECONNECT_COUNT + 1 // 手动断开不触发重连
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        notifyCharacteristic = null
        synchronized(writeQueue) { writeQueue.clear(); writeBusy = false }
        synchronized(pendingCccdChannels) { pendingCccdChannels.clear() }
        synchronized(readQueue) { readQueue.clear(); readBusy = false }
        readProbed = false
        mainHandler.removeCallbacks(readWatchdog)
        mainHandler.removeCallbacks(writeWatchdog)
        mainHandler.removeCallbacks(notifyReadyFallback)
        notifyReadyEmitted = false
        _connectedName = null
        _state.value = State.DISCONNECTED
        _events.tryEmit(Event.ConnectionState(State.DISCONNECTED))
    }

    /** 掉线自动重连：10 次 × 500ms（ScanMainActivity.java:395-398） */
    private fun scheduleReconnect() {
        val address = gatt?.device?.address ?: return
        if (reconnectAttempts >= BleConstants.RECONNECT_COUNT) return
        reconnectAttempts++
        _state.value = State.RECONNECTING
        _events.tryEmit(Event.ConnectionState(State.RECONNECTING))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            connect(address, fromReconnect = true)
        }, BleConstants.RECONNECT_DELAY_MS)
    }

    fun release() {
        reconnectAttempts = BleConstants.RECONNECT_COUNT + 1
        stopScan()
        disconnect()
    }

    companion object {
        private const val TAG = "BleConnMgr"
    }
}
