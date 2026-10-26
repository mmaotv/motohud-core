package com.motohud.ble.ui.navi

import android.app.Application
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motohud.ble.MotoHudApp
import com.motohud.ble.amap.AmapCache
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.amap.AmapRepo
import com.motohud.ble.amap.RoutePlanResult
import com.motohud.ble.amap.TipItem
import com.motohud.ble.data.SavedPlaceEntity
import com.motohud.ble.navi.CoordConverter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 导航页 ViewModel：定位 / 常用地址 / POI 搜索 / 路线规划。
 */
class NavigationViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = (app as MotoHudApp).savedPlaceDao
    private val locationManager =
        app.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
    private val amapRepo = AmapRepo(app)

    /** 常用地址列表（Flow 自动更新） */
    val places: StateFlow<List<SavedPlaceEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前定位（GCJ02 纬度, 经度），null = 未获取 */
    var currentLocation: Pair<Double, Double>? = null
        private set

    /**
     * 当前位置流（GCJ02 纬度, 经度 + 精度）。
     * 地图上的定位点要跟着它动，所以必须是可观察的。
     */
    private val _locationFlow = MutableStateFlow<LocationFix?>(null)
    val locationFlow: StateFlow<LocationFix?> = _locationFlow.asStateFlow()

    /** 一次定位结果 */
    data class LocationFix(
        val lat: Double,
        val lng: Double,
        val accuracy: Float
    )

    private val _locationLabel = MutableStateFlow("")
    val locationLabel: StateFlow<String> = _locationLabel.asStateFlow()

    /** 正在定位（界面显示进度） */
    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    /** 当前定位精度（米），<=0 表示未知 */
    private val _accuracy = MutableStateFlow(0f)
    val accuracy: StateFlow<Float> = _accuracy.asStateFlow()

    /** POI 搜索结果 */
    private val _tips = MutableStateFlow<List<TipItem>>(emptyList())
    val tips: StateFlow<List<TipItem>> = _tips.asStateFlow()

    private val _tipsState = MutableStateFlow(TipsState.IDLE)
    val tipsState: StateFlow<TipsState> = _tipsState.asStateFlow()

    /** 搜索失败时的服务错误码（10001=Key 无效，10009=未开通服务…） */
    private val _tipsErrorCode = MutableStateFlow(0)
    val tipsErrorCode: StateFlow<Int> = _tipsErrorCode.asStateFlow()

    enum class TipsState { IDLE, LOADING, DONE, EMPTY, ERROR }

    /** 高德功能是否可用（Key + 隐私合规） */
    val amapReady: Boolean
        get() = AmapManager.hasKey(getApplication()) && AmapManager.isPrivacyAgreed(getApplication())

    private var tipsJob: Job? = null

    private val locator = com.motohud.ble.amap.AmapLocator(app)

    private var locatingJob: Job? = null
    private var systemUpdatesOn = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // 系统定位只作兜底：高德定位已经拿到值时不再覆盖（两者坐标基准不同）
            if (currentLocation != null && !systemUpdatesOn) return
            val (lat, lng) = CoordConverter.wgs84ToGCJ(location.latitude, location.longitude)
            currentLocation = lat to lng
            _accuracy.value = location.accuracy
            _locationFlow.value = LocationFix(lat, lng, location.accuracy)
            _locationLabel.value = "当前位置 · ${CoordConverter.formatLatLng(lat, lng)}"
        }

        @Deprecated("旧版回调保留兼容")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    init {
        refreshLocation()
    }

    /**
     * 刷新当前位置。
     *
     * 旧实现只有"读系统最后已知位置"一条路，而 `getLastKnownLocation` 拿到的是
     * **系统缓存**，可能是几个小时前、甚至别的城市的位置，于是出现
     * "人在重庆，导航页却一直显示北京"。
     *
     * 现在的顺序：
     *  1. 系统缓存只作"占位"，且必须足够新（90 秒内），否则丢弃；
     *  2. 主动向高德定位 SDK 要一次**新鲜**定位（不走缓存），拿到后覆盖；
     *  3. 高德不可用 / 超时，才退回系统持续定位。
     *
     * @param force true = 用户点了刷新按钮，忽略"已在定位中"的拦截
     */
    fun refreshLocation(force: Boolean = false) {
        seedFromSystem()
        if (locatingJob?.isActive == true && !force) return
        locatingJob?.cancel()
        locatingJob = viewModelScope.launch {
            _locating.value = true
            val fix = runCatching { locator.locateOnce() }.getOrNull()
            _locating.value = false
            if (fix != null && !fix.isCache) {
                currentLocation = fix.lat to fix.lng
                _accuracy.value = fix.accuracy
                _locationFlow.value = LocationFix(fix.lat, fix.lng, fix.accuracy)
                _locationLabel.value = buildLabel(fix)
            } else if (currentLocation == null) {
                // 高德拿不到（无 Key / 无网络）→ 系统定位持续更新
                startSystemUpdates()
            }
        }
    }

    private fun buildLabel(fix: com.motohud.ble.amap.AmapLocator.Fix): String {
        val addr = fix.address?.trim().orEmpty()
        return if (addr.isNotEmpty()) {
            "当前位置 · $addr"
        } else {
            "当前位置 · ${CoordConverter.formatLatLng(fix.lat, fix.lng)}"
        }
    }

    /** 用系统最后已知位置占位；超过 90 秒的缓存直接丢弃 */
    @Suppress("MissingPermission")
    private fun seedFromSystem() {
        if (!hasLocationPermission()) return
        try {
            val best = listOfNotNull(
                locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ).maxByOrNull { it.time } ?: return
            val ageMs = System.currentTimeMillis() - best.time
            if (ageMs > 90_000L) return
            val (lat, lng) = CoordConverter.wgs84ToGCJ(best.latitude, best.longitude)
            currentLocation = lat to lng
            _accuracy.value = best.accuracy
            _locationFlow.value = LocationFix(lat, lng, best.accuracy)
            _locationLabel.value = "当前位置 · ${CoordConverter.formatLatLng(lat, lng)}"
        } catch (_: Exception) {
        }
    }

    /** 注册系统定位更新（仅在高德定位不可用时） */
    @Suppress("MissingPermission")
    private fun startSystemUpdates() {
        if (systemUpdatesOn || !hasLocationPermission()) return
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 2000L, 5f, locationListener
            )
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener
            )
            systemUpdatesOn = true
        } catch (_: Exception) {
        }
    }

    /** 是否有定位权限 */
    fun hasLocationPermission(): Boolean {
        val ctx = getApplication<MotoHudApp>()
        return listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ).any {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    // ---------- POI 搜索 ----------

    /**
     * POI 输入提示（输入中，300ms 防抖后调用）。
     *
     * ## 排序逻辑（这是"同名地点按距离排序"的核心）
     * 原先：`_tips.value = r.items`，直接把高德返回的数组顺序渲染，
     * 等于使用**服务端相关性排序**（POI 等级 + 热度 + 名称匹配度），
     * 跨城市混排 —— 搜"万达广场"，外地的可能排在本地前面。
     *
     * 现在：本地补距离（Inputtips 没有 distance 字段，用当前定位算球面距离）
     * 后按 `名称完全匹配(降序) → 距离(升序)` 排。
     * 同名地点必然近的在前；名称完全匹配的优先于"包含关键字"的。
     */
    fun searchTips(keyword: String) {
        tipsJob?.cancel()
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            _tips.value = emptyList()
            _tipsState.value = TipsState.IDLE
            return
        }
        // 一个字不出请求：联想召回基本无意义，却是输入过程中最密集的一批请求
        if (kw.length < 2) {
            _tips.value = emptyList()
            _tipsState.value = TipsState.IDLE
            return
        }
        if (!amapReady) {
            _tipsState.value = TipsState.ERROR
            return
        }
        _tipsState.value = TipsState.LOADING
        tipsJob = viewModelScope.launch {
            val origin = currentLocation
            // 同一个词 30 分钟内直接复用：退格重打、清空重输都不再发请求
            val key = AmapCache.tipsKey(kw, "", origin?.first, origin?.second)
            val cached = AmapCache.get<List<TipItem>>(key, AmapCache.TTL_TIPS_MS)
            if (cached != null) {
                val list = rank(kw, cached)
                _tips.value = list
                _tipsState.value = if (list.isEmpty()) TipsState.EMPTY else TipsState.DONE
                return@launch
            }
            val result = runCatching { amapRepo.searchTips(kw, "", origin) }
            result.onSuccess { r ->
                when (r) {
                    is com.motohud.ble.amap.TipsResult.Ok -> {
                        AmapCache.put(key, r.items)
                        val list = rank(kw, r.items)
                        _tips.value = list
                        _tipsState.value = if (list.isEmpty()) TipsState.EMPTY else TipsState.DONE
                    }
                    is com.motohud.ble.amap.TipsResult.Err -> {
                        _tips.value = emptyList()
                        _tipsErrorCode.value = r.code
                        _tipsState.value = TipsState.ERROR
                    }
                }
            }.onFailure {
                _tipsErrorCode.value = -1
                _tipsState.value = TipsState.ERROR
            }
        }
    }

    /**
     * POI 搜索结果（回车 / 点搜索时调用）。
     *
     * 换用 `PoiSearch`：它**自带 distance**、能分页、召回比 Inputtips 更全。
     * 只改前端排序救不回来 —— Inputtips 的召回本身受服务端相关度限制，
     * 如果它只返回 10 条外地同名点，本地排得再准也没用。
     */
    fun searchResults(keyword: String) {
        tipsJob?.cancel()
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        if (!amapReady) {
            _tipsState.value = TipsState.ERROR
            return
        }
        _tipsState.value = TipsState.LOADING
        tipsJob = viewModelScope.launch {
            val origin = currentLocation
            // POI 结果比联想更重（一页几十条带图片/评分字段），同一个词必须复用
            val key = AmapCache.poiKey(kw, "", origin?.first, origin?.second, 1, 20)
            val cached = AmapCache.get<List<TipItem>>(key, AmapCache.TTL_POI_MS)
            if (cached != null) {
                val list = rank(kw, cached)
                _tips.value = list
                _tipsState.value = if (list.isEmpty()) TipsState.EMPTY else TipsState.DONE
                return@launch
            }
            val result = runCatching { amapRepo.searchPoi(kw, "", origin) }
            result.onSuccess { r ->
                when (r) {
                    is com.motohud.ble.amap.TipsResult.Ok -> {
                        AmapCache.put(key, r.items)
                        val list = rank(kw, r.items)
                        _tips.value = list
                        _tipsState.value = if (list.isEmpty()) TipsState.EMPTY else TipsState.DONE
                    }
                    is com.motohud.ble.amap.TipsResult.Err -> {
                        _tips.value = emptyList()
                        _tipsErrorCode.value = r.code
                        _tipsState.value = TipsState.ERROR
                    }
                }
            }.onFailure {
                _tipsErrorCode.value = -1
                _tipsState.value = TipsState.ERROR
            }
        }
    }

    /**
     * 排序 + 同名归并。
     *
     * 1. 排序键：名称与关键字完全一致优先，其次按距离升序（无距离的排最后）；
     * 2. 归并：同名且相距 < 500m 的合并成一条（显示"（N 个地点）"），
     *    避免"XX 大厦"在同一栋楼里出现三四条把列表刷满。
     */
    private fun rank(keyword: String, items: List<TipItem>): List<TipItem> {
        val sorted = items.sortedWith(
            compareBy<TipItem> { if (it.name.trim() == keyword) 0 else 1 }
                .thenBy { it.distanceM ?: Int.MAX_VALUE }
        )
        val out = ArrayList<TipItem>(sorted.size)
        val merged = HashMap<String, Int>()
        for (item in sorted) {
            val dup = out.indexOfFirst { exist ->
                exist.name == item.name && near(exist, item)
            }
            if (dup >= 0) {
                val count = (merged[item.name] ?: 1) + 1
                merged[item.name] = count
                out[dup] = out[dup].copy(name = "${item.name}（$count 个地点）")
            } else {
                out.add(item)
            }
        }
        return out
    }

    private fun near(a: TipItem, b: TipItem): Boolean {
        val dLat = (a.latitude - b.latitude) * 111_320.0
        val dLng = (a.longitude - b.longitude) * 111_320.0 *
            kotlin.math.cos(Math.toRadians(a.latitude))
        return kotlin.math.sqrt(dLat * dLat + dLng * dLng) < 500.0
    }

    fun clearTips() {
        tipsJob?.cancel()
        _tips.value = emptyList()
        _tipsState.value = TipsState.IDLE
    }

    // ---------- 路线规划 ----------

    /**
     * 规划路线。起点用当前定位；未获得定位时返回失败。
     * 策略由 [com.motohud.ble.data.NaviPrefs.strategy] 按「路线偏好」组合得出。
     */
    suspend fun planRoute(toLat: Double, toLng: Double): RoutePlanResult {
        val from = currentLocation
            ?: return RoutePlanResult.Failure("尚未获取到当前位置，请检查定位权限后重试")
        if (!amapReady) return RoutePlanResult.Failure("地图服务不可用")
        AmapManager.initSearch(getApplication())
        return amapRepo.planDriveRoute(
            from.first, from.second, toLat, toLng,
            com.motohud.ble.data.NaviPrefs.strategy(),
            com.motohud.ble.data.NaviPrefs.summary()
        )
    }

    // ---------- 常用地址 ----------

    fun addPlace(name: String, lat: Double, lng: Double, type: String, remark: String) {
        viewModelScope.launch {
            dao.insert(
                SavedPlaceEntity(
                    name = name,
                    latitude = lat,
                    longitude = lng,
                    type = type,
                    remark = remark
                )
            )
        }
    }

    fun updatePlace(place: SavedPlaceEntity) {
        viewModelScope.launch { dao.update(place) }
    }

    fun deletePlace(place: SavedPlaceEntity) {
        viewModelScope.launch { dao.delete(place) }
    }

    override fun onCleared() {
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        super.onCleared()
    }
}
