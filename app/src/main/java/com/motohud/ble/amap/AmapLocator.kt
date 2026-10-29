package com.motohud.ble.amap

import android.content.Context
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 高德连续定位封装。
 *
 * 为什么不用系统的 LocationManager 直接导航：
 *  1. 系统 GPS 给 WGS84，国内地图/路线是 GCJ02，差 300~700 米；
 *  2. 网络定位点精度差、抖动大，且不带速度/航向；
 *  3. 高德定位 SDK 内部做了 GCJ02 纠偏、多源融合、缓存与传感器辅助，
 *     是地图蓝点与路线匹配的同一套坐标基准。
 *
 * 三个开关的取舍：
 *  - Hight_Accuracy + GPS 优先：骑行导航要有真实速度与航向，必须优先卫星；
 *  - sensorEnable：隧道/高楼间用陀螺仪补航向，避免红点乱转；
 *  - mockEnable：正式包固定 false（拒绝模拟位置，防止被第三方定位软件污染）；
 *    debug 包放开，便于用 `cmd location set-test-provider-location` 做路线/漂移联调。
 */
class AmapLocator(private val context: Context) {

    data class Fix(
        val lat: Double,
        val lng: Double,
        val accuracy: Float,
        val bearing: Float,
        val speedMps: Float,
        /** 定位来源：1=GPS 2=前次 4=缓存 5=离线 6=网络 8=高精度融合 */
        val locationType: Int,
        /** 反地理编码地址（仅单次定位请求，连续定位为 null） */
        val address: String? = null,
        /**
         * GPS 信号质量（地图/定位 SDK 11.x 才有）：1=好 0=差 -1=未知。
         *
         * 比只看 [accuracy] 更可信 —— 高德是拿卫星数、信噪比、速度一致性综合判的，
         * 而 `accuracy` 在某些机型上会被网络定位"美化"成 5 米，实际是基站粗定位。
         */
        val gpsAccuracyStatus: Int = AMapLocation.GPS_ACCURACY_UNKNOWN
    ) {
        val fromSatellite: Boolean get() = locationType == 1 || locationType == 8

        /** 是否来自本地缓存（上一次定位结果，可能已经是很久以前的位置） */
        val isCache: Boolean get() = locationType == 4

        /**
         * 信号是否够用：卫星定位且高德判定不是"差"。
         *
         * 三档判断，避免把"网络定位的 5 米"当成好信号：
         *  1. 官方给了 GOOD → 直接好；
         *  2. 官方给了 BAD  → 直接差（哪怕 accuracy 显示很好）；
         *  3. 官方未知（老定位源/非卫星帧）→ 回退到 accuracy 阈值。
         */
        val signalWeak: Boolean
            get() = when {
                !fromSatellite -> true
                gpsAccuracyStatus == AMapLocation.GPS_ACCURACY_GOOD -> false
                gpsAccuracyStatus == AMapLocation.GPS_ACCURACY_BAD -> true
                else -> accuracy > 40f
            }
    }

    private var client: AMapLocationClient? = null
    private var running = false

    /** 是否可用（已授权隐私 + 已配置 Key） */
    fun isAvailable(): Boolean = AmapManager.initLocation(context)

