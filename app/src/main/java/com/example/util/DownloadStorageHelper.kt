package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class StoragePermissionInfo(
    val hasStorageAccess: Boolean,
    val hasNotificationPermission: Boolean,
    val isLegacyStorage: Boolean,
    val statusMessage: String
)

data class FolderPreset(
    val id: String,
    val name: String,
    val description: String,
    val uriOrPath: String,
    val isDefault: Boolean = false
)

object DownloadStorageHelper {

    fun getDefaultDownloadDirectory(): File {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!publicDir.exists()) {
            publicDir.mkdirs()
        }
        return publicDir
    }

    fun getStandardPresets(): List<FolderPreset> {
        val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        val documentsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath
        val moviesPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath
        val musicPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath
        val picturesPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath

        return listOf(
            FolderPreset(
                id = "downloads",
                name = "Downloads (Padrão)",
                description = "Pasta pública de transferências do dispositivo",
                uriOrPath = downloadPath,
                isDefault = true
            ),
            FolderPreset(
                id = "documents",
                name = "Documentos",
                description = "Pasta pública de Documentos",
                uriOrPath = documentsPath
            ),
            FolderPreset(
                id = "movies",
                name = "Filmes e Vídeos",
                description = "Pasta pública de Vídeos e Filmes",
                uriOrPath = moviesPath
            ),
            FolderPreset(
                id = "music",
                name = "Músicas e Áudios",
                description = "Pasta pública de Músicas",
                uriOrPath = musicPath
            ),
            FolderPreset(
                id = "pictures",
                name = "Imagens",
                description = "Pasta pública de Imagens",
                uriOrPath = picturesPath
            )
        )
    }

    fun resolveDestinationDirectory(context: Context, customUriOrPath: String?): File {
        if (customUriOrPath.isNullOrBlank()) {
            return getDefaultDownloadDirectory()
        }

        // Direct path
        if (customUriOrPath.startsWith("/")) {
            val file = File(customUriOrPath)
            if (!file.exists()) {
                file.mkdirs()
            }
            if (file.canWrite()) {
                return file
            }
        }

        // Storage Access Framework Tree Uri (content://...)
        if (customUriOrPath.startsWith("content://")) {
            try {
                val uri = Uri.parse(customUriOrPath)
                val docId = DocumentsContract.getTreeDocumentId(uri)
                if (docId.startsWith("primary:")) {
                    val relative = docId.substringAfter("primary:")
                    val externalStorage = Environment.getExternalStorageDirectory()
                    val target = if (relative.isBlank()) externalStorage else File(externalStorage, relative)
                    if (!target.exists()) {
                        target.mkdirs()
                    }
                    if (target.canWrite()) {
                        return target
                    }
                }
            } catch (_: Exception) {
            }

            // If direct file access is restricted by scoped storage, return app external downloads directory
            // which can then be mirrored or accessed safely
            val appExternal = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (appExternal != null && (appExternal.exists() || appExternal.mkdirs())) {
                return appExternal
            }
        }

        return getDefaultDownloadDirectory()
    }

    fun getDisplayName(context: Context, uriOrPath: String?): String {
        if (uriOrPath.isNullOrBlank()) {
            return "Downloads (Padrão)"
        }

        val presets = getStandardPresets()
        val matchingPreset = presets.firstOrNull { it.uriOrPath.equals(uriOrPath, ignoreCase = true) }
        if (matchingPreset != null) {
            return matchingPreset.name
        }

        if (uriOrPath.startsWith("/")) {
            val file = File(uriOrPath)
            return file.name.takeIf { it.isNotBlank() } ?: uriOrPath
        }

        if (uriOrPath.startsWith("content://")) {
            try {
                val doc = DocumentFile.fromTreeUri(context, Uri.parse(uriOrPath))
                val name = doc?.name
                if (!name.isNullOrBlank()) {
                    return name
                }
                val docId = DocumentsContract.getTreeDocumentId(Uri.parse(uriOrPath))
                if (docId.contains(':')) {
                    val sub = docId.substringAfter(':')
                    if (sub.isNotBlank()) return sub
                }
            } catch (_: Exception) {
            }
            return "Pasta Personalizada"
        }

        return uriOrPath
    }

    fun copyFileToTreeUri(
        context: Context,
        sourceFile: File,
        treeUri: Uri,
        fileName: String,
        mimeType: String
    ): Uri? {
        return try {
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val existing = treeDoc.findFile(fileName)
            val targetDoc = existing ?: treeDoc.createFile(mimeType.ifBlank { "application/octet-stream" }, fileName) ?: return null

            context.contentResolver.openOutputStream(targetDoc.uri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            targetDoc.uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun checkPermissions(context: Context): StoragePermissionInfo {
        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        val isLegacy = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        val hasStorage = if (isLegacy) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 10+ uses Scoped Storage or SAF permissions
            true
        }

        val message = when {
            !hasStorage -> "Permissão de escrita em armazenamento pendente (necessária no Android 9 ou inferior)."
            !hasNotifications -> "Notificações desativadas. Ative para acompanhar o progresso das transferências."
            else -> "Acesso ao armazenamento e notificações configurados corretamente."
        }

        return StoragePermissionInfo(
            hasStorageAccess = hasStorage,
            hasNotificationPermission = hasNotifications,
            isLegacyStorage = isLegacy,
            statusMessage = message
        )
    }
}
