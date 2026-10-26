package com.motohud.ble.ui.navi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.motohud.ble.R
import com.motohud.ble.data.NaviTrack
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * 轨迹缩略图（列表用）。
 *
 * 用 `Canvas` 直接画折线，不加载地图瓦片——40~56dp 的小方块里加载地图既慢又糊，
 * 而且列表滚动时会疯狂请求瓦片。这里只做"形状暗示"，让用户一眼分辨哪条记录走过哪里。
 *
 * 没有轨迹（老记录）时画一条淡淡的占位斜线，不显示空白方块。
 */
class TrackThumbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var points: List<Pair<Double, Double>> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.track_ahead)
    }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.text_tertiary)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.fill)
    }

    private val path = Path()

    fun setTrack(raw: String?) {
        points = NaviTrack.decode(raw)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = dp(10f)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bgPaint)

        val pad = dp(8f)
        val w = width - pad * 2
        val h = height - pad * 2
        if (w <= 0 || h <= 0) return

        if (points.size < 2) {
            canvas.drawLine(pad, height - pad, width - pad, pad, emptyPaint)
            return
        }
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        points.forEach {
            minLat = minOf(minLat, it.first)
            maxLat = maxOf(maxLat, it.first)
            minLng = minOf(minLng, it.second)
            maxLng = maxOf(maxLng, it.second)
        }
        // 经度方向按纬度余弦压缩，避免高纬度轨迹被拉得过宽
        val latSpan = max(maxLat - minLat, 1e-6)
        val lngSpan = max((maxLng - minLng) * 0.85, 1e-6)
        val scale = minOf(w / lngSpan.toFloat(), h / latSpan.toFloat())

        path.reset()
        points.forEachIndexed { i, p ->
            val x = pad + ((p.second - minLng) * scale).toFloat() +
                (w - (lngSpan * scale).toFloat()) / 2f
            val y = pad + ((maxLat - p.first) * scale).toFloat() +
                (h - (latSpan * scale).toFloat()) / 2f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // 单点/极短轨迹：画一个小圆点，避免什么都看不见
        if (points.size == 2) {
            canvas.drawPath(path, linePaint)
        } else {
            canvas.drawPath(path, linePaint)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
