package com.motohud.ble.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 导航轨迹原始采样点。
 *
 * 导航过程中每秒最多写一个点（受 10m / 5s 门槛约束），结束时由
 * [NaviTrack] 做 Douglas-Peucker 简化、压成字符串写回
 * [NaviHistoryEntity.trackPolyline]，然后把这些原始点删掉。
 * 所以本表在稳态下几乎是空的，峰值也就一次导航几百行。
 */
@Entity(
    tableName = "navi_track_point",
    indices = [Index("historyId")]
)
data class NaviTrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val historyId: Long,
    /** GCJ02 纬度 */
    val lat: Double,
    /** GCJ02 经度 */
    val lng: Double,
    val ts: Long,
    val speedKmh: Float = 0f,
    val bearing: Float = 0f
)
