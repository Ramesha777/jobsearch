package com.wordwatcher.data

import android.content.Context
import androidx.room.*

// ── Entity ──────────────────────────────────────────────────────────────────
@Entity(tableName = "watch_items")
data class WatchItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val keyword: String
)

// ── DAO ──────────────────────────────────────────────────────────────────────
@Dao
interface WatchItemDao {
    @Query("SELECT * FROM watch_items")
    fun getAll(): List<WatchItem>

    @Insert
    fun insert(item: WatchItem)

    @Delete
    fun delete(item: WatchItem)
}

// ── Database ─────────────────────────────────────────────────────────────────
@Database(entities = [WatchItem::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchItemDao(): WatchItemDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wordwatcher.db"
                ).build().also { INSTANCE = it }
            }
    }
}
