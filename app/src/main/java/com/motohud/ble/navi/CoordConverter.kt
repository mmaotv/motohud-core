package com.motohud.ble.navi

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标系转换工具（复刻原 App JsBridgeInterface.GCJ2BD / 地图跳转参数）。
 * - GCJ02（高德/腾讯/国内国测局坐标）
 * - BD09（百度地图坐标）
 * - WGS84（GPS 原始坐标）
 */
object CoordConverter {

    private const val X_PI = Math.PI * 3000.0 / 180.0
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** GCJ02 -> BD09（百度地图 intent 需要） */
    fun gcj2BD(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        val x = gcjLng
        val y = gcjLat
        val z = sqrt(x * x + y * y) + 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) + 0.000003 * cos(x * X_PI)
        return (z * sin(theta) + 0.006) to (z * cos(theta) + 0.0065)
    }

    /** WGS84 -> GCJ02（GPS 原始坐标转国内地图坐标） */
    fun wgs84ToGCJ(wgsLat: Double, wgsLng: Double): Pair<Double, Double> {
        if (outOfChina(wgsLat, wgsLng)) return wgsLat to wgsLng
        var dLat = transformLat(wgsLng - 105.0, wgsLat - 35.0)
        var dLng = transformLng(wgsLng - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return (wgsLat + dLat) to (wgsLng + dLng)
    }

    /** 经纬度格式化（5 位小数，约 1m 精度） */
    fun formatLatLng(lat: Double, lng: Double): String =
        String.format("%.5f, %.5f", lat, lng)

    /** 距离格式化：<1000m 显示米，否则公里 */
    fun formatDistance(m: Int): String =
        if (m < 1000) "$m 米" else String.format("%.1f 公里", m / 1000.0)

    /**
     * 八方位（北/东北/东/…）。
     *
     * 搜索结果只给"3.2 公里"时，用户还得自己判断在哪个方向、顺不顺路。
     * 加上方位（"东南 3.2 公里"）才是「这地方离我多远、在哪边」的完整回答。
     */
    fun direction8(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): String {
        val dLat = Math.toRadians(toLat - fromLat)
        val dLng = Math.toRadians(toLng - fromLng)
        val mLat = Math.toRadians((fromLat + toLat) / 2.0)
        // 米制平面分量：x 向东、y 向北
        val x = dLng * cos(mLat)
        val y = dLat
        if (x == 0.0 && y == 0.0) return "正前方"
        var deg = Math.toDegrees(atan2(x, y))
        if (deg < 0) deg += 360.0
        val names = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val idx = (((deg + 22.5) / 45.0).toInt()) % 8
        return names[idx]
    }

    /** 时间格式化：秒 -> "x 分钟" / "x 小时 x 分钟" */
    fun formatEta(seconds: Int): String {
        if (seconds <= 0) return "--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> "${h} 小时 ${m} 分钟"
            m > 0 -> "$m 分钟"
            else -> "1 分钟内"
        }
    }

    /** 方位角 -> 中文方向 */
    fun bearingToDirection(bearing: Float): String {
        val dirs = listOf("正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北")
        val idx = ((bearing + 22.5f) / 45f).toInt() % 8
        return dirs[idx]
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * abs(x)
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * abs(x)
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