    /**
     * 开始连续定位，把每帧通过 [onFix] 回调（主线程），仅 errCode == 0 时触发。
     *
     * 地图蓝点（AMap.setLocationSource）需要持续的位置流，
     * 所以这与 [locateOnce] 分开：后者是一次性的新鲜定位，用于"刷新定位"。
     */
    fun start(onFix: (Fix) -> Unit, onError: ((Int) -> Unit)? = null) {
        if (running) stop()
        if (!isAvailable()) return
        val c = runCatching { AMapLocationClient(context.applicationContext) }.getOrNull() ?: return
        runCatching {
            c.setLocationOption(
                AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    // 1 秒一次，与导航引擎 tick 对齐
                    interval = 1000L
                    isNeedAddress = false
                    isOnceLocation = false
                    isOnceLocationLatest = false
                    isMockEnable = com.motohud.ble.BuildConfig.DEBUG
                    isLocationCacheEnable = true
                    isGpsFirst = true
                    gpsFirstTimeout = 15_000L
                    isSensorEnable = true
                    httpTimeOut = 10_000L
                }
            )
            c.setLocationListener { loc: AMapLocation? ->
                if (loc == null) return@setLocationListener
                if (loc.errorCode != 0) {
                    onError?.invoke(loc.errorCode)
                    return@setLocationListener
                }
                // coordType 正常为 GCJ02；万一是 WGS84（境外/特殊机型）就地纠偏
                val isWgs = loc.coordType.equals("WGS84", ignoreCase = true)
                val (lat, lng) = if (isWgs) {
                    com.motohud.ble.navi.CoordConverter.wgs84ToGCJ(loc.latitude, loc.longitude)
                } else {
                    loc.latitude to loc.longitude
                }
                onFix(
                    Fix(
                        lat = lat,
                        lng = lng,
                        accuracy = loc.accuracy,
                        bearing = loc.bearing,
                        speedMps = loc.speed,
                        locationType = loc.locationType,
                        gpsAccuracyStatus = runCatching { loc.gpsAccuracyStatus }
                            .getOrDefault(AMapLocation.GPS_ACCURACY_UNKNOWN)
                    )
                )
            }
            c.startLocation()
            client = c
            running = true
        }.onFailure {
            runCatching { c.onDestroy() }
        }
    }

    /**
     * 单次定位（导航页「刷新定位」与浮动定位按钮用）。
     *
     * 与连续定位的关键差异：
     *  - `isLocationCacheEnable = false`：**不要缓存结果**。高德默认会先把上一次
     *    的定位（可能是几小时前、甚至别的城市）当第一帧回调回来，界面就会
     *    "人在重庆却显示北京"。单次定位必须是新鲜值；
     *  - `isNeedAddress = true`：带回城市/街道，界面直接显示
     *    "重庆市渝北区龙山街道"，用户一眼能判断对不对；
     *  - 超时兜底，避免一直转圈。
     */
    suspend fun locateOnce(timeoutMs: Long = 9000L): Fix? {
        if (!isAvailable()) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Fix?> { cont ->
                var finished = false
                var client: AMapLocationClient? = null

                fun cleanup() = runCatching {
                    client?.stopLocation()
                    client?.onDestroy()
                }.let { }

                fun finish(value: Fix?) {
                    if (finished) return
                    finished = true
                    cleanup()
                    if (cont.isActive) cont.resume(value)
                }

                val c = runCatching { AMapLocationClient(context.applicationContext) }.getOrNull()
                if (c == null) {
                    finish(null)
                    return@suspendCancellableCoroutine
                }
                client = c
                runCatching {
                    c.setLocationOption(
                        AMapLocationClientOption().apply {
                            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                            isOnceLocation = true
                            isOnceLocationLatest = true
                            isNeedAddress = true
                            isMockEnable = com.motohud.ble.BuildConfig.DEBUG
                            isLocationCacheEnable = false
                            isGpsFirst = false
                            httpTimeOut = 8000L
                        }
                    )
                    c.setLocationListener { loc: AMapLocation? ->
                        if (loc == null || loc.errorCode != 0) {
                            finish(null)
                            return@setLocationListener
                        }
                        val isWgs = loc.coordType.equals("WGS84", ignoreCase = true)
                        val (lat, lng) = if (isWgs) {
                            com.motohud.ble.navi.CoordConverter.wgs84ToGCJ(
                                loc.latitude, loc.longitude
                            )
                        } else {
                            loc.latitude to loc.longitude
                        }
                        finish(
                            Fix(
                                lat = lat,
                                lng = lng,
                                accuracy = loc.accuracy,
                                bearing = loc.bearing,
                                speedMps = loc.speed,
                                locationType = loc.locationType,
                                address = buildAddress(loc),
                                gpsAccuracyStatus = runCatching { loc.gpsAccuracyStatus }
                                    .getOrDefault(AMapLocation.GPS_ACCURACY_UNKNOWN)
                            )
                        )
                    }
                    c.startLocation()
                }.onFailure { finish(null) }

                cont.invokeOnCancellation { cleanup() }
            }
        }
    }

    /** 把省市区街道拼成一句可读地址（去重，避免"北京市北京市"） */
    private fun buildAddress(loc: AMapLocation): String? {
        val parts = mutableListOf<String>()
        listOf(loc.province, loc.city, loc.district, loc.street, loc.streetNum, loc.poiName)
            .filterNotNull()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { p -> if (parts.none { it == p || it.endsWith(p) }) parts.add(p) }
        val joined = parts.joinToString("")
        if (joined.isNotBlank()) return joined
        return loc.address?.takeIf { it.isNotBlank() }
    }

    fun stop() {
        running = false
        runCatching {
            client?.stopLocation()
            client?.onDestroy()
        }
        client = null
    }
}

/** 定位类型的人类可读标签（导航页状态位展示） */
fun locationTypeLabel(type: Int): String = when (type) {
    1 -> "GPS"
    2 -> "GPS"
    4 -> "缓存"
    5 -> "离线"
    6 -> "网络"
    8 -> "融合"
    else -> "定位"
}

/** 精度分档：用于界面上"信号弱"提示 */
fun accuracyLevel(accuracy: Float): Int = when {
    accuracy <= 0f -> 0
    accuracy <= 15f -> 3   // 好
    accuracy <= 40f -> 2   // 一般
    else -> 1              // 弱
}

/** 精度显示文案 */
fun formatAccuracy(accuracy: Float): String =
    if (accuracy <= 0f) "--" else "±${accuracy.roundToInt()}m"
