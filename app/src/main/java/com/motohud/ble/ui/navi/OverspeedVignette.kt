package com.motohud.ble.ui.navi

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 超速红光：屏幕**四周一圈**红色渐变 + 呼吸脉冲，中心完全透明。
 *
 * 参考高德导航的做法（超速时左上角时速变红、屏幕两侧红光闪动）：
 * 用余光就能察觉，又不像"屏幕中央弹个红框"那样挡住转向箭头和地图 ——
 * 用户上一轮刚要求把顶部遮罩收窄，这里绝不能再来一层大面积遮挡。
 *
 * 为什么自己画而不用 layer-list：四条边各要一条 LinearGradient，
 * 且宽度要按横竖屏动态给（横屏屏矮，56dp 会吃掉小半张地图）。
 *
 * **不吃触摸**：clickable/focusable 全关，View.dispatchTouchEvent 返回 false，
 * 事件会继续传给下面的地图和按钮。
 */
class OverspeedVignette @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bandPx = 0f
    private var pulse: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0.55f
    }

    /** 红光带宽（dp）：竖屏 56、横屏 34，由导航页按方向给 */
    fun setBandDp(dp: Float) {
        bandPx = dp * resources.displayMetrics.density
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val b = bandPx.coerceAtMost(minOf(w, h) * 0.45f)
        if (b <= 0f) return
        // 边缘约 75% 不透明，往里线性渐隐到全透明；再叠 View.alpha 做呼吸
        val edge = Color.argb(190, 255, 40, 36)
        val clear = Color.argb(0, 255, 40, 36)

        paint.shader = LinearGradient(0f, 0f, 0f, b, edge, clear, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, b, paint)

        paint.shader = LinearGradient(0f, h - b, 0f, h, clear, edge, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h - b, w, h, paint)

        paint.shader = LinearGradient(0f, 0f, b, 0f, edge, clear, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, b, h, paint)

        paint.shader = LinearGradient(w - b, 0f, w, 0f, clear, edge, Shader.TileMode.CLAMP)
        canvas.drawRect(w - b, 0f, w, h, paint)
    }

    /** 约 0.9Hz 的呼吸（往返 1.1s），比常亮更抓余光，也不会一直刺眼 */
    fun startPulse() {
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(0.42f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    fun stopPulse() {
        pulse?.cancel()
        pulse = null
        alpha = 0.55f
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }
}
