package com.example.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import java.io.File

object SecureDownloadController {

    private val ILLEGAL_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

    fun sanitizeFileName(contentDisposition: String?, url: String, mimeType: String?): String {
        // Use Android standard URLUtil for robust filename extraction (handles RFC 5987, UTF-8 encoded names, etc.)
        var name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        
        if (name.isBlank() || name == "downloadfile") {
            name = "download_${System.currentTimeMillis()}"
        }

        // Clean any path traversal sequences and strip directory structure
        name = File(name).name

        // Replace illegal filesystem characters with underscore
        name = name.replace(ILLEGAL_FILENAME_CHARS, "_").trim()

        if (name.isEmpty()) {
            name = "download_${System.currentTimeMillis()}"
        }

        return name
    }

    fun startDownload(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        safeFileName: String
    ) {
        val request = DownloadManager.Request(Uri.parse(url))
        
        // Isolate cookies strictly to the download URL
        if (url.isNotEmpty()) {
            val cookies = CookieManager.getInstance().getCookie(url)
            if (cookies != null) {
                request.addRequestHeader("Cookie", cookies)
            }
        }
        
        request.addRequestHeader("User-Agent", userAgent)
        if (!mimeType.isNullOrBlank()) {
            request.setMimeType(mimeType)
        }
        request.setTitle(safeFileName)
        request.setDescription("Baixando ficheiro através do Focus Browser")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFileName)
        
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)
    }
}

