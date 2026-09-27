package com.example.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY id DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY id DESC")
    suspend fun getAllDownloadsList(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownloadById(id: Long): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadEntity): Long

    @Update
    suspend fun updateDownload(download: DownloadEntity)

    @Query("UPDATE downloads SET fileName = :newFileName, filePath = :newFilePath WHERE id = :id")
    suspend fun updateDownloadFileName(id: Long, newFileName: String, newFilePath: String?)

    @Query("UPDATE downloads SET state = :state, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes WHERE id = :id")
    suspend fun updateDownloadProgress(id: Long, state: String, downloadedBytes: Long, totalBytes: Long)

    @Query("UPDATE downloads SET state = :state WHERE id = :id")
    suspend fun updateDownloadState(id: Long, state: String)

    @Delete
    suspend fun deleteDownload(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownloadById(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun clearAllDownloads()
}
