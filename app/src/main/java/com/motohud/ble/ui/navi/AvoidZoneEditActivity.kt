package com.motohud.ble.ui.navi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Polygon
import com.amap.api.maps.model.PolygonOptions
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.data.AppDatabase
import com.motohud.ble.data.AvoidZoneEntity
import com.motohud.ble.data.AvoidZoneStore
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 避让区圈选页：**地图中心 = 区域中心，滑块 = 边长**。
 *
 * ## 为什么不用"拖矩形/拖多边形"
 *
 * 手机小屏上拖顶点几乎不可能拖准，误触概率极高；而高德对避让区面积又很敏感
 * （超过约 2km 见方常直接返回空路线）。把交互降成"移图 + 滑一下"，
 * 既好操作，又天然能把尺寸限制住。
 *
 * ## 一个必须遵守的初始化顺序
 *
 * `setContentView` 里就构造 `MapView`，所以高德隐私合规（[AmapManager.initMap]）
 * 必须**在 setContentView 之前**完成，否则冷启动直接进本页会"地图服务不可用"。
 */
class AvoidZoneEditActivity : AppCompatActivity() {

    private var mapView: MapView? = null
    private var aMap: AMap? = null
    private var polygon: Polygon? = null

    private lateinit var nameInput: EditText
    private lateinit var warn: TextView
    private lateinit var saveBtn: View

    /** 当前中心（地图静止时的相机中心） */
    private var centerLat = 0.0
    private var centerLng = 0.0

    /** 中心是否已"可信"（来自上次地图位置 / 已保存的区 / 定位结果） */
    private var hasCenter = false

    /** 用户自己拖过地图：此时以他选的位置为准，定位结果不再抢相机 */
    private var userTouched = false

    /** 宽度（米）：滑块 0..18 → 400..2200 */
    private var widthM = 1200.0

    /** 高度（米）：与宽度独立，方便框长条形区域 */
    private var heightM = 1200.0

    private var editingId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 顺序不能改：MapView 在布局里，必须先完成隐私合规
        val mapReady = AmapManager.hasKey(this) &&
            AmapManager.isPrivacyAgreed(this) &&
            runCatching { AmapManager.initMap(this) }.getOrDefault(false)
        setContentView(R.layout.activity_avoid_zone_edit)

        editingId = intent.getLongExtra(EXTRA_ID, 0L)
        nameInput = findViewById(R.id.aze_name)
        warn = findViewById(R.id.aze_warn)
        saveBtn = findViewById(R.id.aze_save)

        findViewById<View>(R.id.aze_btn_back).setOnClickListener { finish() }
        setupInsets()

