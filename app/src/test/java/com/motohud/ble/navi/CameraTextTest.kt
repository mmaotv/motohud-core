package com.motohud.ble.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电子眼分类与距离策略的离线校验。
 *
 * 这两块逻辑以前只能靠真机跑一条路线看日志，改一个阈值就要骑一趟车。
 * 这里把「官方文本 → 细化类型 / 距离」和「类型 → 要不要念、念几档」钉死，
 * 真机只用来验界面和听感。
 *
 * 文本样本全部来自 2026-11-01 机场高速的真机日志（`CameraText` 标签）。
 */
class CameraTextTest {

    // ---------- 官方文本解析 ----------

    @Test
    fun `解析压线拍照`() {
        val p = CameraText.parse("三百米内,有多个压线拍照")!!
        assertEquals(CameraKind.F_PRESS_LINE, p.type)
        assertEquals(300, p.distanceM)
        assertTrue(p.multi)
    }

    @Test
    fun `解析不系安全带`() {
        val p = CameraText.parse("一公里内,有多个不系安全带拍照")!!
        assertEquals(CameraKind.F_SEATBELT, p.type)
        assertEquals(1000, p.distanceM)
    }

    @Test
    fun `解析接打电话`() {
        val p = CameraText.parse("九百米内,有多个接打电话拍照")!!
        assertEquals(CameraKind.F_PHONE, p.type)
        assertEquals(900, p.distanceM)
    }

    @Test
    fun `解析带尾巴的违法拍照`() {
        // 官方会在后面接一句"请按交规行驶"，不能因此解析失败
        val p = CameraText.parse("四百米内,有多个违法拍照,请按交规行驶")!!
        assertEquals(CameraKind.F_ILLEGAL, p.type)
        assertEquals(400, p.distanceM)
    }

    @Test
    fun `解析无逗号的前方句式`() {
        val p = CameraText.parse("前方300米有违章拍照")!!
        assertEquals(CameraKind.F_ILLEGAL, p.type)
        assertEquals(300, p.distanceM)
        assertFalse(p.multi)
    }

    @Test
    fun `词表外的类型仍算电子眼`() {
        // 宁可显示"电子眼"，也别把这条情报整条丢掉
        val p = CameraText.parse("二百米内,有多个抛物拍照")!!
        assertEquals(CameraKind.F_GENERIC, p.type)
        assertEquals(200, p.distanceM)
    }

    @Test
    fun `非电子眼文本一律不认`() {
        // 这些是官方语音通道里同时存在的其它提示，一个都不能被吃掉
        assertNull(CameraText.parse("前方进入隧道，请开灯"))
        assertNull(CameraText.parse("沿当前道路继续行驶一点七公里"))
        assertNull(CameraText.parse("所有车道均可通行,保持主路行驶,上坡"))
        assertNull(CameraText.parse("前方路口有违章拍照"))   // 没有距离，不能瞎猜
    }

    @Test
    fun `中文距离`() {
        assertEquals(300, CameraText.distanceOf("三百米"))
        assertEquals(900, CameraText.distanceOf("九百米"))
        assertEquals(1000, CameraText.distanceOf("一公里"))
        assertEquals(2000, CameraText.distanceOf("两公里"))
        assertEquals(150, CameraText.distanceOf("一百五十米"))
        assertEquals(300, CameraText.distanceOf("300米"))
        assertNull(CameraText.distanceOf("米"))
    }

    // ---------- 分类与播报策略 ----------

    @Test
    fun `通用违章只上屏不念`() {
        // 实测里 93% 的点位都是这一类，念它就是复读机
        assertFalse(CameraKind.worthSpeaking(CameraKind.BREAK_RULE))
        assertFalse(CameraKind.worthSpeaking(CameraKind.SURVEILLANCE))
        assertFalse(CameraKind.worthSpeaking(CameraKind.F_ILLEGAL))
        assertFalse(CameraKind.worthSpeaking(CameraKind.F_SEATBELT))
        assertFalse(CameraKind.worthSpeaking(CameraKind.F_PHONE))
        assertTrue(CameraKind.worthShowing(CameraKind.BREAK_RULE))
    }

    @Test
    fun `三类要念`() {
        assertTrue(CameraKind.worthSpeaking(CameraKind.SPEED))
        assertTrue(CameraKind.worthSpeaking(CameraKind.TRAFFIC_LIGHT))
        assertTrue(CameraKind.worthSpeaking(CameraKind.EMERGENCY))
        assertTrue(CameraKind.worthSpeaking(CameraKind.F_PRESS_LINE))
        assertTrue(CameraKind.worthSpeaking(CameraKind.F_WRONG_WAY))
    }

    @Test
    fun `车流量与ETC连屏都不上`() {
        assertFalse(CameraKind.worthShowing(CameraKind.FLOW_SPEED))
        assertFalse(CameraKind.worthShowing(CameraKind.ETC))
    }

    @Test
    fun `距离档按类型给`() {
        // 测速两档（远程预警 + 临门确认）
        assertEquals(listOf(500, 200), CameraKind.tiersOf(CameraKind.SPEED).toList())
        // 闯红灯一档
        assertEquals(listOf(200), CameraKind.tiersOf(CameraKind.TRAFFIC_LIGHT).toList())
        // 占道一档
        assertEquals(listOf(100), CameraKind.tiersOf(CameraKind.EMERGENCY).toList())
        // 细化类型只念一次
        assertEquals(listOf(300), CameraKind.tiersOf(CameraKind.F_PRESS_LINE).toList())
        // 不念的没有档
        assertTrue(CameraKind.tiersOf(CameraKind.BREAK_RULE).isEmpty())
    }

    @Test
    fun `串内代表类型优先级`() {
        // 一串"测速 + 违章拍照"要说测速
        assertTrue(CameraKind.rank(CameraKind.SPEED) > CameraKind.rank(CameraKind.BREAK_RULE))
        assertTrue(CameraKind.rank(CameraKind.F_RED_LIGHT) > CameraKind.rank(CameraKind.F_PRESS_LINE))
    }

    @Test
    fun `去重键要区分档位与兜底句`() {
        val a = CameraAlert(CameraKind.SPEED, 200, id = 7)
        assertEquals("7|200", a.speechKey(200))
        // 同一档位的一次"兜底句"必须和正常跨档分开记，否则第二句被吞
        assertEquals("7|200|e", a.speechKey(200, exact = true))
    }
}
