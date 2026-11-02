package com.motohud.ble.amap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.motohud.ble.R

/**
 * 「当前位置」地图图标：摩托车俯视剪影，车头朝上（正北方向由调用方按行进方向旋转）。
 *
 * 为什么自己造图标而不用高德原生蓝点：原生蓝点只是纯色圆点，看不出车。
 *
 * 车头朝向由 Marker 的 rotateAngle 控制（见 GuidanceActivity.updateMeMarker），
 * 图标本身「上」= 车头方向。
 *
 * 尺寸：按 32dp 出图，再乘屏幕密度换算成像素（AMap 的 fromBitmap 按像素 1:1 绘制），
 * 各分辨率下都清晰。带缓存。
 *
 * 健壮性：矢量图若因任何原因画不出来（pathData 异常、资源缺失），兜底画一个蓝底白环，
 * 至少不会出现「地图上没有车标」这种更糟的情况。
 */
object VehicleMarker {

    /** 图标视觉直径（dp），与原 defaultMarker 点位尺寸相当 */
    private const val DIAMETER_DP = 32

    @Volatile
    private var cached: BitmapDescriptor? = null

    @Volatile
    private var cachedDensity: Float = 0f

    fun icon(context: Context): BitmapDescriptor {
        val density = context.resources.displayMetrics.density
        cached?.let { if (cachedDensity == density) return it }
        return synchronized(this) {
            val again = cached
            if (again != null && cachedDensity == density) {
                again
            } else {
                val app = context.applicationContext
                val px = (DIAMETER_DP * density).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                var vectorOk = false
                try {
                    val d = ContextCompat.getDrawable(app, R.drawable.ic_vehicle_motorcycle)
                    if (d != null) {
                        d.setBounds(0, 0, px, px)
                        d.draw(canvas)
                        vectorOk = (bmp.getPixel(px / 2, px / 2) ushr 24) > 8
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("VehicleMarker", "摩托车矢量图标绘制失败，使用兜底圆点", e)
                }
                if (!vectorOk) {
                    drawFallbackCircle(canvas, px)
                }
                android.util.Log.i(
                    "VehicleMarker",
                    "icon px=$px vectorOk=$vectorOk centerAlpha=${bmp.getPixel(px / 2, px / 2) ushr 24}"
                )
                val bd = BitmapDescriptorFactory.fromBitmap(bmp)
                cached = bd
                cachedDensity = density
                bd
            }
        }
    }

    /** 兜底：蓝底 + 白环 + 白色中心点，保证地图上一定有可见的定位标 */
    private fun drawFallbackCircle(canvas: Canvas, px: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#FF2C7BE5")
        canvas.drawCircle(px / 2f, px / 2f, px * 0.46f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = px * 0.07f
        p.color = Color.WHITE
        canvas.drawCircle(px / 2f, px / 2f, px * 0.42f, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        canvas.drawCircle(px / 2f, px / 2f, px * 0.12f, p)
    }
}
