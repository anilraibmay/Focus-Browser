package com.example.model

import android.webkit.WebView
import java.util.UUID

enum class FocusMode {
    NORMAL,            // Navigates normally in the same tab
    BACKGROUND,        // Opens in a background tab and alerts the user
    SPLIT_SCREEN,      // Opens in a bottom/side split screen pane
    TOTAL_BLOCK        // Intercepts and immediately blocks any external link navigation
}

enum class AppThemeMode(val id: String, val title: String, val description: String) {
    SYSTEM("system", "Padrão do Sistema", "Segue a preferência do dispositivo"),
    LIGHT("light", "Modo Claro", "Visual clássico de alto contraste"),
    DARK("dark", "Modo Escuro", "Confortável para ambientes escuros"),
    OLED_DARK("oled_dark", "Preto Puro (OLED)", "Economia máxima de bateria em ecrãs OLED")
}

enum class WebPermissionType(val id: String, val title: String) {
    CAMERA("camera", "Câmara"),
    MICROPHONE("microphone", "Microfone"),
    LOCATION("location", "Localização")
}

enum class PermissionPolicy(val key: String, val label: String) {
    ASK("ask", "Perguntar (Padrão)"),
    BLOCK("block", "Bloquear"),
    ALLOW("allow", "Permitir")
}

data class SitePermissionRecord(
    val origin: String,
    val camera: PermissionPolicy = PermissionPolicy.ASK,
    val microphone: PermissionPolicy = PermissionPolicy.ASK,
    val location: PermissionPolicy = PermissionPolicy.ASK,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    fun getPolicy(type: WebPermissionType): PermissionPolicy = when (type) {
        WebPermissionType.CAMERA -> camera
        WebPermissionType.MICROPHONE -> microphone
        WebPermissionType.LOCATION -> location
    }

    fun withPolicy(type: WebPermissionType, policy: PermissionPolicy): SitePermissionRecord = when (type) {
        WebPermissionType.CAMERA -> copy(camera = policy, lastUpdated = System.currentTimeMillis())
        WebPermissionType.MICROPHONE -> copy(microphone = policy, lastUpdated = System.currentTimeMillis())
        WebPermissionType.LOCATION -> copy(location = policy, lastUpdated = System.currentTimeMillis())
    }

    val hasAnyExplicitPolicy: Boolean
        get() = camera != PermissionPolicy.ASK || microphone != PermissionPolicy.ASK || location != PermissionPolicy.ASK
}

data class BrowserPreferences(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val focusMode: FocusMode = FocusMode.NORMAL,
    val desktopMode: Boolean = false,
    val blockPopups: Boolean = true,
    val enableJavaScript: Boolean = true,
    val enableAdBlock: Boolean = true,
    val clearCacheOnExit: Boolean = false,
    val enableBackgroundSync: Boolean = true,
    val downloadDirectoryUri: String? = null,
    val downloadDirectoryName: String = "Downloads (Padrão)",
    val askDownloadLocation: Boolean = false,
    val defaultCameraPolicy: PermissionPolicy = PermissionPolicy.ASK,
    val defaultMicrophonePolicy: PermissionPolicy = PermissionPolicy.ASK,
    val defaultLocationPolicy: PermissionPolicy = PermissionPolicy.ASK
)

data class SslErrorState(
    val handler: android.webkit.SslErrorHandler,
    val error: android.net.http.SslError,
    val url: String
)

data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "Nova guia",
    val url: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val openedInBackground: Boolean = false,
    val isPrivate: Boolean = false,
    val lastUpdated: String? = null,
    val thumbnail: android.graphics.Bitmap? = null,
    val isFrozen: Boolean = false,
    val isThrottled: Boolean = false,
    val isDiscarded: Boolean = false,
    val lastInteractionTime: Long = System.currentTimeMillis(),
    val hasActiveMedia: Boolean = false,
    val hasActiveWebSocket: Boolean = false,
    val hasActiveWebRTC: Boolean = false,
    val hasActiveExternalDevice: Boolean = false,
    val extractedVideo: com.example.extractor.ExtractedVideo? = null,
    val extractedVideos: List<com.example.extractor.ExtractedVideo> = emptyList(),
    val mediaGroups: List<ExtractedMediaGroup> = emptyList(),
    val isSecure: Boolean = true,
    val isRestoringSnapshot: Boolean = false
) {
    val webView: WebView?
        get() = com.example.util.WebViewRegistry.get(id)
}

/**
 * Representa uma resolução ou opção individual de stream para uma mídia extraída.
 */
data class MediaStreamOption(
    val id: String = java.util.UUID.randomUUID().toString(),
    val rawVideo: com.example.extractor.ExtractedVideo,
    val qualityLabel: String,
    val resolutionLabel: String,
    val formatBadge: String,
    val sizeBytes: Long? = null,
    val formattedSize: String = "Tamanho sob consulta",
    val isAudioOnly: Boolean = false,
    val isAdaptiveVideoOnly: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val estimationConfidence: String? = null,
    val estimationMethod: String? = null,
    val estimationNotes: String? = null
)

/**
 * Entidade Mestre que agrupa um vídeo específico da página web com todas as suas resoluções
 * disponíveis e opções de áudio, eliminando redundâncias e repetições.
 */
data class ExtractedMediaGroup(
    val id: String,
    val title: String,
    val pageTitle: String? = null,
    val pageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    val formattedDuration: String? = null,
    val streams: List<MediaStreamOption> = emptyList(),
    val videoStreams: List<MediaStreamOption> = emptyList(),
    val audioStreams: List<MediaStreamOption> = emptyList(),
    val selectedStreamId: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    val selectedStream: MediaStreamOption?
        get() = streams.firstOrNull { it.id == selectedStreamId }
            ?: videoStreams.firstOrNull()
            ?: streams.firstOrNull()
}

data class DownloadItem(
    val id: Long, // DownloadManager ID
    val fileName: String,
    val url: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: Int, // DownloadManager.STATUS_*
    val mimeType: String?,
    val localUri: String?,
    val isDangerous: Boolean = false,
    val extension: String = ""
)

data class DownloadPayload(
    val url: String,
    val userAgent: String,
    val contentDisposition: String?,
    val mimeType: String?,
    val customFileName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val targetDirectoryPath: String? = null,
    val targetDirectoryName: String? = null,
    val targetTreeUri: String? = null
)

data class PendingDownloadDialog(
    val url: String,
    val userAgent: String,
    val contentDisposition: String,
    val mimeType: String,
    val contentLength: Long,
    val fileName: String,
    val extension: String,
    val isDangerous: Boolean,
    val isGhostDownload: Boolean
)

enum class NetworkEventType(val label: String) {
    ALL("ALL"),
    MANIFEST("MANIFEST"),
    VIDEO("VIDEO"),
    AUDIO("AUDIO"),
    SEGMENT("SEGMENT"),
    XHR("XHR/FETCH"),
    IMAGE("IMAGE"),
    SCRIPT("SCRIPT"),
    OTHER("OTHER")
}

data class NetworkEvent(
    val id: String = UUID.randomUUID().toString(),
    val tabId: String,
    val url: String,
    val method: String = "GET",
    val type: NetworkEventType = NetworkEventType.OTHER,
    val timestamp: Long = System.currentTimeMillis(),
    val headers: Map<String, String> = emptyMap(),
    val isBlocked: Boolean = false
)

data class ActiveDownloadNotification(
    val id: Long = System.currentTimeMillis(),
    val fileName: String
)

