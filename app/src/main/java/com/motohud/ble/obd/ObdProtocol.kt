package com.motohud.ble.obd

/**
 * ELM327 文本协议的解析层。
 *
 * 复刻自 YMOBD 的 `common/js/format.js`（app-service.js 的 `1783` 模块）。
 * 与原实现的差异只有两处，都在注释里标了原因：
 *  1. 原版按 `indexOf(命令回显)` 取数据，一旦 ECU 数据里恰好出现同样的两个字节就会错位；
 *     这里先按帧头/PCI 字节定位再校验模式字节，容错更稳。
 *  2. 原版把 DTC 的 4 位 hex 直接拼前缀，遇到 `0000` 之外的填充会多出假码；
 *     这里逐帧校验「0x43 + 成对的 4 位」，并且过滤全零。
 *
 * 关键事实（决定了下面每处偏移）：
 *  - 初始化时发了 `ATH1`（帧头开）、`ATE0`（回显关）、`ATL0`（换行关），**没发 ATS0**，
 *    所以 ELM327 仍会用空格分隔每个字节，例如 `7E8 04 41 0C 1A F8`。
 *  - CAN 协议下帧头是 3 位 hex（11 位 ID）或 8 位 hex（29 位 ID），紧随其后是 1 字节 PCI；
 *    ISO9141/KWP 下帧头是 3 字节（6 位 hex）且没有 PCI。
 *  - 一条命令的回复以 `>`（0x3E）结束，之前可能有多行（多个 ECU 各一行）。
 */
object ObdProtocol {

    /** ELM327 的提示符，收到它就代表这一条命令的回复完整了 */
    const val PROMPT = 0x3E.toChar()

