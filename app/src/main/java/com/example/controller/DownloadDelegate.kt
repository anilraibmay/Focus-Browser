package com.example.controller

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.example.model.DownloadPayload
import com.example.util.AdvancedDownloadItem
import com.example.util.AdvancedDownloadManager
import com.example.util.SecureDownloadController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Delegate responsible for download operations, file associations,
 * and dispatching downloads to AdvancedDownloadManager.
 */
class DownloadDelegate(
    private val application: Application,
    private val onNotify: (String) -> Unit
) {
    private val _pendingDownloadPayload = MutableStateFlow<DownloadPayload?>(null)
    val pendingDownloadPayload: StateFlow<DownloadPayload?> = _pendingDownloadPayload.asStateFlow()

    val downloadsList: StateFlow<List<AdvancedDownloadItem>> =
        AdvancedDownloadManager.getInstance(application).downloads

    fun handleDownloadRequest(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        initiatingTabId: String,
        pageTitle: String? = null,
        defaultTargetTreeUri: String? = null,
        defaultTargetDirectoryName: String? = null
    ) {
        val isVideo = (mimeType?.startsWith("video/", ignoreCase = true) == true) ||
                      url.contains(".mp4", ignoreCase = true) ||
                      url.contains(".m3u8", ignoreCase = true) ||
                      url.contains(".webm", ignoreCase = true) ||
                      url.contains(".mkv", ignoreCase = true)

        val customFileName = if (isVideo && !pageTitle.isNullOrBlank()) {
            val ext = when {
                url.contains(".webm", ignoreCase = true) || mimeType?.contains("webm", ignoreCase = true) == true -> "webm"
                url.contains(".mkv", ignoreCase = true) -> "mkv"
                else -> "mp4"
            }
            val base = com.example.util.VideoFileNameGenerator.sanitizePageTitle(pageTitle)
            "$base.$ext"
        } else null

        _pendingDownloadPayload.value = DownloadPayload(
            url = url,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            customFileName = customFileName,
            targetTreeUri = defaultTargetTreeUri,
            targetDirectoryName = defaultTargetDirectoryName
        )
    }

    fun cancelPendingDownload() {
        _pendingDownloadPayload.value = null
    }

    fun executeDirectDownload(
        context: Context,
        payload: DownloadPayload
    ) {
        try {
            val safeFileName = if (!payload.customFileName.isNullOrBlank()) {
                payload.customFileName
            } else {
                SecureDownloadController.sanitizeFileName(
                    payload.contentDisposition,
                    payload.url,
                    payload.mimeType
                )
            }
            val cookies = if (payload.headers.containsKey("Cookie") && payload.headers["Cookie"]?.isNotBlank() == true) {
                payload.headers["Cookie"] ?: ""
            } else {
                try {
                    android.webkit.CookieManager.getInstance().getCookie(payload.url) ?: ""
                } catch (e: Exception) {
                    ""
                }
            }

            val ua = if (payload.userAgent.isNotBlank()) {
                payload.userAgent
            } else if (payload.headers.containsKey("User-Agent") && payload.headers["User-Agent"]?.isNotBlank() == true) {
                payload.headers["User-Agent"] ?: ""
            } else {
                "Mozilla/5.0 (Linux; Android 10; Mobile)"
            }

            val mergedHeaders = payload.headers.toMutableMap()
            if (!mergedHeaders.containsKey("Cookie") && cookies.isNotBlank()) {
                mergedHeaders["Cookie"] = cookies
            }
            if (!mergedHeaders.containsKey("User-Agent") && ua.isNotBlank()) {
                mergedHeaders["User-Agent"] = ua
            }

            AdvancedDownloadManager.getInstance(context).startDownload(
                url = payload.url,
                fileName = safeFileName,
                mimeType = payload.mimeType ?: "",
                userAgent = ua,
                cookies = cookies,
                headers = mergedHeaders,
                targetDirectoryPath = payload.targetDirectoryPath,
                targetTreeUri = payload.targetTreeUri
            )
        } catch (e: Exception) {
            e.printStackTrace()
            onNotify("Erro ao iniciar download")
        } finally {
            cancelPendingDownload()
        }
    }

    fun startYouTubeDualStreamDownload(
        context: Context,
        title: String,
        videoUrl: String,
        audioUrl: String,
        ua: String = "",
        cookies: String = "",
        targetDirectoryPath: String? = null,
        targetTreeUri: String? = null
    ) {
        try {
            AdvancedDownloadManager.getInstance(context).startYouTubeDualStreamDownload(
                title = title,
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                ua = ua,
                cookies = cookies,
                targetDirectoryPath = targetDirectoryPath,
                targetTreeUri = targetTreeUri
            )
        } catch (e: Exception) {
            e.printStackTrace()
            onNotify("Erro ao iniciar download do YouTube")
        }
    }

    fun startYouTubeAudioDownload(
        context: Context,
        title: String,
        audioUrl: String,
        ua: String = "",
        cookies: String = "",
        asMp3: Boolean = false,
        targetDirectoryPath: String? = null,
        targetTreeUri: String? = null
    ) {
        try {
            AdvancedDownloadManager.getInstance(context).startYouTubeAudioDownload(
                title = title,
                audioUrl = audioUrl,
                ua = ua,
                cookies = cookies,
                asMp3 = asMp3,
                targetDirectoryPath = targetDirectoryPath,
                targetTreeUri = targetTreeUri
            )
        } catch (e: Exception) {
            e.printStackTrace()
            onNotify("Erro ao iniciar download de áudio")
        }
    }

    fun confirmDownload(context: Context, payload: DownloadPayload) {
        executeDirectDownload(context, payload)
    }

    fun cancelDownload(downloadId: Long) {
        val advancedDm = AdvancedDownloadManager.getInstance(application)
        advancedDm.cancelDownload(downloadId)
        onNotify("Download cancelado!")
    }

    fun pauseDownload(downloadId: Long) {
        val advancedDm = AdvancedDownloadManager.getInstance(application)
        advancedDm.pauseDownload(downloadId)
        onNotify("Download em pausa")
    }

    fun resumeDownload(downloadId: Long) {
        val advancedDm = AdvancedDownloadManager.getInstance(application)
        advancedDm.resumeDownload(downloadId)
        onNotify("Download retomado")
    }

    fun deleteDownload(downloadId: Long, deleteFileFromDisk: Boolean = true) {
        val advancedDm = AdvancedDownloadManager.getInstance(application)
        advancedDm.deleteDownload(downloadId, deleteFileFromDisk)
        onNotify(if (deleteFileFromDisk) "Download e ficheiro eliminados!" else "Download removido da lista!")
    }

    fun renameDownload(downloadId: Long, newName: String): Boolean {
        val advancedDm = AdvancedDownloadManager.getInstance(application)
        val result = advancedDm.renameDownload(downloadId, newName)
        return if (result.isSuccess) {
            onNotify("Ficheiro renomeado para: ${result.getOrNull()}")
            true
        } else {
            onNotify(result.exceptionOrNull()?.message ?: "Erro ao renomear ficheiro")
            false
        }
    }

    fun clearCompletedDownloads(deleteFilesFromDisk: Boolean = false) {
        val advancedDm = AdvancedDownloadManager.getInstance(application)
        advancedDm.clearCompletedDownloads(deleteFilesFromDisk)
        onNotify("Histórico de downloads limpo!")
    }

    fun openDownloadedFile(context: Context, item: AdvancedDownloadItem) {
        val file = item.file ?: return
        if (!file.exists()) {
            onNotify("Ficheiro não encontrado no dispositivo")
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val resolvedMime = if (item.mimeType.isNotBlank() && item.mimeType != "*/*") {
                item.mimeType
            } else {
                getMimeTypeFromExtension(file.extension)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to wildcard mime type
            try {
                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, file)
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(fallbackIntent, "Abrir com..."))
            } catch (fallbackEx: Exception) {
                onNotify("Nenhuma aplicação disponível para abrir este tipo de ficheiro")
            }
        }
    }

    fun getMimeTypeFromExtension(extension: String): String {
        return when (extension.lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4", "mkv" -> "video/mp4"
            "mp3", "wav", "m4a" -> "audio/mpeg"
            "txt", "srt" -> "text/plain"
            "zip" -> "application/zip"
            else -> "*/*"
        }
    }
}
