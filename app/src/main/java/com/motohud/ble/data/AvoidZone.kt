package com.motohud.ble.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 避让区 —— 用户自己圈出来的「这块地方我不能进」。
 *
 * ## 为什么让用户自己标，而不是我们维护禁摩数据
 *
 * 见《摩托车导航方案-轻量版》：全国没有结构化的禁摩区坐标数据，各地政策
 * 描述方式五花八门（合围区域 / 按排量 / 按时段），人工翻译成几何等于持续
 * 投入且永远滞后。而**老骑手比任何算法都清楚哪块不能进** —— 让他圈一次，
 * 永久有效，我们一条数据都不用维护，也天然全国通用。
 *
 * ## 存储形态
 *
 * 多边形坐标序列化成一个字符串（`lat,lng;lat,lng;...`）。
 * 不用 TypeConverter + List，是因为这张表只在算路前一瞬间读一次，
 * 字符串足够、也不引入额外的转换层。
 */
@Entity(tableName = "avoid_zone")
data class AvoidZoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 用户起的名字，如"南滨路""观音桥商圈" */
    val name: String,
    /** 多边形顶点，格式 `lat,lng;lat,lng;...`（GCJ-02） */
    val polygon: String,
    /** 关掉后不参与算路，但保留定义（比删了重建方便） */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface AvoidZoneDao {

    @Query("SELECT * FROM avoid_zone ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<AvoidZoneEntity>>

    @Query("SELECT * FROM avoid_zone WHERE enabled = 1 ORDER BY createdAt ASC")
    suspend fun enabledZones(): List<AvoidZoneEntity>

    /** 管理页用：连已关闭的一起读（用户随时可以再打开） */
    @Query("SELECT * FROM avoid_zone ORDER BY createdAt ASC")
    suspend fun allOnce(): List<AvoidZoneEntity>

    @Query("SELECT COUNT(*) FROM avoid_zone")
    suspend fun count(): Int

    @Insert
    suspend fun insert(zone: AvoidZoneEntity): Long

    @Update
    suspend fun update(zone: AvoidZoneEntity)

    @Delete
    suspend fun delete(zone: AvoidZoneEntity)
}

/**
 * 避让区的几何工具与仓库。
 *
 * ## 两个必须校验的硬限制（不校验就等着"导航算不出路"）
 *
 * 1. **面积**：高德对避让区大小很敏感，超过约 2km 见方常常直接返回空路线。
 *    用户不会觉得是自己圈大了，只会觉得"导航坏了"。
 * 2. **数量**：高德最多 100 个区域、每区 16 个点。我们内部收得更紧。
 */
object AvoidZoneStore {

    /** 单个避让区允许的最大边长（米）。宽、高分别按这个上限校验 */
    const val MAX_SIDE_M = 2000.0

    /** 一个多边形最多顶点数（高德限制 16） */
    const val MAX_POINTS = 16

    /** 宽/高的最小值，再小就框不住一个路口了 */
    const val MIN_SIDE_M = 200.0

    data class Zone(
        val id: Long,
        val name: String,
        val polygon: List<Pair<Double, Double>>,
        val enabled: Boolean
    )

    /** 序列化：保留 6 位小数（约 0.1m），够用且不会让字符串过长 */
    fun encode(polygon: List<Pair<Double, Double>>): String =
        polygon.joinToString(";") { "${fmt(it.first)},${fmt(it.second)}" }

    fun decode(raw: String): List<Pair<Double, Double>> =
        raw.split(';').mapNotNull { seg ->
            val p = seg.split(',')
            if (p.size != 2) return@mapNotNull null
            val lat = p[0].toDoubleOrNull() ?: return@mapNotNull null
            val lng = p[1].toDoubleOrNull() ?: return@mapNotNull null
            lat to lng
        }

    /**
     * 以 [lat],[lng] 为中心生成一个宽 [widthM] × 高 [heightM] 米的矩形。
     *
     * 为什么用矩形而不是让用户手拖顶点：拖多边形在手机小屏上极难操作，
     * 而"圈个大概范围"已经足够把一条路/一个商圈框住。中心可移动、宽高可调，
     * 交互成本远低于自由绘制，效果对避让来说没有本质差别。
     *
     * 宽、高分开给，是因为现实中的禁摩范围常常是**长条**：一条沿江路、
     * 一段高架、一条穿城的主干道。正方形要去框这些东西，不是框不满
     * 就是顺带把旁边能走的路也禁了。
     */
    fun rectAround(
        lat: Double,
        lng: Double,
        widthM: Double,
        heightM: Double = widthM
    ): List<Pair<Double, Double>> {
        val halfW = widthM / 2.0
        val halfH = heightM / 2.0
        val dLat = halfH / 111_320.0
        val dLng = halfW / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return listOf(
            lat - dLat to lng - dLng,
            lat - dLat to lng + dLng,
            lat + dLat to lng + dLng,
            lat + dLat to lng - dLng
        )
    }

    /**
     * 外接矩形的宽高（米），返回 `宽 to 高`。
     *
     * 只看外接矩形就够判断"是不是圈太大了"，不需要真的算面积
     * （顶点很少，但没必要为此引入几何库）。
     */
    fun sizeM(polygon: List<Pair<Double, Double>>): Pair<Double, Double> {
        if (polygon.isEmpty()) return 0.0 to 0.0
        val lats = polygon.map { it.first }
        val lngs = polygon.map { it.second }
        val h = (lats.max() - lats.min()) * 111_320.0
        val midLat = (lats.max() + lats.min()) / 2.0
        val w = (lngs.max() - lngs.min()) * 111_320.0 *
            Math.cos(Math.toRadians(midLat)).coerceAtLeast(0.01)
        return w to h
    }

    /** 最大边长（米），用于快速判断是否超限 */
    fun maxSideM(polygon: List<Pair<Double, Double>>): Double {
        val (w, h) = sizeM(polygon)
        return maxOf(w, h)
    }

    /** 宽或高任一超限（保存时拦下 + 算路前二次兜底） */
    fun tooLarge(polygon: List<Pair<Double, Double>>): Boolean = maxSideM(polygon) > MAX_SIDE_M

    /** 读取启用的避让区（算路前调用；纯本地，不联网、不上传） */
    suspend fun enabled(context: Context): List<Zone> =
        runCatching {
            AppDatabase.get(context).avoidZoneDao().enabledZones().map { it.toZone() }
        }.getOrDefault(emptyList())

    fun AvoidZoneEntity.toZone(): Zone =
        Zone(id = id, name = name, polygon = decode(polygon), enabled = enabled)

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.6f", v)
}
