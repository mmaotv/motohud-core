package com.motohud.ble.ble

/**
 * 车机 BLE 通信协议常量（按公开可见的车机广播与通知行为归纳）。
 *
 * 全部常量均从原 APK 反编译代码还原，证据见《逆向分析报告》第 3 章：
 *  - UUID:      BleDealer.java:13-15 / GlobalConstants.java:9,13
 *  - 设备名前缀: ScanMainActivity.java:253 / StringUtils.java:133
 *  - 故障码表:   model/BleCmdE.java:5 / model/BleCmdF.java:5
 */
object BleConstants {

    // ---------- GATT（FastBLE 栈，原 App 当前主用） ----------
    const val UUID_SERVICE = "00003719-0000-1000-8000-00805f9b34fb"
    const val UUID_NOTIFY = "00001234-0000-1000-8000-00805f9b34fb" // 设备 -> 手机
    const val UUID_WRITE = "00002345-0000-1000-8000-00805f9b34fb" // 手机 -> 设备
    const val UUID_CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    // ---------- GATT（legacy 原生栈，860745020 世代设备） ----------
    const val UUID_SERVICE_LEGACY = "0000ff33-0000-1000-8000-00805f9b34fb"
    const val UUID_CHART_LEGACY = "0000ff02-0000-1000-8000-00805f9b34fb"

    // ---------- 设备识别（原 App 两套规则） ----------
    /** FastBLE 栈：名称前缀 BYQ 或 SR（大小写不敏感） */
    val FASTBLE_PREFIXES = listOf("BYQ", "SR")

    /** legacy 栈：15 位名称，前缀 860745020，显示为 SR Max300_XXXXXX */
    const val LEGACY_PREFIX = "860745020"
    const val LEGACY_NAME_LENGTH = 15

    /** 连接参数（复刻 ScanMainActivity.java:95-97,209） */
    const val RECONNECT_COUNT = 10
    const val RECONNECT_DELAY_MS = 500L
    const val CONNECT_TIMEOUT_MS = 20_000L
    const val OPERATE_TIMEOUT_MS = 5_000L
    const val SCAN_TIMEOUT_MS = 10_000L

    // ---------- 指令号 ----------
    const val CMD_A = 1 // 开关位域
    const val CMD_B = 2 // 实时：速度/转速/油耗
    const val CMD_C = 3 // 水温/环境温度/油量/电瓶
    const val CMD_D = 4 // 里程：总里程/极速/均速
    const val CMD_E = 5 // ECU 故障码（14）
    const val CMD_F = 6 // OBD 故障码（49）

    /** 水温离散映射（BleCmdC.java:14-42） */
    val WATER_TEMP_MAP = mapOf(
        0 to 0, 1 to 45, 2 to 50, 3 to 60,
        4 to 70, 5 to 80, 6 to 90, 7 to 100, 8 to 110
    )

    /** CMD_A 开关位名称，bit7 -> bit0（BleCmdA.java:19-26） */
    val SWITCH_NAMES = listOf(
        "电机" to "switchMotor",
        "润滑" to "switchLubricating",
        "燃油" to "switchFuel",
        "ABS" to "switchABS",
        "水温" to "switchTemperature",
        "保养" to "switchMaintenance",
        "前" to "switchFront",
        "后" to "switchRear"
    )

    /** CMD_E：ECU 故障码表（BleCmdE.java:5） */
    val MALFUNCTION_CODE_E = arrayOf(
        "0x5055", "0x5019", "0x5017", "0x5013", "0x5018", "0x5014",
        "0x5053", "0x5052", "0x5035", "0x5043", "0x5045", "0x5042",
        "0x5044", "0x5025"
    )

    /** CMD_F：OBD 故障码表（BleCmdF.java:5） */
    val MALFUNCTION_CODE_F = arrayOf(
        "P0503", "C0083", "C0085", "P0105", "P0110", "P0115", "P0120",
        "P0130", "P0135", "P0150", "P0155", "P0201", "P0202", "P0217",
        "P0230", "P0335", "P0336", "P0351", "P0352", "P0410", "P0480",
        "P0500", "P0501", "P0505", "P0512", "P0560", "P0601", "P0604",
        "P0605", "P0606", "P0620_PIN2", "P0620_PIN31", "P0650", "P0655",
        "P0A0F", "P1300", "P1310", "P1536", "P1607", "P1800", "P2158",
        "P2600", "U0001", "U0002", "U0121", "U0122", "U0128", "U0140",
        "U0426", "U0486"
    )

