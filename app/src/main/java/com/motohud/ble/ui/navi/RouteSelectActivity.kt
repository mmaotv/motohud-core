package com.motohud.ble.ui.navi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.motohud.ble.R
import com.motohud.ble.amap.AmapCache
import com.motohud.ble.amap.AmapLocator
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.amap.AmapRepo
import com.motohud.ble.amap.RoutePlanResult
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.navi.CoordConverter
import com.motohud.ble.navi.RouteData
import com.motohud.ble.navi.RouteHandoff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 路线选择页。
 *
 * 以前点一个地点就直接进导航、用第一条路线，用户没有选择权。
 * 现在先算 2~3 套方案（快 / 短 / 省钱），看清楚距离、时间、红绿灯、
 * 过路费，再决定走哪条；右上「路线偏好」可随时改变方案生成规则。
 */
class RouteSelectActivity : AppCompatActivity() {

    private lateinit var adapter: RouteOptionAdapter
    private lateinit var list: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var empty: TextView
    private lateinit var prefValue: TextView

    private var destName = "目的地"
    private var destLat = 0.0
    private var destLng = 0.0
    private var startLat = 0.0
    private var startLng = 0.0

    private var routes: List<RouteData> = emptyList()
    private var selected = 0

    /**
     * 起点：默认「当前位置」（有存档坐标时直接用，不再每次都去gps取一次，
     * 那既慢又会让"原地反复查同一段路"的请求缓存永远命中不了）。
     * 用户可以从起点面板改成地图选点 / 常用地址 / 手动坐标。
     */
    private var originName: String = ""

    private val mapPickLauncher = registerMapPick { lat, lng ->
        setOrigin(getString(R.string.origin_map_pick), lat, lng)
    }

    // ---------- 路线预览图 ----------
    private var mapView: com.amap.api.maps.MapView? = null
    private var aMap: com.amap.api.maps.AMap? = null
    private var mapReady = false
    private val drawnLines = mutableListOf<com.amap.api.maps.model.Polyline>()
    private val drawnMarkers = mutableListOf<com.amap.api.maps.model.Marker>()

    /**
     * 每条方案一个颜色。选中的那条用原色 + 加粗，
     * 其余同名同色但压低透明度 —— 既区分方案，又不会把地图画成调色盘。
     */
    private val routeColors = intArrayOf(
        android.graphics.Color.parseColor("#FFFF9F0A"),
        android.graphics.Color.parseColor("#FF3E8BFF"),
        android.graphics.Color.parseColor("#FF34C77B"),
        android.graphics.Color.parseColor("#FFBF6BFF")
    )

    private val permLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { plan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_select)

