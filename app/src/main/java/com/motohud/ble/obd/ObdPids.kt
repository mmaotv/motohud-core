package com.motohud.ble.obd

/**
 * 标准 OBD-II 模式 01 的 PID 定义表。
 *
 * 来源：YMOBD `common/js/pid.js`（反编译 app-service.js 里的 `d5bf` 模块）的
 * `pid / name / unit / effectiveLength / formula` 四列，逐条搬过来。
 * 公式本身是 SAE J1979 规定的，原 App 也只是照抄，这里保持同一套系数，
 * 保证「同一个车、同一个码，两个 App 显示的数字一样」。
 *
 * 只收 `dataFlow = true` 且我们界面/播报用得到的 PID：
 * 仪表类（转速/车速/水温/电压）、体检类（负荷/进气/油量）、故障相关（运行时间/里程）。
 * 氧传感器、催化剂温度这些一次性诊断项没进轮询队列，需要时直接按 [byCmd] 取定义再问。
 */
object ObdPids {

    /**
     * @param bytes 有效数据字节数（对应原 `effectiveLength`），解析时不够长就丢弃这一帧
     * @param decode 入参是数据字节（已按顺序取够 [bytes] 个），返回工程量
     */
    data class Pid(
        val cmd: String,
        val name: String,
        val unit: String,
        val bytes: Int,
        val min: Double,
        val max: Double,
        val decode: (IntArray) -> Double
    )

    private fun pid(
        cmd: String,
        name: String,
        unit: String,
        bytes: Int,
        min: Double,
        max: Double,
        decode: (IntArray) -> Double
    ) = Pid(cmd, name, unit, bytes, min, max, decode)

    val ALL: List<Pid> = listOf(
        pid("0104", "发动机负荷", "%", 1, 0.0, 100.0) { v -> round1(100.0 * v[0] / 255) },
        pid("0105", "冷却液温度", "℃", 1, -40.0, 215.0) { v -> (v[0] - 40).toDouble() },
        pid("0106", "短期燃油修正", "%", 1, -100.0, 99.2) { v -> round1(100.0 * (v[0] - 128) / 128) },
        pid("0107", "长期燃油修正", "%", 1, -100.0, 99.2) { v -> round1(100.0 * (v[0] - 128) / 128) },
        pid("010A", "燃油压力", "kPa", 1, 0.0, 765.0) { v -> round1(3.0 * v[0]) },
        pid("010B", "进气歧管压力", "kPa", 1, 0.0, 255.0) { v -> v[0].toDouble() },
        pid("010C", "发动机转速", "rpm", 2, 0.0, 16383.75) { v -> ((v[0] shl 8) + v[1]) / 4.0 },
        pid("010D", "车辆速度", "km/h", 1, 0.0, 255.0) { v -> v[0].toDouble() },
        pid("010E", "点火提前角", "°", 1, -64.0, 63.5) { v -> round1((v[0] - 128) / 2.0) },
        pid("010F", "进气温度", "℃", 1, -40.0, 215.0) { v -> (v[0] - 40).toDouble() },
        pid("0110", "空气流量", "g/s", 2, 0.0, 655.35) { v -> round2(((v[0] shl 8) + v[1]) / 100.0) },
        pid("0111", "节气门位置", "%", 1, 0.0, 100.0) { v -> round1(100.0 * v[0] / 255) },
        pid("0114", "氧传感器 1", "V", 2, 0.0, 1.275) { v -> round3(v[0] / 200.0) },
        pid("011F", "启动后运行时间", "s", 2, 0.0, 65535.0) { v -> ((v[0] shl 8) + v[1]).toDouble() },
        pid("0121", "故障灯亮后行驶", "km", 2, 0.0, 65535.0) { v -> ((v[0] shl 8) + v[1]).toDouble() },
        pid("012F", "剩余油量", "%", 1, 0.0, 100.0) { v -> round1(100.0 * v[0] / 255) },
        pid("0131", "清码后行驶", "km", 2, 0.0, 65535.0) { v -> ((v[0] shl 8) + v[1]).toDouble() },
        // 原 App 这里写的是 (256a+b)/4（当成无符号），SAE 规定是有符号的：
        // 0 对应 -8192 Pa，蒸发系统读数是负压，照抄会得到 +8192 的错值。
        pid("0132", "蒸发系统压力", "Pa", 2, -8192.0, 8191.75) { v -> round1((((v[0] shl 8) + v[1]) - 32768) / 4.0) },
        pid("0133", "大气压", "kPa", 1, 0.0, 255.0) { v -> v[0].toDouble() },
        pid("013C", "催化器温度", "℃", 2, -40.0, 6513.5) { v -> round1(((v[0] shl 8) + v[1]) / 10.0 - 40) },
        pid("0142", "控制模块电压", "V", 2, 0.0, 65.535) { v -> round2(((v[0] shl 8) + v[1]) / 1000.0) },
        pid("0143", "绝对负荷", "%", 2, 0.0, 25700.0) { v -> (100.0 * ((v[0] shl 8) + v[1]) / 255).toDouble() },
        pid("0145", "相对节气门位置", "%", 1, 0.0, 100.0) { v -> round1(100.0 * v[0] / 255) },
        pid("0146", "环境温度", "℃", 1, -40.0, 215.0) { v -> (v[0] - 40).toDouble() },
        pid("015C", "机油温度", "℃", 1, -40.0, 215.0) { v -> (v[0] - 40).toDouble() }
    )

    private val map: Map<String, Pid> = ALL.associateBy { it.cmd }

    fun byCmd(cmd: String): Pid? = map[cmd.uppercase()]

    /**
     * 轮询队列。前六条与原版 `obdManager.js` 的首页队列完全一致
     * （`010C,010D,010C,0105,010C,ATRV` 重复）—— 转速表是唯一「每秒要换十几次」的量，
     * 混在别的一起排队会明显跳格；后面几条是补充项，本车不支持时适配器回 NO DATA，不影响主循环。
     *
     * `ATRV` 不是 PID，是适配器自己测的电瓶电压：摩托 ECU 不支持 0142 时靠它兜底。
     */
    val POLL_QUEUE: List<String> = listOf(
        "010C", "010D", "010C", "0105", "010C", "ATRV",
        "010C", "010F", "010C", "0110", "010C", "0104", "0142"
    )

    /**
     * 解析 `0100 / 0120 / 0140` 的 32 位支持位图，返回该组里支持的 PID 命令集合。
     * 对应原 App 的 `pid_0100` 字符串 + `isSupport()`。
     */
    fun supportedFrom(mask: IntArray, base: Int): Set<String> {
        val out = LinkedHashSet<String>()
        for (i in 0 until 32) {
            val bit = 31 - i
            val on = (mask[bit / 8] shr (bit % 8)) and 1 == 1
            if (on) out += String.format("01%02X", base + i + 1)
        }
        return out
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0
}
