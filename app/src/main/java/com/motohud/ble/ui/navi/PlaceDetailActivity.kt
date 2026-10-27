package com.motohud.ble.ui.navi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.navi.CoordConverter
import com.motohud.ble.navi.LocationFilter

/**
 * 地点详情页 —— 搜索结果的第一站。
 *
 * ## 为什么要有这一页
 *
 * 以前点搜索结果直接进「选择路线」页，等于把"查一下"和"要去"绑死了：
 * 用户很多时候只是想知道这个地方离自己多远、在哪一片，
 * 被迫进算路页再退出来，多花一次算路请求，还容易误触开始导航。
 *
 * 现在点搜索结果（或最近搜索）先落到这里：
 *  1. 地图直接移到该地址（[EXTRA_LAT]/[EXTRA_LNG]），红色图钉标出目的地，
 *     有当前定位时同时画一个蓝点，位置关系一眼可见；
 *  2. 底部卡片给出名称、地址与**直线距离**；
 *  3. 只有点「导航到这里」才进「选择路线」页。
 *
 * ## 为什么是"直线距离"
 *
 * 真实驾车距离要发一次算路请求（有流量，且要等）。这一页的用途是"大概多远"，
 * 用两点球面距离就够，而且**不发任何网络请求**。文案里写明"直线距离"，
 * 不让用户把直线距离当成驾车里程 —— 那是下一步的事。
 */
class PlaceDetailActivity : AppCompatActivity() {

    private var mapView: MapView? = null
    private var aMap: AMap? = null

    /** 地图能否使用（Key + 隐私合规 + 初始化成功），在 setContentView 之前就定下来 */
    private var mapReady = false

    private var name: String = ""
    private var lat: Double = 0.0
    private var lng: Double = 0.0
    private var fromLat: Double = 0.0
    private var fromLng: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /*
         * 高德的三条隐私合规开关必须在 **MapView 被 inflate 之前** 生效：
         * 布局里的 MapView 是在 setContentView 阶段构造的，如果这时还没设置
         * updatePrivacyShow/Agree，构造会失败 —— 冷启动（进程刚起、还没走过地图页）
         * 直接进本页时就会看到"地图服务不可用"。
         * 先做初始化，再加载布局。
         */
        mapReady = AmapManager.hasKey(this) &&
            AmapManager.isPrivacyAgreed(this) &&
            runCatching { AmapManager.initMap(this) }.getOrDefault(false)

        setContentView(R.layout.activity_place_detail)

        name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank {
            getString(R.string.place_detail_default_name)
        }
        lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
        fromLat = intent.getDoubleExtra(EXTRA_FROM_LAT, 0.0)
        fromLng = intent.getDoubleExtra(EXTRA_FROM_LNG, 0.0)
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val district = intent.getStringExtra(EXTRA_DISTRICT).orEmpty()
        val searchDistanceM = intent.getIntExtra(EXTRA_DISTANCE_M, -1)

        val tvTitle = findViewById<TextView>(R.id.pd_title)
        val tvName = findViewById<TextView>(R.id.pd_name)
        val tvAddress = findViewById<TextView>(R.id.pd_address)
        val tvDistance = findViewById<TextView>(R.id.pd_distance)

        tvTitle.text = name
        tvName.text = name

        val addrLine = listOf(district, address)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
        if (addrLine.isBlank()) {
            tvAddress.visibility = View.GONE
        } else {
            tvAddress.text = addrLine
        }

        // 距离：优先用当前定位现算（最准），没有定位时退回搜索结果自带的那条
        val straight = if (fromLat != 0.0 && fromLng != 0.0 && lat != 0.0 && lng != 0.0) {
            LocationFilter.distanceM(fromLat, fromLng, lat, lng)
        } else {
            null
        }
        when {
            straight != null -> tvDistance.text = CoordConverter.formatDistance(straight.toInt())
            searchDistanceM >= 0 -> tvDistance.text = CoordConverter.formatDistance(searchDistanceM)
            else -> {
                // 既没定位也没距离：这一行没有信息量，收掉
                (tvDistance.parent as? View)?.visibility = View.GONE
            }
        }

