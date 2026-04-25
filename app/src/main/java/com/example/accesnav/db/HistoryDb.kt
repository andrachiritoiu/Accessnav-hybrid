package com.example.accesnav.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history_items")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // e.g., "DETECTION", "ROUTE", "SIMULATION"
    val content: String, // e.g., "Detected EXIT", "Route to Library"
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(item: HistoryItem)

    @Query("SELECT * FROM history_items ORDER BY timestamp DESC")
    fun getAll(): Flow<List<HistoryItem>>

    @Query("DELETE FROM history_items")
    suspend fun clearAll()
}

@Database(entities = [HistoryItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "accessnav_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
