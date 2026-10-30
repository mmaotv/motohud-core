package com.motohud.ble.data

import android.content.Context
import com.amap.api.maps.MapsInitializer
import java.io.File

/**
 * 应用缓存统计与清理。
 *
 * 背景：导航页每次打开都会加载高德地图瓦片，SDK 会把瓦片/搜索结果写进缓存目录，
 * 长期使用缓存会一直变大。这里提供统一的「算大小 + 清理」能力，供设置页入口使用。
 *
 * 两个容易漏掉的点：
 *  1. **高德的地图缓存不在 cacheDir**。实测一台跑过几次导航的机器，
 *     `externalFilesDir/amap` 有 40MB+（res920 / data_v6 / VM3DRes / databases），
 *     而 cacheDir 只有 1MB。只清 cacheDir 等于没清。
 *  2. 这部分由 SDK 自己管理，**不能直接删目录**——删错资源包会让地图重新下载几十 MB。
 *     用 V11 新增的 [MapsInitializer.removecache] 让 SDK 自己决定清什么。
 *
 * 只清理缓存性质的位置，绝不碰 filesDir 里用户的车照与数据库。
 */
object AppCache {

    /** 高德在地图 SDK 里的外部存储目录（相对 externalFilesDir） */
    private const val AMAP_DIR = "amap"

    private fun amapDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, AMAP_DIR) }

    /** 缓存总大小（字节）：内部缓存 + 外部缓存 + 高德地图缓存。 */
    fun size(context: Context): Long =
        dirSize(context.cacheDir) + dirSize(context.externalCacheDir) + dirSize(amapDir(context))

    /** 清空缓存，返回实际释放的字节数。 */
    fun clear(context: Context): Long {
        val before = size(context)
        // 高德自己的瓦片/资源缓存：交给 SDK 清，避免误删资源包
        runCatching { MapsInitializer.removecache(context) }
        listOf(context.cacheDir, context.externalCacheDir).forEach { root ->
            if (root == null || !root.exists()) return@forEach
            root.listFiles()?.forEach { it.deleteRecursively() }
        }
        val released = (before - size(context)).coerceAtLeast(0L)
        return released
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /** 人性化大小：KB / MB / GB。 */
    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
