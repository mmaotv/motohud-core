package com.motohud.ble.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.motohud.ble.R
import com.motohud.ble.data.BleDiagnostic
import java.util.UUID

/**
 * ELM327 蓝牙适配器（OBD 诊断头）客户端。
 *
 * ## 为什么单独一套 GATT 栈
 *
 * 和车机（BYQ 仪表）那条链路**不是**同一个外设，也不是同一种协议：
 * 车机走二进制帧 + 3719/1234 文本通道，OBD 头走标准 ELM327 文本 AT 指令。
 * 共用 [com.motohud.ble.ble.BleConnectionManager] 会把两套状态机缠在一起
 * （它有自动重连、导航帧队列、CCCD 探测等一堆和 OBD 无关的逻辑），
 * 所以这里另起一个独立的、只做「发一条 AT 指令、等一个 `>`」的瘦客户端。
 *
 * ## 协议事实（全部来自 YMOBD 反编译，见 `common/js/obdManager.js` / `bluetooth.js`）
 *
 *  - 只认 BLE：服务 `0000FFF0-...`，写特征与通知特征由特征属性决定，不写死 UUID。
 *  - 指令以 `\r` 结尾，回复以 `>` 结束 —— 这是原 App 唯一的分包依据
 *    （`bluetooth.js:386-410` 逐字节累积，遇到 0x3E 才算一条命令完成）。
 *  - 初始化顺序 `ATZ → ATE0 → ATL0 → ATH1 → ATSP0 → ATI → ATDP → ATRV → 0100 → 0120 → 0140`
 *    （`obdManager.js:170-180`）。原 App 还夹了 `AT+VERSION`、`AT+CRYPT` 两条
 *    YMOBD 私有指令（读设备型号、做正品校验），我们自己的适配器不需要，跳过。
 *  - 数据轮询队列见 [ObdPids.POLL_QUEUE]。
 *
 * ## 线程模型
 *
 * 所有 AT 指令串行：一次只允许一条在途（[writeGate]），收到 `>` 才发下一条。
 * ELM327 本身没有队列，连着写两条会把回复混在一起。
 */
class ObdClient(private val context: Context) {

    enum class Phase { IDLE, SCANNING, CONNECTING, INITIALIZING, READY, ERROR }

    data class Device(val name: String, val address: String, val rssi: Int)

    /** 实时数据。字段为 null 表示这台车不支持该 PID（不是没读到）。 */
    data class Live(
        val rpm: Int? = null,
        val speed: Int? = null,
        val coolant: Int? = null,
        val voltage: Float? = null,
        val intakeTemp: Int? = null,
        val maf: Double? = null,
        val load: Double? = null,
        val fuelLevel: Double? = null,
        val throttle: Double? = null,
        val runTime: Int? = null,
        val updatedAt: Long = 0L
    ) {
        val hasData: Boolean get() = updatedAt > 0L
    }

    data class Dtc(val code: String, val kind: Kind) {
        enum class Kind(val label: String) {
            STORED("已存"),
            PENDING("待定"),
            PERMANENT("永久")
        }
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    private val _dtcs = MutableStateFlow<List<Dtc>>(emptyList())
    val dtcs: StateFlow<List<Dtc>> = _dtcs.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 最后一次失败的详细原因，界面上要能一直看到（Toast 一闪就没了，用户没法反馈） */
    private val _detail = MutableStateFlow<String?>(null)
    val detail: StateFlow<String?> = _detail.asStateFlow()

    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName.asStateFlow()

    private val _connectedAddress = MutableStateFlow<String?>(null)
    val connectedAddress: StateFlow<String?> = _connectedAddress.asStateFlow()

    private val _adapterId = MutableStateFlow<String?>(null)
    val adapterId: StateFlow<String?> = _adapterId.asStateFlow()

    private val _battery = MutableStateFlow<Float?>(null)

