package com.example.controller

import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebChromeClient
import com.example.extractor.ExtractedVideo
import com.example.extractor.VideoExtractionManager
import com.example.extractor.VideoType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Sub-controlador responsável pela reprodução e detecção de mídia web:
 * - Controle de tela cheia para elementos de vídeo (WebChromeClient custom views)
 * - Interceptação e sniffing de requisições de rede para URLs de vídeo e streams (.m3u8, .mpd, .mp4)
 * - Integração com a ponte JavaScript de extração de vídeo do DOM
 * - Sondagem assíncrona (probing) de metadados de vídeo (duração e Content-Length / Range bytes)
 */
class WebMediaController(
    private val scope: CoroutineScope,
    private val onVideoExtracted: (tabId: String, video: ExtractedVideo?) -> Unit,
    private val onVideoEnriched: (tabId: String, video: ExtractedVideo) -> Unit
) {
    private val _fullscreenView = MutableStateFlow<View?>(null)
    val fullscreenView: StateFlow<View?> = _fullscreenView.asStateFlow()

    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val videoExtractionManager = VideoExtractionManager { tabId, video ->
        onVideoExtracted(tabId, video)
        probeAndEnrichVideo(tabId, video)
    }

    private val activeProbingKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Registra exibição de visualização em tela cheia do WebView.
     */
    fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        _fullscreenView.value = view
        customViewCallback = callback
    }

    /**
     * Notifica que a visualização em tela cheia foi fechada pelo WebView.
     */
    fun onHideCustomView() {
        _fullscreenView.value = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    /**
     * Força a saída do modo de tela cheia a partir da UI (botão voltar / controle).
     */
    fun exitFullscreen() {
        Handler(Looper.getMainLooper()).post {
            _fullscreenView.value = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
        }
    }

    /**
     * Fornece o objeto WebAppInterface para injeção via addJavascriptInterface no WebView.
     */
    fun createJavascriptInterface(tabId: String): Any {
        return videoExtractionManager.WebAppInterface(tabId)
    }

    /**
     * Analisa requisições de rede contínuas para interceptar e capturar fluxos de mídia dinâmicos
     * (.m3u8, .mpd, .mp4, .webm) exatamente no instante em que o utilizador clica em Play.
     */
    fun handleNetworkIntercept(tabId: String, url: String, headers: Map<String, String>, pageTitle: String?, pageUrl: String? = null) {
        val lowerUrl = url.lowercase()

        // Ignorar redes conhecidas de anúncios, telemetria (ex: cdn-cgi/rum) e rastreamento
        val isAd = listOf(
            "googleads", "doubleclick", "popads", "adsystem", "trafficjunky",
            "adsterra", "exoclick", "googlesyndication", "popunder", "adserver",
            "analytics", "adform", "adnxs", "gamoshi", "pubmatic", "rubiconproject",
            "openx", "appnexus", "adsrvr", "adcolony", "unityads", "vungle",
            "stats/playback", "stats/watchtime", "log_event", "pagead",
            "cdn-cgi/", "/rum", "beacon", "telemetry"
        ).any { lowerUrl.contains(it) }

        // Ignorar formatos explícitos de imagens estáticas, scripts, estilos e páginas web
        val isExplicitNonMedia = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".ico", ".svg", "/favicon",
            ".js", ".css", ".html", ".htm", ".php", "mime=image", "mime=text"
        ).any { lowerUrl.contains(it) }

        if (isAd || isExplicitNonMedia) return

        // Ignorar fragmentos/chunks intermediários de HLS/DASH para evitar repetições desnecessárias
        val isSegmentChunk = lowerUrl.contains(".ts") ||
                lowerUrl.contains(".m4s") ||
                lowerUrl.contains("/init.mp4") ||
                Regex("(/|_|-)seg(ment)?[_-]?[0-9]+", RegexOption.IGNORE_CASE).containsMatchIn(lowerUrl) ||
                Regex("(/|_|-)chunk[_-]?[0-9]+", RegexOption.IGNORE_CASE).containsMatchIn(lowerUrl) ||
                Regex("(/|_|-)frag[_-]?[0-9]+", RegexOption.IGNORE_CASE).containsMatchIn(lowerUrl)

        if (isSegmentChunk) return

        // Identificar manifestos de streaming (.m3u8, .mpd), fluxos de vídeo diretos, ABYS (.fd, /sora/) ou fluxos de áudio (.mp4, .webm, .m4a, .mp3)
        val isVideo = com.example.util.AbysDedicatedEngine.isAbysUrl(lowerUrl) ||
                lowerUrl.contains(".m3u8") ||
                lowerUrl.contains(".mpd") ||
                lowerUrl.contains(".mp4") ||
                lowerUrl.contains(".webm") ||
                lowerUrl.contains(".m4v") ||
                lowerUrl.contains(".mov") ||
                lowerUrl.contains(".3gp") ||
                lowerUrl.contains(".m4a") ||
                lowerUrl.contains(".mp3") ||
                lowerUrl.contains(".aac") ||
                lowerUrl.contains("googlevideo.com/videoplayback") ||
                lowerUrl.contains("mime=video") ||
                lowerUrl.contains("mime=audio") ||
                lowerUrl.contains("format=m3u8") ||
                lowerUrl.contains("format=mp4") ||
                lowerUrl.contains("video/mp4") ||
                lowerUrl.contains("audio/") ||
                lowerUrl.contains("application/x-mpegurl") ||
                lowerUrl.contains("application/vnd.apple.mpegurl") ||
                lowerUrl.contains("application/dash+xml") ||
                (lowerUrl.contains("fbcdn.net") && (lowerUrl.contains("video") || lowerUrl.contains("bytestart") || lowerUrl.contains(".mp4"))) ||
                (lowerUrl.contains("cdninstagram.com") && (lowerUrl.contains("video") || lowerUrl.contains(".mp4"))) ||
                ((lowerUrl.contains("tiktok.com") || lowerUrl.contains("byteoversea.com") || lowerUrl.contains("ibytedtos.com")) && (lowerUrl.contains("video") || lowerUrl.contains(".mp4")))

        if (isVideo) {
            val enrichedHeaders = headers.toMutableMap()
            
            // Prioritize actual page URL as Referer for embedded players (e.g. embedplay.one)
            if (!pageUrl.isNullOrBlank() && (!enrichedHeaders.containsKey("Referer") || enrichedHeaders["Referer"].isNullOrBlank())) {
                enrichedHeaders["Referer"] = pageUrl
            } else if (com.example.util.AbysDedicatedEngine.isAbysUrl(url)) {
                if (!enrichedHeaders.containsKey("Referer")) {
                    val host = android.net.Uri.parse(url).host
                    enrichedHeaders["Referer"] = if (!host.isNullOrBlank()) "https://$host/" else com.example.util.AbysDedicatedEngine.DEFAULT_REFERER
                }
            }

            if (!enrichedHeaders.containsKey("Origin") && !pageUrl.isNullOrBlank()) {
                try {
                    val pageUri = android.net.Uri.parse(pageUrl)
                    val origin = "${pageUri.scheme}://${pageUri.host}"
                    enrichedHeaders["Origin"] = origin
                } catch (_: Exception) {}
            }

            // Merge cookies from both media URL and hosting page URL
            try {
                val cookieManager = android.webkit.CookieManager.getInstance()
                val urlCookie = cookieManager.getCookie(url).orEmpty()
                val pageCookie = if (!pageUrl.isNullOrBlank()) cookieManager.getCookie(pageUrl).orEmpty() else ""
                val existingCookie = enrichedHeaders["Cookie"].orEmpty()
                val mergedCookies = listOf(existingCookie, urlCookie, pageCookie)
                    .filter { it.isNotBlank() }
                    .flatMap { it.split(";") }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString("; ")

                if (mergedCookies.isNotBlank()) {
                    enrichedHeaders["Cookie"] = mergedCookies
                }
            } catch (_: Exception) {}

            if (!enrichedHeaders.containsKey("User-Agent")) {
                enrichedHeaders["User-Agent"] = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
            }

            val resolvedTitle = com.example.extractor.MediaTitleHelper.cleanMediaTitle(pageTitle, url)
            videoExtractionManager.onNetworkVideoDetected(tabId, url, enrichedHeaders, resolvedTitle)
        }
    }

    fun clearTabMedia(tabId: String) {
        activeProbingKeys.clear()
        videoExtractionManager.clearTab(tabId)
    }

    /**
     * Sonda tamanho (Content-Length / Content-Range / BYTERANGE / Bandwidth / Amostragem),
     * duração e resolução de vídeos diretos ou playlists HLS/DASH de maneira assíncrona e progressiva,
     * aplicando estritamente a Metodologia de Estimativa de Tamanho de Mídia.
     */
    fun probeAndEnrichVideo(tabId: String, video: ExtractedVideo) {
        val canonicalKey = com.example.extractor.MediaTitleHelper.getCanonicalKey(video.url)
        if (!activeProbingKeys.add(canonicalKey)) {
            return // Já existe uma sondagem em andamento para este stream canônico
        }

        scope.launch(Dispatchers.IO) {
            val enriched = try {
                when (video.type) {
                    VideoType.HLS -> {
                        val estimation = com.example.util.MediaSizeEstimationEngine.estimateHlsStream(
                            playlistUrl = video.url,
                            headers = video.headers,
                            explicitDurationMs = video.duration,
                            explicitQuality = video.quality
                        )

                        // Se sub-variantes de resolução forem identificadas na Master Playlist, emitir para a aba
                        if (estimation.probedQuality != null && estimation.probedQuality != video.quality) {
                            video.copy(
                                size = if (estimation.sizeBytes > 0) estimation.sizeBytes else null,
                                duration = estimation.durationMs ?: video.duration,
                                formattedSize = estimation.formattedSize,
                                formattedDuration = estimation.formattedDuration,
                                quality = estimation.probedQuality,
                                estimationConfidence = estimation.confidence.label,
                                estimationMethod = estimation.method.label,
                                estimationNotes = estimation.notes
                            )
                        } else {
                            video.copy(
                                size = if (estimation.sizeBytes > 0) estimation.sizeBytes else null,
                                duration = estimation.durationMs ?: video.duration,
                                formattedSize = estimation.formattedSize,
                                formattedDuration = estimation.formattedDuration,
                                quality = video.quality ?: estimation.representationLabel,
                                estimationConfidence = estimation.confidence.label,
                                estimationMethod = estimation.method.label,
                                estimationNotes = estimation.notes
                            )
                        }
                    }
                    VideoType.DASH -> {
                        val estimation = com.example.util.MediaSizeEstimationEngine.estimateDashStream(
                            mpdUrl = video.url,
                            headers = video.headers,
                            explicitDurationMs = video.duration,
                            explicitQuality = video.quality
                        )
                        video.copy(
                            size = if (estimation.sizeBytes > 0) estimation.sizeBytes else null,
                            duration = estimation.durationMs ?: video.duration,
                            formattedSize = estimation.formattedSize,
                            formattedDuration = estimation.formattedDuration,
                            quality = video.quality ?: estimation.representationLabel,
                            estimationConfidence = estimation.confidence.label,
                            estimationMethod = estimation.method.label,
                            estimationNotes = estimation.notes
                        )
                    }
                    VideoType.ABYS -> {
                        val fn = com.example.util.AbysDedicatedEngine.extractFilename(video.url)
                        val parsedFd = com.example.util.AbysQualityEngine.parseFromFdUrl(video.url)
                        val probe = try {
                            com.example.util.AbysDedicatedEngine.probeStreamDetails(video.url, video.headers)
                        } catch (_: Exception) { null }

                        val durationMs = probe?.durationMs ?: video.duration
                        val totalBytes = probe?.totalBytes ?: video.size ?: parsedFd?.sizeBytes
                        val formattedSize = probe?.formattedSize ?: video.formattedSize ?: parsedFd?.formattedSize
                        val formattedDur = probe?.formattedDuration
                            ?: com.example.util.AbysDedicatedEngine.formatDuration(durationMs)
                            ?: video.formattedDuration

                        val bitrateNote = if (totalBytes != null && totalBytes > 0 && durationMs != null && durationMs > 0) {
                            val mbps = com.example.util.AbysQualityEngine.calculateBitrateMbps(totalBytes, durationMs / 1000.0)
                            if (mbps != null) " • $mbps Mbps" else ""
                        } else ""

                        video.copy(
                            size = totalBytes,
                            duration = durationMs,
                            formattedSize = formattedSize ?: "Tamanho sob consulta",
                            formattedDuration = formattedDur,
                            quality = video.quality ?: probe?.resolutionLabel ?: parsedFd?.resolutionLabel ?: "ABYS HD",
                            estimationConfidence = if (totalBytes != null && totalBytes > 0) "Exacto" else "Alta",
                            estimationMethod = "ABYS Engine",
                            estimationNotes = "Fluxo ABYS ($fn)$bitrateNote"
                        )
                    }
                    VideoType.DIRECT, VideoType.AUDIO -> {
                        val isAudio = video.type == VideoType.AUDIO || video.url.contains("mime=audio") || video.url.contains(".m4a") || video.url.contains(".mp3")
                        var durationMs = video.duration
                        var probedQuality: String? = null

                        if (!isAudio) {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(video.url, video.headers)
                                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                durationMs = durationStr?.toLongOrNull() ?: durationMs

                                val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                val width = widthStr?.toIntOrNull()
                                val height = heightStr?.toIntOrNull()
                                if (width != null && height != null && width > 0 && height > 0) {
                                    probedQuality = com.example.extractor.VideoQualityHelper.getQualityFromResolution(width, height)
                                }
                            } catch (t: Throwable) {
                                // Ignore retriever errors
                            } finally {
                                try {
                                    retriever.release()
                                } catch (ignored: Throwable) {}
                            }
                        }

                        val estimation = com.example.util.MediaSizeEstimationEngine.estimateDirectStream(
                            url = video.url,
                            headers = video.headers,
                            explicitDurationMs = durationMs,
                            explicitQuality = video.quality ?: probedQuality,
                            isAudio = isAudio
                        )

                        video.copy(
                            size = if (estimation.sizeBytes > 0) estimation.sizeBytes else null,
                            duration = estimation.durationMs ?: durationMs,
                            formattedSize = estimation.formattedSize,
                            formattedDuration = estimation.formattedDuration,
                            quality = video.quality ?: probedQuality ?: estimation.representationLabel,
                            estimationConfidence = estimation.confidence.label,
                            estimationMethod = estimation.method.label,
                            estimationNotes = estimation.notes
                        )
                    }
                }
            } catch (e: Exception) {
                video
            }

            Handler(Looper.getMainLooper()).post {
                onVideoEnriched(tabId, enriched)
            }
            activeProbingKeys.remove(canonicalKey)
        }
    }

    private fun resolveHlsUrl(baseUrl: String, relativeOrAbsolute: String): String {
        return try {
            val baseUri = java.net.URI(baseUrl)
            val resolvedUri = baseUri.resolve(relativeOrAbsolute)
            if (resolvedUri.query.isNullOrEmpty() && !baseUri.query.isNullOrEmpty()) {
                val str = resolvedUri.toString()
                if (str.contains("?")) "$str&${baseUri.query}" else "$str?${baseUri.query}"
            } else {
                resolvedUri.toString()
            }
        } catch (e: Exception) {
            if (relativeOrAbsolute.startsWith("http://") || relativeOrAbsolute.startsWith("https://")) {
                relativeOrAbsolute
            } else {
                val lastSlash = baseUrl.lastIndexOf('/')
                if (lastSlash != -1) {
                    baseUrl.substring(0, lastSlash + 1) + relativeOrAbsolute
                } else {
                    relativeOrAbsolute
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return com.example.ui.formatFileSize(bytes)
    }
}
