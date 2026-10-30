package com.motohud.ble.ui.navi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.amap.AmapLocator
import com.motohud.ble.amap.AmapRepo
import com.motohud.ble.amap.TipItem
import com.motohud.ble.amap.TipsResult
import com.motohud.ble.data.NaviPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 地点搜索选点页（起点 / 途经点 / 顺路搜 共用）。
 *
 * ## 三种用法
 *  1. **普通选点**（途经点 / 起点）：以传入中心（路线起点）画 30km 地理围栏搜 POI；
 *  2. **顺路搜（沿路线）**：[EXTRA_ALONG]=true 时进入"沿途搜索"模式，底部显示地图与当前路线，
 *     顶部一排类型筛选（加油站 / 充电站 / 餐饮 / 卫生间 / 服务区 / 银行 / 全部），
 *     用高德 [com.amap.api.services.routepoisearch.RoutePOISearch] 沿路线走廊召回 POI，
 *     点结果即加为途经点 —— 这就是"顺路规划"：在去目的地的路上顺手办件事。
 *
 * ## 地图背景
 * 无论哪种用法，只要调用方传了路线几何（[EXTRA_ROUTE] + 起终点），背景地图就画出当前路线，
 * 选点的同时能看到它落在路线的哪个位置（参考高德 / Google Maps 的"添加途经点"做法：
 * 地图常驻、搜索面板作底部浮层，而不是把地图彻底挡住）。
 */
/** 高德沿途搜索 POI 类型枚举（嵌套类）的简写别名 */
private typealias RPType = com.amap.api.services.routepoisearch.RoutePOISearch.RoutePOISearchType

class PlaceSearchActivity : AppCompatActivity() {

    private lateinit var et: EditText
    private lateinit var clear: ImageView
    private lateinit var state: TextView
    private lateinit var list: androidx.recyclerview.widget.RecyclerView
    private lateinit var catsBox: HorizontalScrollView
    private lateinit var catsRow: LinearLayout

    private val repo by lazy { AmapRepo(applicationContext) }
    private val scope get() = lifecycleScope
    private var searchJob: Job? = null
    private var debounce: Job? = null

    /** 搜索地理中心：[lat, lng] */
    private var center: Pair<Double, Double>? = null

    // ---------- 地图 ----------
    private var mapView: MapView? = null
    private var aMap: AMap? = null
    private val drawnLines = mutableListOf<Polyline>()
    private val drawnMarkers = mutableListOf<Marker>()

    // ---------- 路线几何（由 RouteSelect 传入）----------
    private var routePolyline: List<Pair<Double, Double>> = emptyList()
    private var startLL: Pair<Double, Double>? = null
    private var destLL: Pair<Double, Double>? = null

    // ---------- 顺路搜模式 ----------
    private var alongRoute = false
    private var selectedType: RPType? = null
    private val allTypes = listOf(
        RPType.TypeGasStation,
        RPType.TypeChargeStation,
        RPType.TypeFood,
        RPType.TypeToilet,
        RPType.TypeServiceArea,
        RPType.TypeATM
    )

    private lateinit var adapter: TipAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 高德隐私合规 + Key 必须早于 MapView inflate（否则冷启动地图服务不可用）
        AmapManager.initMap(this)
        val ready = AmapManager.hasKey(this) && AmapManager.isPrivacyAgreed(this)
        setContentView(R.layout.activity_place_search)

        et = findViewById(R.id.ps_et)
        clear = findViewById(R.id.ps_clear)
        state = findViewById(R.id.ps_state)
        list = findViewById(R.id.ps_list)
        catsBox = findViewById(R.id.ps_cats)
        catsRow = findViewById(R.id.ps_cats_row)

        // 路线几何：起点 + 终点 + 折线（用于背景地图与顺路搜）
        val routeArr = intent.getDoubleArrayExtra(EXTRA_ROUTE)
        if (routeArr != null && routeArr.size >= 4) {
            routePolyline = routeArr.toList().chunked(2) { it[0] to it[1] }
        }
        val sLat = intent.getDoubleExtra(EXTRA_START_LAT, 0.0)
        val sLng = intent.getDoubleExtra(EXTRA_START_LNG, 0.0)
        val dLat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
        val dLng = intent.getDoubleExtra(EXTRA_DEST_LNG, 0.0)
        if (sLat != 0.0 && sLng != 0.0) startLL = sLat to sLng
        if (dLat != 0.0 && dLng != 0.0) destLL = dLat to dLng
        alongRoute = intent.getBooleanExtra(EXTRA_ALONG, false)
        // 顺路搜模式给搜索框一个明确提示
        if (alongRoute) {
            et.hint = getString(R.string.place_search_along_title)
        }

        // 初始搜索中心：调用方给的点 > 路线起点 > 地图视野
        val inLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val inLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
        center = if (inLat != 0.0 && inLng != 0.0) inLat to inLng
        else startLL ?: NaviPrefs.mapCamera()?.let { it.lat to it.lng }

        if (center == null) {
            scope.launch {
                runCatching { AmapLocator(applicationContext).locateOnce(6000) }.getOrNull()
                    ?.let { fix -> if (center == null) center = fix.lat to fix.lng }
            }
        }

