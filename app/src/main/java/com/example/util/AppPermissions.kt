package com.example.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

data class AppPermissionItem(
    val id: String,
    val name: String,
    val description: String,
    val permissions: List<String>,
    val isGranted: Boolean,
    val isRevokedOrDenied: Boolean,
    val category: String
)

data class WebPermissionRequestEvent(
    val request: PermissionRequest,
    val origin: String,
    val resources: List<String>,
    val requiredAndroidPermissions: List<String>
) {
    fun grant() {
        try {
            request.grant(resources.toTypedArray())
        } catch (_: Exception) {}
    }

    fun deny() {
        try {
            request.deny()
        } catch (_: Exception) {}
    }
}

data class WebGeolocationRequestEvent(
    val origin: String,
    val callback: GeolocationPermissions.Callback
) {
    fun grant(remember: Boolean = true) {
        try {
            callback.invoke(origin, true, remember)
        } catch (_: Exception) {}
    }

    fun deny() {
        try {
            callback.invoke(origin, false, false)
        } catch (_: Exception) {}
    }
}

data class WebFileChooserEvent(
    val filePathCallback: ValueCallback<Array<Uri>>,
    val fileChooserParams: WebChromeClient.FileChooserParams
) {
    fun onResult(uris: Array<Uri>?) {
        try {
            filePathCallback.onReceiveValue(uris)
        } catch (_: Exception) {
            try {
                filePathCallback.onReceiveValue(null)
            } catch (_: Exception) {}
        }
    }
}

data class RevokedPermissionAlert(
    val permissionTitle: String,
    val rationale: String,
    val origin: String? = null
)

object AppPermissionsHelper {

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAllPermissions(context: Context, permissions: List<String>): Boolean {
        return permissions.all { hasPermission(context, it) }
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun getAppPermissionStatusList(context: Context): List<AppPermissionItem> {
        val list = mutableListOf<AppPermissionItem>()

        // 1. Notificações (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            list.add(
                AppPermissionItem(
                    id = "notifications",
                    name = "Notificações",
                    description = "Alertas de progresso e conclusão de transferências em segundo plano.",
                    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                    isGranted = granted,
                    isRevokedOrDenied = !granted,
                    category = "Sistema"
                )
            )
        }

        // 2. Câmara (WebRTC / Sites)
        val cameraGranted = hasPermission(context, Manifest.permission.CAMERA)
        list.add(
            AppPermissionItem(
                id = "camera",
                name = "Câmara",
                description = "Permite que páginas web capturem fotos, leiam códigos QR ou façam videochamadas quando solicitado.",
                permissions = listOf(Manifest.permission.CAMERA),
                isGranted = cameraGranted,
                isRevokedOrDenied = !cameraGranted,
                category = "Navegação Web"
            )
        )

        // 3. Microfone (WebRTC / Voz)
        val micGranted = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        list.add(
            AppPermissionItem(
                id = "microphone",
                name = "Microfone",
                description = "Permite pesquisas por voz e chamadas de áudio em websites quando solicitado.",
                permissions = listOf(Manifest.permission.RECORD_AUDIO),
                isGranted = micGranted,
                isRevokedOrDenied = !micGranted,
                category = "Navegação Web"
            )
        )

        // 4. Localização (Geolocalização HTML5)
        val locationGranted = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        list.add(
            AppPermissionItem(
                id = "location",
                name = "Localização",
                description = "Permite a websites (mapas, entregas, meteorologia) apresentar conteúdos locais quando autorizado.",
                permissions = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                isGranted = locationGranted,
                isRevokedOrDenied = !locationGranted,
                category = "Navegação Web"
            )
        )

        // 5. Armazenamento Legado (apenas Android 9 ou inferior)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val storageGranted = hasPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            list.add(
                AppPermissionItem(
                    id = "storage",
                    name = "Armazenamento",
                    description = "Necessário no Android 9 para gravar ficheiros descarregados na memória pública.",
                    permissions = listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    isGranted = storageGranted,
                    isRevokedOrDenied = !storageGranted,
                    category = "Armazenamento"
                )
            )
        }

        return list
    }

    fun isPermanentlyDenied(activity: Activity?, permission: String): Boolean {
        if (activity == null) return false
        val isNotGranted = !hasPermission(activity, permission)
        val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        // If not granted and system says we should not show rationale, user might have checked "Don't ask again" or revoked
        return isNotGranted && !shouldShowRationale
    }
}
