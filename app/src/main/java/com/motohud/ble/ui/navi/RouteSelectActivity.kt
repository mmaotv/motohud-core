package com.motohud.ble.ui.navi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import com.motohud.ble.amap.NaviPlanner
import com.motohud.ble.amap.RoutePlanResult
import com.motohud.ble.amap.RouteQueryOptions
import com.motohud.ble.data.AvoidZoneStore
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.navi.CoordConverter
import com.motohud.ble.navi.RouteData
import com.motohud.ble.navi.RouteHandoff
import com.motohud.ble.ui.theme.AppTheme
import kotlin.math.max
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

    private var destName = ""
    private var destLat = 0.0
    private var destLng = 0.0
    private var startLat = 0.0
    private var startLng = 0.0

    private var routes: List<RouteData> = emptyList()
    private var selected = 0

    /** 当前生效的避让区数量，用于路线卡片回显（"避开 N 区"） */
    private var avoidZoneCount = 0

    /**
     * 起点：默认「当前位置」（有存档坐标时直接用，不再每次都去gps取一次，
     * 那既慢又会让"原地反复查同一段路"的请求缓存永远命中不了）。
     * 用户可以从起点面板改成地图选点 / 常用地址 / 手动坐标。
     */
    private var originName: String = ""

    /**
     * 途经点（必经）。
     *
     * 只保证"经过这些点"，两点之间怎么走仍由高德决定 —— 这也是为什么
     * 还要配避让区。上限 [RouteQueryOptions.MAX_WAYPOINTS]，不是 SDK 限制，
     * 是"点越多算路越慢、路线越绕"的体验上限。
     */
    private data class Waypoint(val name: String, val lat: Double, val lng: Double)

    private val waypoints = mutableListOf<Waypoint>()

    /** 正在为哪个目标选点：-1 = 起点，>=0 = 途经点下标 */
    private var pickingForWaypoint = -1

    /** 长按「途经点」直接地图选点：选完才落位，取消不留空胶囊 */
    private var pickingNewWaypoint = false

    private val mapPickLauncher = registerMapPick { lat, lng ->
        /*
         * 长按「途经点」进来的：这时候还没有那一行（取消选点不该留个空胶囊），
         * 所以走 append 而不是 setWaypoint。
         */
        if (pickingNewWaypoint) {
            pickingNewWaypoint = false
            appendWaypoint(getString(R.string.waypoint_map_pick), lat, lng)
            return@registerMapPick
        }
        val target = pickingForWaypoint
        pickingForWaypoint = -1
        if (target >= 0) {
            setWaypoint(target, getString(R.string.waypoint_map_pick), lat, lng)
        } else {
            setOrigin(getString(R.string.origin_map_pick), lat, lng)
        }
    }

    /** 地点搜索选点（起点 / 途经点共用），结果与地图选点同款回写 */
    private val searchPickLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val name = r.data?.getStringExtra(PlaceSearchActivity.EXTRA_NAME)
            .orEmpty().ifBlank { getString(R.string.place_card_point) }
        val lat = r.data?.getDoubleExtra(PlaceSearchActivity.EXTRA_LAT, 0.0) ?: 0.0
        val lng = r.data?.getDoubleExtra(PlaceSearchActivity.EXTRA_LNG, 0.0) ?: 0.0
        if (lat == 0.0 && lng == 0.0) return@registerForActivityResult
        val target = pickingForWaypoint
        pickingForWaypoint = -1
        if (target >= 0) setWaypoint(target, name, lat, lng)
        else setOrigin(name, lat, lng)
    }

    /**
     * 途经点搜索（合并入口那一单击）：选出的 POI **追加**为一个新途经点。
     *
     * 顺路搜和普通搜在这里合流 —— 搜索页内部会同时给出"沿途分类"和自由输入，
     * 选哪个最后都是往路线里加一个点，所以回到本页只需要一条处理路径。
     */
    private val alongRouteLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val name = r.data?.getStringExtra(PlaceSearchActivity.EXTRA_NAME)
            .orEmpty().ifBlank { getString(R.string.place_card_point) }
        val lat = r.data?.getDoubleExtra(PlaceSearchActivity.EXTRA_LAT, 0.0) ?: 0.0
        val lng = r.data?.getDoubleExtra(PlaceSearchActivity.EXTRA_LNG, 0.0) ?: 0.0
        if (lat == 0.0 && lng == 0.0) return@registerForActivityResult
        appendWaypoint(name, lat, lng)
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
        /*
         * 日 / 夜主题必须在 super 之前定下来。
         * `?attr/mtXxx` 是在布局 inflate 那一刻从主题里取值的，晚一步设置
         * 只能影响之后新建的 View，已经铺好的界面一个字都不会变。
         */
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_select)

        destName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { getString(R.string.dest_default) }
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

        prefValue = findViewById(R.id.rs_pref_row)
        renderPref()

        list = findViewById(R.id.rs_list)
        loading = findViewById(R.id.rs_loading)
        empty = findViewById(R.id.rs_empty)
        adapter = RouteOptionAdapter(
            context = this,
            onClick = { index ->
                selected = index
                adapter.setSelected(index)
                updateStartButton()
                // 点哪条，地图就把哪条加粗并转过去（fitAll=false）
                drawRoutes(fitAll = false)
                prefetchSpeech()
            }
        )
        /*
         * 方案列表横向排（方案是"并排比大小"，竖排一屏只看得到一条）。
         * 横屏且面板挪到右侧时改回竖排 —— 由 applyLayout() 统一处理。
         */
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.adapter = adapter

        originName = getString(R.string.origin_current)
        renderOrigin()

        findViewById<View>(R.id.rs_btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.rs_pref_row).setOnClickListener { showPrefs() }
        findViewById<View>(R.id.rs_origin_row).setOnClickListener { showOriginPicker() }
        findViewById<View>(R.id.rs_start).setOnClickListener { startNavi() }
        /*
         * 长按「开始导航」= 模拟导航（不读 GPS，按 60km/h 沿路线推演）。
         *
         * 这是导航 SDK 的原生能力（`startNavi(NaviType.EMULATOR)` + `setEmulatorNaviSpeed`），
         * 用来在室内把「转向图标 / 播报 / 投屏帧」整条链路走一遍，不必实车跑一趟。
         * 做成隐藏手势而不是显式按钮：正常用户不需要它，多一个按钮只会增加误触。
         */
        findViewById<View>(R.id.rs_start).setOnLongClickListener {
            startNavi(emulator = true)
            true
        }
        findViewById<View>(R.id.rs_other_map).setOnClickListener { showThirdPartyMaps() }
        findViewById<View>(R.id.rs_avoid_row).setOnClickListener {
            startActivity(Intent(this, AvoidZoneActivity::class.java))
        }

        /*
         * 合并后的**唯一**途经点入口。
         *
         * 原先「添加途经点」和「沿途搜索」是两个并排按钮，点进去还要再选一次
         * 「搜索 / 地图选点」——同一个动作被拆成三层。现在：
         * · 单击 → 搜索页（沿途分类 + 自由搜索都在里面，选中即追加为途经点）
         * · 长按 → 直接在地图上点位置（少数情况：用户知道那个路口在哪）
         */
        findViewById<View>(R.id.rs_add_waypoint).setOnClickListener { pickWaypointBySearch() }
        findViewById<View>(R.id.rs_add_waypoint).setOnLongClickListener {
            pickWaypointOnMap()
            true
        }

        renderWaypoints()
        renderAvoidZones()

        setupMap(savedInstanceState)
        applyLayout()
        ensureLocationPermissionThenPlan()
    }

    /**
     * 选路页是"一张全屏地图 + 两层浮层"，横竖屏只调面板的位置和列表方向，
     * 不重建页面（重建会丢已算好的方案和地图视野）。
     *
     * 竖屏：面板贴底通栏，方案横向滑 —— 手机上一屏能横着比 2~3 条。
     * 横屏：面板收窄贴右下，方案改竖排 —— 车机高只有 360dp，通栏贴底会把地图压没。
     */
    private fun applyLayout() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val panel = findViewById<View>(R.id.rs_panel)
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        if (land) {
            lp.width = dp(300)
            lp.gravity = Gravity.END or Gravity.BOTTOM
            lp.marginEnd = dp(10)
            lp.bottomMargin = dp(10)
            list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
            adapter.horizontal = false
        } else {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM
            lp.marginEnd = 0
            lp.bottomMargin = 0
            list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            adapter.horizontal = true
        }
        panel.layoutParams = lp
        // 方向换了卡片尺寸也得跟着换（横向 188dp / 竖向撑满）
        adapter.notifyDataSetChanged()

        // 地图提示贴在顶部卡下方：insets 是在 topBar 上异步算出来的，
        // 这里等它落定再取 bottom，否则拿到的是加 insets 之前的位置
        val hint = findViewById<View>(R.id.rs_map_hint)
        hint.post {
            val hp = hint.layoutParams as FrameLayout.LayoutParams
            hp.topMargin = findViewById<View>(R.id.rs_top_bar).bottom + dp(6)
            hint.layoutParams = hp
        }
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
                com.motohud.ble.amap.AmapMapConfig.applyDisplay(this)
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
        // 起终点 + 途经点一起重画：途经点会随用户增删而变，
        // 只在"第一次空着"时画一次的话，新加的途经点在图上根本看不见
        drawnMarkers.forEach { runCatching { it.remove() } }
        drawnMarkers.clear()
        drawEndpoints(map)
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
                fitRoutesInBand(map, builder.build())
            }
        }
    }

    /**
     * 把整条路线缩放进「顶部卡 ↔ 底部面板」之间的可视带。
     *
     * **直接用 SDK 的 `calculateZoomToSpanLevel(矩形, 两点)`**：它按给定的
     * 屏幕像素矩形反算 zoom 与相机中心，上下左右留白可以完全不对称 ——
     * 这正是"路线完整落在可视带里、又尽量大"需要的接口。
     *
     * 以前自己按 256·2^z 推墨卡托缩放，看似能算，实际有两处系统性偏差：
     * 一是 AMap 的 zoom→像素比例并不等于裸的 256·2^z，二是纬度方向要用
     * cos(lat) 修正。两者叠加的结果就是"缩得不对、中心也偏"——
     * 真机表现为路线左右都被切掉。SDK 自己的投影才是唯一权威。
     *
     * 横屏面板在右下角：把右边界也让出来，路线就不会钻到面板底下。
     */
    private fun fitRoutesInBand(
        map: com.amap.api.maps.AMap,
        bounds: com.amap.api.maps.model.LatLngBounds
    ) {
        val mv = mapView ?: return
        val mapW = mv.width
        val mapH = mv.height
        if (mapW <= 0 || mapH <= 0) return

        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val topBar = findViewById<View>(R.id.rs_top_bar)
        val panel = findViewById<View>(R.id.rs_panel)

        // 可视带 = 顶部卡下沿 ↔ 面板上沿（横屏再让出面板宽度）
        val topInset = (if (topBar.bottom > 0) topBar.bottom else dp(72)) + dp(12)
        val bottomInset = if (land) dp(12)
        else (if (panel.top > 0) (mapH - panel.top) else dp(240)) + dp(12)
        val leftInset = dp(18)
        val rightInset = if (land && panel.width > 0) panel.width + dp(20) else dp(18)

        val l = leftInset.coerceIn(0, mapW - 1)
        val t = topInset.coerceIn(0, mapH - 1)
        val r = (mapW - rightInset).coerceIn(l + 1, mapW)
        val b = (mapH - bottomInset).coerceIn(t + 1, mapH)

        val sw = bounds.southwest
        val ne = bounds.northeast

        /*
         * 把「非对称可视带」换算成一个**虚拟包围盒**，再交给 SDK 的
         * `newLatLngBounds` 去算缩放与中心。
         *
         * 为什么不自己算 zoom：AMap 的 zoom→像素比例并不是裸的 256·2^z
         * （试过，同一段路算出来差近一倍），只有 SDK 自己的投影是权威。
         * 虚拟盒的做法是：把路线包围盒按「整屏 / 可视带」的比例放大，
         * 并让盒子的中心偏离路线中心 —— 这样 SDK 按整屏适配虚拟盒时，
         * 真正的路线正好落在可视带里，且四边留白按需不对称。
         *
         * 注意 `calculateZoomToSpanLevel` 不能用于这个目的：它那四个 int
         * 既不是可视矩形也不是 padding，实测给出的 zoom 偏小 5~10 级。
         */
        val bandW = (r - l).toDouble().coerceAtLeast(1.0)
        val bandH = (b - t).toDouble().coerceAtLeast(1.0)
        val fx = mapW / bandW
        val fy = mapH / bandH

        // 墨卡托归一化 y（0=北、1=南），用于按比例放大纬度方向
        val yN = mercY(ne.latitude)
        val yS = mercY(sw.latitude)
        val routeYC = (yN + yS) / 2.0
        val routeYSpan = (yS - yN).coerceAtLeast(1e-9)
        val lngC = (sw.longitude + ne.longitude) / 2.0
        val lngSpan = (ne.longitude - sw.longitude).coerceAtLeast(1e-9)

        val vSpanY = routeYSpan * fy
        val vSpanX = lngSpan * fx
        // 可视带中心相对屏幕中心的偏移（像素）→ 换算成虚拟盒的经纬度偏移
        val dxNorm = ((l + r) / 2.0 - mapW / 2.0) / mapW * vSpanX
        val dyNorm = ((t + b) / 2.0 - mapH / 2.0) / mapH * vSpanY
        val camLng = lngC - dxNorm
        val camY = routeYC - dyNorm

        val virtual = runCatching {
            com.amap.api.maps.model.LatLngBounds(
                com.amap.api.maps.model.LatLng(mercLat(camY + vSpanY / 2), camLng - vSpanX / 2),
                com.amap.api.maps.model.LatLng(mercLat(camY - vSpanY / 2), camLng + vSpanX / 2)
            )
        }.getOrNull()
        if (virtual != null) {
            map.animateCamera(
                com.amap.api.maps.CameraUpdateFactory.newLatLngBounds(virtual, 0)
            )
        } else {
            map.animateCamera(
                com.amap.api.maps.CameraUpdateFactory.newLatLngBounds(bounds, dp(72))
            )
        }
    }

    /** 纬度 → 归一化墨卡托 y（0=北、1=南） */
    private fun mercY(lat: Double): Double {
        val rad = Math.toRadians(lat)
        return (1.0 - kotlin.math.ln(kotlin.math.tan(Math.PI / 4 + rad / 2)) / Math.PI) / 2.0
    }

    /** 归一化墨卡托 y → 纬度（[mercY] 的逆运算） */
    private fun mercLat(y: Double): Double {
        val n = Math.PI * (1 - 2 * y)
        return Math.toDegrees(2 * kotlin.math.atan(kotlin.math.exp(n)) - Math.PI / 2)
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
        /*
         * 途经点：优先画 SDK 本程实际会经过的那一串（`AMapNaviPath.getWayPoint()`）。
         *
         * SDK 会因为"就在路边/不可达"合并或忽略某些点，界面必须按它给的画 ——
         * 否则用户看到一个编号 3 的点，实际导航根本不经过，会以为设错了。
         * 还没算出路线时退回用户输入的那一串，保证编辑过程中点位不会消失。
         */
        val wps = routes.getOrNull(selected)?.waypoints?.takeIf { it.isNotEmpty() }
            ?: waypoints.map { it.lat to it.lng }
        wps.forEachIndexed { i, p ->
            if (p.first == 0.0 && p.second == 0.0) return@forEachIndexed
            val icon = runCatching {
                com.amap.api.maps.model.BitmapDescriptorFactory
                    .fromBitmap(com.motohud.ble.amap.WaypointMarker.badge(this, i + 1))
            }.getOrNull() ?: return@forEachIndexed
            drawnMarkers += map.addMarker(
                com.amap.api.maps.model.MarkerOptions()
                    .position(com.amap.api.maps.model.LatLng(p.first, p.second))
                    .icon(icon)
                    .anchor(0.5f, 0.5f)
                    .title(getString(R.string.route_waypoint_n, i + 1))
                    .zIndex(8f)
            )
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- MapView 生命周期 ----------
    // MapView 不跟着 Activity 走这几个回调，会出现"回来后地图全黑"或纹理泄漏

    override fun onResume() {
        super.onResume()
        runCatching { mapView?.onResume() }
        // 从避让区管理页 / 路线偏好页回来时，计数与摘要可能已经变了
        renderAvoidZones()
        renderPref()
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
        // 胶囊里只有一行字，"偏好 · 速度最快" 比单写"速度最快"更能说明这个可以点
        prefValue.text = getString(R.string.route_pref_chip, NaviPrefs.summary())
    }

    // ---------- 起点 ----------

    private fun renderOrigin() {
        findViewById<TextView>(R.id.rs_origin_name).text = originName
    }

    // ---------- 途经点 ----------

    /*
     * 合并后的唯一途经点入口（见 onCreate 的按钮绑定）。
     *
     * 原先这里有两个并列按钮：「添加途经点（必经）」和「沿途搜索（顺路加站）」，
     * 而且「添加途经点」点开还要再选一次"搜索 / 地图选点"——同一个目标（往路线里
     * 加一个点）被拆成两三个入口，用户每次都得先想"我这个情况该点哪个"。
     *
     * 现在只剩一个：「途经点」。
     * · 单击 → 搜索页。那一页同时有"沿途分类"（加油/充电/餐饮…）和自由输入，
     *   沿途搜和普通搜本来就是同一件事的两种取法，没必要在上一页就逼用户选。
     * · 长按 → 地图选点。少数情况：用户知道那个路口在哪，直接戳地图更快。
     */

    /** 单击：进搜索页选点（沿途分类 + 自由搜索都在里面） */
    private fun pickWaypointBySearch() {
        if (!checkWaypointCapacity()) return
        alongRouteLauncher.launch(
            PlaceSearchActivity.intent(
                this,
                getString(R.string.route_waypoint_pick_title),
                startLat, startLng,
                // 把当前路线几何传过去：搜索页据此画路线背景、并支持沿走廊召回
                routeLatLng = routePolylineArray(),
                startLat = startLat, startLng = startLng,
                destLat = destLat, destLng = destLng,
                alongRoute = true
            )
        )
    }

    /** 长按：直接在地图上点位置 */
    private fun pickWaypointOnMap() {
        if (!checkWaypointCapacity()) return
        pickingNewWaypoint = true
        // 没选过点时坐标是 0，直接传 0 会让选点页停在高德默认城市（北京）。
        // 退到当前位置 / 上次地图位置，至少让用户落在自己所在的城市。
        val base = currentFix() ?: NaviPrefs.mapCamera()?.let { it.lat to it.lng }
        mapPickLauncher.launch(MapPickActivity.intent(this, base?.first ?: 0.0, base?.second ?: 0.0))
    }

    private fun checkWaypointCapacity(): Boolean {
        if (waypoints.size < RouteQueryOptions.MAX_WAYPOINTS) return true
        Toast.makeText(
            this,
            getString(R.string.route_waypoint_limit, RouteQueryOptions.MAX_WAYPOINTS),
            Toast.LENGTH_SHORT
        ).show()
        return false
    }

    /** 追加一个途经点并重算 —— 上限检查在调用方做，这里只管落位 */
    private fun appendWaypoint(name: String, lat: Double, lng: Double) {
        if (waypoints.size >= RouteQueryOptions.MAX_WAYPOINTS) return
        waypoints += Waypoint(name, lat, lng)
        renderWaypoints()
        plan()
    }

    private fun showWaypointPicker(index: Int) {
        if (index !in waypoints.indices) return
        pickingForWaypoint = index
        val wp = waypoints[index]
        val dialog = OriginPickDialog.newInstance(
            currentLocation = if (wp.lat != 0.0 && wp.lng != 0.0) wp.lat to wp.lng else currentFix(),
            titleRes = R.string.route_waypoint_pick_title
        )
        dialog.onPicked = { name, lat, lng -> setWaypoint(index, name, lat, lng) }
        dialog.onPickOnMap = {
            pickingForWaypoint = index
            // 途经点还没选过时坐标是 0，直接传 0 会让选点页停在高德默认城市（北京）。
            // 退到起点 / 上次地图位置，至少让用户落在自己所在的城市。
            val clat: Double
            val clng: Double
            if (wp.lat != 0.0 && wp.lng != 0.0) {
                clat = wp.lat
                clng = wp.lng
            } else {
                val base = currentFix() ?: com.motohud.ble.data.NaviPrefs.mapCamera()
                    ?.let { it.lat to it.lng }
                clat = base?.first ?: 0.0
                clng = base?.second ?: 0.0
            }
            mapPickLauncher.launch(MapPickActivity.intent(this, clat, clng))
        }
        dialog.onPickSearch = {
            // 以路线起点为中心搜途经点，结果天然围绕本程路线，不会搜到别的大城市；
            // 同时把当前路线几何传过去，让搜索页背景地图画出路线，选点看得见落点
            pickingForWaypoint = index
            searchPickLauncher.launch(
                PlaceSearchActivity.intent(
                    this,
                    getString(R.string.route_waypoint_pick_title),
                    startLat, startLng,
                    routeLatLng = routePolylineArray(),
                    startLat = startLat, startLng = startLng,
                    destLat = destLat, destLng = destLng
                )
            )
        }
        dialog.show(supportFragmentManager, "waypoint_pick")
    }

    private fun setWaypoint(index: Int, name: String, lat: Double, lng: Double) {
        if (index !in waypoints.indices) return
        waypoints[index] = Waypoint(name, lat, lng)
        renderWaypoints()
        plan()
    }

    /**
     * 把当前选中方案的路线折线降采样成 DoubleArray（lat,lng 交替），
     * 通过 Intent 传给搜索页画背景地图。降采样是为了避免几千个点撑爆 Intent 的事务缓冲区。
     */
    private fun routePolylineArray(): DoubleArray? {
        val r = routes.getOrNull(selected) ?: return null
        val pts = r.steps.flatMap { it.polyline }
        if (pts.isEmpty()) return null
        val maxPts = 120
        val stepN = max(1, pts.size / maxPts)
        val sampled = if (stepN <= 1) pts else pts.filterIndexed { i, _ -> i % stepN == 0 }
        val out = sampled.toMutableList().also { if (it.last() != pts.last()) it.add(pts.last()) }
        val arr = DoubleArray(out.size * 2)
        out.forEachIndexed { i, p -> arr[i * 2] = p.first; arr[i * 2 + 1] = p.second }
        return arr
    }

    private fun removeWaypoint(index: Int) {
        if (index !in waypoints.indices) return
        waypoints.removeAt(index)
        renderWaypoints()
        plan()
    }

    /**
     * 重绘途经点列表。
     *
     * 用动态 inflate 而不是 RecyclerView：最多 5 条、且跟页面一起滚动，
     * 套一层 RecyclerView 只会带来嵌套滚动的麻烦，没有收益。
     */
    private fun renderWaypoints() {
        val box = findViewById<android.widget.LinearLayout>(R.id.rs_waypoint_box)
        box.removeAllViews()
        waypoints.forEachIndexed { index, wp ->
            val row = layoutInflater.inflate(R.layout.item_route_waypoint, box, false)
            row.findViewById<TextView>(R.id.wp_index).text = "${index + 1}"
            row.findViewById<TextView>(R.id.wp_name).text = wp.name
            row.findViewById<View>(R.id.wp_remove).setOnClickListener { removeWaypoint(index) }
            row.setOnClickListener { showWaypointPicker(index) }
            // 长按排序：影响路线顺序，必须让用户能调
            row.setOnLongClickListener {
                showWaypointOrderMenu(index)
                true
            }
            box.addView(row)
        }
        findViewById<TextView>(R.id.rs_waypoint_count).text =
            if (waypoints.isEmpty()) "" else "${waypoints.size}/${RouteQueryOptions.MAX_WAYPOINTS}"
    }

    /** 排序用「上移/下移」菜单：小屏上拖拽比菜单更难点中 */
    private fun showWaypointOrderMenu(index: Int) {
        val wp = waypoints.getOrNull(index) ?: return
        val items = arrayOf(
            getString(R.string.route_waypoint_move_up),
            getString(R.string.route_waypoint_move_down),
            getString(R.string.route_waypoint_repick),
            getString(R.string.delete)
        )
        com.motohud.ble.ui.common.MotoDialog.builder(this)
            .setTitle(wp.name)
            .setItems(items) { d, which ->
                when (which) {
                    0 -> if (index > 0) {
                        waypoints.add(index - 1, waypoints.removeAt(index))
                        renderWaypoints(); plan()
                    }
                    1 -> if (index < waypoints.lastIndex) {
                        waypoints.add(index + 1, waypoints.removeAt(index))
                        renderWaypoints(); plan()
                    }
                    2 -> showWaypointPicker(index)
                    else -> removeWaypoint(index)
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 避让区 ----------

    /** 避让区是本地数据，进入页面/返回时刷新一次计数即可 */
    private fun renderAvoidZones() {
        lifecycleScope.launch {
            val zones = AvoidZoneStore.enabled(this@RouteSelectActivity)
            val tv = findViewById<TextView>(R.id.rs_avoid_row)
            // 胶囊宽度只有半行，只报数量；具体哪几个区在点进去的页面里看
            tv.text = if (zones.isEmpty()) {
                getString(R.string.route_avoid_chip)
            } else {
                getString(R.string.route_avoid_chip_count, zones.size)
            }
        }
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
        dialog.onPickSearch = {
            pickingForWaypoint = -1
            searchPickLauncher.launch(
                PlaceSearchActivity.intent(
                    this,
                    getString(R.string.origin_pick_title),
                    startLat, startLng,
                    routeLatLng = routePolylineArray(),
                    startLat = startLat, startLng = startLng,
                    destLat = destLat, destLng = destLng
                )
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

            // 「自定义路线」三件套：途经点（必经）+ 避让区（必绕）+ 排除高速（全局）
            val avoidZones = AvoidZoneStore.enabled(this@RouteSelectActivity).map { it.polygon }
            avoidZoneCount = avoidZones.size
            val options = RouteQueryOptions(
                waypoints = waypoints.filter { it.lat != 0.0 && it.lng != 0.0 }
                    .map { it.lat to it.lng },
                avoidPolygons = avoidZones,
                excludeMotorway = NaviPrefs.excludeMotorway(),
                // 这两个参数以前根本没下发（SDK 里有 setUseFerry / setCarType，一直没调）
                avoidFerry = NaviPrefs.avoidFerry.value,
                carType = NaviPrefs.carType.value
            )

            // 同一段路 10 分钟内复用上次算路结果：不产生网络请求，直接省掉最贵的一次调用。
            // 注意 optionsTag 必须参与 key —— 否则改了途经点会命中旧缓存，
            // 表现就是"设置了没反应"。
            val optionsTag = AmapCache.optionsTag(
                options.waypoints, options.avoidPolygons, options.excludeMotorway,
                options.avoidFerry, options.carType
            )
            // 缓存 key 里带 "navi" 标记：接入导航 SDK 后算路算法变了，
            // 老的搜索 SDK 缓存（没有 sdkRouteId）不能再命中，否则"选了方案进导航又重算"。
            val cacheKey = AmapCache.routeKey(
                fromLat, fromLng, destLat, destLng, strategy, LIMIT, "$optionsTag|navi"
            )
            val cached = AmapCache.get<RoutePlanResult>(cacheKey, AmapCache.TTL_ROUTE_MS)
            if (cached != null) {
                loading.visibility = View.GONE
                applyResult(cached)
                return@launch
            }

            // 首选：导航 SDK 算路（与导航页同一个引擎，选中的方案就是走的那条）
            val viaSdk = runCatching {
                NaviPlanner.planCandidates(
                    context = this@RouteSelectActivity,
                    fromLat = fromLat,
                    fromLng = fromLng,
                    toLat = destLat,
                    toLng = destLng,
                    waypoints = options.waypoints,
                    strategy = NaviPrefs.naviSdkStrategy(),
                    modeLabel = summary
                )
            }.getOrNull()

            // 兜底：导航 SDK 不可用（无 Key / 未授权 / 算路失败）时才回搜索 SDK
            val result = if (viaSdk is RoutePlanResult.Many || viaSdk is RoutePlanResult.Success) {
                viaSdk
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        AmapRepo(this@RouteSelectActivity)
                            .planCandidates(
                                fromLat, fromLng, destLat, destLng, strategy, summary, LIMIT, options
                            )
                    }
                }.getOrNull() ?: viaSdk
            }

            // 只有算成功了才进缓存：失败/错误码不缓存，避免一次抖动被记十分钟
            if (result is RoutePlanResult.Many) AmapCache.put(cacheKey, result)

            loading.visibility = View.GONE
            applyResult(result)
        }
    }

    /**
     * 卡片上回显"用户自己设了什么"。
     *
     * 不做合规审计（我们不敢替用户保证哪条路能走），只如实反映：
     * 你设了 2 个途经点、1 个避让区 —— 用户自己看得懂，也知道设置生效了。
     */
    private fun routeOptionsSummary(): String {
        val parts = mutableListOf<String>()
        if (waypoints.isNotEmpty()) parts += getString(R.string.route_waypoint_count, waypoints.size)
        if (avoidZoneCount > 0) parts += getString(R.string.route_avoid_zone_count, avoidZoneCount)
        return parts.joinToString(" · ")
    }

    /** 算路结果的统一落渲染处：缓存命中与真实网络两条路都走这里 */
    private fun applyResult(result: RoutePlanResult?) {
        when (result) {
            is RoutePlanResult.Many -> {
                // 高德返回的顺序是按策略来的，不保证最快在前。
                // 统一按耗时升序排，让第一项就是"最快"，默认选中它也符合直觉。
                routes = result.routes.sortedBy { it.durationS }
                selected = 0
                adapter.submit(routes, 0, routeOptionsSummary())
                list.visibility = View.VISIBLE
                updateStartButton()
                drawRoutes(fitAll = true)
                revealRouteList()
                prefetchSpeech()
            }
            is RoutePlanResult.Success -> {
                routes = listOf(result.route)
                selected = 0
                adapter.submit(routes, 0, routeOptionsSummary())
                list.visibility = View.VISIBLE
                updateStartButton()
                drawRoutes(fitAll = true)
                revealRouteList()
                prefetchSpeech()
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

    /**
     * 预合成当前方案的前两段要说的话。
     *
     * 算完路线到用户点「开始导航」通常有几秒，正好够离线引擎合成前几句；
     * 不提前做的话，进导航后的第一句必然赶不上（合成 700ms+，看门狗一到
     * 就退回语音包，路名又没了）。
     *
     * 文本必须与导航页**逐字一致**才命中缓存 —— 所以统一走叙事层
     * ([com.motohud.ble.navi.VoiceGuidance])，不要在这里另拼一套。
     */
    private fun prefetchSpeech() {
        if (!com.motohud.ble.data.NaviPrefs.voice.value) return
        val r = routes.getOrNull(selected) ?: return
        // 文本来源必须与导航页完全一致：播报文本现在由叙事层 (VoiceGuidance) 组合，
        // 进段确认 + 各阈值临门指令都按「下一段有多长」算出不同结果，
        // 所以在路线页把前两段的**全部可能文本**一次预合成，进导航第一句不用等。
        val texts = buildList {
            addAll(com.motohud.ble.navi.VoiceGuidance.prewarmTexts(r.steps, 0))
            addAll(com.motohud.ble.navi.VoiceGuidance.prewarmTexts(r.steps, 1))
        }
        com.motohud.ble.navi.tts.SherpaSpeaker.prefetch(this, texts)
    }

    /**
     * 让方案列表进入视野。
     *
     * 上半部分（起点 / 途经点 / 目的地 / 预览图）内容多时可以滚动，
     * 算完路线如果不滚一下，用户看到的是"为你找到以下方案"却看不到方案。
     */
    /**
     * 方案算完把列表滚到第一条 —— 以前是整页滚动（NestedScrollView 时代），
     * 现在方案列表是独立的一条，滚它自己就行。
     */
    private fun revealRouteList() {
        if (routes.isEmpty()) return
        list.post {
            (list.layoutManager as? LinearLayoutManager)?.scrollToPosition(0)
        }
    }

    private fun updateStartButton() {
        val btn = findViewById<TextView>(R.id.rs_start)
        btn.isEnabled = routes.isNotEmpty()
        btn.alpha = if (routes.isNotEmpty()) 1f else 0.5f
    }

    private fun startNavi(emulator: Boolean = false) {
        val r = routes.getOrNull(selected) ?: return
        RouteHandoff.put(destName, destLat, destLng, r)
        startActivity(
            Intent(this, GuidanceActivity::class.java).apply {
                putExtra(GuidanceActivity.EXTRA_NAME, destName)
                putExtra(GuidanceActivity.EXTRA_LAT, destLat)
                putExtra(GuidanceActivity.EXTRA_LNG, destLng)
                putExtra(GuidanceActivity.EXTRA_FROM_LAT, startLat)
                putExtra(GuidanceActivity.EXTRA_FROM_LNG, startLng)
                putExtra(GuidanceActivity.EXTRA_EMULATOR, emulator)
                // 途经点要带过去：导航中途再追加途经点时，必须拿原有的整串一起重算，
                // 否则"加一个点"会把之前设的途经点全丢了。
                putExtra(
                    GuidanceActivity.EXTRA_WAYPOINTS,
                    waypoints.filter { it.lat != 0.0 && it.lng != 0.0 }
                        .joinToString(";") { "${it.lat},${it.lng}" }
                )
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
    private val context: android.content.Context,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<RouteOptionAdapter.VH>() {

    private var data: List<RouteData> = emptyList()
    private var selected = 0

    /**
     * 列表方向。由宿主在 [RouteSelectActivity.applyLayout] 里设置 ——
     * 不能在 onBindViewHolder 里读 `itemView.parent`，那时 ViewHolder 还没挂到
     * RecyclerView 上，parent 是 null，会一律当成竖排撑满宽度（真机就是这么发现的：
     * 只有一条方案时卡片横着铺满整行）。
     */
    var horizontal = true

    /** 每条方案的角标文案，见 [buildBadges] */
    private var badges: List<String> = emptyList()

    /** "途经 2 点 · 避开 1 区" —— 用户自己设的东西，如实回显 */
    private var optionsSummary: String = ""

    fun submit(list: List<RouteData>, sel: Int, optionsSummary: String = "") {
        data = list
        selected = sel
        badges = buildBadges(list)
        this.optionsSummary = optionsSummary
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
        if (distinct > 1) return list.map { it.label.ifBlank { context.getString(R.string.route_scheme) } }
        val fastest = list.indices.minByOrNull { list[it].durationS } ?: 0
        val shortest = list.indices.minByOrNull { list[it].distanceM } ?: 0
        val fewestLights = list.indices.minByOrNull { list[it].trafficLights } ?: 0
        return list.indices.map { i ->
            when {
                i == fastest -> context.getString(R.string.route_badge_fastest)
                i == shortest -> context.getString(R.string.route_badge_shortest)
                i == fewestLights -> context.getString(R.string.route_badge_fewer_lights)
                else -> context.getString(R.string.route_badge_backup, i + 1)
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
        holder.label.text = badges.getOrElse(position) { context.getString(R.string.route_scheme_n, position + 1) }
        holder.eta.text = CoordConverter.formatEta(r.durationS.toInt())
        holder.distance.text = CoordConverter.formatDistance(r.distanceM.toInt())
        holder.detail.text = buildString {
            append(context.getString(R.string.route_lights_count, r.trafficLights))
            if (r.tolls > 0f) {
                append(context.getString(R.string.route_toll_suffix))
                append(String.format(Locale.US, "¥%.0f", r.tolls))
            } else {
                append(context.getString(R.string.route_no_toll_suffix))
            }
            // 限行：高德 DrivePath.getRestriction() 一直在返回，但从来没人读过。
            // 外地牌照骑进限行区是要吃罚单的，这个提示必须放在**选路线时**，
            // 而不是等开进去了才说。
            if (r.restriction != 0) {
                append(context.getString(R.string.route_restricted_suffix))
            }
            if (r.modeLabel.isNotBlank()) {
                append(" · ")
                append(r.modeLabel)
            }
            if (optionsSummary.isNotBlank()) {
                append(" · ")
                append(optionsSummary)
            }
        }
        val ctx = holder.itemView.context
        holder.root.setBackgroundResource(
            if (position == selected) R.drawable.bg_card_selected else R.drawable.bg_card
        )
        holder.root.setOnClickListener { onClick(position) }

        /*
         * 卡片尺寸跟着列表方向走：
         * 横向条（竖屏底部）→ 固定 188dp 宽、右边留 8dp，一屏能并排 2~3 条；
         * 竖向列（横屏右栏）→ 撑满栏宽、下边留 8dp。
         * 布局里写的 188dp 只是"横向"时的初值，这里会覆盖掉。
         */
        val d = ctx.resources.displayMetrics.density
        (holder.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.width = if (horizontal) (188 * d).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
            lp.marginEnd = if (horizontal) (8 * d).toInt() else 0
            lp.bottomMargin = if (horizontal) 0 else (8 * d).toInt()
            holder.root.layoutParams = lp
        }
    }
}