    /** 适配器自己报的电压（ATRV），和 ECU 的 0142 不是一回事：前者是诊断头测的电瓶电压 */
    val battery: StateFlow<Float?> = _battery.asStateFlow()

    private val _supported = MutableStateFlow<Set<String>>(emptySet())
    val supported: StateFlow<Set<String>> = _supported.asStateFlow()

    // ---------- GATT ----------
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var mtuPayload = 20
    private var connectedAddr: String? = null
    private var connectedLabel: String? = null
    private var manualDisconnect = false
    private var reconnectTries = 0

    private val writeGate = Mutex()
    private var pending: CompletableDeferred<String>? = null
    private val rx = StringBuilder()

    private var pollJob: Job? = null
    private var proto = ObdProtocol.Protocol(can = true, headerChars = 3)
    private var consecutiveMiss = 0

    /** CCCD 写入回调与「无 CCCD 兜底」可能同时触发 runInit，握手只能跑一次 */
    private var initStarted = false

    // ---------- 扫描 ----------

    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rec = result.scanRecord
            val advertised = rec?.serviceUuids?.any { it.uuid.toString().lowercase().startsWith("0000fff0") } == true
            val name = runCatching {
                result.device.name ?: rec?.deviceName
            }.getOrNull()?.trim().orEmpty()
            if (!advertised && !looksLikeObd(name)) return
            val item = Device(name.ifBlank { result.device.address }, result.device.address, result.rssi)
            BleDiagnostic.log("OBD 发现 ${item.name} ${item.address} rssi=${item.rssi} adv=$advertised")
            val cur = _devices.value.toMutableList()
            val idx = cur.indexOfFirst { it.address == item.address }
            if (idx >= 0) cur[idx] = item else cur += item
            _devices.value = cur.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _message.value = context.getString(R.string.obd_err_scan_failed, errorCode)
            _phase.value = Phase.IDLE
        }
    }

    /**
     * 名称白名单来自原 App `bluetooth.js` 的 `b` 数组（YMOBD 自家 + 兼容的十几个型号），
     * 再加两个通用前缀。真正的判据仍是广播里的 FFF0 服务，名称只是兜底 ——
     * 有些便宜的 ELM327 克隆不做服务 UUID 广播，只报名字。
     */
    private fun looksLikeObd(name: String): Boolean {
        if (name.isBlank()) return false
        val u = name.uppercase()
        if (u in KNOWN_NAMES) return true
        return u.startsWith("OBD") || u.contains("ELM327") || u.startsWith("V-LINK") || u.startsWith("VEEPEAK")
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val a = adapter ?: run { _message.value = context.getString(R.string.obd_err_no_bt); return }
        if (!a.isEnabled) {
            _message.value = context.getString(R.string.obd_err_bt_off)
            return
        }
        if (scanning) return
        _devices.value = emptyList()
        _phase.value = Phase.SCANNING
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { a.bluetoothLeScanner?.startScan(null, settings, scanCallback) }
            .onFailure {
                scanning = false
                _phase.value = Phase.IDLE
                _message.value = context.getString(R.string.obd_err_scan_start, it.message.orEmpty())
            }
        scope.launch {
            delay(SCAN_MS)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_phase.value == Phase.SCANNING) _phase.value = Phase.IDLE
    }

    // ---------- 连接 ----------

    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String?) = connectInternal(address, name, userInitiated = true)

    /**
     * @param userInitiated 用户主动点连接=true（重置重试计数）；自动回连=false。
     *   这个区分必须留：`connect()` 里无条件 `reconnectTries = 0` 会让
     *   [RECONNECT_MAX] 永远达不到 —— 熄火后适配器断电，程序就一直重连下去，
     *   一晚上能把电瓶耗掉一格。
     */
    @SuppressLint("MissingPermission")
    private fun connectInternal(address: String, name: String?, userInitiated: Boolean) {
        val a = adapter ?: return
        if (!a.isEnabled) {
            _message.value = context.getString(R.string.obd_err_bt_off)
            return
        }
        stopScan()
        if (userInitiated) {
            manualDisconnect = false
            reconnectTries = 0
        }
        connectedAddr = address
        connectedLabel = name
        _connectedName.value = name
        _connectedAddress.value = address
        _phase.value = Phase.CONNECTING
        // 换设备前先把旧连接关干净：Android 允许同一外设多份 GATT 实例，
        // 不关的话上一份的 notify 还会往同一个 rx 缓冲里写字。
        closeGatt()
        initStarted = false
        runCatching {
            val dev = a.getRemoteDevice(address)
            gatt = dev.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.onFailure {
            _phase.value = Phase.ERROR
            _message.value = context.getString(R.string.obd_err_connect, it.message.orEmpty())
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        manualDisconnect = true
        pollJob?.cancel()
        pollJob = null
        scope.launch {
            runCatching { gatt?.disconnect() }
            delay(150)
            closeGatt()
        }
        _phase.value = Phase.IDLE
        _connectedName.value = null
        _connectedAddress.value = null
        _live.value = Live()
        _dtcs.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        notifyChar = null
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                gatt = g
                reconnectTries = 0
                g.discoverServices()
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                pollJob?.cancel()
                pollJob = null
                closeGatt()
                _connectedName.value = null
                _live.value = Live()
                if (manualDisconnect) {
                    _phase.value = Phase.IDLE
                    return
                }
                // 适配器被拔掉/点烟器断电很常见，自动回连几次；
                // 但别无限重试 —— 熄火后一直扫会白耗电。
                if (reconnectTries < RECONNECT_MAX) {
                    reconnectTries++
                    _phase.value = Phase.CONNECTING
                    _message.value = context.getString(R.string.obd_msg_reconnect, reconnectTries, RECONNECT_MAX)
                    val addr = connectedAddr
                    scope.launch {
                        delay(1200L * reconnectTries)
                        if (!manualDisconnect && addr != null) {
                            connectInternal(addr, connectedLabel, userInitiated = false)
                        }
                    }
                } else {
                    _phase.value = Phase.ERROR
                    _message.value = context.getString(R.string.obd_err_disconnected)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _phase.value = Phase.ERROR
                _message.value = context.getString(R.string.obd_err_service, status)
                return
            }
            val services = g.services ?: emptyList()
            BleDiagnostic.log(
                "OBD 服务 ${services.size} 个：" + services.joinToString {
                    it.uuid.toString().take(8) + "/" + it.characteristics.size
                }
            )
            // 原版只认 FFF0（扫描时也用 FFF0 过滤广播，bluetooth.js:53/496），
            // 但 HM-10 系兼容头用 FFE0。先按优先级找，实在没有就退化成
            // 「同时有可写+可通知特征的服务」——放宽不会误连，发数据前还有 AT 探活。
            var svc: BluetoothGattService? = null
            for (prefix in SERVICE_PREFIXES) {
                svc = services.firstOrNull { it.uuid.toString().lowercase().startsWith(prefix) }
                if (svc != null) break
            }
            if (svc == null) {
                svc = services.firstOrNull { s ->
                    s.characteristics.any { canWrite(it) } && s.characteristics.any { canNotify(it) }
                }
            }
            if (svc == null) {
                _phase.value = Phase.ERROR
                _message.value = context.getString(R.string.obd_err_not_obd)
                runCatching { g.disconnect() }
                return
            }
            val chars = svc.characteristics ?: emptyList()
            BleDiagnostic.log(
                "OBD 特征 " + chars.joinToString {
                    it.uuid.toString().take(8) + "/0x" + Integer.toHexString(it.properties)
                }
            )
            // 原版是「遍历全部特征、后面的覆盖前面的」（bluetooth.js:372-374），
            // 等价于优先靠后的写特征（FFF2）、靠后的通知特征（FFF1）。
            // 第一版取的是 firstOrNull，遇到 FFF1 同时标了 WRITE 的头就会往通知口写 → 永远没人应答。
            writeChar = chars.lastOrNull { canWrite(it) }
            notifyChar = chars.lastOrNull { canNotify(it) }
            if (writeChar == null) {
                _phase.value = Phase.ERROR
                _message.value = context.getString(R.string.obd_err_no_write)
                return
            }
            val n = notifyChar ?: writeChar!!
            notifyChar = n
            g.setCharacteristicNotification(n, true)
            val cccd = n.getDescriptor(CCCD)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            } else {
                // 少数克隆头没有 CCCD，只能直接开跑
                scope.launch { runInit() }
            }
        }

        @Suppress("DEPRECATION")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != CCCD) return
            BleDiagnostic.log("OBD 通知订阅完成 status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) scope.launch { runInit() }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val s = String(ch.value ?: ByteArray(0), Charsets.ISO_8859_1)
            onRx(s)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onRx(String(value, Charsets.ISO_8859_1))
        }
    }

    /**
     * 累计通知字节，遇到 `>` 才算一条命令说完。
     *
     * 这里**必须**按字节流拼接：ELM327 的回复经常被拆成 2~3 个通知包
     * （20 字节 MTU 下一条 `7E8 04 41 0C 1A F8` + 回车就已经 16 字节），
     * 每个包单独解析只会拿到半行 hex。
     */
    private fun onRx(s: String) {
        val d: CompletableDeferred<String>?
        synchronized(rx) {
            rx.append(s)
            if (!rx.contains(ObdProtocol.PROMPT)) return
            d = pending
        }
        if (d != null && d.isActive) {
            val text = synchronized(rx) { rx.toString() }
            d.complete(text)
        }
    }

    // ---------- 指令收发 ----------

    private suspend fun request(cmd: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        if (gatt == null) return@withContext null
        writeGate.withLock {
            val d = CompletableDeferred<String>()
            synchronized(rx) {
                rx.setLength(0)
                pending = d
            }
            try {
                writeRaw(cmd + "\r")
                withTimeoutOrNull(timeoutMs) { d.await() }
            } finally {
                synchronized(rx) {
                    if (pending === d) pending = null
                    rx.setLength(0)
                }
            }
        }
    }

    private fun canWrite(c: BluetoothGattCharacteristic): Boolean =
        (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0

    private fun canNotify(c: BluetoothGattCharacteristic): Boolean =
        (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0

    /**
     * 写一条指令。
     *
     * 两种写类型都试：只支持 WRITE_NO_RESPONSE 的克隆头（很常见）在
     * WRITE_TYPE_DEFAULT 下 `writeCharacteristic` 直接返回 false，
     * 不换类型就一直发不出去 —— 界面上就停在「正在初始化」。
     */
    @SuppressLint("MissingPermission")
    private fun writeRaw(text: String) {
        val g = gatt ?: return
        val ch = writeChar ?: return
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        var off = 0
        while (off < bytes.size) {
            val n = minOf(mtuPayload, bytes.size - off)
            val chunk = bytes.copyOfRange(off, off + n)
            var sent = false
            for (attempt in 0 until 4) {
                @Suppress("DEPRECATION")
                ch.value = chunk
                ch.writeType =
                    if (attempt % 2 == 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                sent = runCatching { g.writeCharacteristic(ch) }.getOrDefault(false)
                if (sent) break
                Thread.sleep(60)
            }
            if (!sent) BleDiagnostic.log("OBD 写入失败：$text（特征 ${ch.uuid}）")
            off += n
            if (off < bytes.size) Thread.sleep(12)
        }
    }

    // ---------- 初始化 ----------

    /**
     * 握手。**逐条对齐原版 `obdManager.js:170` 的行为**，三处细节缺一不可：
     *
     *  1. 订阅成功后固定等 1s 再发第一条（`bluetooth.js:577` 的 `setTimeout(...,1e3)`）。
     *     便宜克隆头在 CCCD 写完的瞬间还没准备好收包，第一包被吞掉的话，
     *     后面每条都等不到 `>`，表现就是「连上了但协议跑不起来」。
     *  2. **每条指令的等待上限是 20s**（`obdManager.js` 里 `setTimeout(...,2e4)`），
     *     不是几百毫秒。`ATSP0` 自动匹配协议、第一次 `0100` 都要好几秒，
     *     给 1~3s 会把正常适配器判成死的。
     *  3. `ATE0/ATL0/ATH1` 没回 `OK` 就重发（原版 `!f.includes("OK")` → `b--` 重跑同一条）。
     *     回显没关掉时，后续 hex 里混着命令本身，PID 定位会整体错位。
     */
    private suspend fun runInit() {
        if (initStarted) return
        initStarted = true
        _phase.value = Phase.INITIALIZING
        consecutiveMiss = 0
        try {
            delay(1000)

            // 探活：连 `AT` 都不回，说明这条 GATT 链路根本不通（被别的 App 占着、
            // 或者写特征选错了），没必要再白等五六个 20s 超时。
            if (request("AT", 5000) == null) {
                BleDiagnostic.log("OBD 探活失败：AT 无响应")
                failInit(R.string.obd_err_silent)
                return
            }

            // ATZ 会重启适配器；原版收到 STOPPED 会重发同一条
            for (i in 0 until 3) {
                val z = request("ATZ", 20000)
                BleDiagnostic.log("OBD ATZ[$i] ${preview(z)}")
                if (z != null && !z.uppercase().contains("STOPPED")) break
            }
            delay(300)

            mustOk("ATE0", 8000)   // 关回显：不回显才能用「找 41 xx」定位数据
            mustOk("ATL0", 8000)   // 关换行
            mustOk("ATH1", 8000)   // 开帧头：多 ECU 时才分得清谁答的

            val sp = initCmd("ATSP0", 20000) ?: initCmd("ATSP0", 20000)
            BleDiagnostic.log("OBD ATSP0 ${preview(sp)}")

            _adapterId.value = ObdProtocol.adapterId(initCmd("AT+VERSION", 8000).orEmpty())
                ?: ObdProtocol.adapterId(initCmd("ATI", 8000).orEmpty())
            _battery.value = ObdProtocol.battery(initCmd("ATRV", 8000).orEmpty())

            // 第一次问 PID：适配器这时可能还在 SEARCHING…，原版也是 20s 上限
            var raw0100 = initCmd("0100", 20000) ?: initCmd("0100", 20000)
            BleDiagnostic.log("OBD 0100 ${preview(raw0100)}")

            val dp = initCmd("ATDP", 12000).orEmpty()
            proto = ObdProtocol.protocolOf(dp)
            BleDiagnostic.log("OBD 协议 ${dp.replace('\r', ' ').trim()} -> $proto")

            val sup = LinkedHashSet<String>()
            val m0 = raw0100?.let { ObdProtocol.pidMask("0100", it) }
            if (m0 != null) {
                sup += ObdPids.supportedFrom(m0, 0x00)
                if (m0[3] and 0x01 != 0) {
                    initCmd("0120", 12000)?.let { r ->
                        ObdProtocol.pidMask("0120", r)?.let { m ->
                            sup += ObdPids.supportedFrom(m, 0x20)
                            if (m[3] and 0x01 != 0) {
                                initCmd("0140", 12000)?.let { r2 ->
                                    ObdProtocol.pidMask("0140", r2)?.let { sup += ObdPids.supportedFrom(it, 0x40) }
                                }
                            }
                        }
                    }
                }
            }
            _supported.value = sup
            BleDiagnostic.log("OBD 支持 ${sup.size} 个 PID")

            // 原版的判据是 pid_0100 != 全零（obdManager.js:426）。
            // 全零 = 适配器在线但车上没有 ECU 应答（点火没开最常见）。
            if (sup.isEmpty()) {
                BleDiagnostic.log("OBD 握手失败：0100 位图为空")
                failInit(R.string.obd_err_no_ecu)
                return
            }

            _detail.value = null
            _phase.value = Phase.READY
            startPoll()
        } catch (t: Throwable) {
            Log.w(tag, "init failed", t)
            BleDiagnostic.log("OBD 初始化异常 ${t.message}")
            _phase.value = Phase.ERROR
            _message.value = context.getString(R.string.obd_err_init, t.message.orEmpty())
            _detail.value = _message.value
        }
    }

    /** 失败统一收尾：断链，避免留下一个「连着但不可用」的僵尸 GATT */
    @SuppressLint("MissingPermission")
    private fun failInit(msgRes: Int) {
        _phase.value = Phase.ERROR
        _message.value = context.getString(msgRes)
        _detail.value = context.getString(msgRes)
        // 标成「手动断开」再断链：否则 onConnectionStateChange 会当成意外掉线，
        // 自动回连 3 次、每次重跑一遍 20s 超时的握手，用户要等好几分钟才看到失败。
        manualDisconnect = true
        runCatching { gatt?.disconnect() }
    }

    /** 发一条不要求 OK 的初始化指令，超时留痕 */
    private suspend fun initCmd(cmd: String, timeoutMs: Long): String? {
        val raw = request(cmd, timeoutMs)
        if (raw == null) BleDiagnostic.log("OBD $cmd 超时（${timeoutMs}ms）")
        return raw
    }

    /**
     * 要求回 `OK` 的指令（ATE0/ATL0/ATH1）。没回 OK 说明适配器没接受，
     * 必须重发 —— 回显没关掉的话，后面每条 hex 都混着命令本身。
     */
    private suspend fun mustOk(cmd: String, timeoutMs: Long): Boolean {
        repeat(3) { i ->
            val raw = request(cmd, timeoutMs)
            val ok = raw != null && raw.uppercase().contains("OK")
            BleDiagnostic.log("OBD $cmd[$i] ${preview(raw)}")
            if (ok) return true
            delay(150)
        }
        return false
    }

    private fun preview(raw: String?): String {
        if (raw == null) return "<超时>"
        return raw.replace("\r", "\\r").replace("\n", "\\n").take(60)
    }

    // ---------- 轮询 ----------

    private fun startPoll() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var i = 0
            while (isActive && _phase.value == Phase.READY) {
                val cmd = ObdPids.POLL_QUEUE[i % ObdPids.POLL_QUEUE.size]
                i++
                val raw = request(cmd, POLL_TIMEOUT_MS)
                if (raw == null) {
                    consecutiveMiss++
                    // 连续丢 6 条（约 2 秒没有任何回复）判定链路挂了，交给重连逻辑
                    if (consecutiveMiss >= 6) {
                        _message.value = context.getString(R.string.obd_err_no_response)
                        runCatching { gatt?.disconnect() }
                        break
                    }
                } else {
                    consecutiveMiss = 0
                    applyPid(cmd, raw)
                }
                delay(POLL_GAP_MS)
            }
        }
    }

    private fun applyPid(cmd: String, raw: String) {
        // ATRV 不是 PID，是适配器量的电瓶电压（原版首页队列里也有它）
        if (cmd == "ATRV") {
            val v = ObdProtocol.battery(raw) ?: return
            _battery.value = v
            // ECU 不报 0142 时（摩托很常见）用适配器测的电压兜底，否则界面上电压永远是空的
            val cur = _live.value
            if (cur.voltage == null) {
                _live.value = cur.copy(voltage = v, updatedAt = System.currentTimeMillis())
            }
            return
        }
        val def = ObdPids.byCmd(cmd) ?: return
        val data = ObdProtocol.pidData(cmd, raw, def.bytes) ?: return
        val v = def.decode(data)
        val cur = _live.value
        val now = System.currentTimeMillis()
        _live.value = when (cmd) {
            "010C" -> cur.copy(rpm = v.toInt(), updatedAt = now)
            "010D" -> cur.copy(speed = v.toInt(), updatedAt = now)
            "0105" -> cur.copy(coolant = v.toInt(), updatedAt = now)
            "0142" -> cur.copy(voltage = v.toFloat(), updatedAt = now)
            "010F" -> cur.copy(intakeTemp = v.toInt(), updatedAt = now)
            "0110" -> cur.copy(maf = v, updatedAt = now)
            "0104" -> cur.copy(load = v, updatedAt = now)
            "012F" -> cur.copy(fuelLevel = v, updatedAt = now)
            "0111" -> cur.copy(throttle = v, updatedAt = now)
            "011F" -> cur.copy(runTime = v.toInt(), updatedAt = now)
            else -> cur
        }
    }

    // ---------- 故障码 ----------

    /**
     * 读故障码：03 已存 / 07 待定 / 0A 永久。
     * 三条都要读 —— 摩托车 ECU 常把当前故障放在 07 而不是 03（原 App 也是三条一起发）。
     *
     * @return 读到的码；空列表表示「读成功但确实没有故障」
     */
    suspend fun readDtcs(): List<Dtc> = withContext(Dispatchers.IO) {
        if (_phase.value != Phase.READY) {
            _message.value = context.getString(R.string.obd_not_ready)
            return@withContext emptyList()
        }
        pollJob?.cancel()
        pollJob = null
        val out = LinkedHashSet<Dtc>()
        try {
            val plan = listOf(
                "03" to Dtc.Kind.STORED,
                "07" to Dtc.Kind.PENDING,
                "0A" to Dtc.Kind.PERMANENT
            )
            for ((cmd, kind) in plan) {
                val raw = request(cmd, 6000) ?: continue
                val codes = ObdProtocol.parseDtc(raw, proto.headerChars, proto.can, cmd)
                codes.forEach { out += Dtc(it, kind) }
            }
            _dtcs.value = out.toList()
        } finally {
            if (_phase.value == Phase.READY) startPoll()
        }
        out.toList()
    }

    /** 清故障码（模式 04）。清完必须重新读一遍 —— 永久码（0A）擦不掉。 */
    suspend fun clearDtcs(): Boolean = withContext(Dispatchers.IO) {
        pollJob?.cancel()
        pollJob = null
        var ok = false
        try {
            val raw = request("04", 6000)
            ok = raw != null && ObdProtocol.hexOnly(raw).contains("44") &&
                !ObdProtocol.isNegative(raw, "04")
            if (ok) _dtcs.value = emptyList()
        } finally {
            if (_phase.value == Phase.READY) startPoll()
        }
        ok
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        private const val tag = "ObdClient"
        /** 服务 UUID 前缀，按优先级。原版只认 FFF0，FFE0 是 HM-10 系兼容头的常见形态 */
        private val SERVICE_PREFIXES = listOf("0000fff0", "0000ffe0", "0000ffd0")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_MS = 12_000L
        private const val POLL_TIMEOUT_MS = 2500L
        private const val POLL_GAP_MS = 20L
        private const val RECONNECT_MAX = 3

        /** 原 App `bluetooth.js` 的设备名白名单 */
        private val KNOWN_NAMES = setOf(
            "OBDII", "MS310", "B25", "V500", "YM529", "YM329", "YM129", "YM819",
            "BT529", "P300", "BT15", "BT17", "OBD114", "OBD147",
            "BROM S10", "BROM S15", "BROM S20", "BT319", "BT369",
            "TPMS", "C15", "C35"
        )
    }
}
