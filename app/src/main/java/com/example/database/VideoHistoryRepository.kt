package com.example.database

import kotlinx.coroutines.flow.Flow

class VideoHistoryRepository(private val videoHistoryDao: VideoHistoryDao) {
    val allVideoHistoryFlow: Flow<List<VideoHistoryEntity>> = videoHistoryDao.getAllVideoHistoryFlow()

    suspend fun recordVideoPlayback(
        title: String,
        videoUrl: String,
        originalPageUrl: String? = null,
        thumbnailUrl: String? = null,
        mimeType: String? = null,
        isAudioOnly: Boolean = false,
        subtitle: String? = null,
        lastPositionMs: Long = 0L,
        durationMs: Long = 0L
    ) {
        val cleanUrl = videoUrl.trim()
        if (cleanUrl.isBlank()) return

        val existing = videoHistoryDao.findByUrl(cleanUrl)
        val entity = if (existing != null) {
            existing.copy(
                title = if (title.isNotBlank() && title != "Stream Direto") title else existing.title,
                originalPageUrl = originalPageUrl ?: existing.originalPageUrl,
                thumbnailUrl = thumbnailUrl?.takeIf { it.isNotBlank() } ?: existing.thumbnailUrl,
                mimeType = mimeType ?: existing.mimeType,
                isAudioOnly = isAudioOnly,
                subtitle = subtitle ?: existing.subtitle,
                lastPositionMs = if (lastPositionMs > 0) lastPositionMs else existing.lastPositionMs,
                durationMs = if (durationMs > 0) durationMs else existing.durationMs,
                timestamp = System.currentTimeMillis()
            )
        } else {
            VideoHistoryEntity(
                title = if (title.isBlank()) "Vídeo Sem Título" else title,
                videoUrl = cleanUrl,
                originalPageUrl = originalPageUrl,
                thumbnailUrl = thumbnailUrl?.takeIf { it.isNotBlank() },
                mimeType = mimeType,
                isAudioOnly = isAudioOnly,
                subtitle = subtitle,
                lastPositionMs = lastPositionMs,
                durationMs = durationMs,
                timestamp = System.currentTimeMillis()
            )
        }
        videoHistoryDao.insertVideoHistory(entity)
    }

    suspend fun updatePlaybackPosition(url: String, positionMs: Long, durationMs: Long) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        videoHistoryDao.updatePlaybackPosition(cleanUrl, positionMs, durationMs)
    }

    suspend fun deleteById(id: Long) {
        videoHistoryDao.deleteVideoHistoryById(id)
    }

    suspend fun deleteByUrl(url: String) {
        videoHistoryDao.deleteVideoHistoryByUrl(url)
    }

    suspend fun deleteAll() {
        videoHistoryDao.deleteAllVideoHistory()
    }
}
