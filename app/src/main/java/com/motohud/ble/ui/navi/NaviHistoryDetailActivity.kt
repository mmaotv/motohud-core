package com.motohud.ble.ui.navi

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.motohud.ble.MotoHudApp
import com.motohud.ble.R
import com.motohud.ble.amap.AmapManager
import com.motohud.ble.data.NaviHistoryEntity
import com.motohud.ble.data.NaviTrack
import com.motohud.ble.navi.CoordConverter
import com.motohud.ble.navi.TrackSmooth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导航历史详情 —— 行驶轨迹回放。
 *
 * 视觉对齐高德：
 *  ① 全屏夜间地图，隐藏缩放按钮；
 *  ② 轨迹**双层描边**（外层宽线深色半透明打底，内层主色细线），
 *     这是"看起来像专业地图"最省力的一招；
 *  ③ 起点绿点 / 终点红点，白字徽标，anchor 落在圆心下方不被线压住；
 *  ④ `LatLngBounds` 自动全览（padding 64dp），单点时 zoom 16；
 *  ⑤ 底部 `BottomSheetBehavior` 统计卡，可上滑；主按钮「再次导航」。
 *
 * 老记录（升级前的导航，没有 trackPolyline）走兜底：只用起终点画一条虚线，
 * 并明确提示"本次导航未记录轨迹"，不装作有轨迹。
 */
class NaviHistoryDetailActivity : AppCompatActivity() {

    private var mapView: MapView? = null
    private var aMap: AMap? = null
    private var entity: NaviHistoryEntity? = null

    /** 分享流程进行中：挡 SDK 的两个回调重载重复触发 */
    private var shareBusy = false

