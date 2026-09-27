package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val mimeType: String,
    val state: String, // PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, BLOCKED_EXTENSION, CANCELLED
    val downloadedBytes: Long,
    val totalBytes: Long,
    val filePath: String?,
    val errorMessage: String? = null,
    val userAgent: String = "",
    val cookies: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)
