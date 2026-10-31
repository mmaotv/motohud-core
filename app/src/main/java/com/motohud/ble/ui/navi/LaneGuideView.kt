package com.motohud.ble.ui.navi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.motohud.ble.navi.Arrow
import com.motohud.ble.navi.LaneGuide
import kotlin.math.min

/**
 * 车道线指引。
 *
 * 为什么自绘而不是拿 SDK 的图：SDK 的车道图是 `AMapNaviView` 内部资源，
 * 官方没有公开"车道箭头位图"的取用接口（`assets/navi` 下的图名是混淆的、
 * 且按设备密度分桶），外接很难稳定命中。自绘的好处：
 *  - 颜色跟随主题（深色底/亮色底都能用，投屏也一致）；
 *  - 横屏 360dp 高度下能压到 30dp 而不糊；
 *  - 可用车道与不可用车道的**对比**可以自己定（高德就是灰掉不可走的）。
 *
 * 画法与高德观感一致：每条车道一个圆角格子，格子里并排画 1~3 个箭头，
 * 可走的用强调色实心描边，不可走的整格压暗。
 */
class LaneGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var guide: LaneGuide? = null

    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 强调色（可走车道） */
    var accentColor: Int = Color.parseColor("#FFFF9F0A")
        set(v) { field = v; invalidate() }

    /** 弱化色（不可走车道） */
    var dimColor: Int = Color.parseColor("#66FFFFFF")
        set(v) { field = v; invalidate() }

    /** 格子底色（不可走 / 判定不出可用性时） */
    var laneBgColor: Int = Color.parseColor("#1AFFFFFF")
        set(v) { field = v; invalidate() }

    /** 可走车道的格子底色：整格提亮，远看先分辨"哪几格是亮的" */
    var laneBgActiveColor: Int = Color.parseColor("#33FFFFFF")
        set(v) { field = v; invalidate() }

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
            lanePaint.color = if (usable) laneBgActiveColor else laneBgColor
            canvas.drawRoundRect(rect, radius, radius, lanePaint)

            arrowPaint.color = if (usable) accentColor else dimColor
            arrowPaint.strokeWidth = (3.0f * density).coerceAtMost(rect.width() / 8f)

            val arrows = g.arrowsAt(i)
            if (arrows.isNotEmpty()) drawGlyph(canvas, rect, arrows)
            x += cellW + gap
        }
    }

    /**
     * 一条车道画**一个**整字形：一根主干，到分叉点后按动作分出去。
     *
     * 早先是"每个动作各画一个独立箭头横向并排"，结果是两个箭头挤在
     * 26dp 的格子里互相覆盖，糊成一团（真机截图已复现）。
     * 高德的做法本来就是"主干分叉"：直行+左转 = 一根竖线在顶上分左，
     * 直行+左转+右转 = 三叉。这样无论几个动作都只有一个主干，永远不会叠。
     */
    private fun drawGlyph(canvas: Canvas, rect: RectF, arrows: IntArray) {
        val cx = rect.centerX()
        val bottom = rect.bottom - rect.height() * 0.12f
        val forkY = rect.top + rect.height() * 0.46f
        val topY = rect.top + rect.height() * 0.14f
        val head = rect.height() * 0.20f
        val dx = rect.width() * 0.26f

        val hasStraight = arrows.contains(Arrow.STRAIGHT)
        val hasLeft = arrows.contains(Arrow.LEFT)
        val hasRight = arrows.contains(Arrow.RIGHT)
        val hasUturnL = arrows.contains(Arrow.UTURN_LEFT)
        val hasUturnR = arrows.contains(Arrow.UTURN_RIGHT)
        val hasBus = arrows.contains(Arrow.BUS)

        // 主干：所有动作共用（这是不重叠的关键）
        val stem = Path().apply {
            moveTo(cx, bottom)
            lineTo(cx, forkY)
        }
        canvas.drawPath(stem, arrowPaint)

        if (hasBus) {
            drawBus(canvas, rect)
            return
        }

        // 直行：主干直接顶到箭头；否则主干只到分叉点
        if (hasStraight) {
            canvas.drawLine(cx, forkY, cx, topY, arrowPaint)
            drawHead(canvas, cx, topY, DIR_UP, head)
        }
        // 左转：分叉 → 左上斜出 → 箭头朝左
        if (hasLeft) {
            val leftX = cx - dx
            val branchY = forkY - rect.height() * 0.16f
            val p = Path().apply {
                moveTo(cx, forkY)
                quadTo(cx - dx * 0.35f, branchY, leftX, branchY)
            }
            canvas.drawPath(p, arrowPaint)
            drawHead(canvas, leftX, branchY, DIR_LEFT, head)
        }
        // 右转
        if (hasRight) {
            val rightX = cx + dx
            val branchY = forkY - rect.height() * 0.16f
            val p = Path().apply {
                moveTo(cx, forkY)
                quadTo(cx + dx * 0.35f, branchY, rightX, branchY)
            }
            canvas.drawPath(p, arrowPaint)
            drawHead(canvas, rightX, branchY, DIR_RIGHT, head)
        }
        // 掉头（左/右）：从分叉点向上兜小半圈再向下回
        if (hasUturnL || hasUturnR) {
            val sign = if (hasUturnL) -1f else 1f
            val tipX = cx + sign * dx * 0.95f
            val curlY = forkY - rect.height() * 0.20f
            val p = Path().apply {
                moveTo(cx, forkY)
                quadTo(cx + sign * dx * 0.5f, curlY - head * 0.4f, tipX, curlY)
                lineTo(tipX, curlY + head * 0.9f)
            }
            canvas.drawPath(p, arrowPaint)
            drawHead(canvas, tipX, curlY + head * 1.4f, DIR_DOWN, head)
        }
    }

    /** 公交专用道：画一个方块 + 两道横杠，不做文字（避免又多一处硬编码中文） */
    private fun drawBus(canvas: Canvas, box: RectF) {
        val w = box.width() * 0.62f
        val h = box.height() * 0.5f
        val r = RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
        canvas.drawRoundRect(r, 2f, 2f, arrowPaint.also { it.style = Paint.Style.STROKE })
        val p = Paint(arrowPaint)
        p.style = Paint.Style.FILL
        canvas.drawRect(r.left + w * 0.14f, r.top + h * 0.22f, r.right - w * 0.14f, r.top + h * 0.42f, p)
        canvas.drawRect(r.left + w * 0.14f, r.bottom - h * 0.42f, r.right - w * 0.14f, r.bottom - h * 0.22f, p)
        arrowPaint.style = Paint.Style.STROKE
    }

    private fun drawHead(canvas: Canvas, x: Float, y: Float, dir: Int, size: Float) {
        val path = Path()
        when (dir) {
            DIR_UP -> {
                path.moveTo(x - size * 0.5f, y + size * 0.72f)
                path.lineTo(x, y)
                path.lineTo(x + size * 0.5f, y + size * 0.72f)
            }
            DIR_DOWN -> {
                path.moveTo(x - size * 0.5f, y - size * 0.72f)
                path.lineTo(x, y)
                path.lineTo(x + size * 0.5f, y - size * 0.72f)
            }
            DIR_LEFT -> {
                path.moveTo(x + size * 0.72f, y - size * 0.5f)
                path.lineTo(x, y)
                path.lineTo(x + size * 0.72f, y + size * 0.5f)
            }
            else -> {
                path.moveTo(x - size * 0.72f, y - size * 0.5f)
                path.lineTo(x, y)
                path.lineTo(x - size * 0.72f, y + size * 0.5f)
            }
        }
        canvas.drawPath(path, arrowPaint)
    }

    private companion object {
        const val DIR_UP = 0
        const val DIR_DOWN = 1
        const val DIR_LEFT = 2
        const val DIR_RIGHT = 3
    }
}
