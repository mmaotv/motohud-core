package com.motohud.ble.ui.navi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.navi.CoordConverter

/**
 * 地图选点：屏幕上有一枚固定指针，拖动地图把想选的点挪到指针下，按确定回传坐标。
 *
 * 只干这一件事，起点 / 目的地都能用。
 * 逆地理编码（把坐标转成文字地址）要额外发一次请求，而这里的用途是"起点"，
 * 名称用不上多精确，所以直接回传坐标 + 「地图选点」这个名字，不多花流量。
 */
class MapPickActivity : AppCompatActivity() {

    private var mapView: MapView? = null
    private var aMap: AMap? = null

    private lateinit var coord: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 同 PlaceDetailActivity：高德隐私合规必须早于 MapView 的 inflate，
        // 否则进程刚起（冷启动）时布局里的 MapView 构造会失败
        val mapReady = AmapManager.hasKey(this) &&
            AmapManager.isPrivacyAgreed(this) &&
            runCatching { AmapManager.initMap(this) }.getOrDefault(false)
        setContentView(R.layout.activity_map_pick)
        coord = findViewById(R.id.pick_coord)

        findViewById<android.view.View>(R.id.pick_btn_back).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.pick_confirm).setOnClickListener { confirm() }

        val startLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val startLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)

        if (!mapReady) {
            Toast.makeText(this, R.string.route_select_no_map, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        runCatching {
            mapView = findViewById(R.id.pick_map)
            mapView?.onCreate(savedInstanceState)
            aMap = mapView?.map?.apply {
                com.motohud.ble.amap.AmapMapConfig.applyDisplay(this)
                uiSettings.apply {
                    isZoomControlsEnabled = false
                    isMyLocationButtonEnabled = false
                    // 路况图层在这一页毫无用处（只在挑点），顺手关掉省流量
                    runCatching { isMyLocationEnabled = false }
                    runCatching { setTrafficEnabled(false) }
                }
                // 每次地图移动都刷新中心点坐标
                setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                    override fun onCameraChange(pos: com.amap.api.maps.model.CameraPosition?) {}
                    override fun onCameraChangeFinish(pos: com.amap.api.maps.model.CameraPosition?) {
                        pos?.target?.let { renderCoord(it) }
                    }
                })
            }
        }

        val map = aMap
        if (map == null) {
            finish()
            return
        }
        val center = if (startLat != 0.0 && startLng != 0.0) LatLng(startLat, startLng) else null
        if (center != null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(center, 16f))
            renderCoord(center)
        } else {
            renderCoord(map.cameraPosition.target)
        }
    }

    private fun renderCoord(p: LatLng) {
        coord.text = CoordConverter.formatLatLng(p.latitude, p.longitude)
    }

    private fun confirm() {
        val target = aMap?.cameraPosition?.target
        if (target == null || target.latitude == 0.0 && target.longitude == 0.0) {
            Toast.makeText(this, R.string.origin_map_failed, Toast.LENGTH_SHORT).show()
            return
        }
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_LAT, target.latitude)
                putExtra(EXTRA_LNG, target.longitude)
            }
        )
        finish()
    }

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
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"

        fun intent(ctx: Context, lat: Double = 0.0, lng: Double = 0.0): Intent =
            Intent(ctx, MapPickActivity::class.java).apply {
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LNG, lng)
            }
    }
}

/**
 * 给宿主用的便捷注册：拉 Sheet → 选点 → 回调坐标。
 * 放在这里而不是调用方，是因为 Activity Result 的注册必须在 onCreate 之前完成，
 * 写错位置会直接抛异常。
 */
fun ComponentActivity.registerMapPick(
    onResult: (lat: Double, lng: Double) -> Unit
) = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    if (r.resultCode != Activity.RESULT_OK) return@registerForActivityResult
    val lat = r.data?.getDoubleExtra(MapPickActivity.EXTRA_LAT, 0.0) ?: 0.0
    val lng = r.data?.getDoubleExtra(MapPickActivity.EXTRA_LNG, 0.0) ?: 0.0
    if (lat != 0.0 && lng != 0.0) onResult(lat, lng)
}
