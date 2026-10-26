package com.motohud.ble.data.bike

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 用户自定义车照存储。
 *
 * 每个车型一张图，存 filesDir/bike_photos/<bikeId>.jpg：
 *  - filesDir 不占"缓存"配额，也不会被系统清掉（清缓存不能把用户选的照片弄丢）
 *  - 按车型分开存，换车后各自显示各自的照片
 *
 * 保存时做一次居中裁切，把比例统一到 Hero 图框（约 1.4:1），
 * 配合 ImageView 的 CENTER_CROP 保证任何来源的图都不会被拉伸变形。
 */
object BikePhotoStore {

    private const val TAG = "BikePhotoStore"

    /** Hero 图框可见区域的目标宽高比（宽 / 高）。 */
    private const val TARGET_ASPECT = 1.4f

    /** 保存后的最长边上限，控制文件体积。 */
    private const val MAX_SIDE = 1440

    private fun dir(context: Context): File = File(context.filesDir, "bike_photos")

    fun photoFile(context: Context, bikeId: String): File =
        File(dir(context), "$bikeId.jpg")

    fun hasPhoto(context: Context, bikeId: String): Boolean =
        photoFile(context, bikeId).length() > 0

    /**
     * 从相册 URI 读取图片 → 降采样 → 居中裁切到目标比例 → 存为 JPEG。
     * @return 是否成功
     */
    suspend fun save(context: Context, bikeId: String, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val bmp = decodeDownsampled(context, uri) ?: return@withContext false
                val cropped = centerCrop(bmp, TARGET_ASPECT)
                val out = photoFile(context, bikeId)
                out.parentFile?.mkdirs()
                out.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                if (cropped !== bmp) bmp.recycle()
                cropped.recycle()
                true
            } catch (e: Exception) {
                android.util.Log.w("BikePhotoStore", "save failed", e)
                false
            }
        }

    fun delete(context: Context, bikeId: String) {
        photoFile(context, bikeId).delete()
    }

    /** 读取自定义车照，按视图宽度做降采样，避免整图上屏。 */
    suspend fun load(context: Context, bikeId: String, viewWidthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val f = photoFile(context, bikeId)
            if (!f.exists()) return@withContext null
            runCatching {
                val sample = if (viewWidthPx > 0) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, opts)
                    var s = 1
                    while (opts.outWidth / (s * 2) >= viewWidthPx) s *= 2
                    s
                } else 1
                BitmapFactory.decodeFile(
                    f.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }

    /** 先查 bounds 再按 MAX_SIDE 降采样解码，防止 8000 万像素的图直接撑爆内存。 */
    private fun decodeDownsampled(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 注意：inJustDecodeBounds=true 时 decodeStream 永远返回 null，只能靠 bounds.outWidth 判断是否取到有效尺寸
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** 居中裁切到指定宽高比。 */
    private fun centerCrop(src: Bitmap, aspect: Float): Bitmap {
        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()
        val srcAspect = srcW / srcH
        var cropW = srcW
        var cropH = srcH
        if (srcAspect > aspect) cropW = srcH * aspect else cropH = srcW / aspect
        // 已经是目标比例（±1%）就直接返回，省一次拷贝
        if (kotlin.math.abs(srcAspect - aspect) < 0.01f) return src
        val left = (srcW - cropW) / 2f
        val top = (srcH - cropH) / 2f
        val outW = cropW.toInt().coerceAtLeast(1)
        val outH = cropH.toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src,
            Matrix().apply { postTranslate(-left, -top) },
            null
        )
        return out
    }
}
