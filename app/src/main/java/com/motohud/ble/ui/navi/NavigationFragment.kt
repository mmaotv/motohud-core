package com.motohud.ble.ui.navi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.motohud.ble.ui.common.MotoDialog
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.data.NaviPrefs
import com.motohud.ble.data.SavedPlaceEntity
import com.motohud.ble.data.SearchHistory
import com.motohud.ble.ble.BleConnectionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 导航主界面（复刻原车机 App 的高德导航入口）：
 *
 *  1. 高德夜间地图 + 定位蓝点 + 实时路况；
 *  2. 顶部搜索栏：POI 输入提示（450ms 防抖）→ 点结果先进[PlaceDetailActivity]
 *     确认位置与距离，再决定是「导航到这里」还是只是看一眼；
 *  3. 常用地址 chips（家/公司/其他）：点击 → 直接进入路线选择（用户自己存的快捷入口）；
 *  4. 底部操作卡：管理地址 / 选择地图 / 高德 Key 配置；
 *  5. 未连接车机时给出明确提示；未配置高德 Key 时地图功能优雅降级。
 */
class NavigationFragment : Fragment(R.layout.fragment_navigation) {

    private val vm: NavigationViewModel by viewModels {
        NavigationViewModelFactory(requireActivity().application as MotoHudApp)
    }

    private var mapView: com.amap.api.maps.MapView? = null
    private var aMap: AMap? = null

    /** 屏幕中间的当前位置点 */
    private var meMarker: Marker? = null
    private lateinit var tipAdapter: TipAdapter

    private lateinit var etSearch: EditText
    private lateinit var btnClear: ImageView
    private lateinit var rvTips: RecyclerView
    private lateinit var tvTipsState: TextView
    private lateinit var chipsContainer: LinearLayout
    private lateinit var cardKeyMissing: View
    private lateinit var tvBleHint: TextView
    private lateinit var tvLocation: TextView
    private lateinit var fabLocation: View
    private lateinit var btnTraffic: ImageView
    private lateinit var chipsScroll: View
    private lateinit var cardBottom: View
    private lateinit var searchArea: View

    // ---------- 最近搜索 ----------
    private lateinit var historyBlock: View
    private lateinit var rvHistory: RecyclerView
    private lateinit var historyAdapter: SearchHistoryAdapter
    private var historyVisible = false

    /** 搜索建议列表的高度上限（屏高 62%） */
    private var tipsMaxHeight = 0

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.refreshLocation()
        enableMyLocation()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etSearch = view.findViewById(R.id.et_search)
        btnClear = view.findViewById(R.id.btn_search_clear)
        rvTips = view.findViewById(R.id.rv_tips)
        tvTipsState = view.findViewById(R.id.tv_tips_state)
        chipsContainer = view.findViewById(R.id.chips_container)
        cardKeyMissing = view.findViewById(R.id.card_key_missing)
        tvBleHint = view.findViewById(R.id.tv_ble_hint)
        tvLocation = view.findViewById(R.id.tv_current_location)
        fabLocation = view.findViewById(R.id.fab_location)
        btnTraffic = view.findViewById(R.id.btn_traffic)
        chipsScroll = view.findViewById(R.id.chips_scroll)
        cardBottom = view.findViewById(R.id.card_bottom)
        searchArea = view.findViewById(R.id.search_area)
        mapView = view.findViewById(R.id.map_view)

        historyBlock = view.findViewById(R.id.search_history_block)
        rvHistory = view.findViewById(R.id.rv_search_history)
        historyAdapter = SearchHistoryAdapter(
            onPick = { item ->
                hideHistory()
                etSearch.setText("")
                etSearch.clearFocus()
                openPlaceDetail(item.name, item.lat, item.lng)
            },
            onDelete = { item ->
                SearchHistory.remove(requireContext(), item.name)
                refreshHistory()
            }
        )
        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        rvHistory.adapter = historyAdapter
        view.findViewById<View>(R.id.search_history_clear).setOnClickListener {
            SearchHistory.clear(requireContext())
            refreshHistory()
        }

