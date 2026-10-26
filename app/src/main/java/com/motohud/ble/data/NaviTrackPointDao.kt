package com.motohud.ble.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** 轨迹原始点 DAO（只在一次导航的生命周期内使用） */
@Dao
interface NaviTrackPointDao {

    @Insert
    suspend fun insertAll(points: List<NaviTrackPointEntity>)

    @Query("SELECT * FROM navi_track_point WHERE historyId = :historyId ORDER BY ts ASC")
    suspend fun listByHistory(historyId: Long): List<NaviTrackPointEntity>

    @Query("DELETE FROM navi_track_point WHERE historyId = :historyId")
    suspend fun deleteByHistory(historyId: Long)

    /** 清理孤儿点（记录已被删除却没来得及清理缓冲的情况） */
    @Query("DELETE FROM navi_track_point WHERE historyId NOT IN (SELECT id FROM navi_history)")
    suspend fun deleteOrphans()

    @Query("SELECT COUNT(*) FROM navi_track_point")
    suspend fun count(): Int
}
