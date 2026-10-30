package com.motohud.ble.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 弯道识别的几何测试。
 *
 * 这一块以前完全靠"跑一段真实山路听一遍"来验证，改一行就得实车跑，
 * 结果就是没人敢动。这里用**构造出来的折线**把方向和阈值钉死 ——
 * 特别是左右方向：判反了会把左弯播成"向右"，比不播还危险。
 */
class RoadShapeTest {

    /** 以 (30, 106) 为原点，按「东 x 米 / 北 y 米」构造一个点 */
    private fun pt(eastM: Double, northM: Double): Pair<Double, Double> {
        val lat = 30.0 + northM / 111_320.0
        val lng = 106.0 + eastM / (111_320.0 * Math.cos(Math.toRadians(30.0)))
        return lat to lng
    }

    @Test
    fun `直线不产生弯道提示`() {
        val line = listOf(pt(0.0, 0.0), pt(0.0, 50.0), pt(0.0, 100.0), pt(0.0, 150.0))
        assertTrue(RoadShape.scan(line).isEmpty())
    }

    @Test
    fun `点数不足直接返回空`() {
        assertTrue(RoadShape.scan(emptyList()).isEmpty())
        assertTrue(RoadShape.scan(listOf(pt(0.0, 0.0), pt(0.0, 50.0))).isEmpty())
    }

    /**
     * 向北走 100 米后向东 —— 这是**右转**。
     *
     * 历史上 `hint()` 里写成 `left = acc > 0`，配合 `atan2(东, 北)` 的方位角语义
     * 恰好是反的，播报会把左右说反。这个用例就是为了锁死它。
     */
    @Test
    fun `北向东是右转`() {
        val line = listOf(pt(0.0, 0.0), pt(0.0, 100.0), pt(100.0, 100.0))
        val hints = RoadShape.scan(line)
        assertEquals(1, hints.size)
        assertFalse("北→东应为右转", hints[0].left)
        assertTrue("90 度应判为急弯", hints[0].sharp)
    }

    @Test
    fun `北向西是左转`() {
        val line = listOf(pt(0.0, 0.0), pt(0.0, 100.0), pt(-100.0, 100.0))
        val hints = RoadShape.scan(line)
        assertEquals(1, hints.size)
        assertTrue("北→西应为左转", hints[0].left)
    }

    @Test
    fun `弯道位置换算到距本段起点的距离`() {
        val line = listOf(pt(0.0, 0.0), pt(0.0, 100.0), pt(100.0, 100.0))
        val hint = RoadShape.scan(line).single()
        // 折线第一个弯折点在 100 米处，误差给到 5 米（球面近似 + 构造精度）
        assertTrue("atM=${hint.atM}", abs(hint.atM - 100f) < 5f)
    }

    /**
     * 山路长弯：每次只折 25°，单点判定完全命中不到，
     * 必须靠"同向小转角合并"才能识别出来。
     *
     * 折线按方位角 0°/-25°/-50°/-75°/-100° 逐段 30 米构造（向左持续扭转），
     * 相邻弯折点间距 30 米 < 合并阈值 40 米，累计 -100°。
     */
    @Test
    fun `多个小转角同向累计识别为连续弯道`() {
        val line = listOf(
            pt(0.0, 0.0),
            pt(0.0, 40.0),
            pt(-12.7, 67.2),
            pt(-35.7, 86.5),
            pt(-64.7, 94.3),
            pt(-94.2, 89.1)
        )
        val hints = RoadShape.scan(line)
        assertEquals(1, hints.size)
        assertTrue("累计转角 ${hints[0].angle} 应 ≥ 90", hints[0].angle >= RoadShape.CONTINUOUS_ANGLE)
        assertTrue("应判为连续弯道", hints[0].continuous)
        assertTrue("持续左扭应为左转", hints[0].left)
    }

    /** 中间隔了一长段直路的两处弯折，不能被合并成一个 */
    @Test
    fun `长直路隔开的两处弯折分别上报`() {
        val line = listOf(
            pt(0.0, 0.0),
            pt(0.0, 50.0),
            pt(450.0, 50.0),   // 北→东：右转 90，随后 450 米直路
            pt(450.0, 150.0)   // 东→北：左转 90
        )
        val hints = RoadShape.scan(line)
        assertEquals(2, hints.size)
        assertFalse("北→东是右转", hints[0].left)
        assertTrue("东→北是左转", hints[1].left)
    }

    @Test
    fun `折线长度接近实际里程`() {
        val line = listOf(pt(0.0, 0.0), pt(0.0, 100.0), pt(100.0, 100.0))
        assertEquals(200f, RoadShape.lengthOf(line), 5f)
    }
}
