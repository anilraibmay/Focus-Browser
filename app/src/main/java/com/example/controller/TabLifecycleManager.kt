package com.example.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.webkit.WebView
import com.example.database.TabStateEntity
import com.example.database.TabStateRepository
import com.example.model.BrowserTab
import com.example.util.WebViewRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Sub-controlador responsável pelo ciclo de vida das abas:
 * - Persistência no banco de dados local (Room)
 * - Serialização de estado (Bundle / WebView state e thumbnail JPEG)
 * - Congelamento (Freezing) e descarte (Discarding) de abas em segundo plano
 * - Restauração segura de abas congeladas/descartadas
 * - Captura de miniaturas (thumbnails)
 * - Monitoramento em segundo plano e Throttling de timers JS (60s)
 * - Sincronização periódica de abas inativas
 */
class TabLifecycleManager(
    private val repository: TabStateRepository,
    private val scope: CoroutineScope
) {

    /**
     * Persiste metadados e estado da aba no Room em background garantindo captura segura do WebView na UI thread.
     */
    fun persistTab(tab: BrowserTab) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val capturedViewState = tab.webView?.let { wv ->
                try {
                    val bundle = Bundle()
                    wv.saveState(bundle)
                    bundleToBytes(bundle)
                } catch (e: Exception) {
                    null
                }
            }
            val thumbnailBytes = bitmapToBytes(tab.thumbnail)
            saveEntityToDb(tab, capturedViewState, thumbnailBytes)
        } else {
            Handler(Looper.getMainLooper()).post {
                val capturedViewState = tab.webView?.let { wv ->
                    try {
                        val bundle = Bundle()
                        wv.saveState(bundle)
                        bundleToBytes(bundle)
                    } catch (e: Exception) {
                        null
                    }
                }
                val thumbnailBytes = bitmapToBytes(tab.thumbnail)
                saveEntityToDb(tab, capturedViewState, thumbnailBytes)
            }
        }
    }

    private fun saveEntityToDb(tab: BrowserTab, capturedViewState: ByteArray?, thumbnailBytes: ByteArray?) {
        scope.launch(Dispatchers.IO) {
            val existing = if (capturedViewState == null || thumbnailBytes == null) {
                repository.getTabById(tab.id)
            } else null

            val finalViewState = capturedViewState ?: existing?.webViewState
            val finalThumbnail = thumbnailBytes ?: existing?.thumbnailState

            val entity = TabStateEntity(
                id = tab.id,
                title = tab.title,
                url = tab.url,
                openedInBackground = tab.openedInBackground,
                lastUpdated = tab.lastUpdated,
                isFrozen = tab.isFrozen,
                webViewState = finalViewState,
                thumbnailState = finalThumbnail,
                isDiscarded = tab.isDiscarded
            )
            repository.insertTab(entity)
        }
    }

    /**
     * Remove do banco de dados registros órfãos de abas que não estão mais na sessão ativa.
     */
    fun cleanOrphanTabs(activeTabIds: List<String>) {
        if (activeTabIds.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                repository.deleteTabsNotIn(activeTabIds)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Remove aba persistida no banco de dados.
     */
    fun deleteTab(tabId: String) {
        scope.launch(Dispatchers.IO) {
            repository.deleteTabById(tabId)
        }
    }

    /**
     * Carrega abas persistidas do banco na inicialização do aplicativo preservando a ordem original.
     */
    fun loadInitialTabs(
        savedOrderIds: List<String> = emptyList(),
        onLoaded: (List<BrowserTab>) -> Unit,
        onEmpty: () -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val dbTabs = repository.getAllTabs()
                if (dbTabs.isNotEmpty()) {
                    val sortedEntities = if (savedOrderIds.isNotEmpty()) {
                        val orderMap = savedOrderIds.withIndex().associate { it.value to it.index }
                        dbTabs.sortedBy { orderMap[it.id] ?: Int.MAX_VALUE }
                    } else {
                        dbTabs
                    }

                    val restoredTabs = sortedEntities.map { entity ->
                        val isHome = entity.url.isEmpty() || entity.url == "about:blank"
                        BrowserTab(
                            id = entity.id,
                            title = if (isHome) "Nova guia" else entity.title,
                            url = entity.url,
                            progress = 100,
                            isLoading = false,
                            openedInBackground = entity.openedInBackground,
                            lastUpdated = entity.lastUpdated,
                            thumbnail = bytesToBitmap(entity.thumbnailState),
                            isFrozen = !isHome, // Abas da tela inicial não necessitam de congelamento
                            isDiscarded = entity.isDiscarded
                        )
                    }
                    Handler(Looper.getMainLooper()).post {
                        onLoaded(restoredTabs)
                    }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        onEmpty()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Handler(Looper.getMainLooper()).post {
                    onEmpty()
                }
            }
        }
    }

    /**
     * Congela a aba, salvando seu WebViewState no banco e liberando recursos de memória do WebView.
     */
    fun freezeTab(tab: BrowserTab, onTabUpdated: (BrowserTab) -> Unit) {
        val webView = tab.webView ?: return

        val bundle = Bundle()
        webView.saveState(bundle)
        val webViewStateBytes = bundleToBytes(bundle)
        val thumbnailBytes = bitmapToBytes(tab.thumbnail)

        val entity = TabStateEntity(
            id = tab.id,
            title = tab.title,
            url = tab.url,
            openedInBackground = tab.openedInBackground,
            lastUpdated = tab.lastUpdated,
            isFrozen = true,
            webViewState = webViewStateBytes,
            thumbnailState = thumbnailBytes,
            isDiscarded = false
        )

        scope.launch(Dispatchers.IO) {
            repository.insertTab(entity)
        }

        Handler(Looper.getMainLooper()).post {
            WebViewRegistry.remove(tab.id)?.let { wv ->
                wv.stopLoading()
                wv.clearHistory()
                wv.destroy()
            }
        }

        onTabUpdated(tab.copy(isFrozen = true))
    }

    /**
     * Restaura uma aba congelada reidratando seu WebViewState original salvo no banco.
     */
    fun unfreezeTab(
        context: Context,
        tabId: String,
        createConfiguredWebView: (Context, String) -> WebView,
        onRestored: (BrowserTab) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val entity = repository.getTabById(tabId)
            if (entity != null) {
                Handler(Looper.getMainLooper()).post {
                    val newWebView = createConfiguredWebView(context, tabId)
                    WebViewRegistry.put(tabId, newWebView)

                    var restoredWithState = false
                    entity.webViewState?.let { bytes ->
                        try {
                            val bundle = bytesToBundle(bytes)
                            val list = newWebView.restoreState(bundle)
                            if (list != null && list.size > 0) {
                                restoredWithState = true
                            }
                        } catch (e: Exception) {
                            restoredWithState = false
                        }
                    }

                    if (!restoredWithState) {
                        if (entity.url.isNotEmpty() && entity.url != "about:blank") {
                            newWebView.loadUrl(entity.url)
                        }
                    }

                    val restoredThumbnail = bytesToBitmap(entity.thumbnailState)
                    val restoredTab = BrowserTab(
                        id = entity.id,
                        title = entity.title,
                        url = entity.url,
                        progress = 100,
                        isLoading = false,
                        openedInBackground = false,
                        lastUpdated = entity.lastUpdated,
                        thumbnail = restoredThumbnail,
                        isFrozen = false,
                        isDiscarded = false,
                        isRestoringSnapshot = restoredThumbnail != null,
                        lastInteractionTime = System.currentTimeMillis()
                    )

                    scope.launch(Dispatchers.IO) {
                        repository.insertTab(
                            entity.copy(
                                isFrozen = false,
                                isDiscarded = false,
                                lastUpdated = restoredTab.lastUpdated
                            )
                        )
                    }

                    onRestored(restoredTab)
                }
            }
        }
    }

    /**
     * Descarta completamente uma aba para liberar memória RAM preservando estado para restauração instantânea.
     */
    fun discardTab(tab: BrowserTab, onTabUpdated: (BrowserTab) -> Unit) {
        val capturedViewState = tab.webView?.let { wv ->
            try {
                val bundle = Bundle()
                wv.saveState(bundle)
                bundleToBytes(bundle)
            } catch (e: Exception) {
                null
            }
        }
        val thumbnailBytes = bitmapToBytes(tab.thumbnail)

        scope.launch(Dispatchers.IO) {
            val existing = if (capturedViewState == null || thumbnailBytes == null) {
                repository.getTabById(tab.id)
            } else null

            val finalViewState = capturedViewState ?: existing?.webViewState
            val finalThumbnail = thumbnailBytes ?: existing?.thumbnailState

            val entity = TabStateEntity(
                id = tab.id,
                title = tab.title,
                url = tab.url,
                openedInBackground = tab.openedInBackground,
                lastUpdated = tab.lastUpdated,
                isFrozen = false,
                webViewState = finalViewState,
                thumbnailState = finalThumbnail,
                isDiscarded = true
            )
            repository.insertTab(entity)
        }

        Handler(Looper.getMainLooper()).post {
            WebViewRegistry.remove(tab.id)?.let { wv ->
                wv.stopLoading()
                wv.clearHistory()
                wv.destroy()
            }
        }

        onTabUpdated(tab.copy(isDiscarded = true, isFrozen = false))
    }

    /**
     * Restaura uma aba descartada recuperando o histórico, rolagem e sessão salvos.
     */
    fun restoreDiscardedTab(
        context: Context,
        tabId: String,
        fallbackUrl: String,
        createConfiguredWebView: (Context, String) -> WebView,
        onRestored: (BrowserTab) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val entity = repository.getTabById(tabId)
            val urlToLoad = entity?.url ?: fallbackUrl
            val webViewStateBytes = entity?.webViewState

            Handler(Looper.getMainLooper()).post {
                val newWebView = createConfiguredWebView(context, tabId)
                WebViewRegistry.put(tabId, newWebView)

                var restoredWithState = false
                if (webViewStateBytes != null && webViewStateBytes.isNotEmpty()) {
                    try {
                        val bundle = bytesToBundle(webViewStateBytes)
                        val backForwardList = newWebView.restoreState(bundle)
                        if (backForwardList != null && backForwardList.size > 0) {
                            restoredWithState = true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (!restoredWithState && urlToLoad.isNotEmpty() && urlToLoad != "about:blank") {
                    newWebView.loadUrl(urlToLoad)
                }

                val restoredThumbnail = bytesToBitmap(entity?.thumbnailState)
                val restoredTab = BrowserTab(
                    id = tabId,
                    title = entity?.title ?: "Nova guia",
                    url = urlToLoad,
                    progress = 100,
                    isLoading = false,
                    openedInBackground = false,
                    lastUpdated = getCurrentTimeFormatted(),
                    thumbnail = restoredThumbnail,
                    isFrozen = false,
                    isDiscarded = false,
                    isRestoringSnapshot = restoredThumbnail != null,
                    lastInteractionTime = System.currentTimeMillis()
                )

                scope.launch(Dispatchers.IO) {
                    val updatedEntity = entity?.copy(
                        isFrozen = false,
                        isDiscarded = false,
                        lastUpdated = restoredTab.lastUpdated
                    ) ?: TabStateEntity(
                        id = tabId,
                        title = restoredTab.title,
                        url = restoredTab.url,
                        openedInBackground = false,
                        lastUpdated = restoredTab.lastUpdated,
                        isFrozen = false,
                        webViewState = webViewStateBytes,
                        thumbnailState = bitmapToBytes(restoredTab.thumbnail),
                        isDiscarded = false
                    )
                    repository.insertTab(updatedEntity)
                }

                onRestored(restoredTab)
            }
        }
    }

    /**
     * Captura miniatura leve (320px de largura, formato RGB_565) do WebView da aba.
     */
    fun captureTabThumbnail(tab: BrowserTab?, onThumbnailCaptured: (String, Bitmap) -> Unit) {
        val tabId = tab?.id ?: return
        Handler(Looper.getMainLooper()).post {
            val webView = tab.webView
            if (webView != null && webView.width > 0 && webView.height > 0) {
                var scaledBitmap: Bitmap? = null
                try {
                    val scaledWidth = 320
                    val scaledHeight = (webView.height * (320f / webView.width)).toInt().coerceAtLeast(10)
                    scaledBitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.RGB_565)

                    val canvas = Canvas(scaledBitmap)
                    val scaleX = scaledWidth.toFloat() / webView.width.toFloat()
                    val scaleY = scaledHeight.toFloat() / webView.height.toFloat()
                    canvas.scale(scaleX, scaleY)
                    webView.draw(canvas)

                    scaledBitmap?.let { onThumbnailCaptured(tabId, it) }
                } catch (t: Throwable) {
                    t.printStackTrace()
                    try {
                        scaledBitmap?.recycle()
                    } catch (ignored: Throwable) {}
                }
            }
        }
    }

    /**
     * Inicia serviço de sincronização periódica de abas em segundo plano.
     */
    fun startBackgroundSyncService(
        isEnabled: () -> Boolean,
        onSyncTriggered: () -> Unit
    ) {
        scope.launch {
            while (isActive) {
                delay(30000)
                if (isEnabled()) {
                    onSyncTriggered()
                }
            }
        }
    }

    /**
     * Executa a sincronização em segundo plano nas abas inativas.
     */
    fun syncInactiveTabs(
        tabs: List<BrowserTab>,
        activeTabId: String,
        isManual: Boolean,
        isEnabled: () -> Boolean,
        onNotify: (String) -> Unit
    ): List<BrowserTab> {
        if (!isManual && !isEnabled()) {
            return tabs
        }
        val currentTime = getCurrentTimeFormatted()
        var syncCount = 0

        val updatedTabs = tabs.map { tab ->
            if (tab.id != activeTabId && tab.url.isNotEmpty() && !tab.url.startsWith("about:")) {
                Handler(Looper.getMainLooper()).post {
                    tab.webView?.reload()
                }
                syncCount++
                tab.copy(
                    lastUpdated = currentTime,
                    openedInBackground = true
                )
            } else {
                tab
            }
        }

        if (syncCount > 0 && isManual) {
            // Background synchronization performed silently without toast
        }

        return updatedTabs
    }

    /**
     * Inicia o monitoramento contínuo de exceções (mídia, websockets, webrtc) e throttling de abas.
     */
    fun startBackgroundMonitoring(
        tabsProvider: () -> List<BrowserTab>,
        activeTabIdProvider: () -> String,
        onThrottleTab: (BrowserTab) -> Unit,
        onUpdateTabExceptions: (String, TabExceptions) -> Unit
    ) {
        scope.launch {
            while (isActive) {
                delay(10000)
                monitorTabsExceptionStates(tabsProvider(), onUpdateTabExceptions)
                checkAndThrottleTabs(tabsProvider(), activeTabIdProvider(), onThrottleTab)
            }
        }
    }

    private fun monitorTabsExceptionStates(
        tabs: List<BrowserTab>,
        onUpdateTabExceptions: (String, TabExceptions) -> Unit
    ) {
        tabs.forEach { tab ->
            val webView = tab.webView
            if (webView != null && !tab.isFrozen && !tab.isDiscarded) {
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript(
                        "(function() {\n" +
                                "  var mediaPlaying = false;\n" +
                                "  var mediaElements = document.querySelectorAll('video, audio');\n" +
                                "  for (var i = 0; i < mediaElements.length; i++) {\n" +
                                "    var el = mediaElements[i];\n" +
                                "    if (!el.paused && !el.muted && el.currentTime > 0) {\n" +
                                "      mediaPlaying = true;\n" +
                                "      break;\n" +
                                "    }\n" +
                                "  }\n" +
                                "  var hasActiveWebSockets = (window._activeWebSockets && window._activeWebSockets > 0) ? true : false;\n" +
                                "  var hasActiveWebRTC = (window._activePeerConnections && window._activePeerConnections > 0) ? true : false;\n" +
                                "  var hasExternalDevices = (window._bluetoothActive || window._usbActive || window._printUsed) ? true : false;\n" +
                                "  return JSON.stringify({\n" +
                                "    mediaPlaying: mediaPlaying,\n" +
                                "    hasActiveWebSockets: hasActiveWebSockets,\n" +
                                "    hasActiveWebRTC: hasActiveWebRTC,\n" +
                                "    hasExternalDevices: hasExternalDevices\n" +
                                "  });\n" +
                                "})()",
                        { result ->
                            try {
                                val cleanResult = result?.removePrefix("\"")?.removeSuffix("\"")?.replace("\\\"", "\"")
                                if (cleanResult != null && cleanResult != "null") {
                                    val mediaPlaying = cleanResult.contains("\"mediaPlaying\":true")
                                    val webSockets = cleanResult.contains("\"hasActiveWebSockets\":true")
                                    val webRtc = cleanResult.contains("\"hasActivePeerConnections\":true") ||
                                            cleanResult.contains("\"hasActiveWebRTC\":true") ||
                                            cleanResult.contains("\"activeRTCPeerConnections\":true")
                                    val externalDevices = cleanResult.contains("\"hasExternalDevices\":true")

                                    onUpdateTabExceptions(
                                        tab.id,
                                        TabExceptions(mediaPlaying, webSockets, webRtc, externalDevices)
                                    )
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkAndThrottleTabs(
        tabs: List<BrowserTab>,
        activeId: String,
        onThrottleTab: (BrowserTab) -> Unit
    ) {
        val now = System.currentTimeMillis()
        tabs.forEach { tab ->
            if (tab.id != activeId && !tab.isFrozen && !tab.isDiscarded) {
                val inactiveDuration = now - tab.lastInteractionTime
                // Aplica throttling após 5 minutos de inatividade em background
                if (inactiveDuration >= 5 * 60 * 1000) {
                    if (!tab.isThrottled) {
                        scope.launch {
                            val exceptions = checkTabExceptions(tab)
                            if (!exceptions.hasAnyException()) {
                                onThrottleTab(tab)
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun checkTabExceptions(tab: BrowserTab): TabExceptions {
        val webView = tab.webView ?: return TabExceptions()
        return suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript(
                    "(function() {\n" +
                            "  var mediaPlaying = false;\n" +
                            "  var mediaElements = document.querySelectorAll('video, audio');\n" +
                            "  for (var i = 0; i < mediaElements.length; i++) {\n" +
                            "    var el = mediaElements[i];\n" +
                            "    if (!el.paused && !el.muted && el.currentTime > 0) {\n" +
                            "      mediaPlaying = true;\n" +
                            "      break;\n" +
                            "    }\n" +
                            "  }\n" +
                            "  var hasActiveWebSockets = (window._activeWebSockets && window._activeWebSockets > 0) ? true : false;\n" +
                            "  var hasActiveWebRTC = (window._activePeerConnections && window._activePeerConnections > 0) ? true : false;\n" +
                            "  var hasExternalDevices = (window._bluetoothActive || window._usbActive || window._printUsed) ? true : false;\n" +
                            "  return JSON.stringify({\n" +
                            "    mediaPlaying: mediaPlaying,\n" +
                            "    hasActiveWebSockets: hasActiveWebSockets,\n" +
                            "    hasActiveWebRTC: hasActiveWebRTC,\n" +
                            "    hasExternalDevices: hasExternalDevices\n" +
                            "  });\n" +
                            "})()",
                    { result ->
                        try {
                            val cleanResult = result?.removePrefix("\"")?.removeSuffix("\"")?.replace("\\\"", "\"")
                            if (cleanResult != null && cleanResult != "null") {
                                val mediaPlaying = cleanResult.contains("\"mediaPlaying\":true")
                                val webSockets = cleanResult.contains("\"hasActiveWebSockets\":true")
                                val webRtc = cleanResult.contains("\"hasActivePeerConnections\":true") ||
                                        cleanResult.contains("\"hasActiveWebRTC\":true") ||
                                        cleanResult.contains("\"activeRTCPeerConnections\":true")
                                val externalDevices = cleanResult.contains("\"hasExternalDevices\":true")
                                if (continuation.isActive) {
                                    continuation.resume(TabExceptions(mediaPlaying, webSockets, webRtc, externalDevices))
                                }
                            } else {
                                if (continuation.isActive) {
                                    continuation.resume(TabExceptions())
                                }
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) {
                                continuation.resume(TabExceptions())
                            }
                        }
                    }
                )
            }
        }
    }

    /**
     * Aplica throttling diminuindo frequência de timers JS para 60 segundos.
     */
    fun applyThrottling(tab: BrowserTab, onThrottled: (BrowserTab) -> Unit) {
        val webView = tab.webView ?: return
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript(
                "(function() {\n" +
                        "  if (!window._timerThrottled) {\n" +
                        "    window._timerThrottled = true;\n" +
                        "    window._origSetTimeout = window.setTimeout;\n" +
                        "    window._origSetInterval = window.setInterval;\n" +
                        "    window.setTimeout = function(callback, delay) {\n" +
                        "      var adjustedDelay = Math.max(delay || 0, 60000);\n" +
                        "      return window._origSetTimeout(callback, adjustedDelay);\n" +
                        "    };\n" +
                        "    window.setInterval = function(callback, delay) {\n" +
                        "      var adjustedDelay = Math.max(delay || 0, 60000);\n" +
                        "      return window._origSetInterval(callback, adjustedDelay);\n" +
                        "    };\n" +
                        "  }\n" +
                        "})()",
                null
            )
            onThrottled(tab.copy(isThrottled = true))
        }
    }

    /**
     * Remove o throttling dos timers JS restaurando setTimeout/setInterval normais.
     */
    fun removeThrottling(tab: BrowserTab, onUnthrottled: (BrowserTab) -> Unit) {
        val webView = tab.webView ?: return
        if (!tab.isThrottled) return
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript(
                "(function() {\n" +
                        "  if (window._timerThrottled) {\n" +
                        "    window._timerThrottled = false;\n" +
                        "    if (window._origSetTimeout) window.setTimeout = window._origSetTimeout;\n" +
                        "    if (window._origSetInterval) window.setInterval = window._origSetInterval;\n" +
                        "  }\n" +
                        "})()",
                null
            )
            onUnthrottled(tab.copy(isThrottled = false))
        }
    }

    fun getCurrentTimeFormatted(): String {
        return try {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            sdf.format(Date())
        } catch (e: Exception) {
            "Att"
        }
    }

    fun bitmapToBytes(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null) return null
        return try {
            val stream = ByteArrayOutputStream()
            val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            // WebP lossy a 75% reduz o tamanho da miniatura em ~50% comparado a JPEG mantendo ótima nitidez visual
            bitmap.compress(format, 75, stream)
            stream.toByteArray()
        } catch (t: Throwable) {
            null
        }
    }

    fun bytesToBitmap(bytes: ByteArray?): Bitmap? {
        if (bytes == null) return null
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            null
        }
    }

    fun bundleToBytes(bundle: Bundle): ByteArray {
        return try {
            val parcel = Parcel.obtain()
            parcel.writeBundle(bundle)
            val rawBytes = parcel.marshall()
            parcel.recycle()
            // Compress with GZIP to reduce disk I/O and database footprint
            com.example.util.CompressionUtil.compress(rawBytes) ?: rawBytes
        } catch (t: Throwable) {
            ByteArray(0)
        }
    }

    fun bytesToBundle(bytes: ByteArray): Bundle {
        if (bytes.isEmpty()) return Bundle()
        return try {
            // Decompress GZIP payload (or return legacy uncompressed data seamlessly)
            val decompressedBytes = com.example.util.CompressionUtil.decompress(bytes) ?: bytes
            val parcel = Parcel.obtain()
            parcel.unmarshall(decompressedBytes, 0, decompressedBytes.size)
            parcel.setDataPosition(0)
            val bundle = parcel.readBundle(Bundle::class.java.classLoader)
            parcel.recycle()
            bundle ?: Bundle()
        } catch (t: Throwable) {
            Bundle()
        }
    }
}

data class TabExceptions(
    val mediaPlaying: Boolean = false,
    val hasActiveWebSockets: Boolean = false,
    val hasActiveWebRTC: Boolean = false,
    val hasExternalDevices: Boolean = false
) {
    fun hasAnyException(): Boolean = mediaPlaying || hasActiveWebSockets || hasActiveWebRTC || hasExternalDevices
}