        findViewById<View>(R.id.pd_btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.pd_nav).setOnClickListener { openRouteSelect() }

        setupMap(savedInstanceState)
    }

    private fun openRouteSelect() {
        if (lat == 0.0 && lng == 0.0) {
            finish()
            return
        }
        RouteSelectActivity.start(this, name, lat, lng, fromLat, fromLng)
    }

    private fun setupMap(savedInstanceState: Bundle?) {
        val unavailable = findViewById<View>(R.id.pd_map_unavailable)
        if (lat == 0.0 && lng == 0.0) {
            // 坐标无效（历史数据缺经纬度）时不做地图，卡片与「导航」按钮也不该骗人
            findViewById<View>(R.id.pd_map).visibility = View.GONE
            unavailable.visibility = View.VISIBLE
            findViewById<View>(R.id.pd_nav).apply {
                isEnabled = false
                alpha = 0.4f   // 禁用了就要看得出来，否则用户会一直点
            }
            return
        }
        if (!mapReady) {
            findViewById<View>(R.id.pd_map).visibility = View.GONE
            unavailable.visibility = View.VISIBLE
            return
        }

        runCatching {
            mapView = findViewById(R.id.pd_map)
            mapView?.onCreate(savedInstanceState)
            aMap = mapView?.map?.apply {
                mapType = AMap.MAP_TYPE_NIGHT
                uiSettings.apply {
                    isZoomControlsEnabled = false
                    isMyLocationButtonEnabled = false
                    // 只是"看一眼位置"，路况瓦片纯属浪费流量
                    runCatching { setTrafficEnabled(false) }
                }
            }
        }

        val map = aMap
        if (map == null) {
            findViewById<View>(R.id.pd_map).visibility = View.GONE
            unavailable.visibility = View.VISIBLE
            return
        }

        val dest = LatLng(lat, lng)
        runCatching {
            map.addMarker(
                MarkerOptions()
                    .position(dest)
                    .title(name)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .zIndex(9f)
            )
            // 有当前定位就一并画出来：距离条上的数字才和地图对得上
            if (fromLat != 0.0 && fromLng != 0.0) {
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(fromLat, fromLng))
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                        .setFlat(true)
                        .zIndex(8f)
                )
            }
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(dest, 16f))
        }
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
        const val EXTRA_NAME = "pd_name"
        const val EXTRA_LAT = "pd_lat"
        const val EXTRA_LNG = "pd_lng"
        const val EXTRA_ADDRESS = "pd_address"
        const val EXTRA_DISTRICT = "pd_district"
        const val EXTRA_DISTANCE_M = "pd_distance_m"
        const val EXTRA_FROM_LAT = "pd_from_lat"
        const val EXTRA_FROM_LNG = "pd_from_lng"

        fun start(
            ctx: Context,
            name: String,
            lat: Double,
            lng: Double,
            address: String? = null,
            district: String? = null,
            distanceM: Int? = null,
            fromLat: Double = 0.0,
            fromLng: Double = 0.0
        ) {
            ctx.startActivity(
                Intent(ctx, PlaceDetailActivity::class.java).apply {
                    putExtra(EXTRA_NAME, name)
                    putExtra(EXTRA_LAT, lat)
                    putExtra(EXTRA_LNG, lng)
                    putExtra(EXTRA_ADDRESS, address)
                    putExtra(EXTRA_DISTRICT, district)
                    putExtra(EXTRA_DISTANCE_M, distanceM ?: -1)
                    putExtra(EXTRA_FROM_LAT, fromLat)
                    putExtra(EXTRA_FROM_LNG, fromLng)
                }
            )
        }
    }
}
