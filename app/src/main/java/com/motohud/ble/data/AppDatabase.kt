package com.motohud.ble.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SearchHistoryEntity::class,
        SavedPlaceEntity::class,
        NaviHistoryEntity::class,
        NaviTrackPointEntity::class,
        AvoidZoneEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun naviHistoryDao(): NaviHistoryDao
    abstract fun naviTrackPointDao(): NaviTrackPointDao
    abstract fun avoidZoneDao(): AvoidZoneDao

    companion object {
        /** v1 -> v2：新增 saved_place 表（搜索历史不丢） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS saved_place (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "latitude REAL NOT NULL, " +
                        "longitude REAL NOT NULL, " +
                        "type TEXT NOT NULL DEFAULT 'other', " +
                        "remark TEXT NOT NULL DEFAULT '', " +
                        "createdAt INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        /** v2 -> v3：新增 navi_history 表（导航历史，其他数据不动） */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS navi_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "destName TEXT NOT NULL, " +
                        "destLat REAL NOT NULL, " +
                        "destLng REAL NOT NULL, " +
                        "startName TEXT NOT NULL, " +
                        "startLat REAL NOT NULL, " +
                        "startLng REAL NOT NULL, " +
                        "startedAt INTEGER NOT NULL, " +
                        "finishedAt INTEGER NOT NULL DEFAULT 0, " +
                        "durationMs INTEGER NOT NULL DEFAULT 0, " +
                        "distanceM REAL NOT NULL DEFAULT 0, " +
                        "castToDevice INTEGER NOT NULL DEFAULT 0, " +
                        "deviceName TEXT, " +
                        "deviceType TEXT, " +
                        "battery REAL, " +
                        "waterTemp INTEGER, " +
                        "oil INTEGER, " +
                        "mileage INTEGER)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_navi_history_startedAt " +
                        "ON navi_history (startedAt)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_navi_history_finishedAt " +
                        "ON navi_history (finishedAt)"
                )
            }
        }

        /**
         * v3 -> v4：导航轨迹。
         *
         * 新增 navi_track_point 表（原始采样点，导航结束后清空）+ navi_history 三列
         * （压缩后的轨迹、均速、最高速）。用 ALTER TABLE 加列，**不重建表**，
         * 老用户的导航历史一条都不会丢。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS navi_track_point (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "historyId INTEGER NOT NULL, " +
                        "lat REAL NOT NULL, " +
                        "lng REAL NOT NULL, " +
                        "ts INTEGER NOT NULL, " +
                        "speedKmh REAL NOT NULL DEFAULT 0, " +
                        "bearing REAL NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_navi_track_point_historyId " +
                        "ON navi_track_point (historyId)"
                )
                db.execSQL("ALTER TABLE navi_history ADD COLUMN trackPolyline TEXT")
                db.execSQL("ALTER TABLE navi_history ADD COLUMN avgSpeedKmh REAL")
                db.execSQL("ALTER TABLE navi_history ADD COLUMN maxSpeedKmh REAL")
            }
        }

        /**
         * v4 -> v5：新增 avoid_zone 表（避让区）。
         *
         * 纯新增表，不改任何既有表结构 —— 老用户的导航历史/常用地址/搜索历史都不动。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS avoid_zone (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "polygon TEXT NOT NULL, " +
                        "enabled INTEGER NOT NULL DEFAULT 1, " +
                        "createdAt INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "motohud.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
