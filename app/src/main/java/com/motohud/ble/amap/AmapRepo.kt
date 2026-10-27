package com.motohud.ble.amap

import android.content.Context
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip
import com.amap.api.services.core.PoiItem
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.amap.api.services.route.DrivePath
import com.amap.api.services.route.DriveStep
import com.amap.api.services.route.RouteSearch
import com.motohud.ble.navi.RouteData
import com.motohud.ble.navi.RouteStep
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * POI 结果项（输入提示与搜索结果共用）。
 *
 * [distanceM] 是**本地算的直线距离**（起点 = 当前定位）。
 * 高德的 Inputtips 接口不返回距离字段，所以联想层只能自己算；
 * PoiSearch 自带 distance，直接透传。距离为 null 表示"没有定位，算不了"。
 */
data class TipItem(
    val name: String,
    val address: String,
    val district: String,
    val latitude: Double,
    val longitude: Double,
    val distanceM: Int? = null
)

/** 路线规划结果 */
sealed class RoutePlanResult {
    data class Success(val route: RouteData) : RoutePlanResult()

    /** 多方案（路线选择页用） */
    data class Many(val routes: List<RouteData>) : RoutePlanResult()

    data class Failure(val message: String) : RoutePlanResult()
}

/** POI 提示结果（区分"无结果"与"服务报错"，便于提示 Key/权限问题） */
sealed class TipsResult {
    data class Ok(val items: List<TipItem>) : TipsResult()
    data class Err(val code: Int) : TipsResult()
}

/**
 * 高德搜索封装：POI 输入提示 + 驾车路线规划。
 *
 * 调用方需先通过 [AmapManager.initSearch] 完成 Key 与隐私合规。
 * 所有方法均为挂起函数，内部使用 SDK 的异步回调。
 */
class AmapRepo(private val context: Context) {

