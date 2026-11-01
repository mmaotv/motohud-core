package com.motohud.ble.navi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.amap.api.maps.AMap
import com.amap.api.maps.model.CrossOverlay
import com.amap.api.maps.model.CrossOverlayOptions
import com.autonavi.base.ae.gmap.gloverlay.AVectorCrossAttr

/**
 * 把 `AMapModelCross.picBuf1` 渲染成位图。
 *
 * 为什么需要它：路口放大图在 SDK 里有**两条完全不同的数据通路**，
 * 由 `CrossImageInfo.type` 决定（反编译 `com.amap.api.navi.core.t` 得到）：
 *
 * | type | 含义 | 回调节点 | 数据形态 |
 * |---|---|---|---|
 * | 1 `CROSS_IMAGE_TYPE_GRID` | 栅格图，服务端下发 | `showCross(AMapNaviCross)` | PNG，直接 `BitmapFactory` 解码 |
 * | 3 `CROSS_IMAGE_TYPE_VECTOR` | 矢量图，本地绘制 | `showModeCross(AMapModelCross)` | **自定义二进制**（实测头 `168,51,0,0`），解码必失败 |
 *
 * 以前我们只接了第一条：`showModeCross` 里用 `BitmapFactory.decodeByteArray` 解矢量数据，
 * 必然返回 null 然后静默 return —— 也就是说**矢量放大图一张都没显示过**。而矢量图恰恰是
 * 覆盖率高的那一类（本地按路口几何画，不依赖服务端有没有该点位的图）。
 *
 * 渲染路径就是 SDK 自带的 `AMapModeCrossOverlay` 内部做的事：
 * `AMap.addCrossOverlay()` → `setImageMode(true)` → `setData(矢量字节)` →
 * `GenerateCrossImageListener` 拿到离屏渲染出来的位图。
 *
 * 参数（底色 / 箭头颜色 / 线宽 / 区域）全部照抄 `AMapModeCrossOverlay` 的构造默认值，
 * 保证出来的图跟高德自带放大图一个观感。
 *
 * **四个必须同时满足的条件**（缺一个就是 `code=10103` + `bitmap=null`，全都实测踩过）：
 *  1. map 必须是 **`AMapNaviView` 内部的地图**（`shadowNaviView.map`），不能用我们自己的
 *     主 MapView —— 矢量渲染资源随导航视图的地图引擎初始化，普通地图上渲染不出东西；
 *  2. `CrossOverlayOptions.setRes()` 必须给**非空位图**，返回 null 会被
 *     `addCrossOverlay` 直接判失败返回 null（SDK 用的是它自己编译期的资源 id，
 *     合并进我们 APK 后拿不到）；
 *  3. `stAreaRect` 必须**落在该地图的 view 尺寸以内**（给过 1200x744 而地图只有
 *     1612x720 的「超出高度」也失败）；
 *  4. `setImageMode(true)` 要在 `setData` 之前，`setVisible(true)` 在 add 之后补一次。
 */
class VectorCrossRenderer(private val context: Context, private val aMap: AMap) {

    private var overlay: CrossOverlay? = null

    /** 当前渲染任务的 seq，用来丢弃过期回调 */
    private var renderingSeq = -1

    /**
     * @param bytes `AMapModelCross.picBuf1` 原始字节
     * @param seq 调用方给的流水号，回调时原样带回
     * @param width 目标宽（px）。给得越大图越清晰，但渲染耗时线性上升
     * @param onDone 渲染完成；`bitmap == null` 表示失败（GL 没出图，非异常）
     */
    fun render(bytes: ByteArray, seq: Int, width: Int, onDone: (Bitmap?, Int) -> Unit) {
        renderingSeq = seq
        val w = width.coerceIn(320, 1600)
        val h = (w * 0.62f).toInt().coerceAtLeast(200)
        val density = context.resources.displayMetrics.density
        runCatching {
            // 上一次的 overlay 必须先撤，否则同一个 overlay 换数据会一直挂在地图上
            overlay?.let { runCatching { it.remove() } }

            val attr = AVectorCrossAttr().apply {
                // 出图区域（像素）。SDK 默认给「全屏宽 x 屏宽*6/11」，我们要的是右栏卡片比例
                stAreaRect = Rect(0, 0, w, h)
                stAreaColor = Color.argb(217, 95, 95, 95)
                stArrowBorderColor = Color.argb(0, 0, 50, 20)
                fArrowBorderWidth = (22 * density).toInt()
                fArrowLineWidth = (18 * density).toInt()
                stArrowLineColor = Color.argb(255, 255, 253, 65)
                dayMode = true
            }
            val o = aMap.addCrossOverlay(
                CrossOverlayOptions().setAttribute(attr).setRes(placeholder())
            )
                ?: run {
                    android.util.Log.w(TAG, "addCrossOverlay 返回 null seq=$seq")
                    onDone(null, seq)
                    return
                }
            overlay = o
            o.setVisible(true)
            o.setGenerateCrossImageListener { bitmap, code ->
                android.util.Log.i(TAG, "vector 出图 seq=$seq ${bitmap?.width}x${bitmap?.height} code=$code")
                if (bitmap == null) return@setGenerateCrossImageListener
                // 过期任务（用户已经越过这个路口，或新图已到）直接丢，别闪一下旧图
                if (seq != renderingSeq) return@setGenerateCrossImageListener
                o.remove()
                overlay = null
                onDone(bitmap, seq)
            }
            o.setImageMode(true)
            val ret = o.setData(bytes)
            android.util.Log.i(TAG, "vector setData seq=$seq bytes=${bytes.size} ret=$ret ${w}x$h")
        }.onFailure {
            android.util.Log.w(TAG, "vector cross render failed seq=$seq: $it")
            onDone(null, seq)
        }
    }

    /**
     * 底图纹理。
     *
     * SDK 自带的 `AMapModeCrossOverlay` 传的是 `BitmapFactory.decodeResource(res, 2130837865)`，
     * 那是**编译期写死的 SDK 资源 id**；资源合并进我们 APK 后 id 会变，这里拿不到也解不出，
     * 而 `AMap.addCrossOverlay()` 在 `getRes() == null` 时直接返回 null（实测：
     * 「addCrossOverlay 返回 null」），整条矢量图通路就断在这里。
     *
     * 给一张极小的透明位图占位即可：真正画路口的是 `setData` 喂进去的矢量数据，
     * 这张仅仅是 overlay 初始化要求的纹理占位（不参与路口图形绘制）。
     */
    private fun placeholder(): Bitmap {
        if (placeholderBmp != null) return placeholderBmp!!
        // 尺寸别给 4x4：native 会按底图尺寸算纹理/坐标，太小的图直接让出图失败
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.argb(217, 95, 95, 95))
        placeholderBmp = bmp
        return bmp
    }

    private var placeholderBmp: Bitmap? = null

    /** 导航页销毁时调用，避免 overlay 泄漏 */
    fun release() {
        renderingSeq = -1
        runCatching { overlay?.remove() }
        overlay = null
    }

    private companion object {
        const val TAG = "NaviCross"
    }
}
