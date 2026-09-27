package com.example.util

import android.content.Context
import android.net.Uri
import android.webkit.GeolocationPermissions
import com.example.model.PermissionPolicy
import com.example.model.SitePermissionRecord
import com.example.model.WebPermissionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class SitePermissionsManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("browser_site_permissions", Context.MODE_PRIVATE)

    private val _sitePermissions = MutableStateFlow<Map<String, SitePermissionRecord>>(loadAll())
    val sitePermissions: StateFlow<Map<String, SitePermissionRecord>> = _sitePermissions.asStateFlow()

    private fun loadAll(): Map<String, SitePermissionRecord> {
        val result = mutableMapOf<String, SitePermissionRecord>()
        val allEntries = prefs.all
        for ((key, value) in allEntries) {
            if (value is String) {
                try {
                    val json = JSONObject(value)
                    val origin = json.optString("origin", key)
                    val camera = parsePolicy(json.optString("camera"))
                    val microphone = parsePolicy(json.optString("microphone"))
                    val location = parsePolicy(json.optString("location"))
                    val updated = json.optLong("lastUpdated", System.currentTimeMillis())

                    val record = SitePermissionRecord(
                        origin = origin,
                        camera = camera,
                        microphone = microphone,
                        location = location,
                        lastUpdated = updated
                    )
                    result[origin] = record
                } catch (_: Exception) {}
            }
        }
        return result
    }

    private fun parsePolicy(value: String?): PermissionPolicy {
        return PermissionPolicy.entries.find { it.key == value } ?: PermissionPolicy.ASK
    }

    fun getSiteRecord(rawUrlOrOrigin: String): SitePermissionRecord {
        val origin = extractOrigin(rawUrlOrOrigin) ?: rawUrlOrOrigin
        return _sitePermissions.value[origin] ?: SitePermissionRecord(origin = origin)
    }

    fun getEffectivePolicy(
        rawUrlOrOrigin: String,
        type: WebPermissionType,
        globalDefault: PermissionPolicy
    ): PermissionPolicy {
        val origin = extractOrigin(rawUrlOrOrigin) ?: rawUrlOrOrigin
        val record = _sitePermissions.value[origin]
        val sitePolicy = record?.getPolicy(type) ?: PermissionPolicy.ASK
        return if (sitePolicy != PermissionPolicy.ASK) {
            sitePolicy
        } else {
            globalDefault
        }
    }

    fun setPolicy(
        rawUrlOrOrigin: String,
        type: WebPermissionType,
        policy: PermissionPolicy
    ) {
        val origin = extractOrigin(rawUrlOrOrigin) ?: rawUrlOrOrigin
        if (origin.isBlank() || origin == "null" || origin.startsWith("about:")) return

        val current = _sitePermissions.value[origin] ?: SitePermissionRecord(origin = origin)
        val updated = current.withPolicy(type, policy)

        // Save to SharedPreferences
        val json = JSONObject().apply {
            put("origin", updated.origin)
            put("camera", updated.camera.key)
            put("microphone", updated.microphone.key)
            put("location", updated.location.key)
            put("lastUpdated", updated.lastUpdated)
        }
        prefs.edit().putString(origin, json.toString()).apply()

        // Sync with WebView native Geolocation cache
        if (type == WebPermissionType.LOCATION) {
            try {
                if (policy == PermissionPolicy.ALLOW) {
                    GeolocationPermissions.getInstance().getAllowed(origin) { /* cached */ }
                } else if (policy == PermissionPolicy.BLOCK) {
                    GeolocationPermissions.getInstance().clear(origin)
                }
            } catch (_: Exception) {}
        }

        // Update reactive state
        val updatedMap = _sitePermissions.value.toMutableMap()
        updatedMap[origin] = updated
        _sitePermissions.value = updatedMap
    }

    fun removeSite(rawUrlOrOrigin: String) {
        val origin = extractOrigin(rawUrlOrOrigin) ?: rawUrlOrOrigin
        prefs.edit().remove(origin).apply()
        try {
            GeolocationPermissions.getInstance().clear(origin)
        } catch (_: Exception) {}

        val updatedMap = _sitePermissions.value.toMutableMap()
        updatedMap.remove(origin)
        _sitePermissions.value = updatedMap
    }

    fun clearAllSites() {
        prefs.edit().clear().apply()
        try {
            GeolocationPermissions.getInstance().clearAll()
        } catch (_: Exception) {}
        _sitePermissions.value = emptyMap()
    }

    fun getAllRecords(): List<SitePermissionRecord> {
        return _sitePermissions.value.values.sortedByDescending { it.lastUpdated }
    }

    companion object {
        @Volatile
        private var instance: SitePermissionsManager? = null

        fun getInstance(context: Context): SitePermissionsManager {
            return instance ?: synchronized(this) {
                instance ?: SitePermissionsManager(context.applicationContext).also { instance = it }
            }
        }

        fun extractOrigin(rawUrl: String?): String? {
            if (rawUrl.isNullOrBlank()) return null
            return try {
                val uri = Uri.parse(rawUrl)
                val scheme = uri.scheme?.lowercase() ?: return null
                if (scheme != "http" && scheme != "https") return null
                val host = uri.host?.lowercase() ?: return null
                val port = uri.port
                if (port != -1 && port != 80 && port != 443) {
                    "$scheme://$host:$port"
                } else {
                    "$scheme://$host"
                }
            } catch (_: Exception) {
                null
            }
        }

        fun isOriginSecure(origin: String?): Boolean {
            if (origin == null) return false
            return origin.startsWith("https://") || origin.startsWith("http://localhost") || origin.startsWith("http://127.0.0.1")
        }
    }
}