        destName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "目的地" }
        destLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        destLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
        startLat = intent.getDoubleExtra(EXTRA_FROM_LAT, 0.0)
        startLng = intent.getDoubleExtra(EXTRA_FROM_LNG, 0.0)

        val topBar = findViewById<View>(R.id.rs_top_bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(8))
            insets
        }

        findViewById<TextView>(R.id.rs_dest_name).text = destName
        findViewById<TextView>(R.id.rs_dest_sub).text = CoordConverter.formatLatLng(destLat, destLng)

        prefValue = findViewById(R.id.rs_pref_value)
        renderPref()

        list = findViewById(R.id.rs_list)
        loading = findViewById(R.id.rs_loading)
        empty = findViewById(R.id.rs_empty)
        adapter = RouteOptionAdapter(
            onClick = { index ->
                selected = index
                adapter.setSelected(index)
                updateStartButton()
                // 点哪条，地图就把哪条加粗并转过去（fitAll=false）
                drawRoutes(fitAll = false)
            }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        originName = getString(R.string.origin_current)
        renderOrigin()

        findViewById<View>(R.id.rs_btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.rs_pref_row).setOnClickListener { showPrefs() }
        findViewById<View>(R.id.rs_origin_row).setOnClickListener { showOriginPicker() }
        findViewById<View>(R.id.rs_start).setOnClickListener { startNavi() }
        findViewById<View>(R.id.rs_other_map).setOnClickListener { showThirdPartyMaps() }

        setupMap(savedInstanceState)
        ensureLocationPermissionThenPlan()
    }

    // ---------- 路线预览图 ----------

    /**
     * 地图只做"看轮廓、选方案"这一件事：
     * 不显示实时路况（那是额外流量）、不接管手势定位，简单可控。
     */
    private fun setupMap(savedInstanceState: Bundle?) {
        mapView = findViewById(R.id.rs_map)
        if (!AmapManager.hasKey(this) || !AmapManager.isPrivacyAgreed(this)) {
            // 没 Key 时地图卡整体收掉，列表照常可用
            (mapView?.parent as? View)?.visibility = View.GONE
            return
        }
        runCatching {
            AmapManager.initMap(this)
            mapView?.onCreate(savedInstanceState)
            aMap = mapView?.map?.apply {
                mapType = com.amap.api.maps.AMap.MAP_TYPE_NIGHT
                uiSettings.apply {
                    isZoomControlsEnabled = false
                    isMyLocationButtonEnabled = false
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                }
            }
            mapReady = aMap != null
            if (!mapReady) (mapView?.parent as? View)?.visibility = View.GONE
        }.onFailure {
            android.util.Log.w("RouteSelect", "预览地图初始化失败", it)
            (mapView?.parent as? View)?.visibility = View.GONE
        }
    }

    /** 把一条方案的几何拼起来：逐 step 的 polyline 首尾相接 */
    private fun pathOf(r: RouteData): List<com.amap.api.maps.model.LatLng> {
        val out = ArrayList<com.amap.api.maps.model.LatLng>()
        r.steps.forEach { step ->
            step.polyline.forEach { p ->
                val ll = com.amap.api.maps.model.LatLng(p.first, p.second)
                // 相邻 step 的首尾会重复，去掉可以避免折线出现小毛刺
                if (out.isEmpty() || out.last() != ll) out.add(ll)
            }
        }
        return out
    }

    /** 重画所有路线；[fitAll] = true 时相机包住全部方案 */
    private fun drawRoutes(fitAll: Boolean) {
        val map = aMap ?: return
        drawnLines.forEach { runCatching { it.remove() } }
        drawnLines.clear()
        if (drawnMarkers.isEmpty()) drawEndpoints(map)
        // 有多套方案才提示"颜色对应下方列表"，单方案时说了反而是噪音
        findViewById<TextView>(R.id.rs_map_hint).visibility =
            if (routes.size > 1) View.VISIBLE else View.GONE

        routes.forEachIndexed { index, route ->
            val path = pathOf(route)
            if (path.size < 2) return@forEachIndexed
            val isSel = index == selected
            val color = routeColors[index % routeColors.size]
            val body = if (isSel) color else (color and 0x00FFFFFF) or 0x8C000000.toInt()
            // 外层描边：让选中那条从其它线里"浮"出来
            if (isSel) {
                drawnLines += map.addPolyline(
                    com.amap.api.maps.model.PolylineOptions()
                        .addAll(path)
                        .width(18f)
                        .color(0x33000000)
                        .zIndex(3f)
                )
            }
            drawnLines += map.addPolyline(
                com.amap.api.maps.model.PolylineOptions()
                    .addAll(path)
                    .width(if (isSel) 11f else 8f)
                    .color(body)
                    .zIndex(if (isSel) 4f else 2f)
            )
        }

        val target = if (fitAll) routes.flatMap { pathOf(it) } else pathOf(routes.getOrNull(selected) ?: return)
        if (target.size >= 2) {
            runCatching {
                val builder = com.amap.api.maps.model.LatLngBounds.Builder()
                target.forEach { builder.include(it) }
                map.animateCamera(
                    com.amap.api.maps.CameraUpdateFactory.newLatLngBounds(builder.build(), dp(48))
                )
            }
        }
    }

    private fun drawEndpoints(map: com.amap.api.maps.AMap) {
        if (startLat != 0.0 && startLng != 0.0) {
            drawnMarkers += map.addMarker(
                com.amap.api.maps.model.MarkerOptions()
                    .position(com.amap.api.maps.model.LatLng(startLat, startLng))
                    .title(getString(R.string.route_select_from))
            )
        }
        if (destLat != 0.0 && destLng != 0.0) {
            drawnMarkers += map.addMarker(
                com.amap.api.maps.model.MarkerOptions()
                    .position(com.amap.api.maps.model.LatLng(destLat, destLng))
                    .title(destName)
            )
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- MapView 生命周期 ----------
    // MapView 不跟着 Activity 走这几个回调，会出现"回来后地图全黑"或纹理泄漏

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
        drawnLines.clear()
        drawnMarkers.clear()
        runCatching { mapView?.onDestroy() }
        mapView = null
        aMap = null
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { mapView?.onLowMemory() }
    }

    private fun renderPref() {
        prefValue.text = NaviPrefs.summary()
    }

    // ---------- 起点 ----------

    private fun renderOrigin() {
        findViewById<TextView>(R.id.rs_origin_name).text = originName
    }

    private fun showOriginPicker() {
        val dialog = OriginPickDialog.newInstance(
            currentLocation = if (startLat != 0.0 && startLng != 0.0) startLat to startLng
            else currentFix()
        )
        dialog.onPicked = { name, lat, lng -> setOrigin(name, lat, lng) }
        dialog.onPickOnMap = {
            mapPickLauncher.launch(
                MapPickActivity.intent(this, startLat, startLng)
            )
        }
        dialog.show(supportFragmentManager, "origin_pick")
    }

    /** 起点坐标：调用方没给时才去取一次实时定位 */
    private fun currentFix(): Pair<Double, Double>? =
        if (startLat != 0.0 && startLng != 0.0) startLat to startLng else null

    private fun setOrigin(name: String, lat: Double, lng: Double) {
        // 改成「当前位置」时要把坐标清空，让 plan() 去重新取一次实时定位 ——
        // 沿用上一次的坐标会在用户已经移动后算出一条错误的路线。
        if (name == getString(R.string.origin_current)) {
            startLat = 0.0
            startLng = 0.0
        } else {
            startLat = lat
            startLng = lng
        }
        originName = name
        renderOrigin()
        drawnLines.forEach { runCatching { it.remove() } }
        drawnLines.clear()
        drawnMarkers.forEach { runCatching { it.remove() } }
        drawnMarkers.clear()
        plan()
    }

    private fun showPrefs() {
        val d = RoutePrefsDialog()
        d.onApplied = {
            renderPref()
            plan()
        }
        d.show(supportFragmentManager, "route_prefs")
    }

    private fun ensureLocationPermissionThenPlan() {
        if (startLat != 0.0 && startLng != 0.0) {
            plan()
            return
        }
        val missing = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
            return
        }
        plan()
    }

    private fun plan() {
        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        list.visibility = View.INVISIBLE
        lifecycleScope.launch {
            // 起点：调用方给了就用，没给就现取一次实时定位
            if (startLat == 0.0 || startLng == 0.0) {
                val fix = runCatching { AmapLocator(this@RouteSelectActivity).locateOnce() }.getOrNull()
                if (fix != null) {
                    startLat = fix.lat
                    startLng = fix.lng
                }
            }
            if (startLat == 0.0 || startLng == 0.0) {
                loading.visibility = View.GONE
                empty.visibility = View.VISIBLE
                empty.setText(R.string.navi_locate_failed)
                return@launch
            }
            if (!AmapManager.hasKey(this@RouteSelectActivity) ||
                !AmapManager.isPrivacyAgreed(this@RouteSelectActivity)
            ) {
                loading.visibility = View.GONE
                empty.visibility = View.VISIBLE
                empty.setText(R.string.route_select_no_map)
                return@launch
            }

            AmapManager.initSearch(this@RouteSelectActivity)
            val fromLat = startLat
            val fromLng = startLng
            val strategy = NaviPrefs.strategy()
            val summary = NaviPrefs.summary()

            // 同一段路 10 分钟内复用上次算路结果：不产生网络请求，直接省掉最贵的一次调用
            val cacheKey = AmapCache.routeKey(fromLat, fromLng, destLat, destLng, strategy, LIMIT)
            val cached = AmapCache.get<RoutePlanResult>(cacheKey, AmapCache.TTL_ROUTE_MS)
            if (cached != null) {
                loading.visibility = View.GONE
                applyResult(cached)
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AmapRepo(this@RouteSelectActivity)
                        .planCandidates(fromLat, fromLng, destLat, destLng, strategy, summary, LIMIT)
                }
            }.getOrNull()

            // 只有算成功了才进缓存：失败/错误码不缓存，避免一次抖动被记十分钟
            if (result is RoutePlanResult.Many) AmapCache.put(cacheKey, result)

            loading.visibility = View.GONE
            applyResult(result)
        }
    }

    /** 算路结果的统一落渲染处：缓存命中与真实网络两条路都走这里 */
    private fun applyResult(result: RoutePlanResult?) {
        when (result) {
            is RoutePlanResult.Many -> {
                // 高德返回的顺序是按策略来的，不保证最快在前。
                // 统一按耗时升序排，让第一项就是"最快"，默认选中它也符合直觉。
                routes = result.routes.sortedBy { it.durationS }
                selected = 0
                adapter.submit(routes, 0)
                list.visibility = View.VISIBLE
                updateStartButton()
                drawRoutes(fitAll = true)
            }
            is RoutePlanResult.Success -> {
                routes = listOf(result.route)
                selected = 0
                adapter.submit(routes, 0)
                list.visibility = View.VISIBLE
                updateStartButton()
                drawRoutes(fitAll = true)
            }
            is RoutePlanResult.Failure -> {
                empty.visibility = View.VISIBLE
                empty.text = getString(R.string.guidance_plan_failed, result.message)
            }
            null -> {
                empty.visibility = View.VISIBLE
                empty.setText(R.string.guidance_plan_failed_short)
            }
        }
    }

    private fun updateStartButton() {
        val btn = findViewById<TextView>(R.id.rs_start)
        btn.isEnabled = routes.isNotEmpty()
        btn.alpha = if (routes.isNotEmpty()) 1f else 0.5f
    }

    private fun startNavi() {
        val r = routes.getOrNull(selected) ?: return
        RouteHandoff.put(destName, destLat, destLng, r)
        startActivity(
            Intent(this, GuidanceActivity::class.java).apply {
                putExtra(GuidanceActivity.EXTRA_NAME, destName)
                putExtra(GuidanceActivity.EXTRA_LAT, destLat)
                putExtra(GuidanceActivity.EXTRA_LNG, destLng)
                putExtra(GuidanceActivity.EXTRA_FROM_LAT, startLat)
                putExtra(GuidanceActivity.EXTRA_FROM_LNG, startLng)
            }
        )
    }

    /**
     * 把当前目的地交给第三方地图 App 继续导航。
     *
     * 放在这一层而不是导航页底部，是因为这里已经有确定的坐标 ——
     * 导航页那时还什么都没有，只能拿当前位置凑数，交接过去没意义。
     * 同时只列第三方地图，不列「手机导航 / 蓝牙投屏」：
     * 那两个由 GuidanceActivity 的「显示方式」对话框负责，重复列会让人困惑。
     */
    private fun showThirdPartyMaps() {
        val dialog = MapChoiceDialog.newInstance(destName, destLat, destLng)
        dialog.show(supportFragmentManager, "third_party_map")
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_FROM_LAT = "from_lat"
        const val EXTRA_FROM_LNG = "from_lng"

        /** 最多展示几套方案：多了列表要滑很久，且每多一条就多一份算路数据量 */
        private const val LIMIT = 3

        /** 统一入口：所有"选个地点"的地方都走这里，先出方案再导航 */
        fun start(
            ctx: Context,
            name: String,
            lat: Double,
            lng: Double,
            fromLat: Double = 0.0,
            fromLng: Double = 0.0
        ) {
            ctx.startActivity(
                Intent(ctx, RouteSelectActivity::class.java).apply {
                    putExtra(EXTRA_NAME, name)
                    putExtra(EXTRA_LAT, lat)
                    putExtra(EXTRA_LNG, lng)
                    putExtra(EXTRA_FROM_LAT, fromLat)
                    putExtra(EXTRA_FROM_LNG, fromLng)
                }
            )
        }
    }
}

