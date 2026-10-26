package com.motohud.ble.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * 导航历史 DAO。
 *
 * 查询按 [finishedAt] 倒序 —— 用户最关心的是最近几次。
 * 时间范围筛选用 [since] 一个参数就够（0 = 不限），不搞多套 SQL。
 */
@Dao
interface NaviHistoryDao {

    @Insert
    suspend fun insert(entity: NaviHistoryEntity): Long

    @Update
    suspend fun update(entity: NaviHistoryEntity)

    @Query("SELECT * FROM navi_history WHERE finishedAt >= :since ORDER BY finishedAt DESC")
    suspend fun listSince(since: Long): List<NaviHistoryEntity>

    @Query("SELECT * FROM navi_history ORDER BY finishedAt DESC")
    suspend fun listAll(): List<NaviHistoryEntity>

    @Query("SELECT COUNT(*) FROM navi_history")
    suspend fun count(): Int

    /**
     * 只更新轨迹相关三列。
     *
     * 为什么不复用 [update]：结束导航时 [NaviHistory.finish] 与 [NaviTrack.finish]
     * 是两条并行协程，各自 `copy()` 整行再写回会互相覆盖（例如轨迹写回把 finishedAt 又抹成 0）。
     * 这里只改自己那三列，永远安全。
     */
    @Query(
        "UPDATE navi_history SET trackPolyline = :polyline, " +
            "avgSpeedKmh = :avgSpeed, maxSpeedKmh = :maxSpeed WHERE id = :id"
    )
    suspend fun updateTrack(id: Long, polyline: String?, avgSpeed: Double?, maxSpeed: Double?)

    @Query("DELETE FROM navi_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM navi_history")
    suspend fun clear()

    /** 只保留最近 keep 条，避免长期累积占空间 */
    @Query(
        "DELETE FROM navi_history WHERE id NOT IN " +
            "(SELECT id FROM navi_history ORDER BY finishedAt DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)
}
