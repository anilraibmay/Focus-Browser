package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.ActiveDownloadNotification
import com.example.model.BrowserPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.model.BrowserTab
import com.example.model.FocusMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.app.DownloadManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Environment
import androidx.core.content.FileProvider
import android.content.Intent
import java.io.File
import android.webkit.WebResourceError
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.util.WebPermissionRequestEvent
import com.example.util.WebGeolocationRequestEvent
import com.example.util.WebFileChooserEvent
import com.example.util.RevokedPermissionAlert
import com.example.model.WebPermissionType
import com.example.model.PermissionPolicy
import com.example.model.SitePermissionRecord
import com.example.util.SitePermissionsManager
import com.example.util.AppPermissionsHelper


class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: com.example.database.TabStateRepository
    private val historyRepository: com.example.database.HistoryRepository
    private val videoHistoryRepository: com.example.database.VideoHistoryRepository

    val historyManager: com.example.controller.HistoryManager
    val webMediaController: com.example.controller.WebMediaController
    val tabLifecycleManager: com.example.controller.TabLifecycleManager
    val downloadDelegate: com.example.controller.DownloadDelegate
    val navigationDelegate: com.example.controller.NavigationDelegate
    val networkStateDelegate: com.example.controller.NetworkStateDelegate

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String>("")
    val activeTabId = _activeTabId.asStateFlow()

    private val _splitTab = MutableStateFlow<BrowserTab?>(null)
    val splitTab = _splitTab.asStateFlow()

    private val prefsStorage = application.getSharedPreferences("browser_preferences", Context.MODE_PRIVATE)

    private fun loadInitialPreferences(): BrowserPreferences {
        val themeModeStr = prefsStorage.getString("theme_mode", com.example.model.AppThemeMode.SYSTEM.name) ?: com.example.model.AppThemeMode.SYSTEM.name
        val themeMode = try { com.example.model.AppThemeMode.valueOf(themeModeStr) } catch (_: Exception) { com.example.model.AppThemeMode.SYSTEM }

        val focusModeStr = prefsStorage.getString("focus_mode", FocusMode.NORMAL.name) ?: FocusMode.NORMAL.name
        val focusMode = try { FocusMode.valueOf(focusModeStr) } catch (_: Exception) { FocusMode.NORMAL }

        val camPolicyStr = prefsStorage.getString("default_camera_policy", PermissionPolicy.ASK.name) ?: PermissionPolicy.ASK.name
        val camPolicy = try { PermissionPolicy.valueOf(camPolicyStr) } catch (_: Exception) { PermissionPolicy.ASK }

        val micPolicyStr = prefsStorage.getString("default_mic_policy", PermissionPolicy.ASK.name) ?: PermissionPolicy.ASK.name
        val micPolicy = try { PermissionPolicy.valueOf(micPolicyStr) } catch (_: Exception) { PermissionPolicy.ASK }

        val locPolicyStr = prefsStorage.getString("default_loc_policy", PermissionPolicy.ASK.name) ?: PermissionPolicy.ASK.name
        val locPolicy = try { PermissionPolicy.valueOf(locPolicyStr) } catch (_: Exception) { PermissionPolicy.ASK }

        return BrowserPreferences(
            themeMode = themeMode,
            focusMode = focusMode,
            desktopMode = prefsStorage.getBoolean("desktop_mode", false),
            blockPopups = prefsStorage.getBoolean("block_popups", true),
            enableJavaScript = prefsStorage.getBoolean("enable_js", true),
            enableAdBlock = prefsStorage.getBoolean("enable_adblock", true),
            clearCacheOnExit = prefsStorage.getBoolean("clear_cache_on_exit", false),
            enableBackgroundSync = prefsStorage.getBoolean("enable_bg_sync", true),
            downloadDirectoryUri = prefsStorage.getString("download_dir_uri", null),
            downloadDirectoryName = prefsStorage.getString("download_dir_name", "Downloads (Padrão)") ?: "Downloads (Padrão)",
            askDownloadLocation = prefsStorage.getBoolean("ask_download_location", false),
            defaultCameraPolicy = camPolicy,
            defaultMicrophonePolicy = micPolicy,
            defaultLocationPolicy = locPolicy
        )
    }

    private fun persistPreferences(prefs: BrowserPreferences) {
        prefsStorage.edit()
            .putString("theme_mode", prefs.themeMode.name)
            .putString("focus_mode", prefs.focusMode.name)
            .putBoolean("desktop_mode", prefs.desktopMode)
            .putBoolean("block_popups", prefs.blockPopups)
            .putBoolean("enable_js", prefs.enableJavaScript)
            .putBoolean("enable_adblock", prefs.enableAdBlock)
            .putBoolean("clear_cache_on_exit", prefs.clearCacheOnExit)
            .putBoolean("enable_bg_sync", prefs.enableBackgroundSync)
            .putString("download_dir_uri", prefs.downloadDirectoryUri)
            .putString("download_dir_name", prefs.downloadDirectoryName)
            .putBoolean("ask_download_location", prefs.askDownloadLocation)
            .putString("default_camera_policy", prefs.defaultCameraPolicy.name)
            .putString("default_mic_policy", prefs.defaultMicrophonePolicy.name)
            .putString("default_loc_policy", prefs.defaultLocationPolicy.name)
            .apply()
    }

    private val _preferences = MutableStateFlow(loadInitialPreferences())
    val preferences = _preferences.asStateFlow()

    val sitePermissionsManager = SitePermissionsManager.getInstance(application)
    val sitePermissions = sitePermissionsManager.sitePermissions

    val fullscreenView: StateFlow<View?> get() = webMediaController.fullscreenView

    private val _notification = MutableStateFlow<String?>(null)
    val notification = _notification.asStateFlow()

    val pendingDownloadPayload: StateFlow<com.example.model.DownloadPayload?>
        get() = downloadDelegate.pendingDownloadPayload

    private val _pendingDownload = MutableStateFlow<com.example.model.PendingDownloadDialog?>(null)
    val pendingDownload = _pendingDownload.asStateFlow()

    val downloadsList: StateFlow<List<com.example.util.AdvancedDownloadItem>>
        get() = downloadDelegate.downloadsList

    private val _activeDownloadNotification = MutableStateFlow<ActiveDownloadNotification?>(null)
    val activeDownloadNotification: StateFlow<ActiveDownloadNotification?> = _activeDownloadNotification.asStateFlow()

    fun triggerInstantDownloadNotification(fileName: String) {
        val cleanName = fileName.substringAfterLast("/").substringAfterLast("\\").trim()
        _activeDownloadNotification.value = ActiveDownloadNotification(
            id = System.currentTimeMillis(),
            fileName = if (cleanName.isNotEmpty()) cleanName else "ficheiro"
        )
    }

    fun dismissActiveDownloadNotification() {
        _activeDownloadNotification.value = null
    }

    val historyList: StateFlow<List<com.example.database.HistoryEntity>> get() = historyManager.historyList

    private lateinit var shortcutRepository: com.example.database.ShortcutRepository
    private val _shortcuts = MutableStateFlow<List<com.example.database.ShortcutEntity>>(emptyList())
    val shortcuts: StateFlow<List<com.example.database.ShortcutEntity>> = _shortcuts.asStateFlow()

    private val _sslErrorState = MutableStateFlow<com.example.model.SslErrorState?>(null)
    val sslErrorState = _sslErrorState.asStateFlow()

    // Network Timeline State
    private val _networkEvents = MutableStateFlow<List<com.example.model.NetworkEvent>>(emptyList())
    val networkEvents = _networkEvents.asStateFlow()

    fun recordNetworkEvent(tabId: String, url: String, headers: Map<String, String> = emptyMap(), method: String = "GET", isBlocked: Boolean = false) {
        val type = com.example.util.NetworkEventClassifier.classify(url, headers)
        val event = com.example.model.NetworkEvent(
            tabId = tabId,
            url = url,
            method = method,
            type = type,
            timestamp = System.currentTimeMillis(),
            headers = headers,
            isBlocked = isBlocked
        )
        _networkEvents.value = (listOf(event) + _networkEvents.value).take(1200)
    }

    fun clearNetworkEvents(tabId: String? = null) {
        if (tabId != null) {
            _networkEvents.value = _networkEvents.value.filter { it.tabId != tabId }
        } else {
            _networkEvents.value = emptyList()
        }
    }

    fun startDirectDownload(context: Context, url: String, userAgent: String = "") {
        val payload = com.example.model.DownloadPayload(
            url = url,
            userAgent = userAgent,
            contentDisposition = null,
            mimeType = null,
            targetTreeUri = _preferences.value.downloadDirectoryUri,
            targetDirectoryName = _preferences.value.downloadDirectoryName
        )
        downloadDelegate.executeDirectDownload(context, payload)
    }

    // YouTube Dedicated Extraction & Stream State
    private val _youTubeDetails = MutableStateFlow<com.example.util.YouTubeVideoDetails?>(null)
    val youTubeDetails = _youTubeDetails.asStateFlow()

    private val _isYouTubeExtracting = MutableStateFlow(false)
    val isYouTubeExtracting = _isYouTubeExtracting.asStateFlow()

    // Web permissions & runtime requests
    private val _pendingWebPermissionRequest = MutableStateFlow<WebPermissionRequestEvent?>(null)
    val pendingWebPermissionRequest = _pendingWebPermissionRequest.asStateFlow()

    private val _pendingGeolocationRequest = MutableStateFlow<WebGeolocationRequestEvent?>(null)
    val pendingGeolocationRequest = _pendingGeolocationRequest.asStateFlow()

    private val _pendingFileChooser = MutableStateFlow<WebFileChooserEvent?>(null)
    val pendingFileChooser = _pendingFileChooser.asStateFlow()

    private val _revokedPermissionAlert = MutableStateFlow<RevokedPermissionAlert?>(null)
    val revokedPermissionAlert = _revokedPermissionAlert.asStateFlow()

    // Track URLs where automatic DNS prefix fallback (e.g. apex -> www or www -> apex) was attempted
    private val fallbackAttemptedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        val db = com.example.database.AppDatabase.getDatabase(application)
        repository = com.example.database.TabStateRepository(db.tabStateDao())
        historyRepository = com.example.database.HistoryRepository(db.historyDao())
        videoHistoryRepository = com.example.database.VideoHistoryRepository(db.videoHistoryDao())

        historyManager = com.example.controller.HistoryManager(historyRepository, viewModelScope)

        downloadDelegate = com.example.controller.DownloadDelegate(
            application = application,
            onNotify = { msg -> showNotification(msg) }
        )

        com.example.util.AdvancedDownloadManager.getInstance(application).onDownloadStartedListener = { fileName ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                triggerInstantDownloadNotification(fileName)
            }
        }

        navigationDelegate = com.example.controller.NavigationDelegate(
            onNotify = { msg -> showNotification(msg) }
        )

        networkStateDelegate = com.example.controller.NetworkStateDelegate(
            application = application,
            onNetworkReconnected = {
                activeTab?.let { tab -> refresh(tab) }
            },
            onNotify = { msg -> showNotification(msg) }
        )

        webMediaController = com.example.controller.WebMediaController(
            scope = viewModelScope,
            onVideoExtracted = { tabId, video ->
                updateTabExtractedVideo(tabId, video)
            },
            onVideoEnriched = { tabId, video ->
                updateTabExtractedVideo(tabId, video)
            }
        )

        tabLifecycleManager = com.example.controller.TabLifecycleManager(
            repository = repository,
            scope = viewModelScope
        )

        val shortcutDao = db.shortcutDao()
        shortcutRepository = com.example.database.ShortcutRepository(shortcutDao)
        viewModelScope.launch {
            shortcutRepository.allShortcutsFlow.collect { list ->
                _shortcuts.value = list
            }
        }
        viewModelScope.launch {
            shortcutRepository.seedDefaultsIfEmpty()
        }

        viewModelScope.launch {
            com.example.util.AdBlockEngine.getInstance().loadRules(application)
        }
        
        loadInitialTabs()
        startBackgroundSyncService()
        startBackgroundMonitoringService()
        
        try {
            application.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || 
                        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
                        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                        discardTabsOnLowMemory()
                    }
                }
                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
                override fun onLowMemory() {
                    discardTabsOnLowMemory()
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }

        networkStateDelegate.start()

        // Configure ServiceWorkerClient to intercept streaming requests (ABYS / sora / MSE)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val swController = android.webkit.ServiceWorkerController.getInstance()
                swController.setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        val url = request.url?.toString()
                        if (url != null) {
                            val method = request.method ?: "GET"
                            val headers = request.requestHeaders ?: emptyMap()
                            val curTabId = _activeTabId.value
                            handleNetworkIntercept(curTabId, url, headers, null, null, method = method)
                        }
                        return null
                    }
                })
            }
        } catch (_: Throwable) {}
    }

    private var notificationJob: kotlinx.coroutines.Job? = null

    fun clearNotification() {
        notificationJob?.cancel()
        _notification.value = null
    }

    fun showNotification(msg: String) {
        _notification.value = msg
        notificationJob?.cancel()
        notificationJob = viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _notification.value = null
        }
    }

    fun addShortcut(title: String, url: String) {
        viewModelScope.launch {
            val formattedUrl = navigationDelegate.normalizeAndFormatUrl(url)
            val displayTitle = if (title.isNotBlank()) title.trim() else formattedUrl
            shortcutRepository.insertShortcut(displayTitle, formattedUrl)
            showNotification("Atalho \"$displayTitle\" adicionado!")
        }
    }

    fun updateShortcut(id: Long, title: String, url: String) {
        viewModelScope.launch {
            val formattedUrl = navigationDelegate.normalizeAndFormatUrl(url)
            val displayTitle = if (title.isNotBlank()) title.trim() else formattedUrl
            shortcutRepository.updateShortcut(id, displayTitle, formattedUrl)
            showNotification("Atalho atualizado com sucesso!")
        }
    }

    fun deleteShortcut(id: Long) {
        viewModelScope.launch {
            shortcutRepository.deleteShortcut(id)
            showNotification("Atalho removido.")
        }
    }

    fun resetShortcutsToDefaults() {
        viewModelScope.launch {
            shortcutRepository.resetToDefaults()
            showNotification("Atalhos padrão restaurados!")
        }
    }

    val activeTab: BrowserTab?
        get() = _tabs.value.find { it.id == _activeTabId.value }

    fun updatePreferences(update: (BrowserPreferences) -> BrowserPreferences) {
        val oldPrefs = _preferences.value
        val newPrefs = update(oldPrefs)
        _preferences.value = newPrefs
        persistPreferences(newPrefs)
        
        // Apply global settings to all tabs
        _tabs.value.forEach { tab ->
            tab.webView?.let { webView ->
                applySettingsToWebView(webView, newPrefs)
                // Reload tab instantly if critical rendering settings changed
                if (oldPrefs.desktopMode != newPrefs.desktopMode ||
                    oldPrefs.enableJavaScript != newPrefs.enableJavaScript ||
                    oldPrefs.blockPopups != newPrefs.blockPopups) {
                    webView.reload()
                }
            }
        }
        _splitTab.value?.webView?.let { webView ->
            applySettingsToWebView(webView, newPrefs)
            if (oldPrefs.desktopMode != newPrefs.desktopMode ||
                oldPrefs.enableJavaScript != newPrefs.enableJavaScript ||
                oldPrefs.blockPopups != newPrefs.blockPopups) {
                webView.reload()
            }
        }
    }

    fun setDownloadFolder(uriString: String?, displayName: String) {
        updatePreferences {
            it.copy(
                downloadDirectoryUri = uriString,
                downloadDirectoryName = displayName
            )
        }
    }

    fun resetDownloadFolderToDefault() {
        updatePreferences {
            it.copy(
                downloadDirectoryUri = null,
                downloadDirectoryName = "Downloads (Padrão)"
            )
        }
    }

    fun setAskDownloadLocation(ask: Boolean) {
        updatePreferences {
            it.copy(askDownloadLocation = ask)
        }
    }

    fun clearPendingWebPermissionRequest(rememberBlock: Boolean = false) {
        val req = _pendingWebPermissionRequest.value
        if (req != null) {
            if (rememberBlock) {
                val normOrigin = SitePermissionsManager.extractOrigin(req.origin) ?: req.origin
                if (req.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    sitePermissionsManager.setPolicy(normOrigin, WebPermissionType.CAMERA, PermissionPolicy.BLOCK)
                }
                if (req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    sitePermissionsManager.setPolicy(normOrigin, WebPermissionType.MICROPHONE, PermissionPolicy.BLOCK)
                }
            }
            req.deny()
        }
        _pendingWebPermissionRequest.value = null
    }

    fun grantPendingWebPermissionRequest(remember: Boolean = true) {
        val req = _pendingWebPermissionRequest.value
        if (req != null) {
            if (remember) {
                val normOrigin = SitePermissionsManager.extractOrigin(req.origin) ?: req.origin
                if (req.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    sitePermissionsManager.setPolicy(normOrigin, WebPermissionType.CAMERA, PermissionPolicy.ALLOW)
                }
                if (req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    sitePermissionsManager.setPolicy(normOrigin, WebPermissionType.MICROPHONE, PermissionPolicy.ALLOW)
                }
            }
            req.grant()
        }
        _pendingWebPermissionRequest.value = null
    }

    fun clearPendingGeolocationRequest(rememberBlock: Boolean = false) {
        val req = _pendingGeolocationRequest.value
        if (req != null) {
            if (rememberBlock) {
                val normOrigin = SitePermissionsManager.extractOrigin(req.origin) ?: req.origin
                sitePermissionsManager.setPolicy(normOrigin, WebPermissionType.LOCATION, PermissionPolicy.BLOCK)
            }
            req.deny()
        }
        _pendingGeolocationRequest.value = null
    }

    fun grantPendingGeolocationRequest(remember: Boolean = true) {
        val req = _pendingGeolocationRequest.value
        if (req != null) {
            if (remember) {
                val normOrigin = SitePermissionsManager.extractOrigin(req.origin) ?: req.origin
                sitePermissionsManager.setPolicy(normOrigin, WebPermissionType.LOCATION, PermissionPolicy.ALLOW)
            }
            req.grant(remember)
        }
        _pendingGeolocationRequest.value = null
    }

    fun setSitePermission(origin: String, type: WebPermissionType, policy: PermissionPolicy) {
        sitePermissionsManager.setPolicy(origin, type, policy)
    }

    fun removeSitePermissions(origin: String) {
        sitePermissionsManager.removeSite(origin)
    }

    fun clearAllSitePermissions() {
        sitePermissionsManager.clearAllSites()
    }

    fun getSiteRecord(origin: String): SitePermissionRecord {
        return sitePermissionsManager.getSiteRecord(origin)
    }

    fun handleFileChooserResult(uris: Array<Uri>?) {
        _pendingFileChooser.value?.onResult(uris)
        _pendingFileChooser.value = null
    }

    fun showRevokedPermissionAlert(alert: RevokedPermissionAlert) {
        _revokedPermissionAlert.value = alert
    }

    fun dismissRevokedPermissionAlert() {
        _revokedPermissionAlert.value = null
    }

    fun persistTabToDatabase(tab: BrowserTab) {
        if (!tab.isPrivate) {
            tabLifecycleManager.persistTab(tab)
        }
    }

    fun createNewTab(
        context: Context,
        url: String = "",
        selectNow: Boolean = true,
        openedInBackground: Boolean = false,
        isPrivate: Boolean = false
    ): BrowserTab {
        val tabId = UUID.randomUUID().toString()
        val webView = createConfiguredWebView(context, tabId, isSplitScreen = false)
        com.example.util.WebViewRegistry.put(tabId, webView)
        
        val newTab = BrowserTab(
            id = tabId,
            title = if (isPrivate) "Guia Anônima" else "Nova guia",
            url = url,
            openedInBackground = openedInBackground,
            isPrivate = isPrivate
        )
        
        _tabs.value = _tabs.value + newTab
        
        if (selectNow || _tabs.value.size == 1) {
            selectTab(tabId, context)
        } else {
            saveSessionPreferences()
        }

        if (url.isNotEmpty()) {
            loadUrl(newTab, url)
        }
        
        if (!isPrivate) {
            persistTabToDatabase(newTab)
        }
        freezeTabsIfNeeded()
        
        return newTab
    }

    fun createNewPrivateTab(context: Context, url: String = ""): BrowserTab {
        return createNewTab(context, url, selectNow = true, openedInBackground = false, isPrivate = true)
    }

    fun closeTab(tabId: String, context: Context) {
        val tabToClose = _tabs.value.find { it.id == tabId }
        val remaining = _tabs.value.filter { it.id != tabId }
        
        // Clean up memory and cookies for private tabs
        com.example.util.WebViewRegistry.remove(tabId)?.let { webView ->
            webView.stopLoading()
            webView.clearHistory()
            if (tabToClose?.isPrivate == true) {
                webView.clearFormData()
                webView.clearCache(true)
            }
            webView.destroy()
        }
        
        if (tabToClose?.isPrivate != true) {
            tabLifecycleManager.deleteTab(tabId)
        }

        _tabs.value = remaining

        if (_activeTabId.value == tabId) {
            if (remaining.isNotEmpty()) {
                selectTab(remaining.last().id, context)
            } else {
                createNewTab(context, "")
            }
        }
        saveSessionPreferences()
    }

    fun closeAllPrivateTabs(context: Context) {
        val privateTabs = _tabs.value.filter { it.isPrivate }
        privateTabs.forEach { tab ->
            com.example.util.WebViewRegistry.remove(tab.id)?.let { webView ->
                webView.stopLoading()
                webView.clearHistory()
                webView.clearFormData()
                webView.clearCache(true)
                webView.destroy()
            }
        }
        val remaining = _tabs.value.filter { !it.isPrivate }
        _tabs.value = remaining
        if (remaining.isNotEmpty()) {
            if (_tabs.value.none { it.id == _activeTabId.value }) {
                selectTab(remaining.last().id, context)
            }
        } else {
            createNewTab(context, "")
        }
        saveSessionPreferences()
    }

    fun closeAllTabs(context: Context) {
        val currentTabs = _tabs.value
        currentTabs.forEach { tab ->
            com.example.util.WebViewRegistry.remove(tab.id)?.let { webView ->
                webView.stopLoading()
                webView.clearHistory()
                if (tab.isPrivate) {
                    webView.clearFormData()
                    webView.clearCache(true)
                }
                webView.destroy()
            }
            if (!tab.isPrivate) {
                tabLifecycleManager.deleteTab(tab.id)
            }
        }
        _tabs.value = emptyList()
        createNewTab(context, "")
        saveSessionPreferences()
    }

    fun selectTab(tabId: String) {
        selectTab(tabId, getApplication<Application>().applicationContext)
    }

    fun selectTab(tabId: String, context: Context) {
        val previousActiveId = _activeTabId.value
        if (previousActiveId.isNotEmpty() && previousActiveId != tabId) {
            captureTabThumbnail(previousActiveId)
            _tabs.value = _tabs.value.map {
                if (it.id == previousActiveId) {
                    it.copy(lastInteractionTime = System.currentTimeMillis())
                } else {
                    it
                }
            }
        }
        _activeTabId.value = tabId
        saveSessionPreferences()
        
        val targetTab = _tabs.value.find { it.id == tabId }
        
        if (targetTab?.isDiscarded == true) {
            restoreDiscardedTab(context, tabId)
            return
        }
        
        if (targetTab?.isFrozen == true) {
            unfreezeTab(context, tabId) {
                _tabs.value = _tabs.value.map {
                    if (it.id == tabId) {
                        val tab = it.copy(
                            openedInBackground = false,
                            isThrottled = false,
                            lastInteractionTime = System.currentTimeMillis()
                        )
                        tab.webView?.let { webView ->
                            removeThrottling(tab)
                            webView.evaluateJavascript(
                                "(function() { " +
                                "  var elements = document.querySelectorAll('video, audio'); " +
                                "  for (var i = 0; i < elements.length; i++) { elements[i].muted = false; } " +
                                "  if (window.origPlay) { HTMLMediaElement.prototype.play = window.origPlay; } " +
                                "})()", null
                            )
                        }
                        tab
                    } else {
                        it
                    }
                }
                viewModelScope.launch {
                    delay(500)
                    captureTabThumbnail(tabId)
                }
                freezeTabsIfNeeded()
            }
        } else {
            // Se já não estiver congelada mas possuir URL válida sem WebView instanciada, restaura-a
            if (targetTab != null && targetTab.url.isNotEmpty() && targetTab.url != "about:blank" && targetTab.webView == null) {
                unfreezeTab(context, tabId)
            }
            _tabs.value = _tabs.value.map {
                if (it.id == tabId) {
                    val tab = it.copy(
                        openedInBackground = false,
                        isThrottled = false,
                        lastInteractionTime = System.currentTimeMillis()
                    )
                    tab.webView?.let { webView ->
                        removeThrottling(tab)
                        webView.evaluateJavascript(
                            "(function() { " +
                            "  var elements = document.querySelectorAll('video, audio'); " +
                            "  for (var i = 0; i < elements.length; i++) { elements[i].muted = false; } " +
                            "  if (window.origPlay) { HTMLMediaElement.prototype.play = window.origPlay; } " +
                            "})()", null
                        )
                    }
                    tab
                } else {
                    it
                }
            }
            viewModelScope.launch {
                delay(500)
                captureTabThumbnail(tabId)
            }
            freezeTabsIfNeeded()
        }
    }

    fun closeSplitScreen() {
        _splitTab.value?.let { tab ->
            com.example.util.WebViewRegistry.remove(tab.id)?.let { webView ->
                webView.stopLoading()
                webView.clearHistory()
                webView.destroy()
            }
        }
        _splitTab.value = null
    }

    fun createSplitScreenTab(context: Context, url: String) {
        // Clean previous split webview
        closeSplitScreen()

        val tabId = UUID.randomUUID().toString()
        val webView = createConfiguredWebView(context, tabId, isSplitScreen = true)
        com.example.util.WebViewRegistry.put(tabId, webView)
        
        val newTab = BrowserTab(
            id = tabId,
            title = "Painel de Foco",
            url = url
        )

        _splitTab.value = newTab
        loadUrl(newTab, url)
    }

    fun loadUrl(tab: BrowserTab, urlInput: String) {
        val trimmed = urlInput.trim()
        if (trimmed.isEmpty() || trimmed == "about:blank") {
            navigateToHome(tab)
            return
        }
        // Detecção automática de stream direto (HLS, DASH, MP4, WebM, etc.)
        if (com.example.util.StreamUrlHelper.isDirectStreamUrl(trimmed)) {
            val detected = com.example.util.StreamUrlHelper.detectStreamType(trimmed)
            val title = detected?.label ?: "Stream Direto"
            playDirectStream(trimmed, customTitle = title)
            showNotification("Reproduzindo $title no Reprodutor Nativo")
            return
        }

        val tabId = tab.id
        var webView = com.example.util.WebViewRegistry.get(tabId)
        if (webView == null) {
            val app = getApplication<Application>()
            webView = createConfiguredWebView(app, tabId, isSplitScreen = false)
            com.example.util.WebViewRegistry.put(tabId, webView)
        }
        val formattedUrl = navigationDelegate.normalizeAndFormatUrl(trimmed)
        webView.loadUrl(formattedUrl)
    }

    fun navigateToHome(tab: BrowserTab) {
        com.example.util.WebViewRegistry.get(tab.id)?.let { wv ->
            wv.stopLoading()
            wv.loadUrl("about:blank")
        }
        _tabs.value = _tabs.value.map {
            if (it.id == tab.id) {
                it.copy(
                    url = "",
                    title = if (it.isPrivate) "Guia Anônima" else "Nova guia",
                    progress = 100,
                    isLoading = false,
                    canGoBack = false,
                    canGoForward = false,
                    thumbnail = null,
                    isFrozen = false,
                    isDiscarded = false,
                    extractedVideos = emptyList(),
                    extractedVideo = null,
                    mediaGroups = emptyList()
                )
            } else it
        }
        val updatedTab = _tabs.value.find { it.id == tab.id }
        if (updatedTab != null && !updatedTab.isPrivate) {
            persistTabToDatabase(updatedTab)
        }
        saveSessionPreferences()
    }

    fun goBack(tab: BrowserTab) {
        tab.webView?.let {
            if (it.canGoBack()) {
                it.goBack()
            }
        }
    }

    fun goForward(tab: BrowserTab) {
        tab.webView?.let {
            if (it.canGoForward()) {
                it.goForward()
            }
        }
    }

    fun refresh(tab: BrowserTab) {
        tab.webView?.reload()
    }

    fun addToHistory(title: String, url: String) {
        historyManager.addToHistory(title, url)
    }

    fun deleteHistoryEntry(id: Long) {
        historyManager.deleteHistoryEntry(id)
    }

