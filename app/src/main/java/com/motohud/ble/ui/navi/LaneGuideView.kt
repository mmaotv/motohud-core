package com.motohud.ble.ui.navi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.motohud.ble.R
import com.motohud.ble.navi.Arrow
import com.motohud.ble.navi.LaneGuide
import kotlin.math.min

/**
 * 车道线指引。
 *
 * **箭头用高德官方的车道图标（`amap_navi_action*`），不再自绘。**
 *
 * 这些图和解压出来的 `amap_navi_lbs_sou*`（转向卡那套"白箭头+灰环"）不是一回事：
 * action 系列是**单色填充的纯箭头**（无圆环、无描边，原图 40x40 灰色 #616161），
 * 正好是高德车道卡片里那套箭头。因为它只有一种颜色、背景全透明，所以能直接
 * 用 `PorterDuffColorFilter(SRC_IN)` 染色 —— 可走的染亮、不可走的染暗，
 * 这就是"官方图也能配色"的办法，不用自己再画一遍。
 *
 * 对应表（官方 action 编号）：
 * ```
 * 0 直行   2 左转   3 右转   4 左前   5 右前   8 左掉头
 * ```
 * 右掉头没有单独的图，用 8 水平镜像；公交道官方没有车道箭头，按直行画
 * （能不能走本来就由整格高亮表达，不靠箭头形状）。
 *
 * 一条车道有多种动作时（直行+左转、左转+右转…）**横向并排多个官方箭头**，
 * 早先自绘的"主干分叉"写法已经删掉 —— 分叉图看着像一根树枝，而高德车道卡
 * 里就是一个格子里摆几个箭头，照它来。
 */
class LaneGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var guide: LaneGuide? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 画官方箭头：FILTER_BITMAP_FLAG 必须开，3 倍图缩到 30dp 才不会出锯齿 */
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 官方 action 图按需解码，一次一张，避免每帧 decode */
    private val arrowCache = HashMap<Int, Bitmap>()

    /**
     * 四个颜色全部从**主题属性**读（`nvLaneBg` / `nvLaneBgActive` /
     * `nvLaneArrow` / `nvLaneArrowDim`），不再由调用方传 `nightMode`。
     *
     * 为什么改：早先是 `laneGuideView.nightMode = resolveDark(aMap)`，跟地图同源
     * 但仍然只有"深/浅"两档，而这个 View 的底色本该和它所在的那块面板一致
     * （面板是玻璃白，格子却是半透明白，会糊在一起）。走主题之后它跟整页配色
     * 天然一致，也不用再担心哪天忘了传。
     *
     * **白天就是一块交通指示牌**：蓝底白箭头 —— 现实里的车道指示牌就是这个配色
     * （GB 5768 指示标志蓝 #1466CC），白天强光下最跳。夜间是深色底的半透明白。
     */
    private var accentColor: Int = Color.WHITE
    private var dimColor: Int = Color.parseColor("#59FFFFFF")
    private var laneBgColor: Int = Color.parseColor("#1AFFFFFF")
    private var laneBgActiveColor: Int = Color.parseColor("#33FFFFFF")

    init {
        readPalette()
    }

    /**
     * 从当前 context 的主题取四个颜色。
     *
     * 用 `resolveAttribute` 而不是 `obtainStyledAttributes`：属性值可能指向
     * 颜色资源（`@color/xxx`）也可能是内联色值，`resolveAttribute` + `data`
     * 两条路都能覆盖。
     */
    private fun readPalette() {
        accentColor = themeColor(R.attr.nvLaneArrow, Color.WHITE)
        dimColor = themeColor(R.attr.nvLaneArrowDim, Color.parseColor("#59FFFFFF"))
        laneBgColor = themeColor(R.attr.nvLaneBg, Color.parseColor("#1AFFFFFF"))
        laneBgActiveColor = themeColor(R.attr.nvLaneBgActive, Color.parseColor("#33FFFFFF"))
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return fallback
        return when {
            tv.resourceId != 0 -> ContextCompat.getColor(context, tv.resourceId)
            tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                tv.type <= TypedValue.TYPE_LAST_COLOR_INT -> tv.data
            else -> fallback
        }
    }

    /**
     * 主题换了（Activity 重建 / 手动再套一层 theme）之后重新取色。
     * 正常流程里不会用到 —— 日/夜是进页面时定的 —— 但留着不吃亏。
     */
    fun reloadPalette() {
        readPalette()
        invalidate()
    }

    fun setGuide(g: LaneGuide?) {
        // 内容相同就不重绘：SDK 每秒都会回调一次 showLaneInfo
        if (guide == g) return
        guide = g
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val n = guide?.count ?: 0
        // 宽度：布局给了 match_parent（侧栏整宽）就用满，没给就按车道数估。
        // 以前这里只按 wrap_content 估 26dp/格，4 条道才 116dp —— 车机视角下
        // 箭头只有指甲盖大，等于没画。现在单格最大 60dp，4 条道能铺满 300dp 侧栏。
        val cellW = (60 * density).toInt()
        val desiredW = (n * cellW + (n + 1) * (6 * density).toInt()).coerceAtLeast(cellW)
        val w = resolveSize(desiredW, widthMeasureSpec)
        val h = resolveSize((56 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = guide ?: return
        if (g.count <= 0) return

        val density = resources.displayMetrics.density
        val gap = 6 * density
        val padV = 2 * density
        val cellH = height - 2 * padV
        if (cellH <= 0f) return
        // 格子宽度在可用宽度里均分，单格封顶 60dp：车道少时不会拉成一根柱，
        // 车道多时（6~8 条）自动收窄，一组始终居中、不溢出。
        val avail = width - paddingLeft - paddingRight
        val cellW = min(60 * density, (avail - gap * (g.count - 1)) / g.count)
        if (cellW <= 0f) return

        val radius = 6 * density
        var x = paddingLeft + (avail - (cellW * g.count + gap * (g.count - 1))) / 2f

        for (i in 0 until g.count) {
            val rect = RectF(x, padV, x + cellW, padV + cellH)
            val usable = g.isUsable(i)
            // 可走的车道整格提亮（高德就是这么做的）：远看先分"哪几格是亮的"，
            // 再看格里的箭头，比只给箭头换颜色更容易在颠簸中一眼扫到。
            bgPaint.color = if (usable) laneBgActiveColor else laneBgColor
            canvas.drawRoundRect(rect, radius, radius, bgPaint)

            val arrows = g.arrowsAt(i)
            if (arrows.isNotEmpty()) {
                drawArrows(canvas, rect, arrows, if (usable) accentColor else dimColor)
            }
            x += cellW + gap
        }
    }

    /**
     * 在格子内摆官方箭头。
     *
     * 单个动作就放大到格子 86%；多个动作横向等分（直行+左转、左转+右转…），
     * 每个箭头仍保持正方形比例，不做拉伸 —— 官方图一旦被压扁，"左转"会看着像"左前"。
     */
    private fun drawArrows(canvas: Canvas, rect: RectF, arrows: IntArray, color: Int) {
        val n = arrows.size
        // 竖向只留 5%：横屏车道行才 44dp 高，官方图本身四周就有留白，
        // 再给它加内边距会显得箭头缩在格子里（真机对比过高德的箭头是顶满的）
        val inset = rect.height() * 0.05f
        val areaW = rect.width() - inset * 2
        val areaH = rect.height() - inset * 2
        if (areaW <= 0f || areaH <= 0f) return

        val slot = areaW / n
        // 正方形按短边定尺寸，多箭头时自然收窄，不会互相压住
        val side = min(slot * 0.94f, areaH)
        val top = rect.centerY() - side / 2f

        for (k in 0 until n) {
            val bmp = bitmapFor(arrows[k]) ?: continue
            val cx = rect.left + inset + slot * (k + 0.5f)
            val dst = RectF(cx - side / 2f, top, cx + side / 2f, top + side)
            arrowPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            if (arrows[k] == Arrow.UTURN_RIGHT) {
                // 官方只有左掉头（action8），右掉头镜像一份，仍不改图本身
                canvas.save()
                canvas.scale(-1f, 1f, dst.centerX(), dst.centerY())
                canvas.drawBitmap(bmp, null, dst, arrowPaint)
                canvas.restore()
            } else {
                canvas.drawBitmap(bmp, null, dst, arrowPaint)
            }
        }
    }

    private fun bitmapFor(arrow: Int): Bitmap? {
        val res = when (arrow) {
            Arrow.LEFT -> R.drawable.amap_navi_action2
            Arrow.RIGHT -> R.drawable.amap_navi_action3
            Arrow.UTURN_LEFT, Arrow.UTURN_RIGHT -> R.drawable.amap_navi_action8
            // 直行 / 公交道：公交道官方没有车道箭头，按直行画，
            // 能不能走已由整格高亮表达，不靠箭头形状
            else -> R.drawable.amap_navi_action0
        }
        arrowCache[res]?.let { if (!it.isRecycled) return it }
        // inScaled=false：拿 xxhdpi 那张 120px 原图，缩放交给 canvas（等比、带过滤）
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val bmp = runCatching { BitmapFactory.decodeResource(resources, res, opts) }.getOrNull()
            ?: return null
        arrowCache[res] = bmp
        return bmp
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        arrowCache.values.forEach { if (!it.isRecycled) it.recycle() }
        arrowCache.clear()
    }
}