        adapter = TipAdapter(
            originProvider = { center },
            onClick = { tip -> pick(tip) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.ps_back).setOnClickListener { finish() }
        clear.setOnClickListener { et.setText("") }

        setupMap(savedInstanceState, ready)

        if (alongRoute) {
            catsBox.visibility = View.VISIBLE
            buildChips()
            // 进页先出一屏"沿途"结果（默认全部类型）
            runAlongRouteSearch("")
        }

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                clear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                searchJob?.cancel()
                debounce?.cancel()
                if (text.isBlank()) {
                    if (!alongRoute) {
                        adapter.submitList(emptyList())
                        state.visibility = View.GONE
                    }
                    return
                }
                debounce = scope.launch {
                    delay(450)
                    if (alongRoute) runAlongRouteSearch(text.trim())
                    else runSearch(text.trim(), live = true)
                }
            }
        })

        et.setOnEditorActionListener { _, actionId, event ->
            val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isSearch) {
                val t = et.text?.toString().orEmpty().trim()
                if (t.isNotBlank()) {
                    searchJob?.cancel()
                    debounce?.cancel()
                    if (alongRoute) runAlongRouteSearch(t) else runSearch(t, live = false)
                }
                true
            } else false
        }

        et.requestFocus()
    }

    // ---------- 地图 ----------

    private fun setupMap(savedInstanceState: Bundle?, ready: Boolean) {
        mapView = findViewById(R.id.ps_map)
        if (!ready) {
            mapView?.visibility = View.GONE
            return
        }
        runCatching {
            mapView?.onCreate(savedInstanceState)
            aMap = mapView?.map?.apply {
                com.motohud.ble.amap.AmapMapConfig.applyDisplay(this)
                uiSettings.isZoomControlsEnabled = false
                uiSettings.isMyLocationButtonEnabled = false
                uiSettings.isRotateGesturesEnabled = false
                uiSettings.isTiltGesturesEnabled = false
            }
            drawRouteOnMap()
        }.onFailure { android.util.Log.w("PlaceSearch", "预览地图初始化失败", it) }
    }

    /** 背景画出当前路线 + 起终点，让用户看到选点相对路线的位置 */
    private fun drawRouteOnMap() {
        val map = aMap ?: return
        if (routePolyline.size >= 2) {
            val path = routePolyline.map { LatLng(it.first, it.second) }
            drawnLines += map.addPolyline(
                PolylineOptions().addAll(path).width(10f)
                    .color(0xFFFF9F0A.toInt()).zIndex(3f)
            )
        }
        startLL?.let {
            drawnMarkers += map.addMarker(
                MarkerOptions().position(LatLng(it.first, it.second))
                    .title(getString(R.string.route_select_from))
            )
        }
        destLL?.let {
            drawnMarkers += map.addMarker(
                MarkerOptions().position(LatLng(it.first, it.second))
                    .title(getString(R.string.place_card_point))
            )
        }
        val pts = (routePolyline + listOfNotNull(startLL, destLL))
        if (pts.isNotEmpty()) {
            val b = LatLngBounds.Builder()
            pts.forEach { b.include(LatLng(it.first, it.second)) }
            runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(56))) }
        } else {
            center?.let { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.first, it.second), 14f)) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- 类型筛选（顺路搜）----------

    private fun buildChips() {
        val cats = listOf(
            getString(R.string.cat_all) to null as RPType?,
            "加油站" to RPType.TypeGasStation,
            "充电站" to RPType.TypeChargeStation,
            "餐饮" to RPType.TypeFood,
            "卫生间" to RPType.TypeToilet,
            "服务区" to RPType.TypeServiceArea,
            "银行" to RPType.TypeATM
        )
        catsRow.removeAllViews()
        cats.forEachIndexed { idx, (label, type) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundResource(R.drawable.bg_chip)
                val selected = idx == 0
                applyChipState(this, selected)
                setOnClickListener {
                    selectedType = type
                    for (i in 0 until catsRow.childCount) applyChipState(catsRow.getChildAt(i) as TextView, i == idx)
                    runAlongRouteSearch(et.text?.toString().orEmpty().trim())
                }
            }
            catsRow.addView(chip)
        }
    }

    private fun applyChipState(chip: TextView, selected: Boolean) {
        chip.backgroundTintList = if (selected) {
            android.content.res.ColorStateList.valueOf(Color.parseColor("#33FF9F0A"))
        } else {
            null
        }
        chip.setTextColor(
            if (selected) Color.parseColor("#FFFF9F0A")
            else ContextCompat.getColor(this, R.color.text_secondary)
        )
        chip.typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    }

    // ---------- 搜索 ----------

    /**
     * 普通 POI 搜索（输入中联想 / 回车结果），以 [center] 画地理围栏。
     */
    private fun runSearch(keyword: String, live: Boolean) {
        if (!AmapManager.initSearch(this)) {
            showState(getString(R.string.route_select_no_map))
            return
        }
        state.visibility = View.VISIBLE
        state.text = getString(R.string.place_search_loading)
        searchJob = scope.launch {
            val c = center
            val result = if (live) {
                repo.searchTips(keyword, "", c, c)
            } else {
                repo.searchPoi(keyword, "", c, c, 30_000)
            }
            when (result) {
                is TipsResult.Ok -> {
                    adapter.submitList(result.items)
                    if (result.items.isEmpty()) showState(getString(R.string.place_search_empty))
                    else state.visibility = View.GONE
                }
                is TipsResult.Err -> {
                    adapter.submitList(emptyList())
                    showState(getString(R.string.navi_tips_error))
                }
            }
        }
    }

    /**
     * 沿路线搜索（顺路搜）：按当前类型筛选 + 关键字，沿 [routePolyline] 走廊召回 POI。
     */
    private fun runAlongRouteSearch(keyword: String) {
        if (routePolyline.size < 2) {
            showState(getString(R.string.place_search_along_no_route))
            return
        }
        if (!AmapManager.initSearch(this)) {
            showState(getString(R.string.route_select_no_map))
            return
        }
        state.visibility = View.VISIBLE
        state.text = getString(R.string.place_search_loading)
        searchJob?.cancel()
        searchJob = scope.launch {
            val types = selectedType?.let { listOf(it) } ?: allTypes
            val result = runCatching {
                repo.routePoiSearch(routePolyline, types, ALONG_RADIUS, keyword.trim())
            }.getOrElse { TipsResult.Err(-1) }
            when (result) {
                is TipsResult.Ok -> {
                    adapter.submitList(result.items)
                    if (result.items.isEmpty()) showState(getString(R.string.place_search_empty))
                    else state.visibility = View.GONE
                }
                is TipsResult.Err -> {
                    adapter.submitList(emptyList())
                    showState(getString(R.string.navi_tips_error))
                }
            }
        }
    }

    private fun showState(text: String) {
        state.visibility = View.VISIBLE
        state.text = text
    }

    /** 选中一个地点 → 在地图上落图钉（让用户看到它相对路线的位置），回传坐标后结束 */
    private fun pick(tip: TipItem) {
        if (tip.latitude == 0.0 && tip.longitude == 0.0) return
        aMap?.let { map ->
            drawnMarkers += map.addMarker(
                MarkerOptions().position(LatLng(tip.latitude, tip.longitude))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .title(tip.name)
                    .zIndex(10f)
            )
            runCatching { map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(tip.latitude, tip.longitude)), 400, null) }
        }
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_NAME, tip.name)
                putExtra(EXTRA_LAT, tip.latitude)
                putExtra(EXTRA_LNG, tip.longitude)
            }
        )
        finish()
    }

    // ---------- MapView 生命周期 ----------
    override fun onResume() {
        super.onResume()
        runCatching { mapView?.onResume() }
    }

    override fun onPause() {
        runCatching { mapView?.onPause() }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        runCatching { mapView?.onSaveInstanceState(outState) }
    }

    override fun onDestroy() {
        searchJob?.cancel()
        debounce?.cancel()
        drawnLines.forEach { runCatching { it.remove() } }
        drawnMarkers.forEach { runCatching { it.remove() } }
        runCatching { mapView?.onDestroy() }
        mapView = null
        aMap = null
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { mapView?.onLowMemory() }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_NAME = "name"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        /** 路线折线（lat,lng,lat,lng…），降采样后传入，避免 Intent 过大 */
        const val EXTRA_ROUTE = "route"
        const val EXTRA_START_LAT = "start_lat"
        const val EXTRA_START_LNG = "start_lng"
        const val EXTRA_DEST_LAT = "dest_lat"
        const val EXTRA_DEST_LNG = "dest_lng"
        /** 是否进入"沿路线搜索"模式 */
        const val EXTRA_ALONG = "along"

        /** 顺路搜沿路半径（米） */
        private const val ALONG_RADIUS = 1500

        /** 拉起本页。
         * @param centerLat/centerLng 搜索中心（如路线起点）
         * @param routeLatLng 路线折线（降采样）
         * @param startLat/Lng 起点坐标（地图标记）
         * @param destLat/Lng 终点坐标（地图标记）
         * @param alongRoute 是否进入顺路搜模式
         */
        fun intent(
            ctx: Context,
            title: String? = null,
            centerLat: Double = 0.0,
            centerLng: Double = 0.0,
            routeLatLng: DoubleArray? = null,
            startLat: Double = 0.0,
            startLng: Double = 0.0,
            destLat: Double = 0.0,
            destLng: Double = 0.0,
            alongRoute: Boolean = false
        ): Intent = Intent(ctx, PlaceSearchActivity::class.java).apply {
            title?.let { putExtra(EXTRA_TITLE, it) }
            putExtra(EXTRA_LAT, centerLat)
            putExtra(EXTRA_LNG, centerLng)
            routeLatLng?.let { putExtra(EXTRA_ROUTE, it) }
            putExtra(EXTRA_START_LAT, startLat)
            putExtra(EXTRA_START_LNG, startLng)
            putExtra(EXTRA_DEST_LAT, destLat)
            putExtra(EXTRA_DEST_LNG, destLng)
            putExtra(EXTRA_ALONG, alongRoute)
        }
    }
}