/** 候选路线列表 */
private class RouteOptionAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<RouteOptionAdapter.VH>() {

    private var data: List<RouteData> = emptyList()
    private var selected = 0

    /** 每条方案的角标文案，见 [buildBadges] */
    private var badges: List<String> = emptyList()

    fun submit(list: List<RouteData>, sel: Int) {
        data = list
        selected = sel
        badges = buildBadges(list)
        notifyDataSetChanged()
    }

    /**
     * 高德用 `DRIVING_MULTI_CHOICE_*` 一次返回多套方案时，三套方案的策略名
     * 往往一模一样（都是「速度最快」），并排除列在列表上毫无区分度。
     * 这种情况改成按维度打角标：最快 / 最短 / 灯少，其余标备选序号。
     * 如果高德给的标签本来就有差异，就照用它的。
     */
    private fun buildBadges(list: List<RouteData>): List<String> {
        if (list.isEmpty()) return emptyList()
        val distinct = list.map { it.label }.distinct().size
        if (distinct > 1) return list.map { it.label.ifBlank { "方案" } }
        val fastest = list.indices.minByOrNull { list[it].durationS } ?: 0
        val shortest = list.indices.minByOrNull { list[it].distanceM } ?: 0
        val fewestLights = list.indices.minByOrNull { list[it].trafficLights } ?: 0
        return list.indices.map { i ->
            when {
                i == fastest -> "最快"
                i == shortest -> "最短"
                i == fewestLights -> "灯少"
                else -> "备选 ${i + 1}"
            }
        }
    }