        // 顶部搜索区贴近顶栏（本页已在主界面 banner 之下，状态栏高度由 banner 预留，
        // 这里只留 8dp 间距把搜索框往上提，不再叠加状态栏高度导致黑边/下移）
        ViewCompat.setOnApplyWindowInsetsListener(searchArea) { v, insets ->
            v.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
            insets
        }
        // 搜索建议列表的高度上限：约屏高 62%，保证不越过屏幕底部
        tipsMaxHeight = (resources.displayMetrics.heightPixels * 0.62f).toInt()

        // ---------- 地图 ----------
        setupMap(savedInstanceState)

        // ---------- 搜索 ----------
        tipAdapter = TipAdapter { tip -> openPlaceDetail(tip.name.ifBlank { getString(R.string.place_detail_default_name) }, tip.latitude, tip.longitude, tip.address, tip.district, tip.distanceM) }
        rvTips.layoutManager = LinearLayoutManager(requireContext())
        rvTips.adapter = tipAdapter
        rvTips.layoutAnimation = android.view.animation.LayoutAnimationController(
            android.view.animation.AnimationUtils.loadAnimation(
                requireContext(), R.anim.item_animation_fall_down
            )
        ).apply { delay = 0.08f }

        // ---------- 最近搜索 ----------
        // 只在"点了搜索框、还没开始输入"时展示：
        // 已经有输入内容时屏幕属于联想结果，两者同时出现会互相挤。
        etSearch.setOnFocusChangeListener { _, focused ->
            if (focused && etSearch.text.isNullOrBlank()) refreshHistory() else hideHistory()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                btnClear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                if (text.isEmpty()) {
                    vm.clearTips()
                    hideTips()
                    // 清空后又回到"刚点进搜索框"的状态 → 把历史重新露出来
                    refreshHistory()
                    return
                }
                hideHistory()
                searchRunnable = Runnable { vm.searchTips(text) }.also {
                    // 450ms 而不是 300ms：连续输入时每次多攒一点点字符，
                    // 一次搜索能覆盖更多已输入的字母，请求数明显下降
                    searchHandler.postDelayed(it, 450L)
                }
            }
        })

        // 回车/点搜索：换 PoiSearch 拿更全的召回与真实距离（联想层用 Inputtips，快但召回窄）
        etSearch.setOnEditorActionListener { _, actionId, event ->
            val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
            if (!isSearch) return@setOnEditorActionListener false
            val text = etSearch.text?.toString().orEmpty().trim()
            if (text.isEmpty()) return@setOnEditorActionListener true
            searchRunnable?.let { searchHandler.removeCallbacks(it) }
            vm.searchResults(text)
            true
        }

        btnClear.setOnClickListener { etSearch.setText("") }

        // ---------- 底部操作 ----------
        // 两个定位入口（文字「更新刷新定位」与浮动圆钮）走同一个方法：
        // 都强制向定位 SDK 要一次新鲜位置，再移动地图。
        view.findViewById<View>(R.id.btn_refresh_location).setOnClickListener { locateNow() }
        view.findViewById<View>(R.id.btn_manage_places).setOnClickListener { showManagePlaces() }
        view.findViewById<View>(R.id.btn_route_prefs).setOnClickListener { showRoutePrefs() }
        // 「输入坐标」入口已移除：普通用户没有经纬度概念，放着也没人点
        view.findViewById<View>(R.id.btn_navi_history).setOnClickListener {
            com.motohud.ble.ui.navi.NaviHistoryActivity.start(requireContext())
        }
        // 车况：底部页签收成两个之后，这里是唯一入口（外加各页空态的「去连接」）
        view.findViewById<View>(R.id.btn_vehicle).setOnClickListener {
            com.motohud.ble.ui.vehicle.VehicleActivity.start(requireContext())
        }
        // 实时路况开关。默认关：路况瓦片约 30s 刷一次，是地图页最大的流量项之一
        btnTraffic.setOnClickListener {
            val on = !NaviPrefs.mapTraffic.value
            NaviPrefs.setMapTraffic(on)
            runCatching { aMap?.setTrafficEnabled(on) }
            renderTrafficButton()
        }
        view.findViewById<View>(R.id.btn_setup_key).setOnClickListener { showKeySetup() }
        fabLocation.setOnClickListener { locateNow() }

        // ---------- 状态观察 ----------
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.places.collect { renderChips(it) } }
                launch {
                    vm.tips.collect { list ->
                        tipAdapter.submitList(list)
                        val active = list.isNotEmpty()
                        rvTips.visibility = if (active) View.VISIBLE else View.GONE
                        // 有结果就把状态行收掉，否则"搜索中…"会压在结果列表上方
                        if (active) tvTipsState.visibility = View.GONE
                        // 搜索建议展开时收起底部地址卡与浮动按钮，避免列表被遮挡
                        chipsScroll.visibility = if (active) View.GONE else View.VISIBLE
                        cardBottom.visibility = if (active) View.GONE else View.VISIBLE
                        fabLocation.visibility = if (active) View.GONE else View.VISIBLE
                        if (active) {
                            // 按条目数估高，避免结果很少时拉出一大块空卡片
                            val d = resources.displayMetrics.density
                            val h = minOf(tipsMaxHeight, (list.size * 78 * d).toInt())
                            if (h > 0) {
                                rvTips.updateLayoutParams<LinearLayout.LayoutParams> { height = h }
                            }
                            rvTips.scheduleLayoutAnimation()
                        }
                    }
                }
                launch {
                    vm.tipsState.collect { state ->
                        // 状态改为就地显示，不再弹 Toast：
                        // 打字时每敲几个字就"搜索中/无结果"轮播一次，是最吵的提示
                        when (state) {
                            NavigationViewModel.TipsState.LOADING -> {
                                tvTipsState.visibility = View.VISIBLE
                                tvTipsState.setText(R.string.navi_tips_searching)
                            }
                            NavigationViewModel.TipsState.EMPTY -> {
                                tvTipsState.visibility = View.VISIBLE
                                tvTipsState.setText(R.string.navi_tips_empty)
                            }
                            NavigationViewModel.TipsState.ERROR -> {
                                tvTipsState.visibility = View.VISIBLE
                                // 错误码只进日志：用户看 10009 没有任何意义
                                android.util.Log.w("NavigationFragment", "搜索失败 code=${vm.tipsErrorCode.value}")
                                tvTipsState.setText(R.string.navi_tips_error)
                            }
                            else -> tvTipsState.visibility = View.GONE
                        }
                    }
                }
                launch {
                    vm.locationLabel.collect { label ->
                        tvLocation.text = label.ifEmpty { getString(R.string.navi_location_unknown) }
                    }
                }
                launch {
                    // 当前位置点：跟着定位流走，屏幕中间那个蓝点
                    vm.locationFlow.collect { fix ->
                        if (fix != null) updateMeMarker(fix.lat, fix.lng, fix.accuracy)
                    }
                }
                launch {
                    vm.locating.collect { busy ->
                        if (busy) tvLocation.setText(R.string.navi_locating)
                    }
                }
                launch {
                    (requireActivity().application as MotoHudApp).bleManager.state.collect { state ->
                        val connected = state == BleConnectionManager.State.CONNECTED
                        tvBleHint.visibility = if (connected) View.GONE else View.VISIBLE
                    }
                }
            }
        }

        refreshKeyCard()
    }

    // ---------- 地图 ----------

    private fun setupMap(savedInstanceState: Bundle?) {
        val ready = vm.amapReady
        cardKeyMissing.visibility = if (ready) View.GONE else View.VISIBLE
        if (!ready) {
            mapView?.visibility = View.GONE
            return
        }
        AmapManager.initMap(requireContext())
        mapView?.visibility = View.VISIBLE
        try {
            mapView?.onCreate(savedInstanceState)
            aMap = mapView?.map?.apply {
                mapType = AMap.MAP_TYPE_NIGHT
                uiSettings.apply {
                    isZoomControlsEnabled = false
                    isMyLocationButtonEnabled = false
                    isRotateGesturesEnabled = true
                }
                // 路况图层跟随设置（默认关）。以前这里写死 true，
                // 每 30s 一刷的路况瓦片是地图页最大的流量来源。
                runCatching { setTrafficEnabled(NaviPrefs.mapTraffic.value) }
            }
            renderTrafficButton()
            // 权限检查放到地图准备好之后：没权限时 SDK 初始化会抛异常
            enableMyLocation()
            moveToMyLocation()
        } catch (e: Exception) {
            // 地图没起来时不再弹 Toast（用户看不懂，也关不掉）；
            // 日志留痕，界面保持"地图空白 + 搜索仍可用"的状态
            android.util.Log.w("NavigationFragment", "地图初始化失败", e)
        }
    }

    /** 路况按钮：开=高亮，关=压暗 */
    private fun renderTrafficButton() {
        val on = NaviPrefs.mapTraffic.value
        btnTraffic.alpha = if (on) 1f else 0.5f
        btnTraffic.setBackgroundResource(
            if (on) R.drawable.bg_navi_circle_active else R.drawable.bg_navi_circle
        )
    }

    /**
     * 检查定位权限。
     *
     * 这里**不再**设 `isMyLocationEnabled = true`：高德原生蓝点必须配
     * `setLocationSource` 才会画，只设这个开关不会有任何点，还会多起一个定位源。
     * 当前位置改由 [updateMeMarker] 自己画（与引导页一致）。
     */
    private fun enableMyLocation() {
        val granted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) {
            ensureLocationPermission()
        }
    }

    /**
     * 当前位置点。
     *
     * 为什么自己画而不是用高德原生蓝点：
     * 原生蓝点必须同时设置 `setLocationSource` 和 `isMyLocationEnabled = true`，
     * 只设后者 SDK 不会画任何东西（导航页之前就少了这个点）。
     * 而且原生蓝点用的是 SDK 自己的定位源，与本 App 用来算路的高德定位
     * 不是同一帧数据，点和路线会对不上。自己画一个 marker 更可控，
     * 与引导页的做法保持一致。
     */
    private fun updateMeMarker(lat: Double, lng: Double, accuracy: Float) {
        val map = aMap ?: return
        if (lat == 0.0 && lng == 0.0) return
        try {
            val pos = LatLng(lat, lng)
            val m = meMarker
            if (m == null) {
                meMarker = map.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .anchor(0.5f, 0.5f)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                        .setFlat(true)
                        .zIndex(9f)
                )
            } else {
                m.position = pos
            }
            // 信号差时点变淡，提示用户（不用额外文案打扰）
            meMarker?.alpha = if (accuracy > 0f && accuracy <= 40f) 1f else 0.7f
        } catch (_: Exception) {
        }
    }

    private fun moveToMyLocation() {
        val loc = vm.currentLocation ?: return
        updateMeMarker(loc.first, loc.second, vm.accuracy.value)
        aMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(loc.first, loc.second), 16f)
        )
    }

    /**
     * 强制取一次实时定位，然后移动地图。
     * 「更新刷新定位」文字按钮与右下角浮动定位钮都调这里 —— 两个按钮行为完全一致，
     * 都是"主动向定位 SDK 要一个新位置"，而不是读系统缓存。
     */
    private fun locateNow() {
        ensureLocationPermission()
        if (!vm.hasLocationPermission()) return
        vm.refreshLocation(force = true)
        viewLifecycleOwner.lifecycleScope.launch {
            delay(350)
            vm.locating.first { !it }
            val loc = vm.currentLocation
            if (loc == null) {
                // 不弹 Toast：定位失败本身就显示在底部定位行里，弹窗只是重复打扰
                tvLocation.setText(R.string.navi_locate_failed)
            } else {
                updateMeMarker(loc.first, loc.second, vm.accuracy.value)
                aMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(loc.first, loc.second), 16.5f)
                )
            }
        }
    }

    /**
     * 切换到本页时由 MainActivity 调用。
     * 定位是会过期的：上一次进导航页可能是几小时前，直接沿用会显示旧位置，
     * 所以每次页面可见都主动刷新一次。
     */
    fun onTabShown() {
        if (!isAdded || view == null) return
        if (!vm.hasLocationPermission()) {
            ensureLocationPermission()
            return
        }
        vm.refreshLocation(force = true)
        viewLifecycleOwner.lifecycleScope.launch {
            delay(400)
            vm.locating.first { !it }
            moveToMyLocation()
        }
    }

    private fun ensureLocationPermission() {
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) locationPermLauncher.launch(missing.toTypedArray())
    }

    // ---------- 常用地址 chips ----------

    private fun renderChips(places: List<SavedPlaceEntity>) {
        chipsContainer.removeAllViews()
        places.forEach { place ->
            val chip = layoutInflater.inflate(R.layout.item_place_chip, chipsContainer, false)
            val icon = chip.findViewById<ImageView>(R.id.chip_icon)
            val text = chip.findViewById<TextView>(R.id.chip_text)
            icon.setImageResource(
                when (place.type) {
                    SavedPlaceEntity.TYPE_HOME -> R.drawable.ic_place_home
                    SavedPlaceEntity.TYPE_WORK -> R.drawable.ic_place_work
                    else -> R.drawable.ic_place_other
                }
            )
            text.text = place.name
            // 点击 = 直接进入导航流程（复刻"常用地址一键导航"）
            chip.setOnClickListener { openGuidance(place.name, place.latitude, place.longitude) }
            chip.setOnLongClickListener {
                showEditDialog(place)
                true
            }
            chipsContainer.addView(chip)
        }
        // 末尾"＋ 添加"
        val addChip = layoutInflater.inflate(R.layout.item_place_chip, chipsContainer, false)
        addChip.findViewById<ImageView>(R.id.chip_icon).setImageResource(R.drawable.ic_add)
        addChip.findViewById<TextView>(R.id.chip_text).setText(R.string.navi_add_place)
        addChip.setOnClickListener { showEditDialog(null) }
        chipsContainer.addView(addChip)
    }

    private fun showManagePlaces() {
        val dialog = PlaceManageDialog()
        dialog.onNavi = { place -> openGuidance(place.name, place.latitude, place.longitude) }
        dialog.onEdit = { place -> showEditDialog(place) }
        dialog.onDelete = { place -> confirmDelete(place) }
        dialog.show(childFragmentManager, "place_manage")
    }

    private fun showEditDialog(place: SavedPlaceEntity?) {
        val dialog = PlaceEditDialog.newInstance(place, vm.currentLocation)
        dialog.onSave = { name, lat, lng, type, remark ->
            if (place == null) {
                vm.addPlace(name, lat, lng, type, remark)
                Toast.makeText(requireContext(), R.string.place_added, Toast.LENGTH_SHORT).show()
            } else {
                vm.updatePlace(place.copy(name = name, latitude = lat, longitude = lng, type = type, remark = remark))
                Toast.makeText(requireContext(), R.string.place_updated, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show(childFragmentManager, "place_edit")
    }

    private fun confirmDelete(place: SavedPlaceEntity) {
        MotoDialog.builder(requireContext())
            .setTitle(R.string.place_delete_title)
            .setMessage(getString(R.string.place_delete_message, place.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                vm.deletePlace(place)
                Toast.makeText(requireContext(), R.string.place_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------- 导航启动 ----------

    /**
     * 点击搜索结果 / 最近搜索 → 先到「地点详情」页。
     *
     * 这里只做"看一眼"：地图移到该地址、给出直线距离，由用户在详情页里
     * 点「导航到这里」才继续进「选择路线」页。
     * 以前点一下结果就直接算路，用户只是想查个大概位置也会被推进导航流程。
     */
    private fun openPlaceDetail(
        name: String,
        lat: Double,
        lng: Double,
        address: String? = null,
        district: String? = null,
        distanceM: Int? = null
    ) {
        hideTips()
        hideHistory()
        // 只在这里写历史：表示"用户确实查过这个地方"。
        // 输入过程中写，历史里会塞满打了一半和打错的词。
        SearchHistory.add(requireContext(), name, lat, lng)
        etSearch.setText("")
        // 交出焦点让键盘收掉，否则输入法会把下一页顶上去
        etSearch.clearFocus()
        val loc = vm.currentLocation
        PlaceDetailActivity.start(
            requireContext(), name, lat, lng,
            address = address,
            district = district,
            distanceM = distanceM,
            fromLat = loc?.first ?: 0.0,
            fromLng = loc?.second ?: 0.0
        )
    }

    /**
     * 常用地址 chips / 地址管理 → 直接进「选择路线」页。
     *
     * 这些是**用户自己存下来的**快捷入口（回家 / 去公司），点它就是要去，
     * 再插一层详情页反而多一步。
     */
    private fun openGuidance(name: String, lat: Double, lng: Double) {
        hideTips()
        hideHistory()
        etSearch.setText("")
        etSearch.clearFocus()
        val loc = vm.currentLocation
        RouteSelectActivity.start(
            requireContext(), name, lat, lng,
            fromLat = loc?.first ?: 0.0,
            fromLng = loc?.second ?: 0.0
        )
    }

    /**
     * 刷新最近搜索。
     *
     * 一条历史都没有时整块收掉 —— 一个空标题加上"清空"按钮，
     * 对新用户来说是把界面弄乱，而不是给个功能。
     */
    private fun refreshHistory() {
        val items = SearchHistory.recent(requireContext())
        if (items.isEmpty()) {
            hideHistory()
            return
        }
        historyAdapter.submit(items)
        historyBlock.visibility = View.VISIBLE
        historyVisible = true
    }

    private fun hideHistory() {
        historyBlock.visibility = View.GONE
        historyVisible = false
    }

    private fun hideTips() {
        rvTips.visibility = View.GONE
    }


    // ---------- 高德 Key ----------

    private fun showKeySetup() {
        val dialog = KeySetupDialog()
        dialog.show(childFragmentManager, "key_setup")
    }

    /** 路线偏好：与「选择路线」页共用同一个对话框 */
    private fun showRoutePrefs() {
        val d = RoutePrefsDialog()
        d.show(childFragmentManager, "route_prefs")
    }

    private fun refreshKeyCard() {
        cardKeyMissing.visibility = if (vm.amapReady) View.GONE else View.VISIBLE
    }

    /** Key 在设置中被修改后由 MainActivity 回调：延迟初始化地图并刷新降级卡片 */
    fun onAmapKeyChanged() {
        if (aMap == null) {
            setupMap(null)
        } else {
            mapView?.visibility = View.VISIBLE
        }
        refreshKeyCard()
        enableMyLocation()
        moveToMyLocation()
    }

    // ---------- MapView 生命周期 ----------

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        refreshKeyCard()
        // 从二级页面（管理地址、坐标输入等）返回时同步刷新一次位置
        if (isVisible) vm.refreshLocation()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        meMarker = null
        mapView?.onDestroy()
        mapView = null
        aMap = null
        super.onDestroyView()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }
}

/** NavigationViewModel 工厂（复用 Application 中的 DAO） */
class NavigationViewModelFactory(private val app: MotoHudApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        NavigationViewModel(app) as T
}
