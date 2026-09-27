package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.receiver.DownloadNotificationReceiver
import com.example.service.DownloadService
import java.util.concurrent.ConcurrentHashMap

/**
 * Responsável por exibir e atualizar o estado e progresso das transferências
 * de ficheiros na barra de notificações do sistema, exibindo o ícone do aplicativo,
 * porcentagem, estado em tempo real e ações (Pausar, Retomar, Cancelar, Abrir).
 */
object DownloadNotificationHelper {

    const val CHANNEL_ID = "channel_file_transfers"
    const val CHANNEL_NAME = "Transferências de Ficheiros"
    const val CHANNEL_DESCRIPTION = "Estado e progresso de downloads em tempo real"

    private val lastNotificationTime = ConcurrentHashMap<Long, Long>()
    private var cachedAppIcon: Bitmap? = null

    fun initChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(true)
                enableVibration(false)
                enableLights(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun getAppIconBitmap(context: Context): Bitmap? {
        if (cachedAppIcon != null) return cachedAppIcon
        return try {
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_app_logo)
                ?: androidx.core.content.ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
            if (drawable != null) {
                val size = 128
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                cachedAppIcon = bitmap
                bitmap
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun buildNotification(context: Context, item: AdvancedDownloadItem): android.app.Notification {
        initChannel(context)

        val percent = if (item.totalBytes > 0) {
            ((item.downloadedBytes.toDouble() / item.totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val appIcon = getAppIconBitmap(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(item.fileName)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)

        if (appIcon != null) {
            builder.setLargeIcon(appIcon)
        }

        // PendingIntent to launch app if user taps the active notification
        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            item.id.toInt(),
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(contentPendingIntent)

        when (item.state) {
            DownloadState.DOWNLOADING -> {
                val subText = if (item.totalBytes > 0) {
                    "$percent% • ${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
                } else {
                    "${formatBytes(item.downloadedBytes)} transferidos"
                }
                builder.setContentText(subText)
                    .setProgress(100, percent, item.totalBytes <= 0)
                    .setOngoing(true)
                    .setAutoCancel(false)

                // Action: Pausar
                val pauseIntent = Intent(context, DownloadNotificationReceiver::class.java).apply {
                    action = DownloadNotificationReceiver.ACTION_PAUSE
                    putExtra(DownloadNotificationReceiver.EXTRA_DOWNLOAD_ID, item.id)
                }
                val pausePendingIntent = PendingIntent.getBroadcast(
                    context,
                    (item.id * 10 + 1).toInt(),
                    pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "Pausar", pausePendingIntent)

                // Action: Cancelar
                val cancelIntent = Intent(context, DownloadNotificationReceiver::class.java).apply {
                    action = DownloadNotificationReceiver.ACTION_CANCEL
                    putExtra(DownloadNotificationReceiver.EXTRA_DOWNLOAD_ID, item.id)
                }
                val cancelPendingIntent = PendingIntent.getBroadcast(
                    context,
                    (item.id * 10 + 2).toInt(),
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "Cancelar", cancelPendingIntent)
            }

            DownloadState.PROCESSING -> {
                builder.setContentText("A processar e converter vídeo...")
                    .setProgress(100, 0, true)
                    .setOngoing(true)
                    .setAutoCancel(false)

                val cancelIntent = Intent(context, DownloadNotificationReceiver::class.java).apply {
                    action = DownloadNotificationReceiver.ACTION_CANCEL
                    putExtra(DownloadNotificationReceiver.EXTRA_DOWNLOAD_ID, item.id)
                }
                val cancelPendingIntent = PendingIntent.getBroadcast(
                    context,
                    (item.id * 10 + 2).toInt(),
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "Cancelar", cancelPendingIntent)
            }

            DownloadState.PAUSED -> {
                val subText = if (item.totalBytes > 0) {
                    "Em pausa • $percent% (${formatBytes(item.downloadedBytes)} de ${formatBytes(item.totalBytes)})"
                } else {
                    "Em pausa • ${formatBytes(item.downloadedBytes)}"
                }
                builder.setContentText(subText)
                    .setProgress(100, percent, false)
                    .setOngoing(false)
                    .setAutoCancel(true)

                // Action: Retomar
                val resumeIntent = Intent(context, DownloadNotificationReceiver::class.java).apply {
                    action = DownloadNotificationReceiver.ACTION_RESUME
                    putExtra(DownloadNotificationReceiver.EXTRA_DOWNLOAD_ID, item.id)
                }
                val resumePendingIntent = PendingIntent.getBroadcast(
                    context,
                    (item.id * 10 + 3).toInt(),
                    resumeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "Retomar", resumePendingIntent)

                // Action: Cancelar
                val cancelIntent = Intent(context, DownloadNotificationReceiver::class.java).apply {
                    action = DownloadNotificationReceiver.ACTION_CANCEL
                    putExtra(DownloadNotificationReceiver.EXTRA_DOWNLOAD_ID, item.id)
                }
                val cancelPendingIntent = PendingIntent.getBroadcast(
                    context,
                    (item.id * 10 + 2).toInt(),
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "Cancelar", cancelPendingIntent)
            }

            DownloadState.COMPLETED -> {
                val totalFormatted = formatBytes(if (item.totalBytes > 0) item.totalBytes else item.downloadedBytes)
                builder.setContentText("Transferência concluída • $totalFormatted")
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)

                // Action & Content Intent: Abrir Ficheiro
                val openIntent = Intent(context, DownloadNotificationReceiver::class.java).apply {
                    action = DownloadNotificationReceiver.ACTION_OPEN
                    putExtra(DownloadNotificationReceiver.EXTRA_DOWNLOAD_ID, item.id)
                }
                val openPendingIntent = PendingIntent.getBroadcast(
                    context,
                    (item.id * 10 + 4).toInt(),
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(openPendingIntent)
                builder.addAction(0, "Abrir", openPendingIntent)
            }

            DownloadState.FAILED -> {
                builder.setContentText("Falha na transferência: ${item.errorMessage ?: "Erro desconhecido"}")
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)

                val retryIntent = Intent(context, DownloadNotificationReceiver::class.java).apply {
                    action = DownloadNotificationReceiver.ACTION_RESUME
                    putExtra(DownloadNotificationReceiver.EXTRA_DOWNLOAD_ID, item.id)
                }
                val retryPendingIntent = PendingIntent.getBroadcast(
                    context,
                    (item.id * 10 + 5).toInt(),
                    retryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "Repetir", retryPendingIntent)
            }

            DownloadState.BLOCKED_EXTENSION -> {
                builder.setContentText("Transferência bloqueada por segurança")
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
            }

            DownloadState.PENDING -> {
                builder.setContentText("A iniciar transferência...")
                    .setProgress(100, 0, true)
                    .setOngoing(true)
                    .setAutoCancel(false)
            }

            DownloadState.CANCELLED -> {
                builder.setContentText("Transferência cancelada")
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
            }
        }

        return builder.build()
    }

    fun updateNotification(context: Context, item: AdvancedDownloadItem, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val lastTime = lastNotificationTime[item.id] ?: 0L

        // Throttle progress updates to avoid spamming the notification manager
        val isProgressUpdate = item.state == DownloadState.DOWNLOADING
        if (isProgressUpdate && !force && (now - lastTime < 600)) {
            return
        }
        lastNotificationTime[item.id] = now

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        initChannel(context)

        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) {
            return
        }

        val individualId = (item.id % 100000).toInt() + 1000

        // Se o DownloadService estiver executando em primeiro plano e houver apenas 1 transferência ativa,
        // vinculamos a notificação ao ID do serviço de primeiro plano para evitar QUALQUER duplicação na barra de status.
        val isServiceRunning = DownloadService.isRunning
        val activeList = try {
            AdvancedDownloadManager.getInstance(context).downloads.value.filter {
                it.state == DownloadState.DOWNLOADING ||
                it.state == DownloadState.PENDING ||
                it.state == DownloadState.PROCESSING
            }
        } catch (_: Exception) {
            emptyList()
        }

        val isSingleActive = isServiceRunning && activeList.size == 1 && activeList.first().id == item.id
        val targetNotificationId = if (isSingleActive) {
            // Cancela o ID individual para garantir que apenas o ID do foreground permaneça
            notificationManager.cancel(individualId)
            DownloadService.FOREGROUND_NOTIFICATION_ID
        } else {
            individualId
        }

        if (item.state == DownloadState.CANCELLED) {
            notificationManager.cancel(individualId)
            if (isSingleActive) {
                notificationManager.cancel(DownloadService.FOREGROUND_NOTIFICATION_ID)
            }
            lastNotificationTime.remove(item.id)
            return
        }

        try {
            val notification = buildNotification(context, item)
            notificationManager.notify(targetNotificationId, notification)
        } catch (e: SecurityException) {
            // Missing notification permission on Android 13+
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelNotification(context: Context, downloadId: Long) {
        try {
            val notificationManager = NotificationManagerCompat.from(context)
            val notificationId = (downloadId % 100000).toInt() + 1000
            notificationManager.cancel(notificationId)
            lastNotificationTime.remove(downloadId)

            val remainingActive = try {
                AdvancedDownloadManager.getInstance(context).downloads.value
                    .count { (it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING || it.state == DownloadState.PROCESSING) && it.id != downloadId }
            } catch (_: Exception) { 0 }
            if (remainingActive == 0) {
                notificationManager.cancel(DownloadService.FOREGROUND_NOTIFICATION_ID)
            }
        } catch (_: Exception) {}
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(
            java.util.Locale.getDefault(),
            "%.1f %s",
            bytes / Math.pow(1024.0, digitGroups.toDouble()),
            units[digitGroups]
        )
    }
}
