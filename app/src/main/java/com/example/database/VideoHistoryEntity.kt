package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_history")
data class VideoHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val videoUrl: String,
    val originalPageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val mimeType: String? = null,
    val isAudioOnly: Boolean = false,
    val subtitle: String? = null,
    val lastPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)
