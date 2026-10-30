package com.motohud.ble.amap

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.PI

/**
 * 昼夜判定 —— 按经纬度算当天日出日落，不联网、不依赖系统深色模式。
 *
 * ## 为什么不用系统的深色模式开关
 *
 * 骑行 App 的主题本身只有深色（`Theme.Material3.Dark`），系统那个开关
 * 反映的是「用户偏好」而不是「现在天黑了没有」。骑手白天跑长途时手机常设深色，
 * 但地图还是浅色更好认；夜里十点手机可能还留在浅色，眼睛已经受不了。
 * 所以这里只认**太阳**。
 *
 * ## 算法
 *
 * NOAA 简化太阳位置公式（SPA 的降级版，误差约 1~2 分钟，对导航够用）：
 *   · 赤纬 decl 与均时差 eqtime 由一年中的第几天算出
 *   · 时角 ha = acos( cos(90.833°) / (cos φ cos δ) − tan φ tan δ )
 *   · 日出/日落（UTC 分钟）= 720 ∓ 4·(经度 ± ha) − eqtime
 *
 * 90.833° 而不是 90°：太阳圆面上边缘加 34′ 大气折射 + 16′ 半径，
 * 这才是「天看起来亮了」的时刻，跟高德/DayNight 的表现一致。
 *
 * ## 两个刻意的设计
 *
 * 1. **留 [MARGIN_MIN] 分钟余量**：日出后 20 分钟才算白天、日落前 20 分钟就算夜间。
 *    天没黑透就把地图切成深色，比晚一点切更安全 —— 早切最多少看清几分钟，晚切会晃眼。
 * 2. **算不出来就兜底**。高纬度极昼/极夜时 `acos` 参数越出 [-1,1]，
 *    以及没有位置时，一律退回固定的 [FALLBACK_DAY_START] / [FALLBACK_DAY_END]。
 *    宁可少一点精度，也不要因为数学边界让整块地图变黑或变白。
 */
object DayNight {

    /** 日出日落前后各留的余量（分钟） */
    private const val MARGIN_MIN = 20

    /** 算不出时按 6:30 ~ 18:00 当白天（国内大部分地区的中位数） */
    private const val FALLBACK_DAY_START = 6.5
    private const val FALLBACK_DAY_END = 18.0

    /** 太阳圆面上边缘的视高度（度） */
    private const val ZENITH = 90.833

    /**
     * 现在是不是夜间。
     *
     * @param lat 纬度（GCJ02 与 WGS84 在这个用途上差别可忽略，不必转换）
     * @param lng 经度
     * @param nowMs 判定时刻，默认取当前时间。**传参而不是内部取**，
     *   是为了让单元测试能固定时间跑（见 DayNightTest）。
     */
    fun isNight(lat: Double, lng: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        // 注意单位：localMinutesOfDay 是「当天第几分钟」，dayRangeHours 是「小时」。
        // 曾经直接拿分钟去比小时，结果下午 2 点（840）永远大于日落小时数（19.9），
        // 白天被一律判成夜间 —— 白天开导航满屏深色。
        val hour = localMinutesOfDay(nowMs) / 60.0
        val range = dayRangeHours(lat, lng, nowMs)
        return hour < range.first || hour >= range.second
    }

    /**
     * 当天的日照区间（本地小时，含 [MARGIN_MIN] 的收放）。
     * 白天：`range.first <= 当前小时 < range.second`。
     */
    fun dayRangeHours(
        lat: Double,
        lng: Double,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<Double, Double> {
        val tzOffsetMin = TimeZone.getDefault().getOffset(nowMs) / 60_000.0
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)

        val sunrise = sunEventUtcMinutes(lat, lng, dayOfYear, sunrise = true)
        val sunset = sunEventUtcMinutes(lat, lng, dayOfYear, sunrise = false)
        if (sunrise == null || sunset == null || sunset <= sunrise) {
            return FALLBACK_DAY_START to FALLBACK_DAY_END
        }
        val margin = MARGIN_MIN / 60.0
        // 先折回 0~24 再判方向：跨时区时原始值可能是负的或超过 24，
        // 直接比较会把"跨零点"和"算错"混在一起。
        val start = wrap24(sunrise / 60.0 + tzOffsetMin / 60.0 + margin)
        val end = wrap24(sunset / 60.0 + tzOffsetMin / 60.0 - margin)
        // 仍不成立说明当天数据不可信（极区/时区异常），退回固定区间
        if (end <= start) return FALLBACK_DAY_START to FALLBACK_DAY_END
        return start to end
    }

    /** 本地时间的「当天第几分钟」 */
    private fun localMinutesOfDay(nowMs: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun wrap24(h: Double): Double {
        var v = h % 24.0
        if (v < 0) v += 24.0
        return v
    }

    /**
     * 日出/日落时刻，返回**UTC 分钟数**（0~1440），算不出返回 null。
     */
    private fun sunEventUtcMinutes(
        lat: Double,
        lng: Double,
        dayOfYear: Int,
        sunrise: Boolean
    ): Double? {
        val gamma = 2.0 * PI / 365.0 * (dayOfYear - 1)
        val eqTime = 229.18 * (
            0.000075 +
                0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
            )
        val decl = 0.006918 -
            0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)

        val latRad = Math.toRadians(lat)
        val cosHa = cos(Math.toRadians(ZENITH)) / (cos(latRad) * cos(decl)) -
            tan(latRad) * tan(decl)
        // 极昼（>1）/ 极夜（<-1）：当天没有日出或日落
        if (cosHa > 1.0 || cosHa < -1.0) return null
        val haDeg = Math.toDegrees(acos(cosHa))

        val minutes = if (sunrise) {
            720.0 - 4.0 * (lng + haDeg) - eqTime
        } else {
            720.0 - 4.0 * (lng - haDeg) - eqTime
        }
        return if (minutes.isNaN() || abs(minutes) > 10_000) null else minutes
    }
}
