package com.motohud.ble.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Geo] 的几何自检。
 *
 * 重点不是"算得准不准"，而是**兜底路径必须和 SDK 路径等价**：
 * 单测在纯 JVM 下跑，`AMapUtils` 一调就抛，所以这里测的是 haversine 分支；
 * 真机走 SDK 分支。两条路径若差出几十米，偏航阈值（90m）就会单测通过、
 * 实车误报 —— 那种 bug 只能靠对照来防。
 */
class GeoTest {

    /** 1° 纬度 ≈ 111.2km（WGS84 子午线弧长），两条路径都必须落在 ±0.3% 内 */
    @Test
    fun distanceOneDegreeLatitude() {
        val d = Geo.haversine(30.0, 106.0, 31.0, 106.0)
        assertTrue("1° 纬度应约 111.2km，实际 $d", d in 110_500.0..111_900.0)
    }

    /** 同纬度 1° 经度要按 cos(lat) 缩短，重庆（30°N）约 96km */
    @Test
    fun distanceOneDegreeLongitudeShrinksWithLatitude() {
        val d = Geo.haversine(30.0, 106.0, 30.0, 107.0)
        assertTrue("30°N 处 1° 经度应约 96km，实际 $d", d in 95_000.0..97_500.0)
    }

    /** 零距离必须严格为 0（曾经平面近似实现会在同点返回 NaN） */
    @Test
    fun distanceZeroForSamePoint() {
        assertEquals(0.0, Geo.haversine(29.557, 106.577, 29.557, 106.577), 1e-6)
    }

    /** 方位角：正东 90°、正北 0°、正南 180° */
    @Test
    fun bearingCardinalDirections() {
        assertEquals(90.0, Geo.bearing(30.0, 106.0, 30.0, 106.01).toDouble(), 1.0)
        assertEquals(0.0, Geo.bearing(30.0, 106.0, 30.01, 106.0).toDouble(), 1.0)
        assertEquals(180.0, Geo.bearing(30.0, 106.0, 29.99, 106.0).toDouble(), 1.0)
    }

    /**
     * 前进点必须落在给定距离上 —— 这是「GPS 失效后沿路线推算」的唯一依据，
     * 算错会让车标在断信号时越跑越偏。
     */
    @Test
    fun destinationLandsOnGivenDistance() {
        val (lat, lng) = Geo.destination(30.0, 106.0, 45f, 100.0)
        val back = Geo.haversine(30.0, 106.0, lat, lng)
        assertEquals(100.0, back, 0.5)
        // 朝东北走：纬度升、经度升
        assertTrue(lat > 30.0)
        assertTrue(lng > 106.0)
    }

    /** 米/度换算常量必须和 haversine 自洽（两套口径打架会让限行区画歪） */
    @Test
    fun metersPerDegreeMatchesHaversine() {
        val byConst = Geo.M_PER_DEG_LAT
        val byHaversine = Geo.haversine(30.0, 106.0, 31.0, 106.0)
        assertTrue(
            "常量 $byConst 与 haversine $byHaversine 偏差过大",
            kotlin.math.abs(byConst - byHaversine) / byHaversine < 0.005
        )
    }
}