    /** 只留十六进制字符：去掉空格、回车换行、`>`、`\0` */
    fun hexOnly(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F') sb.append(c)
        }
        return sb.toString()
    }

    fun lines(raw: String): List<String> =
        raw.replace("\r\n", "\r").split('\r').map { it.trim() }.filter { it.isNotEmpty() && it != ">" }

    /** 适配器明确说自己不会回数据 */
    fun isNoData(raw: String): Boolean {
        val u = raw.uppercase()
        return u.contains("NO DATA") || u.contains("NODATA") || u.contains("UNABLE TO CONNECT")
    }

    /**
     * 解析一条模式 01 命令的回复，返回 [len] 个数据字节。
     *
     * 取「第一条能对上 `41 + PID` 的帧」：模式 01 是广播请求，摩托车只有一个发动机 ECU 会答，
     * 多帧同时出现时以最先到达的为准（和原 App 用 `indexOf` 取第一处一致）。
     */
    fun pidData(cmd: String, raw: String, len: Int): IntArray? {
        if (isNoData(raw)) return null
        val mode = cmd.take(2).toIntOrNull(16) ?: return null
        val prefix = String.format("%02X", mode + 0x40) + cmd.takeLast(2).uppercase()
        for (line in lines(raw)) {
            val h = hexOnly(line)
            if (h.length < prefix.length + 2) continue
            val i = h.indexOf(prefix)
            if (i < 0) continue
            val start = i + prefix.length
            if (h.length < start + len * 2) continue
            return IntArray(len) { k ->
                h.substring(start + k * 2, start + k * 2 + 2).toInt(16)
            }
        }
        return null
    }

    /** `0100/0120/0140` 的 4 字节支持位图 */
    fun pidMask(cmd: String, raw: String): IntArray? = pidData(cmd, raw, 4)

    /** 是否是「不支持该 PID」的否定响应（7F 01 12） */
    fun isNegative(raw: String, mode: String): Boolean {
        val m = mode.uppercase()
        return lines(raw).any { line ->
            val h = hexOnly(line)
            h.contains("7F" + m)
        }
    }

    /**
     * 解析故障码回复（模式 03 已存 / 07 待定 / 0A 永久）。
     *
     * 两个偏移都不能想当然（这也是原版和第一版移植都错过的地方）：
     *  1. **正响应字节随模式变**：03→`43`、07→`47`、0A→`4A`。写死 `43` 的话，
     *     待定码和永久码永远读不出来，界面上就是「没有故障」—— 比报错更糟。
     *  2. **正响应字节后面紧跟一个「本帧码数」字节**，再往后才是 4 位一组的码。
     *     从数量字节开始两两切分，会把 `43 01 01 33`（1 个码 P0133）读成
     *     `P0101` + `P3300` —— 数字看着像模像样，其实全是错的。
     *
     * @param headerChars 帧头长度（hex 字符数）：CAN 11 位=3，CAN 29 位=8，非 CAN=6，0=无帧头
     * @param canPci      CAN 协议在帧头后还有 1 字节 PCI，需要多跳 2 个字符
     * @param mode        请求用的模式串（`03`/`07`/`0A`）
     */
    fun parseDtc(raw: String, headerChars: Int, canPci: Boolean, mode: String = "03"): List<String> {
        if (isNoData(raw)) return emptyList()
        val m = mode.take(2).toIntOrNull(16) ?: 3
        val positive = String.format("%02X", m + 0x40)
        val out = LinkedHashSet<String>()
        for (line in lines(raw)) {
            val h = hexOnly(line)
            if (h.length < 6) continue
            var body = h
            if (headerChars > 0 && h.length > headerChars) {
                body = h.substring(headerChars)
                if (canPci && body.length > 2) body = body.substring(2)
            }
            val i = body.indexOf(positive)
            if (i < 0) continue
            var p = i + 2
            // 数量字节：有些克隆头不带它（直接跟码），取到 0 就退化成「有多少读多少」
            val count = if (p + 2 <= body.length) body.substring(p, p + 2).toIntOrNull(16) ?: 0 else 0
            p += 2
            var read = 0
            while (p + 4 <= body.length && (count == 0 || read < count)) {
                val code = body.substring(p, p + 4)
                p += 4
                read++
                if (code == "0000") continue
                out += decodeDtc(code) ?: continue
            }
        }
        return out.toList()
    }

    /** 4 位 hex → `P0171` 这种标准写法 */
    fun decodeDtc(hex4: String): String? {
        if (hex4.length != 4) return null
        val b0 = hex4.substring(0, 2).toIntOrNull(16) ?: return null
        val b1 = hex4.substring(2, 4).toIntOrNull(16) ?: return null
        val prefix = when (b0 shr 6) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val second = (b0 shr 4) and 0x03
        val third = b0 and 0x0F
        return String.format("%c%d%X%02X", prefix, second, third, b1)
    }

    /**
     * 从 `ATDP` 的回复里判断协议类型。
     *
     * 这一步不是可选的：帧头长度和 PCI 字节都取决于它，判错的话 DTC 会整体错位半字节，
     * 读出来是 `P` 开头的乱码。原 App 也是靠 `pid_ATDP.includes("CAN")` 分流。
     */
    fun protocolOf(raw: String): Protocol {
        val u = raw.uppercase()
        return when {
            u.contains("CAN") && (u.contains("29/") || u.contains("29BIT") || u.contains("29 BIT")) ->
                Protocol(can = true, headerChars = 8)
            u.contains("CAN") -> Protocol(can = true, headerChars = 3)
            u.contains("ISO 9141") || u.contains("KWP") || u.contains("ISO 14230") ->
                Protocol(can = false, headerChars = 6)
            u.isBlank() -> Protocol(can = true, headerChars = 3)
            else -> Protocol(can = false, headerChars = 6)
        }
    }

    data class Protocol(val can: Boolean, val headerChars: Int)

    /** 解析 `ATRV` 的电压，例如 `12.4V` */
    fun battery(raw: String): Float? {
        val m = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*V", RegexOption.IGNORE_CASE).find(raw) ?: return null
        return m.groupValues[1].toFloatOrNull()
    }

    /** `ATI` / `ATZ` 的版本串，例如 `ELM327 v1.5` */
    fun adapterId(raw: String): String? =
        lines(raw).map { it.trim() }.firstOrNull { it.length in 3..40 && it.uppercase().contains("ELM") }
            ?: lines(raw).firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
}
