package com.streaksaver.ai

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface FriendDao {
    @Query("SELECT * FROM friends ORDER BY displayName COLLATE NOCASE")
    fun observeFriends(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM friends")
    suspend fun getAll(): List<FriendEntity>

    @Insert
    suspend fun insert(friend: FriendEntity)

    @Delete
    suspend fun delete(friend: FriendEntity)

    @Query("UPDATE friends SET reminderHour = :hour, reminderMinute = :minute WHERE id = :id")
    suspend fun updateReminderTime(id: Long, hour: Int, minute: Int)

    @Query("UPDATE friends SET reminderEnabled = :enabled WHERE id = :id")
    suspend fun setReminderEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE friends SET lastNotificationDate = :date WHERE id = :id")
    suspend fun markNotified(id: Long, date: String)

    @Query("UPDATE friends SET streakDays = CASE WHEN lastCheckInDate = :today THEN streakDays WHEN lastCheckInDate = :yesterday THEN streakDays + 1 ELSE 1 END, lastCheckInDate = :today WHERE id = :id")
    suspend fun markSentToday(id: Long, today: String, yesterday: String)
}

@Database(entities = [FriendEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun friendDao(): FriendDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "streak-saver.db"
                ).build().also { instance = it }
            }
    }
}