    /**
     * POI 输入提示（专业导航软件同款搜索体验）。
     * @param city 城市名或 adcode；空串 = 全国范围
     */
    suspend fun searchTips(
        keyword: String,
        city: String = "",
        origin: Pair<Double, Double>? = null
    ): TipsResult {
        if (keyword.isBlank()) return TipsResult.Ok(emptyList())
        return suspendCancellableCoroutine { cont ->
            try {
                val query = InputtipsQuery(keyword.trim(), city).apply {
                    setCityLimit(false)
                }
                val inputtips = Inputtips(context, query)
                inputtips.setInputtipsListener { tips, rCode ->
                    if (rCode == 1000) {
                        val items = tips.orEmpty().mapNotNull { it.toItem(origin) }
                        cont.resume(TipsResult.Ok(items))
                    } else {
                        // 10001 INVALID_USER_KEY / 10009 未开通服务 等
                        cont.resume(TipsResult.Err(rCode))
                    }
                }
                inputtips.requestInputtipsAsyn()
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

    /**
     * 单条路线规划（第一条）。
     *
     * @param strategy 高德驾车策略（`RouteSearch.Driving*` / `DRIVING_MULTI_*` 常量）
     * @param modeLabel 写进结果的模式描述，供界面显示
     */
    suspend fun planDriveRoute(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        strategy: Int = RouteSearch.DrivingDefault,
        modeLabel: String = ""
    ): RoutePlanResult {
        val paths = requestPaths(fromLat, fromLng, toLat, toLng, strategy)
        return when {
            paths.first != 1000 -> RoutePlanResult.Failure(errorText(paths.first))
            paths.second.isEmpty() -> RoutePlanResult.Failure(errorText(1000))
            else -> RoutePlanResult.Success(paths.second.first().toRouteData(modeLabel))
        }
    }

    /**
     * 多方案路线规划 —— 「先选路线，再导航」的数据来源。
     *
     * 用 `DRIVING_MULTI_CHOICE_*` 策略请求，高德会一次返回 2~3 套方案
     * （速度最快 / 距离最短 / 费用最低 …），这里全部转成 [RouteData]，
     * 由路线选择页展示给用户挑。
     *
     * @param limit 最多返回几条
     */
    suspend fun planCandidates(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        strategy: Int = RouteSearch.DRIVING_MULTI_STRATEGY_FASTEST_SHORTEST_AVOID_CONGESTION,
        modeLabel: String = "",
        limit: Int = 3
    ): RoutePlanResult {
        val paths = requestPaths(fromLat, fromLng, toLat, toLng, strategy)
        if (paths.first != 1000) return RoutePlanResult.Failure(errorText(paths.first))
        val list = paths.second
        if (list.isEmpty()) return RoutePlanResult.Failure(errorText(1000))
        val routes = list.take(limit).mapIndexed { index, p ->
            p.toRouteData(modeLabel, fallbackLabel = if (index == 0) "推荐" else "方案 ${index + 1}")
        }
        return if (routes.size == 1) RoutePlanResult.Many(routes) else RoutePlanResult.Many(routes)
    }

    /** 发起一次驾车算路，返回 (rCode, paths) */
    private suspend fun requestPaths(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        strategy: Int
    ): Pair<Int, List<DrivePath>> {
        return suspendCancellableCoroutine { cont ->
            try {
                val routeSearch = RouteSearch(context)
                val fromAndTo = RouteSearch.FromAndTo(
                    LatLonPoint(fromLat, fromLng),
                    LatLonPoint(toLat, toLng)
                )
                val query = RouteSearch.DriveRouteQuery(fromAndTo, strategy, null, null, "")
                routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                    override fun onDriveRouteSearched(
                        result: com.amap.api.services.route.DriveRouteResult?,
                        rCode: Int
                    ) {
                        cont.resume(rCode to result?.paths.orEmpty())
                    }

                    override fun onBusRouteSearched(
                        result: com.amap.api.services.route.BusRouteResult?,
                        rCode: Int
                    ) = Unit

                    override fun onWalkRouteSearched(
                        result: com.amap.api.services.route.WalkRouteResult?,
                        rCode: Int
                    ) = Unit

                    override fun onRideRouteSearched(
                        result: com.amap.api.services.route.RideRouteResult?,
                        rCode: Int
                    ) = Unit
                })
                routeSearch.calculateDriveRouteAsyn(query)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

    /** 把错误码翻译成用户能看懂的话，不暴露内部编号 */
    private fun errorText(code: Int): String = when (code) {
        1002 -> "网络异常，请检查网络后重试"
        1003 -> "路线服务暂时不可用，请稍后重试"
        1804 -> "起点与终点距离过近"
        1806 -> "起点或终点不在可规划区域内"
        1000 -> "未能规划出可用路线，请换个目的地试试"
        else -> "路线规划失败，请稍后重试"
    }

    /**
     * POI 搜索结果（回车 / 点搜索时用）。
     *
     * 为什么联想层和结果层必须分两套接口：
     *  - `Inputtips` 快、轻，但**没有 distance 字段**，只能本地自算；
     *  - `PoiSearch` 有 distance、能分页、能按类型筛，但每次请求更重。
     * 原先两者混用（只用 Inputtips 就当成结果列表），导致同名地点只能按
     * 服务端相关性排序、跨城市混排 —— 搜"万达广场"排在前面的是外地那个。
     */
    /**
     * 逆地理编码：坐标 → 地址。
     *
     * 用途：地图长按选点、点地图上的 POI 时，屏幕上的一个点只有经纬度，
     * 必须翻译成人能看懂的地址才有意义（也才能拿去收藏）。
     *
     * 这里只取「格式化地址 + 区县」两个字段，不取 POI 列表 ——
     * 用户要的是"这是哪儿"，不是"附近有哪些店"。
     */
    suspend fun reverseGeocode(lat: Double, lng: Double): TipItem? {
        return runCatching {
            suspendCancellableCoroutine { cont ->
                val query = RegeocodeQuery(
                    com.amap.api.services.core.LatLonPoint(lat, lng), 200f, GeocodeSearch.AMAP
                )
                val search = GeocodeSearch(context)
                search.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                    override fun onRegeocodeSearched(result: RegeocodeResult?, rCode: Int) {
                        val addr = result?.regeocodeAddress
                        val text = addr?.formatAddress.orEmpty()
                        if (rCode == 1000 && addr != null && text.isNotBlank()) {
                            cont.resume(
                                TipItem(
                                    name = addr.township?.takeIf { it.isNotBlank() }
                                        ?: text.take(12),
                                    address = text,
                                    district = addr.district.orEmpty(),
                                    latitude = lat,
                                    longitude = lng
                                )
                            )
                        } else {
                            cont.resume(null)
                        }
                    }

                    override fun onGeocodeSearched(result: GeocodeResult?, rCode: Int) = Unit
                })
                search.getFromLocationAsyn(query)
            }
        }.getOrNull()
    }

    suspend fun searchPoi(
        keyword: String,
        city: String = "",
        origin: Pair<Double, Double>? = null,
        pageNum: Int = 1,
        pageSize: Int = 20
    ): TipsResult {
        if (keyword.isBlank()) return TipsResult.Ok(emptyList())
        return suspendCancellableCoroutine { cont ->
            try {
                val query = PoiSearch.Query(keyword.trim(), "", city).apply {
                    this.pageSize = pageSize
                    this.pageNum = pageNum
                    // 不设城市硬限制：用户出差搜外地地址仍要能搜到，
                    // 本地结果会因为距离近自然排到前面
                    setCityLimit(false)
                }
                val search = PoiSearch(context, query)
                search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                    override fun onPoiSearched(result: PoiResult?, rCode: Int) {
                        if (rCode == 1000) {
                            val items = result?.pois.orEmpty().map { it.toItem(origin) }
                            cont.resume(TipsResult.Ok(items))
                        } else {
                            android.util.Log.w("AmapRepo", "PoiSearch rCode=$rCode kw=$keyword")
                            cont.resume(TipsResult.Err(rCode))
                        }
                    }

                    @Deprecated("旧版回调")
                    override fun onPoiItemSearched(item: PoiItem?, rCode: Int) = Unit
                })
                search.searchPOIAsyn()
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

    // ---------- 转换 ----------

    private fun Tip.toItem(origin: Pair<Double, Double>?): TipItem? {
        val p = point ?: return null
        return TipItem(
            name = name.orEmpty(),
            address = address.orEmpty(),
            district = district.orEmpty(),
            latitude = p.latitude,
            longitude = p.longitude,
            distanceM = origin?.let { haversineM(it.first, it.second, p.latitude, p.longitude) }
        )
    }

    private fun PoiItem.toItem(origin: Pair<Double, Double>?): TipItem {
        val p = latLonPoint
        val dist = when {
            p != null && origin != null -> haversineM(origin.first, origin.second, p.latitude, p.longitude)
            distance > 0 -> distance
            else -> 0
        }
        return TipItem(
            name = title.orEmpty(),
            address = snippet.orEmpty(),
            district = cityName.orEmpty().ifBlank { adName.orEmpty() },
            latitude = p?.latitude ?: 0.0,
            longitude = p?.longitude ?: 0.0,
            distanceM = if (dist > 0) dist else null
        )
    }

    /** 球面距离（米）。联想层补距离用，误差 <0.5%，足够排序 */
    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        return (r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))).toInt()
    }

