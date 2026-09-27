package com.example.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoHistoryDao {
    @Query("SELECT * FROM video_history ORDER BY timestamp DESC")
    fun getAllVideoHistoryFlow(): Flow<List<VideoHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideoHistory(item: VideoHistoryEntity): Long

    @Query("SELECT * FROM video_history WHERE videoUrl = :url LIMIT 1")
    suspend fun findByUrl(url: String): VideoHistoryEntity?

    @Query("UPDATE video_history SET lastPositionMs = :positionMs, durationMs = :durationMs, timestamp = :timestamp WHERE videoUrl = :url")
    suspend fun updatePlaybackPosition(url: String, positionMs: Long, durationMs: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM video_history WHERE id = :id")
    suspend fun deleteVideoHistoryById(id: Long)

    @Query("DELETE FROM video_history WHERE videoUrl = :url")
    suspend fun deleteVideoHistoryByUrl(url: String)

    @Query("DELETE FROM video_history")
    suspend fun deleteAllVideoHistory()
}
