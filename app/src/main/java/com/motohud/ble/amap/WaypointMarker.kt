package com.motohud.ble.amap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * 途经点序号徽标（白描边 + 蓝底白字）。
 *
 * 选路页与导航页共用同一套画法：两处都画成"同一个点在同一个位置是同一个编号"，
 * 用户从选路页看"第 2 个途经点"到导航页还是那个 2，不需要重新认。
 *
 * 用位图而不是准备 5 套 drawable：编号是数据驱动的（1..5），
 * 而且以后上限调整不用再补资源。
 */
object WaypointMarker {

    fun badge(ctx: Context, n: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val size = (24 * d).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // 白色描边：压在地图任何底色上都分得清
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = 0xFF1E88E5.toInt()
        c.drawCircle(size / 2f, size / 2f, size / 2f - d * 1.5f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = size * 0.52f
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        val ty = size / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(n.toString(), size / 2f, ty, p)
        return bmp
    }
}