        if (!mapReady) {
            Toast.makeText(this, R.string.route_select_no_map, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupMap(savedInstanceState)
        setupSizeSlider()

        if (editingId > 0) loadExisting() else {
            // 新建：以「地图页上次停留的中心」为起点，比默认城市中心更有意义
            val memo = com.motohud.ble.data.NaviPrefs.mapCamera()
            centerLat = memo?.lat ?: 0.0
            centerLng = memo?.lng ?: 0.0
            nameInput.setText(getString(R.string.avoid_zone_default_name))
            centerOnMap()
        }

        findViewById<View>(R.id.aze_save).setOnClickListener { save() }
    }

    /**
     * 把相机挪到当前中心，没有中心就用定位兜底。
     *
     * 不兜底的话，全新安装（地图页还没滑过）进来会停在高德的默认城市——
     * 用户在重庆看到北京，第一反应是"这功能坏了"。
     */
    private fun centerOnMap() {
        val map = aMap ?: return
        if (centerLat != 0.0 && centerLng != 0.0) {
            hasCenter = true
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLng), 15f))
            drawPreview()
            return
        }
        lifecycleScope.launch {
            val fix = runCatching {
                com.motohud.ble.amap.AmapLocator(this@AvoidZoneEditActivity).locateOnce(6000L)
            }.getOrNull() ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            // 定位期间用户自己拖地图了，就按他拖的位置来，只补画框
            if (userTouched) {
                hasCenter = true
                drawPreview()
                return@launch
            }
            centerLat = fix.lat
            centerLng = fix.lng
            hasCenter = true
            aMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lng), 15f))
            drawPreview()
        }
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.aze_panel)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom + dp(16))
            val back = findViewById<View>(R.id.aze_btn_back)
            (back.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != bars.top + dp(12)) {
                    lp.topMargin = bars.top + dp(12)
                    back.layoutParams = lp
                }
            }
            insets
        }
    }

    private fun setupMap(savedInstanceState: Bundle?) {
        mapView = findViewById(R.id.aze_map)
        runCatching {
            mapView?.onCreate(savedInstanceState)
            aMap = mapView?.map?.apply {
                mapType = AMap.MAP_TYPE_NIGHT
                uiSettings.apply {
                    isZoomControlsEnabled = false
                    isMyLocationButtonEnabled = false
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                }
                runCatching { setTrafficEnabled(false) }
                setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                    override fun onCameraChange(pos: com.amap.api.maps.model.CameraPosition?) {}
                    override fun onCameraChangeFinish(pos: com.amap.api.maps.model.CameraPosition?) {
                        pos?.target?.let {
                            centerLat = it.latitude
                            centerLng = it.longitude
                            // 还没定位到"可信中心"前不画框：此时地图停在高德默认城市，
                            // 画出来的框是北京的，用户会以为保存错了地方
                            if (hasCenter) drawPreview()
                        }
                    }
                })
                // 用户自己拖过地图，就以他的选择为准，不要再被定位结果抢走
                setOnMapTouchListener {
                    userTouched = true
                    hasCenter = true
                }
            }
        }.onFailure {
            android.util.Log.w("AvoidZoneEdit", "地图初始化失败", it)
            Toast.makeText(this, R.string.route_select_no_map, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * 宽、高两个滑块，各 0..18 档 → 400m..2200m（每档 100m）。
     *
     * 上限刻意能越过 2000m 的硬限制，好让"太大"的提示有落点 ——
     * 滑块拖到头却不给任何反馈，用户会以为滑块坏了。
     */
    private fun setupSizeSlider() {
        val wBar = findViewById<SeekBar>(R.id.aze_width)
        val hBar = findViewById<SeekBar>(R.id.aze_height)
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                widthM = 400.0 + wBar.progress * 100.0
                heightM = 400.0 + hBar.progress * 100.0
                renderSize()
                drawPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        wBar.setOnSeekBarChangeListener(listener)
        hBar.setOnSeekBarChangeListener(listener)
        widthM = 400.0 + wBar.progress * 100.0
        heightM = 400.0 + hBar.progress * 100.0
        renderSize()
    }

    private fun renderSize() {
        findViewById<TextView>(R.id.aze_width_value).text =
            String.format(Locale.US, "%.0f m", widthM)
        findViewById<TextView>(R.id.aze_height_value).text =
            String.format(Locale.US, "%.0f m", heightM)
        val tooLarge = widthM > AvoidZoneStore.MAX_SIDE_M || heightM > AvoidZoneStore.MAX_SIDE_M
        warn.visibility = if (tooLarge) View.VISIBLE else View.GONE
    }

    /** 画一个实心半透明矩形预览，让用户直观看到"框住的是哪一块" */
    private fun drawPreview() {
        val map = aMap ?: return
        if (centerLat == 0.0 || centerLng == 0.0) return
        val pts = AvoidZoneStore.rectAround(centerLat, centerLng, widthM, heightM)
            .map { LatLng(it.first, it.second) }
        runCatching {
            polygon?.remove()
            polygon = map.addPolygon(
                PolygonOptions()
                    .addAll(pts)
                    .fillColor(0x33FF453A)
                    .strokeColor(0xCCFF453A.toInt())
                    .strokeWidth(3f)
            )
        }
    }

    private fun loadExisting() {
        lifecycleScope.launch {
            val zone = runCatching {
                AppDatabase.get(this@AvoidZoneEditActivity).avoidZoneDao().allOnce()
                    .firstOrNull { it.id == editingId }
            }.getOrNull() ?: return@launch
            nameInput.setText(zone.name)
            val poly = AvoidZoneStore.decode(zone.polygon)
            if (poly.size >= 3) {
                val lats = poly.map { it.first }
                val lngs = poly.map { it.second }
                centerLat = (lats.max() + lats.min()) / 2.0
                centerLng = (lngs.max() + lngs.min()) / 2.0
                hasCenter = true
                // 反推宽高并夹到滑块范围（老数据是正方形，宽高会相同）
                val (w, h) = AvoidZoneStore.sizeM(poly)
                widthM = w.coerceIn(400.0, 2200.0)
                heightM = h.coerceIn(400.0, 2200.0)
                findViewById<SeekBar>(R.id.aze_width).progress =
                    ((widthM - 400.0) / 100.0).toInt().coerceIn(0, 18)
                findViewById<SeekBar>(R.id.aze_height).progress =
                    ((heightM - 400.0) / 100.0).toInt().coerceIn(0, 18)
                renderSize()
            }
            val map = aMap
            if (map != null && centerLat != 0.0) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLng), 15f))
            }
            drawPreview()
        }
    }

    private fun save() {
        val name = nameInput.text?.toString()?.trim().orEmpty()
            .ifBlank { getString(R.string.avoid_zone_default_name) }
        // hasCenter 为假说明定位还没回来、用户也还没动地图，
        // 此时坐标只是高德默认城市，存下去就是一条错误的避让区
        if (!hasCenter || centerLat == 0.0 || centerLng == 0.0) {
            Toast.makeText(this, R.string.avoid_zone_need_center, Toast.LENGTH_SHORT).show()
            return
        }
        // 面积超限必须拦下：直接下发高德会返回空路线，用户会以为"导航坏了"
        if (widthM > AvoidZoneStore.MAX_SIDE_M || heightM > AvoidZoneStore.MAX_SIDE_M) {
            Toast.makeText(this, R.string.avoid_zone_too_large, Toast.LENGTH_LONG).show()
            return
        }
        val poly = AvoidZoneStore.rectAround(centerLat, centerLng, widthM, heightM)
        saveBtn.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                val dao = AppDatabase.get(this@AvoidZoneEditActivity).avoidZoneDao()
                if (editingId > 0) {
                    val old = dao.allOnce().firstOrNull { it.id == editingId }
                    if (old != null) {
                        dao.update(
                            old.copy(
                                name = name,
                                polygon = AvoidZoneStore.encode(poly)
                            )
                        )
                    }
                } else {
                    dao.insert(
                        AvoidZoneEntity(
                            name = name,
                            polygon = AvoidZoneStore.encode(poly)
                        )
                    )
                }
            }.onSuccess {
                setResult(Activity.RESULT_OK)
                finish()
            }.onFailure {
                saveBtn.isEnabled = true
                Toast.makeText(
                    this@AvoidZoneEditActivity,
                    R.string.avoid_zone_save_fail,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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

    companion object {
        private const val EXTRA_ID = "zone_id"

        fun intent(ctx: Context, zoneId: Long = 0L): Intent =
            Intent(ctx, AvoidZoneEditActivity::class.java).putExtra(EXTRA_ID, zoneId)
    }
}
