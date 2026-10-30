package com.motohud.ble.navi

import com.amap.api.maps.AMapUtils
import com.amap.api.maps.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 球面几何的唯一实现点。
 *
 * 起因：项目里同一件事写了 5 份（[LocationFilter]、[NaviEngine]、[RoadShape]、
 * `AmapRepo.haversineM`、`CoordConverter.direction8`），公式还各不相同 ——
 * 有的是平面近似，有的是 haversine，半径常量三处三个值（6371000 / 6378137 / EARTH_R）。
 * 这类"自己又算一遍"最危险的不是重复，是**改一处忘一处**：调了距离公式，
 * 偏航判定变了、弯道触发了、搜索排序没变。
 *
 * 现在统一走这里：
 *  - **距离**直接用高德官方 `AMapUtils.calculateLineDistance`（椭球距离，与算路 SDK
 *    同一套地理模型，误差 <0.5m/100km），SDK 不可用时退回本文件的 haversine；
 *  - **方位角** SDK 没有对应接口（`LatLng` 只有经纬度），保留自研；
 *  - **前进点** SDK 也没有（只有算路/坐标转换），保留自研。
 *
 * SDK 不可用的场景只有一种：JVM 单元测试。`AMapUtils` 依赖 Android 运行时，
 * 纯 JVM 下第一次调用就抛，此时自动锁定到 haversine，后面的调用不再尝试 —— 
 * 避免每次调用都吃一次异常（距离计算每秒几百次）。
 */
object Geo {

    /** SDK 距离接口是否可用。第一次调用失败后永久置 false */
    @Volatile
    private var sdkUsable = true

    /** 是否已经探过一次（日志只打一次，别刷屏） */
    @Volatile
    private var probed = false

    /** 当前是否真的在用 SDK 算距离（诊断用；false 表示已退回本地 haversine） */
    val usingSdk: Boolean get() = sdkUsable

    private const val EARTH_R = 6_371_000.0

    /** 1 纬度 ≈ 111.32km（本项目所有"米↔度"换算都走这三个常量，别再各写一个数） */
    const val M_PER_DEG_LAT = 111_320.0

    /** 1 经度在某纬度上的长度（米），极区夹到 0.01 避免除零 */
    fun mPerDegLng(lat: Double): Double =
        M_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01)

    /**
     * 两点间距离（米）。
     *
     * 精度足够所有既有用途：偏航判定（阈值 90m）、吸附（阈值 4/15/60m）、
     * 弯道合并（40m）。椭球与球面在几十米到几十公里尺度上差异 <0.3%，
     * 不影响任何阈值判定，但换成官方实现后与算路给的距离口径一致了。
     */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        if (sdkUsable) {
            try {
                val d = AMapUtils.calculateLineDistance(
                    LatLng(lat1, lng1), LatLng(lat2, lng2)
                ).toDouble()
                if (!probed) {
                    probed = true
                    if (com.motohud.ble.BuildConfig.DEBUG) {
                        android.util.Log.d("Geo", "距离计算：走 AMapUtils（官方椭球距离）")
                    }
                }
                return d
            } catch (t: Throwable) {
                // 只在第一次失败时记日志，否则热路径会刷屏
                sdkUsable = false
                if (!probed) {
                    probed = true
                    if (com.motohud.ble.BuildConfig.DEBUG) {
                        android.util.Log.w(
                            "Geo",
                            "AMapUtils 不可用（${t.javaClass.simpleName}），全程退回本地 haversine"
                        )
                    }
                }
            }
        }
        return haversine(lat1, lng1, lat2, lng2)
    }

    /** 本地 haversine（SDK 不可用时的兜底，也是单测实际跑的分支） */
    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rLat1) * cos(rLat2) * sin(dLng / 2) * sin(dLng / 2)
        return EARTH_R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * 起点到终点的方位角（度，0=正北，顺时针为正）。
     *
     * SDK 没有这个接口 —— `LatLng` 只存经纬度，`AMapUtils` 只给距离和面积。
     */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLng)
        var brg = Math.toDegrees(atan2(y, x))
        if (brg < 0) brg += 360.0
        return brg.toFloat()
    }

    /**
     * 从 (lat,lng) 沿 bearing 前进 dist 米后的坐标。
     *
     * 用于「GPS 失效后沿路线推算」和直线演示模式。SDK 没有对应接口
     * （`CoordinateConverter` 只做坐标系换算，不做出发点推算）。
     */
    fun destination(
        lat: Double,
        lng: Double,
        bearingDeg: Float,
        distM: Double
    ): Pair<Double, Double> {
        val rLat = Math.toRadians(lat)
        val rLng = Math.toRadians(lng)
        val brg = Math.toRadians(bearingDeg.toDouble())
        val d = distM / EARTH_R
        val nLat = Math.asin(sin(rLat) * cos(d) + cos(rLat) * sin(d) * cos(brg))
        val nLng = rLng + atan2(sin(brg) * sin(d) * cos(rLat), cos(d) - sin(rLat) * sin(nLat))
        return Math.toDegrees(nLat) to Math.toDegrees(nLng)
    }
}