    // ---------- 上行文本指令（NormalMotorFragment.java:1795-1827 / BaseNaviActivity.java:255） ----------
    /** 握手：HELLO :A:<手机品牌>: */
    fun cmdHello(brand: String) = "HELLO :A:${if (brand.isBlank()) "未知品牌" else brand}:"

    /** 通知/来电转发：MSG :<类型>:<计数>: */
    fun cmdMsg(type: String, count: Int) = "MSG :$type:$count:"

    // BYQ 设备指令集
    fun cmdNa(iconType: Int) = "NA :${pad2(iconType)}:"
    fun cmdRe(distance: Int) = "RE :$distance:"
    fun cmdNe(distance: Int) = "NE :$distance:"

    // SR 设备指令集
    fun cmdRem(distance: Int) = "REM :$distance::"
    fun cmdNext(distance: Int) = "NEXT :$distance::"
    fun cmdNavi(iconType: Int) = "NAVI :${pad2(iconType)}::"

    /** 到达终点（两套设备通用） */
    const val CMD_FINISH = "FINISH :::"

    /** 退出导航（BaseNaviActivity.java:264 onDestroy writeData("NO")） */
    const val CMD_NO = "NO"

    private fun pad2(v: Int) = if (v < 10) "0$v" else "$v"

    // ---------- 通知类型码表（TWNotifyService.java:122-152,237-241） ----------
    /** SR 设备码表 */
    const val TYPE_SR_WECHAT = "WECHAT"
    const val TYPE_SR_LINE = "LINE"
    const val TYPE_TYPE_SR_SMS = "SMS"
    const val TYPE_SR_CALL = "CALL"

    /** 其他（BYQ）设备码表 */
    const val TYPE_WA = "WA" // WhatsApp
    const val TYPE_IG = "IG" // Instagram
    const val TYPE_LI = "LI" // LINE
    const val TYPE_TE = "TE" // Telegram
    const val TYPE_WC = "WC" // 微信
    const val TYPE_SM = "SM" // 短信
    const val TYPE_MC = "MC" // 来电

    /** 监听的包名（TWNotifyService.java:26-34） */
    val WATCHED_PACKAGES = mapOf(
        "com.tencent.mm" to "微信",
        "com.whatsapp" to "WhatsApp",
        "com.instagram.android" to "Instagram",
        "jp.naver.line.android" to "LINE",
        "org.telegram.messenger" to "Telegram",
        "facebook" to "Facebook",
        "android.messaging" to "短信",
        "android.mms" to "短信"
    )

    /** 设备类型 */
    enum class DeviceType { SR, BYQ, UNKNOWN }

    /** 按名称判定设备类型（ScanMainActivity.java:253-259） */
    fun deviceTypeOf(name: String?): DeviceType {
        if (name.isNullOrEmpty()) return DeviceType.UNKNOWN
        return when {
            name.startsWith("SR", ignoreCase = true) -> DeviceType.SR
            name.startsWith("BYQ", ignoreCase = true) -> DeviceType.BYQ
            else -> DeviceType.UNKNOWN
        }
    }

    /** 是否为目标设备（FastBLE 栈过滤规则） */
    fun isTargetDevice(name: String?): Boolean =
        !name.isNullOrEmpty() && FASTBLE_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

    /**
     * 是否为 legacy ECU（二进制遥测）设备：15 位名称、前缀 860745020。
     * 原厂旧栈（BleService/BluetoothBLE）专门连这类设备，车速/转速/油耗等 CMD A~F 从它的
     * 0000ff33/0000ff02 下发；名称本身就是车机 IMEI，但**不需要事先知道 IMEI**，按前缀即可扫到。
     */
    fun isLegacyDevice(name: String?): Boolean =
        !name.isNullOrEmpty() && name.length == LEGACY_NAME_LENGTH && name.startsWith(LEGACY_PREFIX)

    /** 扫描列表要放行的设备：FastBLE 仪表(BYQ/SR) 或 legacy ECU(860745020*) */
    fun isAnyKnownDevice(name: String?): Boolean = isTargetDevice(name) || isLegacyDevice(name)

    /** legacy 栈 15 位 IMEI 式名称 → 显示名（StringUtils.java:133-138） */
    fun encapsulateLegacyName(name: String?): String? =
        if (name != null && name.length == LEGACY_NAME_LENGTH && name.startsWith(LEGACY_PREFIX))
            "SR Max300_" + name.substring(9, 15) else name
}
