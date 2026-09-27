package com.example.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.util.AdvancedDownloadItem
import com.example.util.AdvancedDownloadManager
import com.example.util.DownloadNotificationHelper
import com.example.util.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground Service dedicated to executing and maintaining file transfers
 * completely independent of WebView lifecycle, tab switches, tab closures,
 * backgrounding, or UI activity destruction.
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var observeJob: Job? = null

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 9001
        private const val ACTION_START = "com.example.service.action.START_DOWNLOAD_SERVICE"
        private const val ACTION_STOP = "com.example.service.action.STOP_DOWNLOAD_SERVICE"

        @Volatile
        var isRunning: Boolean = false
            internal set

        fun start(context: Context) {
            try {
                val intent = Intent(context, DownloadService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, DownloadService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        DownloadNotificationHelper.initChannel(this)
        startAsForeground()
        observeActiveTransfers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val hasActive = hasActiveDownloads()
            if (!hasActive) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startAsForeground()
        return START_STICKY
    }

    private fun startAsForeground() {
        val initialNotification = buildServiceNotification(
            title = getString(R.string.app_name),
            contentText = "Transferência de ficheiros em segundo plano..."
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
                if (serviceType != 0) {
                    startForeground(FOREGROUND_NOTIFICATION_ID, initialNotification, serviceType)
                } else {
                    startForeground(FOREGROUND_NOTIFICATION_ID, initialNotification)
                }
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildServiceNotification(title: String, contentText: String): Notification {
        return NotificationCompat.Builder(this, DownloadNotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun observeActiveTransfers() {
        observeJob?.cancel()
        val downloadManager = AdvancedDownloadManager.getInstance(applicationContext)
        observeJob = serviceScope.launch {
            downloadManager.downloads.collectLatest { list ->
                val activeList = list.filter {
                    it.state == DownloadState.DOWNLOADING ||
                    it.state == DownloadState.PENDING ||
                    it.state == DownloadState.PROCESSING
                }

                if (activeList.isEmpty()) {
                    // No active downloads running, stop foreground service cleanly
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    // Update main service notification summary if multiple transfers
                    if (activeList.size == 1) {
                        val single = activeList.first()
                        val percent = if (single.totalBytes > 0) {
                            ((single.downloadedBytes.toDouble() / single.totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                        } else 0
                        val subText = if (single.totalBytes > 0) {
                            "$percent% • ${DownloadNotificationHelper.formatBytes(single.downloadedBytes)} de ${DownloadNotificationHelper.formatBytes(single.totalBytes)}"
                        } else {
                            "${DownloadNotificationHelper.formatBytes(single.downloadedBytes)} transferidos"
                        }
                        val notification = DownloadNotificationHelper.buildNotification(
                            this@DownloadService,
                            single
                        )
                        val manager = androidx.core.app.NotificationManagerCompat.from(this@DownloadService)
                        try {
                            manager.notify(FOREGROUND_NOTIFICATION_ID, notification)
                            // Evita duplicação de notificações cancelando o ID individual
                            val individualId = (single.id % 100000).toInt() + 1000
                            manager.cancel(individualId)
                        } catch (ignored: Exception) {}
                    } else {
                        val summaryText = "${activeList.size} transferências ativas em segundo plano"
                        val notification = buildServiceNotification(
                            title = getString(R.string.app_name),
                            contentText = summaryText
                        )
                        val manager = androidx.core.app.NotificationManagerCompat.from(this@DownloadService)
                        try {
                            manager.notify(FOREGROUND_NOTIFICATION_ID, notification)
                        } catch (ignored: Exception) {}
                    }
                }
            }
        }
    }

    private fun hasActiveDownloads(): Boolean {
        val list = AdvancedDownloadManager.getInstance(applicationContext).downloads.value
        return list.any {
            it.state == DownloadState.DOWNLOADING ||
            it.state == DownloadState.PENDING ||
            it.state == DownloadState.PROCESSING
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        observeJob?.cancel()
        serviceScope.cancel()
    }
}
