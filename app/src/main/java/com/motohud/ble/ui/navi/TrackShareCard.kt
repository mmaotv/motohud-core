package com.motohud.ble.ui.navi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.motohud.ble.data.NaviHistoryEntity
import com.motohud.ble.navi.CoordConverter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把「地图截图 + 本次数据」拼成一张可以直接发出去的骑行卡片。
 *
 * 为什么要自己拼而不是直接发地图截图：地图截图只有一条线，没有任何上下文 ——
 * 发到群里别人不知道这是哪次骑行、多远、骑了多久。加一条数据带，一张图就说完了。
 *
 * 尺寸按地图截图宽度自适应（真机 720×1612 密度 2.0 时约 1080 宽），
 * 不做固定像素，换机不会变形。
 */
object TrackShareCard {

    /** 数据带高度（相对卡片宽度的比例），保证不同分辨率下版式一致 */
    private const val BAND_RATIO = 0.30f

    fun compose(ctx: Context, map: Bitmap, row: NaviHistoryEntity): Bitmap {
        val w = map.width.coerceAtLeast(1)
        val bandH = (w * BAND_RATIO).toInt().coerceAtLeast(220)
        val out = Bitmap.createBitmap(w, map.height + bandH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        // 地图部分（底图由 SDK 渲染，直接贴上去）
        c.drawBitmap(map, 0f, 0f, null)

        // 数据带：纯色深底，不用半透明 —— 分享图常被压缩，半透明会糊成一片
        val band = Paint().apply { color = Color.parseColor("#101418") }
        c.drawRect(0f, map.height.toFloat(), w.toFloat(), out.height.toFloat(), band)

        val pad = w * 0.045f
        val top = map.height.toFloat()

        // 目的地
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = w * 0.052f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        c.drawText(ellipsize(row.destName.ifBlank { "骑行记录" }, title, w - pad * 2), pad, top + bandH * 0.28f, title)

        // 时间 + 设备
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8C939B")
            textSize = w * 0.032f
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val whenText = buildString {
            append(fmt.format(Date(row.startedAt)))
            if (row.finishedAt > 0) append(" — ").append(fmt.format(Date(row.finishedAt)))
            row.deviceName?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        c.drawText(ellipsize(whenText, sub, w - pad * 2), pad, top + bandH * 0.46f, sub)

        // 三列数据
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9F0A")
            textSize = w * 0.062f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8C939B")
            textSize = w * 0.030f
        }
        val cols = listOf(
            (if (row.distanceM > 0) CoordConverter.formatDistance(row.distanceM.toInt()) else "--") to "距离",
            (if (row.durationMs > 0) NaviHistoryAdapter.formatDuration(ctx, row.durationMs) else "--") to "用时",
            (row.avgSpeedKmh?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "--") to "均速"
        )
        val colW = (w - pad * 2) / 3f
        cols.forEachIndexed { i, (value, label) ->
            val x = pad + colW * i
            c.drawText(value, x, top + bandH * 0.78f, valuePaint)
            c.drawText(label, x, top + bandH * 0.92f, labelPaint)
        }

        // 右下角水印：不给大 logo，一行小字就够，避免抢数据
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4A5058")
            textSize = w * 0.026f
            textAlign = Paint.Align.RIGHT
        }
        c.drawText("MotoHUD", w - pad, top + bandH * 0.97f, mark)

        return out
    }

    /** 超宽文本截断加省略号（目的地名可能很长） */
    private fun ellipsize(text: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(text) <= maxW) return text
        var s = text
        while (s.length > 1 && paint.measureText("$s…") > maxW) {
            s = s.dropLast(1)
        }
        return "$s…"
    }
}