// Funções de senhas foram migradas para CredentialViewModel

    fun clearHistory() {
        historyManager.clearHistory()
    }

    fun clearHistoryAndCache() {
        historyManager.clearHistoryAndCache(_tabs.value, _splitTab.value)
    }

    // Helper to configure WebViews beautifully
    private fun createConfiguredWebView(context: Context, tabId: String, isSplitScreen: Boolean): WebView {
        val contextWrapper = android.content.MutableContextWrapper(context)
        val webView = WebView(contextWrapper).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ ->
                androidx.core.view.WindowInsetsCompat.CONSUMED
            }
        }
        
        applySettingsToWebView(webView, _preferences.value)
        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        } catch (_: Exception) {}
        webView.addJavascriptInterface(webMediaController.createJavascriptInterface(tabId), "VideoExtractorBridge")
        
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            handleDownloadRequest(context, url, userAgent, contentDisposition, mimetype, contentLength, tabId)
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                updateTabLoadingState(tabId, isSplitScreen, isLoading = true, progress = 10, url = url ?: "")
                updateTabExtractedVideo(tabId, null)
                webMediaController.clearTabMedia(tabId)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (!isSplitScreen) {
                    _tabs.value = _tabs.value.map {
                        if (it.id == tabId && it.isRestoringSnapshot) {
                            it.copy(isRestoringSnapshot = false)
                        } else it
                    }
                }
                // Early injection so player hooks are active immediately before user plays video
                val mediaScript = getMediaExtractionScript()
                view?.evaluateJavascript(mediaScript, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateTabLoadingState(tabId, isSplitScreen, isLoading = false, progress = 100, url = url ?: "", title = view?.title)
                if (!isSplitScreen) {
                    _tabs.value = _tabs.value.map {
                        if (it.id == tabId && it.isRestoringSnapshot) {
                            it.copy(isRestoringSnapshot = false)
                        } else it
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        captureTabThumbnail(tabId)
                    }, 1000)
                }
                
                if (_preferences.value.enableAdBlock) {
                    val cssHideRules = "img[src*='ads'], div[class*='banner'], [id*='ad-'], [class*='ad-'], .ad-slot, .ad-banner { display: none !important; }"
                    val jsInjection = "(function() { var style = document.createElement('style'); style.innerHTML = '$cssHideRules'; document.head.appendChild(style); })()"
                    view?.evaluateJavascript(jsInjection, null)
                }

                if (_preferences.value.desktopMode) {
                    val viewportJs = """
                        (function() {
                            var viewports = document.querySelectorAll('meta[name="viewport"]');
                            if (viewports && viewports.length > 0) {
                                for (var i = 0; i < viewports.length; i++) {
                                    viewports[i].setAttribute('content', 'width=1280, initial-scale=0.35, user-scalable=yes');
                                }
                            } else {
                                var meta = document.createElement('meta');
                                meta.name = 'viewport';
                                meta.content = 'width=1280, initial-scale=0.35, user-scalable=yes';
                                document.head.appendChild(meta);
                            }
                        })()
                    """.trimIndent()
                    view?.evaluateJavascript(viewportJs, null)
                }
                
                // Inject Video Extract (DOM Crawler, Play Event Hooks, and MutationObserver)
                val mediaScript = getMediaExtractionScript()
                view?.evaluateJavascript(mediaScript, null)
                
                // Inject WebSockets, WebRTC, Bluetooth/USB, and Print hooks!
                view?.evaluateJavascript(
                    "(function() {\n" +
                    "  if (!window._origWebSocket) {\n" +
                    "    window._origWebSocket = window.WebSocket;\n" +
                    "    window._activeWebSockets = 0;\n" +
                    "    window.WebSocket = function(url, protocols) {\n" +
                    "      var ws = new window._origWebSocket(url, protocols);\n" +
                    "      window._activeWebSockets++;\n" +
                    "      ws.addEventListener('close', function() {\n" +
                    "        if (window._activeWebSockets > 0) window._activeWebSockets--;\n" +
                    "      });\n" +
                    "      return ws;\n" +
                    "    };\n" +
                    "  }\n" +
                    "  var OrigPeerConnection = window.RTCPeerConnection || window.webkitRTCPeerConnection || window.mozRTCPeerConnection;\n" +
                    "  if (OrigPeerConnection && !window._origRTCPeerConnection) {\n" +
                    "    window._origRTCPeerConnection = OrigPeerConnection;\n" +
                    "    window._activePeerConnections = 0;\n" +
                    "    var NewPeerConnection = function(config, constraints) {\n" +
                    "      var pc = new OrigPeerConnection(config, constraints);\n" +
                    "      window._activePeerConnections++;\n" +
                    "      var origClose = pc.close;\n" +
                    "      pc.close = function() {\n" +
                    "        if (window._activePeerConnections > 0) window._activePeerConnections--;\n" +
                    "        if (origClose) origClose.apply(this, arguments);\n" +
                    "      };\n" +
                    "      return pc;\n" +
                    "    };\n" +
                    "    window.RTCPeerConnection = NewPeerConnection;\n" +
                    "  }\n" +
                    "  if (navigator.bluetooth && !navigator.bluetooth._origRequestDevice) {\n" +
                    "    navigator.bluetooth._origRequestDevice = navigator.bluetooth.requestDevice;\n" +
                    "    window._bluetoothActive = false;\n" +
                    "    navigator.bluetooth.requestDevice = function() {\n" +
                    "      window._bluetoothActive = true;\n" +
                    "      return navigator.bluetooth._origRequestDevice.apply(this, arguments);\n" +
                    "    };\n" +
                    "  }\n" +
                    "  if (navigator.usb && !navigator.usb._origRequestDevice) {\n" +
                    "    navigator.usb._origRequestDevice = navigator.usb.requestDevice;\n" +
                    "    window._usbActive = false;\n" +
                    "    navigator.usb.requestDevice = function() {\n" +
                    "      window._usbActive = true;\n" +
                    "      return navigator.usb._origRequestDevice.apply(this, arguments);\n" +
                    "    };\n" +
                    "  }\n" +
                    "  if (!window._origPrint) {\n" +
                    "    window._origPrint = window.print;\n" +
                    "    window._printUsed = false;\n" +
                    "    window.print = function() {\n" +
                    "      window._printUsed = true;\n" +
                    "      window._origPrint.apply(this, arguments);\n" +
                    "    };\n" +
                    "  }\n" +
                    "})()", null
                )
                
                // Silent Background Tabs (Abas Silenciosas)
                val isBackground = _tabs.value.find { it.id == tabId }?.openedInBackground == true
                if (isBackground) {
                    view?.evaluateJavascript(
                        "(function() { " +
                        "  if (!window.origPlay) { window.origPlay = HTMLMediaElement.prototype.play; } " +
                        "  var elements = document.querySelectorAll('video, audio'); " +
                        "  for (var i = 0; i < elements.length; i++) { elements[i].muted = true; } " +
                        "  HTMLMediaElement.prototype.play = function() { " +
                        "    this.muted = true; " +
                        "    return window.origPlay.apply(this, arguments); " +
                        "  }; " +
                        "})()", null
                    )
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // Transparently synchronize the address bar and state with server redirects (e.g. 301/302 between apex domain and www)
                if (url != null && url.isNotEmpty() && url != "about:blank") {
                    updateTabLoadingState(tabId, isSplitScreen, isLoading = view?.progress ?: 100 < 100, progress = view?.progress ?: 100, url = url, title = view?.title)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                val currentTabUrl = view?.url ?: getTabCurrentUrl(tabId, isSplitScreen)
                return handleLinkNavigation(context, url, currentTabUrl = currentTabUrl, hasGesture = true, isRedirect = false, targetTabId = tabId)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val isRedirect = request.isRedirect
                
                // Intercept ad/tracker server redirects before following
                if (isRedirect) {
                    if (_preferences.value.enableAdBlock && com.example.util.AdBlockEngine.getInstance().isAdOrTracker(url)) {
                        recordNetworkEvent(tabId, url, emptyMap(), method = "GET", isBlocked = true)
                        return true
                    }
                    return false
                }
                
                // On modern devices we check if navigation was a direct user tap gesture
                val hasGesture = request.hasGesture()
                val currentTabUrl = view?.url ?: getTabCurrentUrl(tabId, isSplitScreen)
                return handleLinkNavigation(context, url, currentTabUrl = currentTabUrl, hasGesture = hasGesture, isRedirect = false, targetTabId = tabId)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldInterceptRequest(view: WebView?, url: String?): android.webkit.WebResourceResponse? {
                if (url != null) {
                    if (_preferences.value.enableAdBlock && com.example.util.AdBlockEngine.getInstance().isAdOrTracker(url)) {
                        recordNetworkEvent(tabId, url, emptyMap(), method = "GET", isBlocked = true)
                        return android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    // shouldInterceptRequest runs on a Chromium background IO thread (e.g. ThreadPoolForeg).
                    // Do NOT call WebView methods (view?.title or view?.url) directly here to avoid WebView.checkThread crash.
                    handleNetworkIntercept(tabId, url, emptyMap(), null, null)
                }
                return super.shouldInterceptRequest(view, url)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString()
                if (url != null) {
                    val method = request.method ?: "GET"
                    val headers = request.requestHeaders ?: emptyMap()
                    if (_preferences.value.enableAdBlock && com.example.util.AdBlockEngine.getInstance().isAdOrTracker(url)) {
                        recordNetworkEvent(tabId, url, headers, method = method, isBlocked = true)
                        return android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    // shouldInterceptRequest runs on a Chromium background IO thread (e.g. ThreadPoolForeg).
                    // Do NOT call WebView methods (view?.title or view?.url) directly here to avoid WebView.checkThread crash.
                    handleNetworkIntercept(tabId, url, headers, null, null, method = method)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                if (handler != null && error != null) {
                    _sslErrorState.value = com.example.model.SslErrorState(handler, error, view?.url ?: error.url)
                    updateTabSecureState(tabId, isSplitScreen, false)
                } else {
                    super.onReceivedSslError(view, handler, error)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                // If main frame failed due to DNS host lookup failure (ERR_NAME_NOT_RESOLVED / ERROR_HOST_LOOKUP)
                if (request != null && request.isForMainFrame && error != null) {
                    val errorCode = error.errorCode
                    if (errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT) {
                        val failingUrl = request.url.toString()
                        val uri = request.url
                        val host = uri.host
                        if (host != null && !fallbackAttemptedUrls.contains(failingUrl)) {
                            val fallbackHost = if (host.startsWith("www.")) {
                                host.removePrefix("www.")
                            } else {
                                "www.$host"
                            }
                            
                            val fallbackUrl = uri.buildUpon().authority(fallbackHost).build().toString()
                            fallbackAttemptedUrls.add(failingUrl)
                            fallbackAttemptedUrls.add(fallbackUrl)

                            // Check DNS in background and silently redirect if the fallback host exists
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    val addresses = java.net.InetAddress.getAllByName(fallbackHost)
                                    if (addresses.isNotEmpty()) {
                                        withContext(Dispatchers.Main) {
                                            view?.loadUrl(fallbackUrl)
                                        }
                                    }
                                } catch (_: Exception) {
                                    // Fallback host does not resolve either; let normal error page stay
                                }
                            }
                        }
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                updateProgress(tabId, isSplitScreen, newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                updateTitle(tabId, isSplitScreen, title ?: "")
            }

            // Video Stream Fullscreen support
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                webMediaController.onShowCustomView(view, callback)
            }

            override fun onHideCustomView() {
                webMediaController.onHideCustomView()
            }

            // HTML5 Geolocation prompt (Origin-isolated + Global policy)
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (origin != null && callback != null) {
                    val normOrigin = SitePermissionsManager.extractOrigin(origin) ?: origin
                    val globalPolicy = _preferences.value.defaultLocationPolicy
                    val effectivePolicy = sitePermissionsManager.getEffectivePolicy(
                        normOrigin,
                        WebPermissionType.LOCATION,
                        globalPolicy
                    )

                    when (effectivePolicy) {
                        PermissionPolicy.BLOCK -> {
                            callback.invoke(origin, false, false)
                        }
                        PermissionPolicy.ALLOW -> {
                            val app = getApplication<Application>()
                            val hasFine = AppPermissionsHelper.hasPermission(app, android.Manifest.permission.ACCESS_FINE_LOCATION)
                            val hasCoarse = AppPermissionsHelper.hasPermission(app, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (hasFine || hasCoarse) {
                                callback.invoke(origin, true, true)
                            } else {
                                _pendingGeolocationRequest.value = WebGeolocationRequestEvent(
                                    origin = normOrigin,
                                    callback = callback
                                )
                            }
                        }
                        PermissionPolicy.ASK -> {
                            _pendingGeolocationRequest.value = WebGeolocationRequestEvent(
                                origin = normOrigin,
                                callback = callback
                            )
                        }
                    }
                } else {
                    callback?.invoke(origin, false, false)
                }
            }

            override fun onGeolocationPermissionsHidePrompt() {
                _pendingGeolocationRequest.value = null
            }

            // WebRTC Camera & Microphone prompt (Origin-isolated + Global policy)
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request != null) {
                    val rawOrigin = request.origin?.toString() ?: "Website"
                    val normOrigin = SitePermissionsManager.extractOrigin(rawOrigin) ?: rawOrigin
                    val requestedResources = request.resources.toList()
                    val wantsCamera = requestedResources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    val wantsMic = requestedResources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

                    val camPolicy = if (wantsCamera) {
                        sitePermissionsManager.getEffectivePolicy(normOrigin, WebPermissionType.CAMERA, _preferences.value.defaultCameraPolicy)
                    } else PermissionPolicy.ALLOW

                    val micPolicy = if (wantsMic) {
                        sitePermissionsManager.getEffectivePolicy(normOrigin, WebPermissionType.MICROPHONE, _preferences.value.defaultMicrophonePolicy)
                    } else PermissionPolicy.ALLOW

                    // If either requested resource is blocked, deny immediately
                    if (camPolicy == PermissionPolicy.BLOCK || micPolicy == PermissionPolicy.BLOCK) {
                        request.deny()
                        return
                    }

                    // If both are ALLOW, check system permissions
                    if (camPolicy == PermissionPolicy.ALLOW && micPolicy == PermissionPolicy.ALLOW) {
                        val requiredAndroid = mutableListOf<String>()
                        if (wantsCamera) requiredAndroid.add(android.Manifest.permission.CAMERA)
                        if (wantsMic) requiredAndroid.add(android.Manifest.permission.RECORD_AUDIO)

                        val hasAll = requiredAndroid.all { AppPermissionsHelper.hasPermission(getApplication<Application>(), it) }
                        if (hasAll) {
                            try {
                                request.grant(requestedResources.toTypedArray())
                            } catch (_: Exception) {
                                request.deny()
                            }
                            return
                        }
                    }

                    // Requires prompt (either policy is ASK or Android system permission needed)
                    val requiredAndroidPermissions = mutableListOf<String>()
                    if (wantsCamera) {
                        requiredAndroidPermissions.add(android.Manifest.permission.CAMERA)
                    }
                    if (wantsMic) {
                        requiredAndroidPermissions.add(android.Manifest.permission.RECORD_AUDIO)
                    }

                    _pendingWebPermissionRequest.value = WebPermissionRequestEvent(
                        request = request,
                        origin = normOrigin,
                        resources = requestedResources,
                        requiredAndroidPermissions = requiredAndroidPermissions
                    )
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (_pendingWebPermissionRequest.value?.request == request) {
                    _pendingWebPermissionRequest.value = null
                }
            }

            // HTML File Upload (<input type="file">)
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback != null && fileChooserParams != null) {
                    _pendingFileChooser.value = WebFileChooserEvent(
                        filePathCallback = filePathCallback,
                        fileChooserParams = fileChooserParams
                    )
                    return true
                }
                return false
            }
        }

        return webView
    }

    fun exitFullscreen() {
        webMediaController.exitFullscreen()
    }

    private fun applySettingsToWebView(webView: WebView, prefs: BrowserPreferences) {
        webView.apply {
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        
        webView.settings.apply {
            javaScriptEnabled = prefs.enableJavaScript
            setGeolocationEnabled(true)
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = !prefs.blockPopups
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            
            // Isolamento e Sandbox para segurança (impede acesso indevido a dados locais)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            
            // User Agent setup for Desktop/Mobile modes
            if (prefs.desktopMode) {
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            } else {
                val defaultUA = WebSettings.getDefaultUserAgent(webView.context)
                userAgentString = defaultUA.replace("; wv", "") // Spoof Chrome to permit Google Account Sign-In natively
            }
        }
    }

    private fun getTabCurrentUrl(tabId: String, isSplitScreen: Boolean): String {
        return if (isSplitScreen) {
            _splitTab.value?.url ?: ""
        } else {
            _tabs.value.find { it.id == tabId }?.url ?: activeTab?.url ?: ""
        }
    }

    private fun handleLinkNavigation(
        context: Context,
        url: String,
        currentTabUrl: String = "",
        hasGesture: Boolean,
        isRedirect: Boolean = false,
        targetTabId: String = ""
    ): Boolean {
        val currentUrl = if (currentTabUrl.isNotEmpty()) currentTabUrl else (activeTab?.url ?: "")
        return navigationDelegate.handleLinkNavigation(
            context = context,
            url = url,
            currentUrl = currentUrl,
            hasGesture = hasGesture,
            isRedirect = isRedirect,
            preferences = _preferences.value,
            onOpenInBackground = { targetUrl ->
                createNewTab(context, targetUrl, selectNow = false, openedInBackground = true)
            },
            onOpenInSplit = { targetUrl ->
                createSplitScreenTab(context, targetUrl)
            },
            onLoadInCurrentTab = { targetUrl ->
                val tab = _tabs.value.find { it.id == targetTabId } ?: activeTab
                tab?.let { loadUrl(it, targetUrl) }
            }
        )
    }

    private fun isWithinSameDomain(url1: String, url2: String): Boolean {
        return navigationDelegate.isWithinSameDomain(url1, url2)
    }

    private fun updateTabLoadingState(
        id: String,
        isSplitScreen: Boolean,
        isLoading: Boolean,
        progress: Int,
        url: String,
        title: String? = null
    ) {
        Handler(Looper.getMainLooper()).post {
            val isSecureUrl = url.startsWith("https://") || url.startsWith("file://") || url.startsWith("about:") || url.contains("localhost")

            if (isSplitScreen) {
                _splitTab.value?.let { tab ->
                    if (tab.id == id) {
                        val updatedTitle = title ?: tab.title
                        if (!isLoading && url.isNotEmpty() && url != "about:blank") {
                            addToHistory(updatedTitle, url)
                        }
                        _splitTab.value = tab.copy(
                            isLoading = isLoading,
                            progress = progress,
                            url = url,
                            title = updatedTitle,
                            canGoBack = tab.webView?.canGoBack() ?: false,
                            canGoForward = tab.webView?.canGoForward() ?: false,
                            lastUpdated = if (!isLoading) getCurrentTimeFormatted() else tab.lastUpdated,
                            isSecure = isSecureUrl
                        )
                    }
                }
            } else {
                _tabs.value = _tabs.value.map { tab ->
                    if (tab.id == id) {
                        val updatedTitle = if (url.isEmpty() || url == "about:blank") {
                            if (tab.isPrivate) "Guia Anônima" else "Nova guia"
                        } else {
                            title ?: tab.title
                        }
                        if (!tab.isPrivate && !isLoading && url.isNotEmpty() && url != "about:blank") {
                            addToHistory(updatedTitle, url)
                        }
                        val updatedTab = tab.copy(
                            isLoading = isLoading,
                            progress = progress,
                            url = url,
                            title = updatedTitle,
                            canGoBack = tab.webView?.canGoBack() ?: false,
                            canGoForward = tab.webView?.canGoForward() ?: false,
                            lastUpdated = if (!isLoading) getCurrentTimeFormatted() else tab.lastUpdated,
                            isSecure = isSecureUrl
                        )
                        if (!tab.isPrivate && !isLoading) {
                            persistTabToDatabase(updatedTab)
                        }
                        updatedTab
                    } else {
                        tab
                    }
                }
            }
        }
    }

    private fun updateProgress(id: String, isSplitScreen: Boolean, progress: Int) {
        Handler(Looper.getMainLooper()).post {
            if (isSplitScreen) {
                _splitTab.value?.let { tab ->
                    if (tab.id == id) {
                        _splitTab.value = tab.copy(
                            progress = progress,
                            isLoading = progress < 100,
                            canGoBack = tab.webView?.canGoBack() ?: false,
                            canGoForward = tab.webView?.canGoForward() ?: false
                        )
                    }
                }
            } else {
                _tabs.value = _tabs.value.map { tab ->
                    if (tab.id == id) {
                        tab.copy(
                            progress = progress,
                            isLoading = progress < 100,
                            canGoBack = tab.webView?.canGoBack() ?: false,
                            canGoForward = tab.webView?.canGoForward() ?: false
                        )
                    } else {
                        tab
                    }
                }
            }
        }
    }

    private fun updateTitle(id: String, isSplitScreen: Boolean, title: String) {
        Handler(Looper.getMainLooper()).post {
            if (isSplitScreen) {
                _splitTab.value?.let { tab ->
                    if (tab.id == id) {
                        _splitTab.value = tab.copy(title = title)
                    }
                }
            } else {
                _tabs.value = _tabs.value.map { tab ->
                    if (tab.id == id) {
                        tab.copy(title = title)
                    } else {
                        tab
                    }
                }
            }
        }
    }

    private fun startBackgroundSyncService() {
        tabLifecycleManager.startBackgroundSyncService(
            isEnabled = { _preferences.value.enableBackgroundSync },
            onSyncTriggered = { syncInactiveTabs(isManual = false) }
        )
    }

    fun syncInactiveTabs(isManual: Boolean = false) {
        _tabs.value = tabLifecycleManager.syncInactiveTabs(
            tabs = _tabs.value,
            activeTabId = _activeTabId.value,
            isManual = isManual,
            isEnabled = { _preferences.value.enableBackgroundSync },
            onNotify = { /* Silent background sync */ }
        )
    }

    fun getCurrentTimeFormatted(): String = tabLifecycleManager.getCurrentTimeFormatted()

    fun captureTabThumbnail(tabId: String) {
        val tab = _tabs.value.find { it.id == tabId }
        tabLifecycleManager.captureTabThumbnail(tab) { id, bitmap ->
            _tabs.value = _tabs.value.map {
                if (it.id == id) {
                    val updated = it.copy(thumbnail = bitmap)
                    tabLifecycleManager.persistTab(updated)
                    updated
                } else it
            }
        }
    }

    fun saveSessionPreferences() {
        val nonPrivateIds = _tabs.value.filter { !it.isPrivate }.map { it.id }
        prefsStorage.edit()
            .putString("last_active_tab_id", _activeTabId.value)
            .putString("open_tab_ids", nonPrivateIds.joinToString(","))
            .apply()
    }

    fun saveCurrentSession() {
        saveSessionPreferences()
        val currentActive = activeTab
        if (currentActive != null && !currentActive.isPrivate) {
            val liveUrl = currentActive.webView?.url ?: currentActive.url
            val liveTitle = currentActive.webView?.title ?: currentActive.title
            val updated = currentActive.copy(
                url = if (liveUrl.isNotEmpty() && liveUrl != "about:blank") liveUrl else currentActive.url,
                title = if (liveTitle.isNotEmpty()) liveTitle else currentActive.title
            )
            persistTabToDatabase(updated)
            captureTabThumbnail(currentActive.id)
        }
    }

    private fun loadInitialTabs() {
        val lastActiveTabId = prefsStorage.getString("last_active_tab_id", null)
        val savedTabOrder = prefsStorage.getString("open_tab_ids", null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() } ?: emptyList()

        tabLifecycleManager.loadInitialTabs(
            savedOrderIds = savedTabOrder,
            onLoaded = { restoredTabs ->
                _tabs.value = restoredTabs
                val targetTab = (if (!lastActiveTabId.isNullOrEmpty()) {
                    restoredTabs.find { it.id == lastActiveTabId }
                } else null) ?: restoredTabs.firstOrNull()

                if (targetTab != null) {
                    _activeTabId.value = targetTab.id
                    selectTab(targetTab.id, getApplication())
                } else {
                    val app = getApplication<Application>()
                    createNewTab(app, "")
                }
                tabLifecycleManager.cleanOrphanTabs(restoredTabs.map { it.id })
            },
            onEmpty = {
                val app = getApplication<Application>()
                createNewTab(app, "")
            }
        )
    }

    private fun freezeTab(tab: BrowserTab) {
        tabLifecycleManager.freezeTab(tab) { updatedTab ->
            _tabs.value = _tabs.value.map {
                if (it.id == updatedTab.id) updatedTab else it
            }
        }
    }

    private fun freezeTabsIfNeeded() {
        // Disallowed to keep tab loading, switching and navigation completely instantaneous
    }

    private fun unfreezeTab(context: Context, tabId: String, onComplete: (BrowserTab) -> Unit = {}) {
        tabLifecycleManager.unfreezeTab(
            context = context,
            tabId = tabId,
            createConfiguredWebView = { ctx, id -> createConfiguredWebView(ctx, id, isSplitScreen = false) },
            onRestored = { restoredTab ->
                _tabs.value = _tabs.value.map {
                    if (it.id == tabId) restoredTab else it
                }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    _tabs.value = _tabs.value.map {
                        if (it.id == tabId && it.isRestoringSnapshot) {
                            it.copy(isRestoringSnapshot = false)
                        } else it
                    }
                }, 1200)
                onComplete(restoredTab)
            }
        )
    }

    private fun restoreDiscardedTab(context: Context, tabId: String) {
        val fallbackUrl = _tabs.value.find { it.id == tabId }?.url ?: "https://google.com"
        tabLifecycleManager.restoreDiscardedTab(
            context = context,
            tabId = tabId,
            fallbackUrl = fallbackUrl,
            createConfiguredWebView = { ctx, id -> createConfiguredWebView(ctx, id, isSplitScreen = false) },
            onRestored = { restoredTab ->
                _tabs.value = _tabs.value.map {
                    if (it.id == tabId) restoredTab else it
                }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    _tabs.value = _tabs.value.map {
                        if (it.id == tabId && it.isRestoringSnapshot) {
                            it.copy(isRestoringSnapshot = false)
                        } else it
                    }
                }, 1200)
            }
        )
    }

    fun discardTab(tab: BrowserTab) {
        tabLifecycleManager.discardTab(tab) { updatedTab ->
            _tabs.value = _tabs.value.map {
                if (it.id == updatedTab.id) updatedTab else it
            }
        }
    }

    fun discardTabsOnLowMemory() {
        // Disallowed to keep tab memory loaded and switching completely instantaneous
    }

    private fun startBackgroundMonitoringService() {
        tabLifecycleManager.startBackgroundMonitoring(
            tabsProvider = { _tabs.value },
            activeTabIdProvider = { _activeTabId.value },
            onThrottleTab = { tab ->
                applyThrottling(tab)
            },
            onUpdateTabExceptions = { tabId, exceptions ->
                _tabs.value = _tabs.value.map {
                    if (it.id == tabId) {
                        it.copy(
                            hasActiveMedia = exceptions.mediaPlaying,
                            hasActiveWebSocket = exceptions.hasActiveWebSockets,
                            hasActiveWebRTC = exceptions.hasActiveWebRTC,
                            hasActiveExternalDevice = exceptions.hasExternalDevices
                        )
                    } else {
                        it
                    }
                }
            }
        )
    }

    private fun applyThrottling(tab: BrowserTab) {
        tabLifecycleManager.applyThrottling(tab) { updatedTab ->
            _tabs.value = _tabs.value.map {
                if (it.id == tab.id) updatedTab else it
            }
        }
    }

    fun removeThrottling(tab: BrowserTab) {
        tabLifecycleManager.removeThrottling(tab) { updatedTab ->
            _tabs.value = _tabs.value.map {
                if (it.id == tab.id) updatedTab else it
            }
        }
    }

    /**
     * Reanalisa o DOM e re-avalia o script de extração multimídia na aba ativa.
     * Permite ao utilizador refrescar vídeos quando um player dinâmico iniciou ou mudou de estado.
     */
    fun rescanVideos(tab: BrowserTab?) {
        val targetTab = tab ?: activeTab ?: return
        val webView = targetTab.webView ?: return
        val jsScript = getMediaExtractionScript()
        webView.evaluateJavascript(jsScript, null)
        showNotification("A reanalisar mídias da página...")
    }

    private fun getMediaExtractionScript(): String {
        return """
            (function() {
              function getBestTitle() {
                var og = document.querySelector('meta[property="og:title"]');
                if (og && og.content) return og.content.trim();
                var tw = document.querySelector('meta[name="twitter:title"]');
                if (tw && tw.content) return tw.content.trim();
                var yt = document.querySelector('h1.ytd-watch-metadata, .slim-video-metadata-title, h1.title, yt-formatted-string.ytd-watch-metadata');
                if (yt && yt.textContent) return yt.textContent.trim();
                var h1 = document.querySelector('h1');
                if (h1 && h1.textContent) return h1.textContent.trim();
                return document.title || "";
              }

              function getBestPoster(v) {
                if (v && v.poster) return v.poster.trim();
                var og = document.querySelector('meta[property="og:image"]');
                if (og && og.content) return og.content.trim();
                var tw = document.querySelector('meta[name="twitter:image"]');
                if (tw && tw.content) return tw.content.trim();
                return "";
              }

              function notifyVideo(url, srcType, poster, duration, width, height, mimeType) {
                if (!url || !url.startsWith('http') || url.startsWith('blob:')) return;
                var title = getBestTitle();
                var p = poster || getBestPoster(null);
                var headers = {
                  "Referer": window.location.href,
                  "User-Agent": navigator.userAgent,
                  "Cookie": document.cookie || ""
                };
                if (typeof VideoExtractorBridge !== 'undefined') {
                  if (duration !== undefined && duration > 0) {
                    VideoExtractorBridge.onVideoFound(url, title, JSON.stringify(headers), p, duration, width || 0, height || 0, mimeType || "");
                  } else {
                    VideoExtractorBridge.onVideoFound(url, title, JSON.stringify(headers), p);
                  }
                }
              }

              function inspectVideoElement(v) {
                if (!v) return;
                var poster = v.poster || "";
                var dur = (v.duration && !isNaN(v.duration) && isFinite(v.duration)) ? v.duration : 0;
                var w = v.videoWidth || 0;
                var h = v.videoHeight || 0;

                var currentSrc = v.currentSrc || v.src;
                if (currentSrc && currentSrc.startsWith('http') && !currentSrc.startsWith('blob:')) {
                  notifyVideo(currentSrc, "Active Video", poster, dur, w, h, "");
                }

                var sources = v.querySelectorAll('source');
                for (var j = 0; j < sources.length; j++) {
                  var sSrc = sources[j].src;
                  var sType = sources[j].type || "";
                  if (sSrc && sSrc.startsWith('http') && !sSrc.startsWith('blob:')) {
                    notifyVideo(sSrc, "Video Source", poster, dur, w, h, sType);
                  }
                }
              }

              function scanAllMedia() {
                // 1. Direct <video> elements
                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                  inspectVideoElement(videos[i]);
                }

                // 2. Open Shadow DOM video components
                function scanShadow(root) {
                  if (!root) return;
                  try {
                    var all = root.querySelectorAll('*');
                    for (var s = 0; s < all.length; s++) {
                      if (all[s].shadowRoot) {
                        var sv = all[s].shadowRoot.querySelectorAll('video');
                        for (var svIdx = 0; svIdx < sv.length; svIdx++) {
                          inspectVideoElement(sv[svIdx]);
                        }
                        scanShadow(all[s].shadowRoot);
                      }
                    }
                  } catch (e) {}
                }
                if (document.body) {
                  scanShadow(document.body);
                }

                // 3. Meta OG Video tags (only direct video files, ignore embed/serie web pages)
                var ogVideo = document.querySelector('meta[property="og:video"], meta[property="og:video:secure_url"]');
                if (ogVideo && ogVideo.content && ogVideo.content.startsWith('http')) {
                  var c = ogVideo.content;
                  var cLower = c.toLowerCase();
                  if ((cLower.indexOf('.mp4') !== -1 || cLower.indexOf('.m3u8') !== -1 || cLower.indexOf('.mpd') !== -1 || cLower.indexOf('.webm') !== -1) &&
                      cLower.indexOf('/serie/') === -1 && cLower.indexOf('/filme/') === -1 && cLower.indexOf('/embed/') === -1) {
                    notifyVideo(c, "Meta OG Video", "", 0, 0, 0, "");
                  }
                }

                // 4. Schema.org VideoObject JSON-LD (only contentUrl, never embedUrl)
                var ldScripts = document.querySelectorAll('script[type="application/ld+json"]');
                for (var l = 0; l < ldScripts.length; l++) {
                  try {
                    var ld = JSON.parse(ldScripts[l].textContent);
                    var items = Array.isArray(ld) ? ld : (ld['@graph'] ? ld['@graph'] : [ld]);
                    for (var it = 0; it < items.length; it++) {
                      var obj = items[it];
                      if (obj && (obj['@type'] === 'VideoObject' || obj.type === 'VideoObject')) {
                        var vUrl = obj.contentUrl; // specifically contentUrl, never embedUrl which is a webpage
                        if (vUrl && typeof vUrl === 'string' && vUrl.startsWith('http')) {
                          var vLower = vUrl.toLowerCase();
                          if (vLower.indexOf('/serie/') === -1 && vLower.indexOf('/filme/') === -1 && vLower.indexOf('/embed/') === -1) {
                            notifyVideo(vUrl, "Schema VideoObject", obj.thumbnailUrl || "", 0, 0, 0, "");
                          }
                        }
                      }
                    }
                  } catch(e) {}
                }

                // 5. Anchors with direct media stream links
                var anchors = document.querySelectorAll('a[href*=".mp4"], a[href*=".m3u8"], a[href*=".mpd"]');
                for (var k = 0; k < anchors.length; k++) {
                  var aHref = anchors[k].href;
                  if (aHref && aHref.startsWith('http')) {
                    var aLower = aHref.toLowerCase();
                    if (aLower.indexOf('/serie/') === -1 && aLower.indexOf('/filme/') === -1 && aLower.indexOf('/embed/') === -1) {
                      notifyVideo(aHref, "Anchor Tag", "", 0, 0, 0, "");
                    }
                  }
                }

                // 6. YouTube Player Initial Data & Streaming Formats Extraction
                try {
                  var ytResp = window.ytInitialPlayerResponse || 
                               (window.ytplayer && window.ytplayer.config && window.ytplayer.config.args && (
                                  (typeof window.ytplayer.config.args.raw_player_response === 'object' ? window.ytplayer.config.args.raw_player_response : null) ||
                                  (typeof window.ytplayer.config.args.raw_player_response === 'string' ? JSON.parse(window.ytplayer.config.args.raw_player_response) : null) ||
                                  (typeof window.ytplayer.config.args.player_response === 'string' ? JSON.parse(window.ytplayer.config.args.player_response) : null)
                               ));
                  
                  // Also check ytcfg and page scripts if window.ytInitialPlayerResponse was not yet globally set
                  if (!ytResp && typeof ytcfg !== 'undefined' && ytcfg.get && ytcfg.get('PLAYER_VARS')) {
                    try {
                      var pVars = ytcfg.get('PLAYER_VARS');
                      if (pVars.embedded_player_response) {
                        ytResp = JSON.parse(pVars.embedded_player_response);
                      } else if (pVars.raw_player_response) {
                        ytResp = pVars.raw_player_response;
                      }
                    } catch(eCfg) {}
                  }

                  if (ytResp && ytResp.streamingData) {
                    var streamingData = ytResp.streamingData;
                    var videoDetails = ytResp.videoDetails || {};
                    var ytTitle = videoDetails.title || getBestTitle();
                    var ytThumbnail = (videoDetails.thumbnail && videoDetails.thumbnail.thumbnails && videoDetails.thumbnail.thumbnails.length > 0) ? 
                                       videoDetails.thumbnail.thumbnails[videoDetails.thumbnail.thumbnails.length - 1].url : "";
                    var ytDur = videoDetails.lengthSeconds ? parseInt(videoDetails.lengthSeconds, 10) : 0;

                    function parseFormatUrl(fmt) {
                      if (!fmt) return "";
                      if (fmt.url && fmt.url.startsWith('http')) return fmt.url;
                      if (fmt.signatureCipher) return fmt.signatureCipher;
                      if (fmt.cipher) return fmt.cipher;
                      return "";
                    }

                    // A. Direct Combined Formats (Audio + Video)
                    if (streamingData.formats && Array.isArray(streamingData.formats)) {
                      for (var yf = 0; yf < streamingData.formats.length; yf++) {
                        var fmt = streamingData.formats[yf];
                        var fUrl = parseFormatUrl(fmt);
                        if (fUrl && fUrl.length > 0) {
                          notifyVideo(fUrl, "YouTube Direct", ytThumbnail, ytDur, fmt.width || 0, fmt.height || 0, fmt.mimeType || "video/mp4");
                        }
                      }
                    }

                    // B. Adaptive Formats (Video and Audio separated)
                    if (streamingData.adaptiveFormats && Array.isArray(streamingData.adaptiveFormats)) {
                      for (var af = 0; af < streamingData.adaptiveFormats.length; af++) {
                        var aFmt = streamingData.adaptiveFormats[af];
                        var afUrl = parseFormatUrl(aFmt);
                        var mime = aFmt.mimeType || "";
                        if (afUrl && afUrl.length > 0) {
                          if (mime.indexOf('video/') !== -1) {
                            notifyVideo(afUrl, "YouTube Adaptive", ytThumbnail, ytDur, aFmt.width || 0, aFmt.height || 0, mime);
                          } else if (mime.indexOf('audio/') !== -1) {
                            notifyVideo(afUrl, "YouTube Audio", ytThumbnail, ytDur, 0, 0, mime);
                          }
                        }
                      }
                    }

                    // C. HLS Master Manifest (m3u8) if available
                    if (streamingData.hlsManifestUrl && streamingData.hlsManifestUrl.startsWith('http')) {
                      notifyVideo(streamingData.hlsManifestUrl, "YouTube HLS", ytThumbnail, ytDur, 0, 0, "application/x-mpegURL");
                    }
                  }
                } catch (eYt) {}

                // 7. ABYS Stream Network, SW-Oracle & Fetch/XHR Interception Hook
                if (!window._abysHooksInstalled) {
                  window._abysHooksInstalled = true;
                  window._abysObservedUrls = window._abysObservedUrls || [];
                  window._abysVirtualCandidates = window._abysVirtualCandidates || [];

                  var probeAbysVirtualUrl = function(candUrl) {
                    if (!candUrl || typeof candUrl !== 'string') return;
                    try {
                      fetch(candUrl, { headers: { Range: 'bytes=0-0' }, cache: 'no-store' })
                        .then(function(r) {
                          var cr = r.headers ? r.headers.get('content-range') : null;
                          var cl = r.headers ? r.headers.get('content-length') : null;
                          var total = null;
                          if (cr) {
                            var mm = cr.match(/\/(\d+)\s*$/);
                            if (mm && mm[1]) total = parseInt(mm[1], 10);
                          }
                          if (!total && cl) total = parseInt(cl, 10);

                          if (total && total > 0 && typeof VideoExtractorBridge !== 'undefined' && VideoExtractorBridge.onAbysVirtualUrlDetected) {
                            var slugMatch = candUrl.match(/[?&]v=([^&#]+)/) || (window.location.search && window.location.search.match(/[?&]v=([^&#]+)/));
                            var slug = slugMatch ? slugMatch[1] : null;
                            var resMatch = candUrl.match(/\/mp4\/(?:[a-fA-F0-9]+\/)?(\d+)/) || candUrl.match(/\/part\/(\d+)/);
                            var resId = resMatch ? parseInt(resMatch[1], 10) : 4;
                            var md5Match = candUrl.match(/\/mp4\/([a-fA-F0-9]{32})\//);
                            var md5Id = md5Match ? md5Match[1] : null;
                            var obsJson = JSON.stringify(window._abysObservedUrls || []);

                            VideoExtractorBridge.onAbysVirtualUrlDetected(
                              candUrl,
                              total,
                              resId,
                              slug,
                              md5Id,
                              document.title,
                              getBestPoster(null),
                              window.location.href,
                              obsJson
                            );
                          }
                        })
                        .catch(function() {});
                    } catch(e) {}
                  };

                  var noteAbysUrl = function(raw) {
                    if (!raw || typeof raw !== 'string') return;
                    var rLow = raw.toLowerCase();
                    if (rLow.indexOf('.fd') !== -1 || rLow.indexOf('/sora/') !== -1 || rLow.indexOf('/mp4/') !== -1 ||
                        rLow.indexOf('/part/') !== -1 || rLow.indexOf('/r2/') !== -1 ||
                        rLow.indexOf('abysscdn.com') !== -1 || rLow.indexOf('abyss.to') !== -1 || rLow.indexOf('embedplayabyss.top') !== -1 ||
                        rLow.indexOf('embedplay.click') !== -1 || rLow.indexOf('embedplay.one') !== -1 || rLow.indexOf('.sssrr.org') !== -1) {
                      if (rLow.indexOf('cdn-cgi') === -1 && rLow.indexOf('/rum') === -1 && rLow.indexOf('.js') === -1 && rLow.indexOf('.css') === -1) {
                        if (window._abysObservedUrls.indexOf(raw) === -1) {
                          window._abysObservedUrls.push(raw);
                        }
                        if (rLow.indexOf('/mp4/') !== -1 || rLow.indexOf('/part/') !== -1 || rLow.indexOf('/r2/') !== -1) {
                          if (window._abysVirtualCandidates.indexOf(raw) === -1) {
                            window._abysVirtualCandidates.push(raw);
                            probeAbysVirtualUrl(raw);
                          }
                        }
                        notifyVideo(raw, "ABYS Stream", "", 0, 0, 0, "video/mp4");
                      }
                    }
                  };

                  var checkMediaJson = function(txt) {
                    if (!txt) return;
                    var jsonStr = (typeof txt === 'object') ? JSON.stringify(txt) : String(txt);
                    if (jsonStr.indexOf('"sources"') !== -1 || jsonStr.indexOf('"fristData"') !== -1 || jsonStr.indexOf('"fristDatas"') !== -1 ||
                        jsonStr.indexOf('"res_id"') !== -1 || (jsonStr.indexOf('abysscdn.com') !== -1 && jsonStr.indexOf('.fd') !== -1)) {
                      if (typeof VideoExtractorBridge !== 'undefined' && VideoExtractorBridge.onAbysMediaBundleFound) {
                        var obsJson = JSON.stringify(window._abysObservedUrls || []);
                        VideoExtractorBridge.onAbysMediaBundleFound(jsonStr, document.title, getBestPoster(null), window.location.href, obsJson);
                      }
                    }
                  };

                  // Check if document contains const datas = "<base64>" (Server rendered payload)
                  try {
                    var htmlSrc = document.documentElement ? document.documentElement.innerHTML : '';
                    var dMatch = htmlSrc.match(/const\s+datas\s*=\s*"([^"]+)"/);
                    if (dMatch && dMatch[1]) {
                      var decodedStr = atob(dMatch[1]);
                      checkMediaJson(decodedStr);
                    }
                  } catch (eDatas) {}

                  // Check Cache Storage for cached parts (abys_capture.user.js v2)
                  try {
                    if (window.caches && window.caches.keys) {
                      window.caches.keys().then(function(keys) {
                        for (var ck = 0; ck < keys.length; ck++) {
                          window.caches.open(keys[ck]).then(function(c) {
                            c.keys().then(function(reqs) {
                              for (var rk = 0; rk < reqs.length; rk++) {
                                noteAbysUrl(reqs[rk].url);
                              }
                            }).catch(function(){});
                          }).catch(function(){});
                        }
                      }).catch(function(){});
                    }
                  } catch (eCaches) {}

                  var origFetch = window.fetch;
                  if (origFetch) {
                    window.fetch = function(input, init) {
                      try {
                        var u = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                        noteAbysUrl(u);
                      } catch (e) {}
                      return origFetch.apply(this, arguments).then(function(res) {
                        try {
                          if (res && res.clone) {
                            var resClone = res.clone();
                            var cType = (res.headers && res.headers.get) ? (res.headers.get('content-type') || '') : '';
                            if (cType.indexOf('json') !== -1 || cType.indexOf('javascript') !== -1 || cType === '') {
                              resClone.text().then(function(t) { checkMediaJson(t); }).catch(function(){});
                            }
                          }
                        } catch (e) {}
                        return res;
                      });
                    };
                  }

                  var origXhrOpen = XMLHttpRequest.prototype.open;
                  var origXhrSend = XMLHttpRequest.prototype.send;
                  if (origXhrOpen && origXhrSend) {
                    XMLHttpRequest.prototype.open = function(m, u) {
                      try {
                        this._interceptUrl = u;
                        noteAbysUrl(u);
                      } catch (e) {}
                      return origXhrOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                      try {
                        this.addEventListener('load', function() {
                          try {
                            var resp = '';
                            if (this.responseType === '' || this.responseType === 'text') {
                              resp = this.responseText;
                            } else if (this.responseType === 'json' && this.response) {
                              resp = JSON.stringify(this.response);
                            }
                            if (resp) checkMediaJson(resp);
                          } catch(e){}
                        });
                      } catch (e) {}
                      return origXhrSend.apply(this, arguments);
                    };
                  }

                  try {
                    window.addEventListener('message', function(e) {
                      try {
                        if (e.data) {
                          var d = e.data;
                          if (typeof d === 'string') {
                            noteAbysUrl(d);
                            checkMediaJson(d);
                          } else if (typeof d === 'object') {
                            var str = JSON.stringify(d);
                            noteAbysUrl(d.url || d.src || d.stream);
                            checkMediaJson(str);
                          }
                        }
                      } catch (eMsg) {}
                    }, true);
                  } catch (e) {}

                  try {
                    if (window.PerformanceObserver) {
                      new PerformanceObserver(function(list) {
                        var entries = list.getEntries();
                        for (var pe = 0; pe < entries.length; pe++) {
                          noteAbysUrl(entries[pe].name);
                        }
                      }).observe({ entryTypes: ['resource'] });
                    }
                  } catch (e) {}

                  // Check if player media data is already present in JS scope or script elements
                  try {
                    var pObj = window.player || window.__media || window.data || window.__abyss || window.datas;
                    if (pObj) {
                      checkMediaJson(pObj);
                    }
                  } catch(e) {}

                  // Expose SW-Oracle helper in WebView window
                  window.__abys_pull = {
                    candidatos: function() { return window._abysVirtualCandidates || []; },
                    vistas: function() { return window._abysObservedUrls || []; },
                    sonda: probeAbysVirtualUrl
                  };
                }

                var iframes = document.querySelectorAll('iframe');
                for (var f = 0; f < iframes.length; f++) {
                  try {
                    var fSrc = iframes[f].src;
                    if (fSrc && fSrc.startsWith('http')) {
                      if (fSrc.indexOf('.m3u8') !== -1 || fSrc.indexOf('.mpd') !== -1 || fSrc.indexOf('.mp4') !== -1) {
                        notifyVideo(fSrc, "Iframe Stream", "", 0, 0, 0, "");
                      }
                    }
                    if (iframes[f].contentDocument) {
                      var iVideos = iframes[f].contentDocument.querySelectorAll('video');
                      for (var iv = 0; iv < iVideos.length; iv++) {
                        inspectVideoElement(iVideos[iv]);
                      }
                    }
                  } catch (e) {}
                }
              }

              // 1. Initial Scan
              scanAllMedia();

              // 2. Play and Metadata Events Hook (solves lazy-loading, click-to-play, and autoplay videos)
              if (!window._mediaHooksInstalled) {
                window._mediaHooksInstalled = true;

                var handleMediaEvent = function(e) {
                  if (e.target && e.target.tagName === 'VIDEO') {
                    inspectVideoElement(e.target);
                    if (typeof VideoExtractorBridge !== 'undefined' && VideoExtractorBridge.onMediaEvent) {
                      var v = e.target;
                      var cur = v.currentSrc || v.src;
                      var dur = (v.duration && !isNaN(v.duration) && isFinite(v.duration)) ? v.duration : 0;
                      if (cur && cur.startsWith('http') && !cur.startsWith('blob:')) {
                        VideoExtractorBridge.onMediaEvent(e.type, cur, dur, v.videoWidth || 0, v.videoHeight || 0, v.poster || "");
                      }
                    }
                  }
                };

                document.addEventListener('play', handleMediaEvent, true);
                document.addEventListener('playing', handleMediaEvent, true);
                document.addEventListener('loadedmetadata', handleMediaEvent, true);
                document.addEventListener('canplay', handleMediaEvent, true);
                document.addEventListener('loadeddata', handleMediaEvent, true);

                // Intercept HTMLMediaElement.prototype.play and load to capture dynamically set src on click
                var origPlay = HTMLMediaElement.prototype.play;
                HTMLMediaElement.prototype.play = function() {
                  try {
                    if (this.tagName === 'VIDEO') {
                      var self = this;
                      inspectVideoElement(self);
                      setTimeout(function() { inspectVideoElement(self); }, 100);
                      setTimeout(function() { inspectVideoElement(self); }, 400);
                      setTimeout(function() { inspectVideoElement(self); }, 1000);
                    }
                  } catch (err) {}
                  return origPlay.apply(this, arguments);
                };

                var origLoad = HTMLMediaElement.prototype.load;
                HTMLMediaElement.prototype.load = function() {
                  try {
                    if (this.tagName === 'VIDEO') {
                      var self = this;
                      setTimeout(function() { inspectVideoElement(self); }, 200);
                    }
                  } catch (err) {}
                  return origLoad.apply(this, arguments);
                };
              }

              // 3. MutationObserver for SPAs and newly injected video players
              if (!window._videoExtObserver) {
                window._videoExtObserver = new MutationObserver(function(mutations) {
                  scanAllMedia();
                });
                var targetNode = document.body || document.documentElement;
                if (targetNode) {
                  window._videoExtObserver.observe(targetNode, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'poster'] });
                }
              }
            })();
        """.trimIndent()
    }

    fun updateTabExtractedVideo(tabId: String, video: com.example.extractor.ExtractedVideo?) {
        Handler(Looper.getMainLooper()).post {
            _tabs.value = _tabs.value.map { tab ->
                if (tab.id == tabId) {
                    if (video == null) {
                        tab.copy(extractedVideo = null, extractedVideos = emptyList(), mediaGroups = emptyList())
                    } else {
                        val canonicalKey = video.streamKey ?: com.example.extractor.MediaTitleHelper.getCanonicalKey(video.url)
                        val existingIndex = tab.extractedVideos.indexOfFirst {
                            it.url == video.url ||
                            (it.streamKey != null && it.streamKey == canonicalKey) ||
                            com.example.extractor.MediaTitleHelper.getCanonicalKey(it.url) == canonicalKey
                        }
                        val newList = if (existingIndex != -1) {
                            val existing = tab.extractedVideos[existingIndex]
                            val merged = com.example.extractor.MediaTitleHelper.mergeVideoData(existing, video)
                            tab.extractedVideos.toMutableList().apply { set(existingIndex, merged) }
                        } else {
                            tab.extractedVideos + video
                        }
                        val groups = com.example.extractor.MediaGroupHelper.buildMediaGroups(newList, tab.title, tab.url)
                        tab.copy(extractedVideo = video, extractedVideos = newList, mediaGroups = groups)
                    }
                } else {
                    tab
                }
            }
            _splitTab.value?.let { tab ->
                if (tab.id == tabId) {
                    if (video == null) {
                        _splitTab.value = tab.copy(extractedVideo = null, extractedVideos = emptyList(), mediaGroups = emptyList())
                    } else {
                        val canonicalKey = video.streamKey ?: com.example.extractor.MediaTitleHelper.getCanonicalKey(video.url)
                        val existingIndex = tab.extractedVideos.indexOfFirst {
                            it.url == video.url ||
                            (it.streamKey != null && it.streamKey == canonicalKey) ||
                            com.example.extractor.MediaTitleHelper.getCanonicalKey(it.url) == canonicalKey
                        }
                        val newList = if (existingIndex != -1) {
                            val existing = tab.extractedVideos[existingIndex]
                            val merged = com.example.extractor.MediaTitleHelper.mergeVideoData(existing, video)
                            tab.extractedVideos.toMutableList().apply { set(existingIndex, merged) }
                        } else {
                            tab.extractedVideos + video
                        }
                        val groups = com.example.extractor.MediaGroupHelper.buildMediaGroups(newList, tab.title, tab.url)
                        _splitTab.value = tab.copy(extractedVideo = video, extractedVideos = newList, mediaGroups = groups)
                    }
                }
            }
        }
    }

    private fun handleNetworkIntercept(tabId: String, url: String, headers: Map<String, String>, pageTitle: String?, pageUrl: String? = null, method: String = "GET") {
        recordNetworkEvent(tabId, url, headers, method = method, isBlocked = false)
        val tab = _tabs.value.find { it.id == tabId }
            ?: (_splitTab.value?.let { if (it.id == tabId) it else null })
        val tabTitle = tab?.title
        val tabCurrentUrl = pageUrl ?: tab?.url
        val resolvedTitle = com.example.extractor.MediaTitleHelper.cleanMediaTitle(pageTitle ?: tabTitle, url)
        webMediaController.handleNetworkIntercept(tabId, url, headers, resolvedTitle, tabCurrentUrl)
    }

    fun dismissSslError() {
        _sslErrorState.value?.handler?.cancel()
        _sslErrorState.value = null
    }

    fun proceedSslError() {
        _sslErrorState.value?.handler?.proceed()
        _sslErrorState.value = null
    }

    private fun updateTabSecureState(tabId: String, isSplitScreen: Boolean, isSecure: Boolean) {
        if (isSplitScreen) {
            _splitTab.value?.let { tab ->
                if (tab.id == tabId) _splitTab.value = tab.copy(isSecure = isSecure)
            }
        } else {
            _tabs.value = _tabs.value.map { tab ->
                if (tab.id == tabId) tab.copy(isSecure = isSecure) else tab
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        networkStateDelegate.stop()
        // Clean up all tab WebViews to prevent memory leaks
        com.example.util.WebViewRegistry.clear()
    }

    // DOWNLOADS MANAGEMENT DELEGATION

    fun handleDownloadRequest(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        initiatingTabId: String
    ) {
        val tab = _tabs.value.find { it.id == initiatingTabId } ?: activeTab
        val pageTitle = tab?.title?.takeIf { it.isNotBlank() && it != "Nova Aba" && it != "about:blank" }
        downloadDelegate.handleDownloadRequest(
            context = context,
            url = url,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            contentLength = contentLength,
            initiatingTabId = initiatingTabId,
            pageTitle = pageTitle,
            defaultTargetTreeUri = _preferences.value.downloadDirectoryUri,
            defaultTargetDirectoryName = _preferences.value.downloadDirectoryName
        )
    }

    fun cancelPendingDownload() {
        downloadDelegate.cancelPendingDownload()
    }

    fun executeDirectDownload(
        context: Context,
        payload: com.example.model.DownloadPayload
    ) {
        val effectivePayload = if (payload.targetTreeUri == null && payload.targetDirectoryPath == null) {
            payload.copy(
                targetTreeUri = _preferences.value.downloadDirectoryUri,
                targetDirectoryName = _preferences.value.downloadDirectoryName
            )
        } else {
            payload
        }
        val guessedName = effectivePayload.customFileName
            ?: android.webkit.URLUtil.guessFileName(effectivePayload.url, effectivePayload.contentDisposition, effectivePayload.mimeType)
            ?: "ficheiro"
        triggerInstantDownloadNotification(guessedName)
        downloadDelegate.executeDirectDownload(context, effectivePayload)
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
        val effectivePath = targetDirectoryPath ?: _preferences.value.downloadDirectoryUri
        val effectiveTree = targetTreeUri ?: _preferences.value.downloadDirectoryUri
        val safeName = com.example.util.VideoFileNameGenerator.sanitizePageTitle(title) + ".mp4"
        triggerInstantDownloadNotification(safeName)
        downloadDelegate.startYouTubeDualStreamDownload(
            context, title, videoUrl, audioUrl, ua, cookies, effectivePath, effectiveTree
        )
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
        val effectivePath = targetDirectoryPath ?: _preferences.value.downloadDirectoryUri
        val effectiveTree = targetTreeUri ?: _preferences.value.downloadDirectoryUri
        val ext = if (asMp3) ".mp3" else ".m4a"
        val safeName = com.example.util.VideoFileNameGenerator.sanitizePageTitle(title) + ext
        triggerInstantDownloadNotification(safeName)
        downloadDelegate.startYouTubeAudioDownload(
            context, title, audioUrl, ua, cookies, asMp3, effectivePath, effectiveTree
        )
    }

    fun extractYouTubeDetails(url: String, tabCookies: String = "", userAgent: String = "", force: Boolean = false) {
        val videoId = com.example.util.YouTubeDedicatedEngine.extractVideoId(url)
        if (videoId == null) {
            _youTubeDetails.value = null
            return
        }

        // Se já extraiu o mesmo videoId com sucesso recentemente e não for force, não recarrega
        if (!force && _youTubeDetails.value?.videoId == videoId && _youTubeDetails.value?.videoFormats?.isNotEmpty() == true) {
            return
        }

        viewModelScope.launch {
            _isYouTubeExtracting.value = true
            try {
                val details = com.example.util.YouTubeDedicatedEngine.fetchVideoDetails(
                    videoId = videoId,
                    webViewCookies = tabCookies,
                    pageUserAgent = userAgent
                )
                _youTubeDetails.value = details
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isYouTubeExtracting.value = false
            }
        }
    }

    fun clearYouTubeDetails() {
        _youTubeDetails.value = null
        _isYouTubeExtracting.value = false
    }

    fun confirmDownload(context: Context, payload: com.example.model.DownloadPayload) {
        executeDirectDownload(context, payload)
    }

    fun cancelDownload(downloadId: Long) {
        downloadDelegate.cancelDownload(downloadId)
    }

    fun pauseDownload(downloadId: Long) {
        downloadDelegate.pauseDownload(downloadId)
    }

    fun resumeDownload(downloadId: Long) {
        downloadDelegate.resumeDownload(downloadId)
    }

    fun deleteDownload(downloadId: Long, deleteFileFromDisk: Boolean = true) {
        downloadDelegate.deleteDownload(downloadId, deleteFileFromDisk)
    }

    fun renameDownload(downloadId: Long, newName: String): Boolean {
        return downloadDelegate.renameDownload(downloadId, newName)
    }

    fun clearCompletedDownloads(deleteFilesFromDisk: Boolean = false) {
        downloadDelegate.clearCompletedDownloads(deleteFilesFromDisk)
    }

    fun openDownloadedFile(context: Context, item: com.example.util.AdvancedDownloadItem) {
        downloadDelegate.openDownloadedFile(context, item)
    }

    private val _activeMediaPlayback = kotlinx.coroutines.flow.MutableStateFlow<com.example.media.MediaPlayRequest?>(null)
    val activeMediaPlayback: kotlinx.coroutines.flow.StateFlow<com.example.media.MediaPlayRequest?> = _activeMediaPlayback.asStateFlow()

    val videoHistory: kotlinx.coroutines.flow.StateFlow<List<com.example.database.VideoHistoryEntity>> =
        videoHistoryRepository.allVideoHistoryFlow.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun playMedia(request: com.example.media.MediaPlayRequest) {
        _activeMediaPlayback.value = request
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                videoHistoryRepository.recordVideoPlayback(
                    title = request.title,
                    videoUrl = request.uri.toString(),
                    thumbnailUrl = request.thumbnailUrl,
                    mimeType = request.mimeType,
                    isAudioOnly = request.isAudioOnly,
                    subtitle = request.subtitle,
                    lastPositionMs = request.initialPositionMs
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateVideoPlaybackProgress(url: String, positionMs: Long, durationMs: Long) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                videoHistoryRepository.updatePlaybackPosition(url, positionMs, durationMs)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteVideoHistoryEntry(id: Long) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                videoHistoryRepository.deleteById(id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearVideoHistory() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                videoHistoryRepository.deleteAll()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun playVideoFromHistory(item: com.example.database.VideoHistoryEntity, resumePlayback: Boolean = true) {
        val detected = com.example.util.StreamUrlHelper.detectStreamType(item.videoUrl)
        val isAudio = item.isAudioOnly || detected?.isAudio == true
        val startPos = if (resumePlayback && item.lastPositionMs > 3000L) item.lastPositionMs else 0L
        val playRequest = com.example.media.MediaPlayRequest(
            title = item.title,
            uri = android.net.Uri.parse(item.videoUrl),
            thumbnailUrl = item.thumbnailUrl,
            initialPositionMs = startPos,
            mimeType = item.mimeType,
            isAudioOnly = isAudio,
            subtitle = item.subtitle ?: item.videoUrl
        )
        playMedia(playRequest)
    }

    fun dismissMediaPlayback() {
        _activeMediaPlayback.value = null
    }

    fun isMediaItem(item: com.example.util.AdvancedDownloadItem): Boolean {
        val file = item.file
        val ext = file?.extension?.lowercase() ?: item.fileName.substringAfterLast('.', "").lowercase()
        val mediaExtensions = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "flv", "3gp", "ts", "m4v",
            "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "wma"
        )
        return item.mimeType.startsWith("video/") || item.mimeType.startsWith("audio/") || ext in mediaExtensions
    }

    fun openDownloadedMediaOrFile(context: Context, item: com.example.util.AdvancedDownloadItem) {
        val file = item.file
        if (file != null && file.exists() && isMediaItem(item)) {
            val isAudio = item.mimeType.startsWith("audio/") ||
                    file.extension.lowercase() in setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "wma")
            val playRequest = com.example.media.MediaPlayRequest(
                title = item.fileName,
                uri = android.net.Uri.fromFile(file),
                mimeType = item.mimeType.ifBlank { null },
                isAudioOnly = isAudio,
                subtitle = "Ficheiro Local • ${com.example.util.DownloadNotificationHelper.formatBytes(item.totalBytes)}",
                sourceDownloadItem = item
            )
            playMedia(playRequest)
        } else {
            downloadDelegate.openDownloadedFile(context, item)
        }
    }

    fun previewExtractedVideo(video: com.example.extractor.ExtractedVideo) {
        if (video.url.isBlank()) return
        val isAudio = video.type == com.example.extractor.VideoType.AUDIO
        val isAbys = video.type == com.example.extractor.VideoType.ABYS || com.example.util.AbysDedicatedEngine.isAbysUrl(video.url)
        val resolvedMimeType = when {
            isAbys -> "video/mp4"
            video.type == com.example.extractor.VideoType.HLS -> "application/x-mpegURL"
            video.type == com.example.extractor.VideoType.DASH -> "application/dash+xml"
            isAudio -> if (video.url.contains(".mp3")) "audio/mpeg" else "audio/mp4"
            else -> "video/mp4"
        }
        val subtitlePrefix = if (isAbys) "Stream ABYS" else "Prévia de Streaming"
        val playRequest = com.example.media.MediaPlayRequest(
            title = video.title,
            uri = android.net.Uri.parse(video.url),
            thumbnailUrl = video.thumbnailUrl,
            headers = video.headers,
            mimeType = resolvedMimeType,
            isAudioOnly = isAudio,
            subtitle = subtitlePrefix + (video.quality?.let { " • $it" } ?: "") + (video.formattedSize?.let { " • $it" } ?: "")
        )
        playMedia(playRequest)
    }

    fun previewMediaGroup(group: com.example.model.ExtractedMediaGroup) {
        val stream = group.selectedStream ?: group.streams.firstOrNull() ?: return
        val raw = stream.rawVideo.copy(
            thumbnailUrl = stream.rawVideo.thumbnailUrl ?: group.thumbnailUrl
        )
        previewExtractedVideo(raw)
    }

    fun getMimeTypeFromExtension(extension: String): String {
        return downloadDelegate.getMimeTypeFromExtension(extension)
    }

    fun playDirectStream(url: String, customTitle: String? = null, headers: Map<String, String> = emptyMap(), thumbnailUrl: String? = null) {
        val clean = url.trim()
        if (clean.isBlank()) return
        val detected = com.example.util.StreamUrlHelper.detectStreamType(clean)
        val isAudio = detected?.isAudio == true
        val title = customTitle?.takeIf { it.isNotBlank() } ?: detected?.label ?: "Stream Direto"
        val playRequest = com.example.media.MediaPlayRequest(
            title = title,
            uri = android.net.Uri.parse(clean),
            thumbnailUrl = thumbnailUrl,
            headers = headers,
            isAudioOnly = isAudio,
            subtitle = clean
        )
        playMedia(playRequest)
    }
}