    private lateinit var tvDest: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvAvg: TextView
    private lateinit var tvMax: TextView
    private lateinit var tvDevice: TextView
    private lateinit var tvNoTrack: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navi_history_detail)

        val topBar = findViewById<View>(R.id.detail_top_bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(8))
            insets
        }

        tvDest = findViewById(R.id.detail_dest)
        tvTime = findViewById(R.id.detail_time)
        tvDistance = findViewById(R.id.detail_distance)
        tvDuration = findViewById(R.id.detail_duration)
        tvAvg = findViewById(R.id.detail_avg_speed)
        tvMax = findViewById(R.id.detail_max_speed)
        tvDevice = findViewById(R.id.detail_device)
        tvNoTrack = findViewById(R.id.detail_no_track)

        findViewById<View>(R.id.detail_btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.detail_btn_again).setOnClickListener { naviAgain() }
        findViewById<View>(R.id.detail_btn_share).setOnClickListener { shareTrack() }

        // 底部卡：peek 只露统计，上滑看得更全
        val sheet = findViewById<View>(R.id.detail_sheet)
        runCatching {
            BottomSheetBehavior.from(sheet).apply {
                peekHeight = dp(250)
                isHideable = false
                state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }

        mapView = findViewById<MapView?>(R.id.detail_map)
        val id = intent.getLongExtra(EXTRA_ID, 0L)

        if (AmapManager.initMap(this)) {
            runCatching {
                mapView?.onCreate(savedInstanceState)
                aMap = mapView?.map?.apply {
                    com.motohud.ble.amap.AmapMapConfig.applyDisplay(this)
                    uiSettings.isZoomControlsEnabled = false
                    uiSettings.isMyLocationButtonEnabled = false
                    uiSettings.isRotateGesturesEnabled = true
                    runCatching { isMyLocationEnabled = false }
                }
            }
        } else {
            // 没配 Key / 未同意隐私：地图不可用，卡片照样能看数据
            tvNoTrack.visibility = View.VISIBLE
            (tvNoTrack as TextView).setText(R.string.navi_history_no_map)
        }

        load(id)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun load(id: Long) {
        val app = application as MotoHudApp
        lifecycleScope.launch {
            val row = withContext(Dispatchers.IO) {
                app.naviHistoryDao.listAll().firstOrNull { it.id == id }
            }
            entity = row ?: return@launch
            bindCard(row)
            drawTrack(row)
        }
    }

    private fun bindCard(row: NaviHistoryEntity) {
        tvDest.text = row.destName
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val from = fmt.format(Date(row.startedAt))
        val to = if (row.finishedAt > 0) fmt.format(Date(row.finishedAt)) else "--"
        tvTime.text = getString(R.string.navi_history_detail_time, from, to)
        tvDistance.text = if (row.distanceM > 0) {
            CoordConverter.formatDistance(row.distanceM.toInt())
        } else "--"
        tvDuration.text = if (row.durationMs > 0) {
            NaviHistoryAdapter.formatDuration(this, row.durationMs)
        } else "--"
        tvAvg.text = row.avgSpeedKmh?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "--"
        tvMax.text = row.maxSpeedKmh?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "--"

        val name = row.deviceName
        if (name.isNullOrBlank()) {
            tvDevice.visibility = View.GONE
        } else {
            tvDevice.visibility = View.VISIBLE
            val type = row.deviceType?.let { "（$it）" } ?: ""
            tvDevice.text = getString(R.string.navi_history_device_name, "$name$type")
        }
    }

    private fun drawTrack(row: NaviHistoryEntity) {
        val map = aMap ?: return
        val pts = NaviTrack.decode(row.trackPolyline)
        val line: List<Pair<Double, Double>>
        if (pts.size >= 2) {
            // 本地轻量纠偏：去噪 + 几何平滑，让轨迹更干净（不吸附真实道路）
            line = TrackSmooth.smooth(pts)
        } else {
            // 兜底：老记录没有轨迹，只画起终点连线并明确提示
            tvNoTrack.visibility = View.VISIBLE
            line = listOf(
                row.startLat to row.startLng,
                row.destLat to row.destLng
            ).filter { it.first != 0.0 || it.second != 0.0 }
            if (line.size < 2) return
        }

        val latLngs = line.map { LatLng(it.first, it.second) }
        runCatching {
            // 外层：深色半透明宽线，给轨迹一圈"描边"，边缘更干净
            map.addPolyline(
                PolylineOptions()
                    .addAll(latLngs)
                    .width(20f)
                    .color(Color.parseColor("#33000000"))
                    .setDottedLine(false)
            )
            // 内层：主色细线
            map.addPolyline(
                PolylineOptions()
                    .addAll(latLngs)
                    .width(11f)
                    .color(ContextCompat.getColor(this, R.color.track_ahead))
                    .setDottedLine(false)
                    .zIndex(2f)
            )
            // 起终点徽标
            map.addMarker(
                MarkerOptions()
                    .position(latLngs.first())
                    .icon(BitmapDescriptorFactory.fromBitmap(badge("起", R.color.green)))
                    .anchor(0.5f, 0.5f)
                    .zIndex(9f)
            )
            map.addMarker(
                MarkerOptions()
                    .position(latLngs.last())
                    .icon(BitmapDescriptorFactory.fromBitmap(badge("终", R.color.red)))
                    .anchor(0.5f, 0.5f)
                    .zIndex(9f)
            )
            val builder = LatLngBounds.Builder()
            latLngs.forEach { builder.include(it) }
            if (latLngs.size == 1) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 16f))
            } else {
                map.moveCamera(
                    CameraUpdateFactory.newLatLngBounds(builder.build(), dp(64))
                )
            }
        }
    }

    /** 圆底白字徽标位图（起/终） */
    private fun badge(text: String, colorRes: Int): Bitmap {
        val size = dp(30).coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(this@NaviHistoryDetailActivity, colorRes)
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(2), fill)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(5), bg)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(this@NaviHistoryDetailActivity, colorRes)
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val baseline = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, size / 2f, baseline, textPaint)
        return bmp
    }

    /** 再次导航：起点用**当前定位**（用户早已离开），不复制历史起点 */
    private fun naviAgain() {
        val row = entity ?: return
        RouteSelectActivity.start(
            this, row.destName, row.destLat, row.destLng,
            fromLat = 0.0, fromLng = 0.0
        )
        finish()
    }

    // ---------- 分享轨迹图 ----------

    /**
     * 分享轨迹图。
     *
     * 必须走 SDK 的 `getMapScreenShot`，不能截 View：地图是 GL 表面，
     * `View.draw(canvas)` / drawing cache 拿到的是黑的 —— 这是渲染层的硬限制。
     *
     * 回调有两个重载，不同版本/机型走的不是同一个（3D 地图一般给
     * `onMapScreenShot(Bitmap)`，带 status 的那个在某些场景才回），
     * 所以用 [shareBusy] 挡重复，避免共享面板弹两次。
     */
    private fun shareTrack() {
        val row = entity ?: return
        val map = aMap
        if (map == null) {
            toast(getString(R.string.track_share_fail_no_map))
            return
        }
        if (shareBusy) return
        toast(getString(R.string.track_share_rendering))
        runCatching {
            map.getMapScreenShot(object : AMap.OnMapScreenShotListener {
                override fun onMapScreenShot(bitmap: Bitmap?) = realizeShot(row, bitmap)
                override fun onMapScreenShot(bitmap: Bitmap?, status: Int) = realizeShot(row, bitmap)
            })
        }.onFailure { toast(getString(R.string.track_share_fail)) }
    }

    private fun realizeShot(row: NaviHistoryEntity, shot: Bitmap?) {
        if (shareBusy) return
        if (shot == null || shot.width <= 0 || shot.height <= 0) {
            toast(getString(R.string.track_share_fail))
            return
        }
        shareBusy = true
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val card = TrackShareCard.compose(this@NaviHistoryDetailActivity, shot, row)
                    val dir = File(getExternalFilesDir(null) ?: filesDir, "shots").apply { mkdirs() }
                    // 只留最近 5 张：每张约 2MB，留着只会一直占内存卡
                    dir.listFiles()?.sortedByDescending { it.lastModified() }
                        ?.drop(4)?.forEach { it.delete() }
                    val out = File(dir, "track-${System.currentTimeMillis()}.png")
                    FileOutputStream(out).use { card.compress(Bitmap.CompressFormat.PNG, 92, it) }
                    card.recycle()
                    out
                }.getOrNull()
            }
            shareBusy = false
            if (file == null) {
                toast(getString(R.string.track_share_fail))
                return@launch
            }
            runCatching {
                val uri = FileProvider.getUriForFile(
                    this@NaviHistoryDetailActivity, "$packageName.fileprovider", file
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, getString(R.string.track_share)))
            }.onFailure { toast(getString(R.string.track_share_fail)) }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- 生命周期 ----------

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mapView?.onDestroy()
        mapView = null
        aMap = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "extra_history_id"

        fun start(ctx: Context, historyId: Long) {
            ctx.startActivity(
                Intent(ctx, NaviHistoryDetailActivity::class.java)
                    .putExtra(EXTRA_ID, historyId)
            )
        }
    }
}