    fun setSelected(index: Int) {
        val old = selected
        selected = index
        if (old >= 0) notifyItemChanged(old)
        notifyItemChanged(index)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.tv_route_label)
        val eta: TextView = v.findViewById(R.id.tv_route_eta)
        val distance: TextView = v.findViewById(R.id.tv_route_distance)
        val detail: TextView = v.findViewById(R.id.tv_route_detail)
        val root: View = v
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_option, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = data[position]
        holder.label.text = badges.getOrElse(position) { "方案 ${position + 1}" }
        holder.eta.text = CoordConverter.formatEta(r.durationS.toInt())
        holder.distance.text = CoordConverter.formatDistance(r.distanceM.toInt())
        holder.detail.text = buildString {
            append("红绿灯 ${r.trafficLights} 个")
            if (r.tolls > 0f) {
                append(" · 过路费 ")
                append(String.format(Locale.US, "¥%.0f", r.tolls))
            } else {
                append(" · 无过路费")
            }
            if (r.modeLabel.isNotBlank()) {
                append(" · ")
                append(r.modeLabel)
            }
        }
        val ctx = holder.itemView.context
        holder.root.setBackgroundResource(
            if (position == selected) R.drawable.bg_card_selected else R.drawable.bg_card
        )
        holder.root.setOnClickListener { onClick(position) }
        // 间距：卡片之间留 8dp
        (holder.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.bottomMargin = (8 * ctx.resources.displayMetrics.density).toInt()
            holder.root.layoutParams = lp
        }
    }
}
