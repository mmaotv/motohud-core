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
    val distanceM: Int? = null,
    /** 逆地理编码得到的行政区划 adcode（如 "500103"），用于给搜索做城市偏置；POI 结果恒为 null */
    val adCode: String? = null
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
 * 算路的附加约束 —— 对应「自定义路线」三件套（见《摩托车导航方案-轻量版》）。
 *
 * 三者的分工必须说清楚，否则容易以为只配途经点就够了：
 *  - [waypoints] 途经点：只保证**经过**这些点，两点之间怎么走仍由高德选；
 *  - [avoidPolygons] 避让区：约束**中间段**绕开某些块（补途经点管不到的地方）；
 *  - [excludeMotorway] 排除高速/快速路：全局兜底。
 *
 * 坐标一律 GCJ-02（高德搜索 SDK 的入参坐标系），与 AmapLocator / 地图选点一致。
 */
data class RouteQueryOptions(
    val waypoints: List<Pair<Double, Double>> = emptyList(),
    val avoidPolygons: List<List<Pair<Double, Double>>> = emptyList(),
    val excludeMotorway: Boolean = false
) {
    val isEmpty: Boolean
        get() = waypoints.isEmpty() && avoidPolygons.isEmpty() && !excludeMotorway

    companion object {
        /**
         * 途经点上限 5 个。
         *
         * 这不是高德的上限（SDK 允许更多），而是**体验上限**：途经点越多，
         * 算路越慢、路线越绕、界面上也放不下。骑手通常只需要 1~2 个"必须经过的路口"。
         */
        const val MAX_WAYPOINTS = 5

        /** 高德限制：避让区最多 100 个，这里按实际可用性收得更紧 */
        const val MAX_AVOID_ZONES = 20
    }
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
     *
     * @param city 城市名或 adcode；空串 = 全国范围（但配合 [center] 仍会按距离偏置）
     * @param origin 用于本地补算直线距离的起点（当前定位），不参与请求
     * @param center 搜索地理中心。非 null 时通过 [InputtipsQuery.setLocation] 让联想结果
     *              向该点聚拢 —— 这是"搜离我最近的地方"的关键，否则高德默认全国范围、
     *              相关性最高的结果往往在别的大城市（典型表现为"结果全跑到北京"）。
     */
    suspend fun searchTips(
        keyword: String,
        city: String = "",
        origin: Pair<Double, Double>? = null,
        center: Pair<Double, Double>? = null
    ): TipsResult {
        if (keyword.isBlank()) return TipsResult.Ok(emptyList())
        return suspendCancellableCoroutine { cont ->
            try {
                val query = InputtipsQuery(keyword.trim(), city).apply {
                    setCityLimit(city.isNotBlank())
                    if (center != null) setLocation(LatLonPoint(center.first, center.second))
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
        modeLabel: String = "",
        options: RouteQueryOptions = RouteQueryOptions()
    ): RoutePlanResult {
        val paths = requestPaths(fromLat, fromLng, toLat, toLng, strategy, options)
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
        limit: Int = 3,
        options: RouteQueryOptions = RouteQueryOptions()
    ): RoutePlanResult {
        val paths = requestPaths(fromLat, fromLng, toLat, toLng, strategy, options)
        if (paths.first != 1000) return RoutePlanResult.Failure(errorText(paths.first))
        val list = paths.second
        if (list.isEmpty()) return RoutePlanResult.Failure(errorText(1000))
        val routes = list.take(limit).mapIndexed { index, p ->
            p.toRouteData(modeLabel, fallbackLabel = if (index == 0) "推荐" else "方案 ${index + 1}")
        }
        return if (routes.size == 1) RoutePlanResult.Many(routes) else RoutePlanResult.Many(routes)
    }

    /**
     * 发起一次驾车算路，返回 (rCode, paths)。
     *
     * 三个「自定义路线」参数以前全传 `null` / `""`（等于没接），现在按
     * [RouteQueryOptions] 填入。注意高德的两个硬限制：
     *  - `avoidpolygons` 最多 **100 个区域、每区 16 个点**，超了直接算不出；
     *  - 避让区面积过大会让算路返回空（用户会以为"导航坏了"），
     *    所以调用方必须先做面积校验，见 `AvoidZoneStore.checkPolygon`。
     */
    private suspend fun requestPaths(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        strategy: Int,
        options: RouteQueryOptions = RouteQueryOptions()
    ): Pair<Int, List<DrivePath>> {
        return suspendCancellableCoroutine { cont ->
            try {
                val routeSearch = RouteSearch(context)
                val fromAndTo = RouteSearch.FromAndTo(
                    LatLonPoint(fromLat, fromLng),
                    LatLonPoint(toLat, toLng)
                )
                val passedBy = options.waypoints
                    .take(RouteQueryOptions.MAX_WAYPOINTS)
                    .map { LatLonPoint(it.first, it.second) }
                    .takeIf { it.isNotEmpty() }
                val avoid = options.avoidPolygons
                    .take(RouteQueryOptions.MAX_AVOID_ZONES)
                    .map { poly -> poly.map { LatLonPoint(it.first, it.second) } }
                    .filter { it.size >= 3 }
                    .takeIf { it.isNotEmpty() }
                val query = RouteSearch.DriveRouteQuery(fromAndTo, strategy, passedBy, avoid, "")
                // 显式按道路等级排除高速/快速路，与 strategy 的 no_highway 双保险
                if (options.excludeMotorway) {
                    query.setExclude(RouteSearch.DRIVING_EXCLUDE_MOTORWAY)
                }
                routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                    override fun onDriveRouteSearched(
                        result: com.amap.api.services.route.DriveRouteResult?,
                        rCode: Int
                    ) {
                        // 途经点/避让区参数出错时高德只回一个错误码，不打日志根本查不出
                        // 「设置了没反应」到底是没生效还是算不出路
                        android.util.Log.i(
                            "AmapRepo",
                            "算路 rCode=$rCode 方案=${result?.paths?.size ?: 0} " +
                                "途经=${passedBy?.size ?: 0} 避让=${avoid?.size ?: 0}"
                        )
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
                                    longitude = lng,
                                    adCode = addr.adCode?.takeIf { it.isNotBlank() }
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
        center: Pair<Double, Double>? = null,
        /** 搜索半径（米）。中心点 30km 范围内召回；北京（上千公里外）自然被排除，
         *  这正是修复"结果迁移到北京"的核心：给高德一个明确的地理围栏。 */
        radiusM: Int = 30_000,
        pageNum: Int = 1,
        pageSize: Int = 20
    ): TipsResult {
        if (keyword.isBlank()) return TipsResult.Ok(emptyList())
        return suspendCancellableCoroutine { cont ->
            try {
                val query = PoiSearch.Query(keyword.trim(), "", city).apply {
                    this.pageSize = pageSize
                    this.pageNum = pageNum
                    // 不设城市硬限制：用户出差搜外地地址仍要能搜到（bound 只在有 center 时生效，
                    // 且只约束"以 center 为圆心的搜索范围"，不影响按名称精确搜外地点）。
                    setCityLimit(city.isNotBlank())
                    if (center != null) setLocation(LatLonPoint(center.first, center.second))
                }
                val search = PoiSearch(context, query)
                // 地理围栏：把搜索结果限制在中心点附近，彻底避免"全国搜索默认落到北京"
                if (center != null) {
                    search.setBound(
                        PoiSearch.SearchBound(LatLonPoint(center.first, center.second), radiusM)
                    )
                }
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

    /**
     * 沿途 POI 搜索（顺路搜）。
     *
     * 沿给定路线走廊（[polyline]，GCJ-02 路线点）查找指定类型 POI，是"顺路加油 / 顺路充电"
     * 这类需求的标准做法：高德 [RoutePOISearch] 直接按路线做空间召回，结果自带"离路线距离"。
     *
     * @param types 一个或多个 POI 类型（"全部"传多种类型会合并去重）
     * @param radiusM 沿线搜索半径（米），默认 1000
     * @param keyword 可选的名称关键字过滤（客户端按标题过滤）
     */
    suspend fun routePoiSearch(
        polyline: List<Pair<Double, Double>>,
        types: List<com.amap.api.services.routepoisearch.RoutePOISearch.RoutePOISearchType>,
        radiusM: Int = 1000,
        keyword: String = ""
    ): TipsResult {
        if (polyline.size < 2) return TipsResult.Ok(emptyList())
        val pts = polyline.map { LatLonPoint(it.first, it.second) }
        val merged = LinkedHashMap<String, com.amap.api.services.routepoisearch.RoutePOIItem>()
        for (t in types) {
            for (item in oneRoutePoi(pts, t, radiusM)) {
                val id = item.getID().orEmpty()
                if (id.isNotBlank()) merged.putIfAbsent(id, item)
            }
        }
        val filtered = if (keyword.isNotBlank()) {
            merged.values.filter { it.getTitle().orEmpty().contains(keyword, true) }
        } else {
            merged.values.toList()
        }
        val items = filtered.map { it.toRouteTip() }
            .sortedBy { it.distanceM ?: Int.MAX_VALUE }
        return TipsResult.Ok(items)
    }

    private suspend fun oneRoutePoi(
        pts: List<LatLonPoint>,
        type: com.amap.api.services.routepoisearch.RoutePOISearch.RoutePOISearchType,
        radiusM: Int
    ): List<com.amap.api.services.routepoisearch.RoutePOIItem> =
        suspendCancellableCoroutine { cont ->
            try {
                val query = com.amap.api.services.routepoisearch.RoutePOISearchQuery(pts, type, radiusM)
                val search = com.amap.api.services.routepoisearch.RoutePOISearch(context, query)
                search.setPoiSearchListener { result, rCode ->
                    if (rCode == 1000 && result != null) {
                        cont.resume(result.routePois.orEmpty())
                    } else {
                        cont.resume(emptyList())
                    }
                }
                search.searchRoutePOIAsyn()
            } catch (e: Exception) {
                cont.resume(emptyList())
            }
        }

    private fun com.amap.api.services.routepoisearch.RoutePOIItem.toRouteTip(): TipItem {
        val p = point
        val detour = if (distance > 0f) distance.toInt() else null
        return TipItem(
            name = getTitle().orEmpty(),
            address = if (detour != null) "离路线约 ${detour} 米" else "沿途",
            district = "",
            latitude = p?.latitude ?: 0.0,
            longitude = p?.longitude ?: 0.0,
            distanceM = detour,
            adCode = null
        )
    }

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
