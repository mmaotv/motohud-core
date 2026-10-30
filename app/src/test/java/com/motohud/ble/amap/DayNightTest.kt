package com.motohud.ble.amap

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 昼夜判定。
 *
 * 这块算错了不会报错，只会让地图在白天变黑、夜里变白 —— 而骑手没空去设置里改回来。
 * 用真实的日期 + 坐标验证，比"看着差不多"可靠。
 */
class DayNightTest {

    private lateinit var original: TimeZone

    @Before
    fun fixTimeZone() {
        // DayNight 用 TimeZone.getDefault() 换算本地时刻，测试必须固定时区，
        // 否则在 CI（UTC）和本地（CST）跑出来的结果不一样。
        original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun restore() {
        TimeZone.setDefault(original)
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()

    // 重庆
    private val lat = 29.56
    private val lng = 106.55

    @Test
    fun `夏至的下午是白天`() {
        assertFalse(DayNight.isNight(lat, lng, at(2026, 6, 21, 14)))
    }

    @Test
    fun `夏至的深夜是夜间`() {
        assertTrue(DayNight.isNight(lat, lng, at(2026, 6, 21, 23)))
        assertTrue(DayNight.isNight(lat, lng, at(2026, 6, 21, 3)))
    }

    @Test
    fun `冬至早上七点天还没亮`() {
        // 重庆冬至日出约 7:45，再加 20 分钟余量，8:05 前都应算夜间
        assertTrue(DayNight.isNight(lat, lng, at(2026, 12, 21, 7)))
    }

    @Test
    fun `冬至上午九点已是白天`() {
        assertFalse(DayNight.isNight(lat, lng, at(2026, 12, 21, 9)))
    }

    /** 夏季白昼明显长于冬季 —— 这正是"按时间死切"做不到的 */
    @Test
    fun `夏季白昼比冬季长`() {
        val summer = DayNight.dayRangeHours(lat, lng, at(2026, 6, 21, 12))
        val winter = DayNight.dayRangeHours(lat, lng, at(2026, 12, 21, 12))
        val summerLen = summer.second - summer.first
        val winterLen = winter.second - winter.first
        assertTrue("夏 $summerLen 应长于冬 $winterLen", summerLen > winterLen + 1.5)
    }

    /** 极昼：算不出日出日落时必须退回固定区间，而不是把整天判成夜间 */
    @Test
    fun `极昼退回兜底区间而不是全天夜间`() {
        val midnightSun = DayNight.dayRangeHours(78.0, 15.0, at(2026, 6, 21, 12))
        assertFalse("正午应判白天", DayNight.isNight(78.0, 15.0, at(2026, 6, 21, 12)))
        assertTrue("兜底区间应约 6点半到18点，实际 $midnightSun", midnightSun.second > midnightSun.first)
        assertTrue("深夜应判夜间", DayNight.isNight(78.0, 15.0, at(2026, 6, 21, 23)))
    }

    /** 经度差 1 小时左右，日出时刻应跟着差 —— 时区固定时，这能验证经度参与计算 */
    @Test
    fun `越往东日出越早`() {
        val west = DayNight.dayRangeHours(30.0, 90.0, at(2026, 3, 21, 12))
        val east = DayNight.dayRangeHours(30.0, 120.0, at(2026, 3, 21, 12))
        assertTrue("东边 ${east.first} 应早于西边 ${west.first}", east.first < west.first)
    }
}
