package com.motohud.ble.amap

import android.content.Context
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng

/**
 * WGS84 → GCJ02 纠偏，走高德官方的 [CoordinateConverter]。
 *
 * 为什么不再自己写公式：`navi/CoordConverter.wgs84ToGCJ` 是按国测局公开公式
 * 手抄的一份，当年是为了「不依赖 Context 也能在纯逻辑层转换」。实际用下来
 * 有两个隐患：
 *  1. 官方 [CoordinateConverter] 还会做 [isAMapDataAvailable] 的可用区判断
 *     （境外/港澳台不偏移、部分边界区域有特殊处理），手抄版只有一条
 *     `outOfChina` 的粗矩形判断；
 *  2. 公式里的参数一旦国测局微调，手抄版不会跟着变，官方实现会。
 *
 * 调用点很杂（定位回调、导航引擎 1Hz 的位置回调、起点纠偏），不可能每处都传
 * Context，所以用 [init] 在 Application 启动时喂一次 —— 之后 `gpsToGcj` 不需要
 * 再带 Context。
 *
 * 拿不到 Context（理论上只有单测/纯逻辑层）时退回本地公式，
 * 保证调用方永远拿到一个可用值，不会因为初始化顺序出空指针。
 */
object CoordFix {

    private var appContext: Context? = null

    /** CoordinateConverter 内部有可变状态，非线程安全；定位回调可能来自不同线程 */
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 是否已经能走官方实现（否则在用本地兜底公式） */
    val usingSdk: Boolean get() = appContext != null

    /**
     * 系统 GPS 的 WGS84 坐标 → 高德 GCJ02。
     *
     * 境外坐标不偏移（高德在境外用的是原始 WGS84，偏移反而会错位）。
     */
    fun gpsToGcj(lat: Double, lng: Double): Pair<Double, Double> {
        if (lat == 0.0 && lng == 0.0) return lat to lng
        val ctx = appContext
            ?: return com.motohud.ble.navi.CoordConverter.wgs84ToGCJ(lat, lng)
        // 该点是否在高德有数据覆盖的范围内：境外/远海不纠偏
        val available = runCatching {
            CoordinateConverter.isAMapDataAvailable(lat, lng)
        }.getOrDefault(false)
        if (!available) return lat to lng
        return synchronized(lock) {
            runCatching {
                val p = CoordinateConverter(ctx)
                    .from(CoordinateConverter.CoordType.GPS)
                    .coord(LatLng(lat, lng))
                    .convert()
                // 极端情况下 SDK 会回 (0,0)/NaN，宁可要原始值也不能把定位弄没
                if (p.latitude == 0.0 || p.longitude == 0.0 ||
                    p.latitude.isNaN() || p.longitude.isNaN()
                ) {
                    lat to lng
                } else {
                    p.latitude to p.longitude
                }
            }.getOrElse { lat to lng }
        }
    }
}