    private fun DrivePath.toRouteData(
        modeLabel: String = "",
        fallbackLabel: String = ""
    ): RouteData {
        val steps = this.steps.orEmpty().map { it.toRouteStep() }
        val label = strategy?.takeIf { it.isNotBlank() } ?: fallbackLabel
        return RouteData(
            distanceM = distance,
            durationS = duration.toFloat(),
            steps = steps,
            modeLabel = modeLabel,
            label = label,
            tolls = tolls,
            trafficLights = totalTrafficlights
        )
    }

    private fun DriveStep.toRouteStep(): RouteStep {
        // TMC 实时拥堵分段：getStatus() 返回中文串（畅通/缓行/拥堵/严重拥堵），
        // 累加含"拥堵"分段里程，作为"前方拥堵"统计与自动换线依据。
        val congestedM = getTMCs().orEmpty()
            .filter { it.status?.contains("拥堵") == true }
            .sumOf { it.distance.toDouble() }
            .toFloat()
        return RouteStep(
            instruction = instruction.orEmpty(),
            action = action.orEmpty(),
            orientation = orientation.orEmpty(),
            road = road.orEmpty(),
            distanceM = distance,
            polyline = polyline.orEmpty().map { it.latitude to it.longitude },
            congestedM = congestedM
        )
    }
}
