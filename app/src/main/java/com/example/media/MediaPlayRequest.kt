package com.example.media

import android.net.Uri
import com.example.util.AdvancedDownloadItem
import java.util.UUID

data class MediaPlayRequest(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val uri: Uri,
    val streamUrl: String? = null,
    val thumbnailUrl: String? = null,
    val initialPositionMs: Long = 0L,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val isAudioOnly: Boolean = false,
    val subtitle: String? = null,
    val sourceDownloadItem: AdvancedDownloadItem? = null
)
