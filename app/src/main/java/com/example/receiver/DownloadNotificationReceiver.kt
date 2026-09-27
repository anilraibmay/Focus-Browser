package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.util.AdvancedDownloadManager
import com.example.util.DownloadNotificationHelper

class DownloadNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE = "com.example.action.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "com.example.action.RESUME_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.action.CANCEL_DOWNLOAD"
        const val ACTION_OPEN = "com.example.action.OPEN_DOWNLOAD"

        const val EXTRA_DOWNLOAD_ID = "download_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId <= 0) return

        val manager = AdvancedDownloadManager.getInstance(context)

        when (intent.action) {
            ACTION_PAUSE -> {
                manager.pauseDownload(downloadId)
            }
            ACTION_RESUME -> {
                manager.resumeDownload(downloadId)
            }
            ACTION_CANCEL -> {
                manager.cancelDownload(downloadId)
                DownloadNotificationHelper.cancelNotification(context, downloadId)
            }
            ACTION_OPEN -> {
                val item = manager._downloads.value[downloadId]
                val file = item?.file
                if (file != null && file.exists()) {
                    try {
                        val authority = "${context.packageName}.fileprovider"
                        val uri = FileProvider.getUriForFile(context, authority, file)
                        val resolvedMime = if (item.mimeType.isNotBlank() && item.mimeType != "*/*") {
                            item.mimeType
                        } else {
                            when (file.extension.lowercase()) {
                                "mp4", "mkv", "webm" -> "video/*"
                                "mp3", "wav", "m4a" -> "audio/*"
                                "pdf" -> "application/pdf"
                                "jpg", "jpeg", "png" -> "image/*"
                                else -> "*/*"
                            }
                        }

                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, resolvedMime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val chooser = Intent.createChooser(viewIntent, "Abrir com...").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooser)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}
