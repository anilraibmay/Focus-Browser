package com.example.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Modelo de dados para opção de fluxo do YouTube dedicado.
 */
data class YouTubeStreamFormat(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val qualityLabel: String,
    val width: Int?,
    val height: Int?,
    val fps: Int?,
    val bitrate: Long,
    val sizeBytes: Long,
    val formattedSize: String,
    val isAudioOnly: Boolean,
    val isVideoOnly: Boolean,
    val isCombined: Boolean, // Possui áudio e vídeo juntos
    val audioCodec: String? = null,
    val videoCodec: String? = null,
    val cipher: String? = null
)

/**
 * Detalhes do vídeo do YouTube extraídos de forma dedicada.
 */
data class YouTubeVideoDetails(
    val videoId: String,
    val title: String,
    val author: String,
    val channelId: String? = null,
    val durationSeconds: Long,
    val formattedDuration: String,
    val thumbnailUrl: String,
    val videoFormats: List<YouTubeStreamFormat>,
    val audioFormats: List<YouTubeStreamFormat>,
    val defaultVideoFormat: YouTubeStreamFormat?,
    val defaultAudioFormat: YouTubeStreamFormat?
)

/**
 * Motor dedicado de extração e resolução de streams do YouTube.
 * Utiliza o endpoint Innertube com clientes Android, iOS e Web,
 * além de suporte para extração direta do DOM.
 */
object YouTubeDedicatedEngine {

    private const val TAG = "YouTubeDedicatedEngine"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Verifica se uma URL pertence ao YouTube.
     */
    fun isYouTubeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("googlevideo.com")
    }

    /**
     * Extrai o Video ID de qualquer URL do YouTube (desktop, mobile, shorts, embed, links encurtados).
     */
    fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return false.let { null }
        
        try {
            // 1. Links diretos youtu.be/<id>
            if (url.contains("youtu.be/")) {
                val id = url.substringAfter("youtu.be/").substringBefore("?").substringBefore("/")
                if (id.length in 8..15) return id
            }

            // 2. Links shorts /shorts/<id>
            if (url.contains("/shorts/")) {
                val id = url.substringAfter("/shorts/").substringBefore("?").substringBefore("/")
                if (id.length in 8..15) return id
            }

            // 3. Links embed /embed/<id>
            if (url.contains("/embed/")) {
                val id = url.substringAfter("/embed/").substringBefore("?").substringBefore("/")
                if (id.length in 8..15) return id
            }

            // 4. Parâmetro v=<id>
            if (url.contains("v=")) {
                val id = url.substringAfter("v=").substringBefore("&").substringBefore("#")
                if (id.length in 8..15) return id
            }

            // 5. Parâmetro vi=<id>
            if (url.contains("vi=")) {
                val id = url.substringAfter("vi=").substringBefore("&").substringBefore("#")
                if (id.length in 8..15) return id
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao extrair videoId de $url: ${e.message}")
        }
        return null
    }

    /**
     * Extrai todos os detalhes e formatos disponíveis do vídeo através de múltiplas estratégias concorrentes
     * (Innertube Android, iOS, Web Embed e Web Scrape) com tempo de resposta ultrarrápido (1 a 3 segundos).
     */
    suspend fun fetchVideoDetails(
        videoId: String, 
        webViewCookies: String = "", 
        pageUserAgent: String = ""
    ): YouTubeVideoDetails? = withContext(Dispatchers.IO) {
        try {
            // Executar estratégias em paralelo com timeout geral de 6 segundos
            withTimeoutOrNull(6000L) {
                val deferredVr = async { fetchViaInnertube(videoId, clientType = "ANDROID_VR", webViewCookies, pageUserAgent) }
                val deferredTv = async { fetchViaInnertube(videoId, clientType = "TVHTML5", webViewCookies, pageUserAgent) }
                val deferredIos = async { fetchViaInnertube(videoId, clientType = "IOS", webViewCookies, pageUserAgent) }
                val deferredAndroid = async { fetchViaInnertube(videoId, clientType = "ANDROID", webViewCookies, pageUserAgent) }
                val deferredHtmlScrape = async { fetchViaHtmlScrape(videoId, webViewCookies, pageUserAgent) }

                val vrResult = deferredVr.await()
                if (vrResult != null && (vrResult.videoFormats.isNotEmpty() || vrResult.audioFormats.isNotEmpty())) {
                    return@withTimeoutOrNull vrResult
                }

                val tvResult = deferredTv.await()
                if (tvResult != null && (tvResult.videoFormats.isNotEmpty() || tvResult.audioFormats.isNotEmpty())) {
                    return@withTimeoutOrNull tvResult
                }

                val iosResult = deferredIos.await()
                if (iosResult != null && (iosResult.videoFormats.isNotEmpty() || iosResult.audioFormats.isNotEmpty())) {
                    return@withTimeoutOrNull iosResult
                }

                val androidResult = deferredAndroid.await()
                if (androidResult != null && (androidResult.videoFormats.isNotEmpty() || androidResult.audioFormats.isNotEmpty())) {
                    return@withTimeoutOrNull androidResult
                }

                val htmlResult = deferredHtmlScrape.await()
                if (htmlResult != null && (htmlResult.videoFormats.isNotEmpty() || htmlResult.audioFormats.isNotEmpty())) {
                    return@withTimeoutOrNull htmlResult
                }

                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha geral ao buscar detalhes do YouTube para $videoId: ${e.message}", e)
            null
        }
    }

    private suspend fun fetchViaHtmlScrape(
        videoId: String,
        cookies: String,
        ua: String
    ): YouTubeVideoDetails? = withContext(Dispatchers.IO) {
        try {
            val targetUrl = "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1"
            val requestBuilder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", ua.ifBlank { "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36" })
                .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

            if (cookies.isNotBlank()) {
                requestBuilder.header("Cookie", cookies)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext null
            }

            val html = response.body?.string() ?: return@withContext null
            
            val regex = Regex("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});<\\/script>", RegexOption.DOT_MATCHES_ALL)
            var match = regex.find(html)?.groupValues?.get(1)

            if (match.isNullOrBlank()) {
                val altRegex = Regex("var\\s+ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});", RegexOption.DOT_MATCHES_ALL)
                match = altRegex.find(html)?.groupValues?.get(1)
            }

            if (!match.isNullOrBlank()) {
                val rootJson = JSONObject(match)
                return@withContext parsePlayerResponse(videoId, rootJson)
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "HTML Scrape falhou para $videoId: ${e.message}")
            null
        }
    }

    private suspend fun fetchViaInnertube(
        videoId: String,
        clientType: String,
        cookies: String,
        ua: String
    ): YouTubeVideoDetails? = withContext(Dispatchers.IO) {
        try {
            val jsonPayload = buildInnertubePayload(videoId, clientType)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonPayload.toString().toRequestBody(mediaType)

            val requestBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", when (clientType) {
                    "ANDROID" -> "3"
                    "IOS" -> "5"
                    "ANDROID_VR" -> "28"
                    "TVHTML5" -> "85"
                    else -> "1"
                })
                .header("X-YouTube-Client-Version", when (clientType) {
                    "ANDROID" -> "19.10.37"
                    "IOS" -> "19.29.1"
                    "ANDROID_VR" -> "1.61.48"
                    "TVHTML5" -> "2.0"
                    else -> "2.20240918.01.00"
                })

            // Clientes nativos móveis (ANDROID / IOS / VR) NÃO devem enviar Origin/Referer web
            if (clientType != "ANDROID" && clientType != "IOS" && clientType != "ANDROID_VR") {
                requestBuilder.header("Origin", "https://www.youtube.com")
                requestBuilder.header("Referer", "https://www.youtube.com/")
                if (cookies.isNotBlank()) {
                    requestBuilder.header("Cookie", cookies)
                }
            }

            val clientUa = when (clientType) {
                "ANDROID" -> "com.google.android.youtube/19.10.37 (Linux; U; Android 14; pt_BR) gzip"
                "IOS" -> "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; pt_BR)"
                "ANDROID_VR" -> "Mozilla/5.0 (Android 12; Mobile VR; Quest 3)"
                "TVHTML5" -> "Mozilla/5.0 (PlayStation 4 5.05) AppleWebKit/537.78 (KHTML, like Gecko)"
                else -> ua.ifBlank { "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36" }
            }
            requestBuilder.header("User-Agent", clientUa)

            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext null
            }

            val responseString = response.body?.string() ?: return@withContext null
            val rootJson = JSONObject(responseString)

            return@withContext parsePlayerResponse(videoId, rootJson)
        } catch (e: Exception) {
            Log.w(TAG, "Innertube [$clientType] falhou para $videoId: ${e.message}")
            null
        }
    }

    private fun buildInnertubePayload(videoId: String, clientType: String): JSONObject {
        val root = JSONObject()
        val context = JSONObject()
        val client = JSONObject()

        when (clientType) {
            "ANDROID_VR" -> {
                client.put("clientName", "ANDROID_VR")
                client.put("clientVersion", "1.61.48")
                client.put("deviceModel", "Quest 3")
                client.put("osName", "Android")
                client.put("osVersion", "12")
                client.put("hl", "pt")
                client.put("gl", "BR")
                client.put("utcOffsetMinutes", 0)
            }
            "TVHTML5" -> {
                client.put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                client.put("clientVersion", "2.0")
                client.put("clientScreen", "EMBED")
                client.put("hl", "pt")
                client.put("gl", "BR")
                client.put("utcOffsetMinutes", 0)

                val thirdParty = JSONObject()
                thirdParty.put("embedUrl", "https://www.youtube.com")
                context.put("thirdParty", thirdParty)
            }
            "ANDROID" -> {
                client.put("clientName", "ANDROID")
                client.put("clientVersion", "19.10.37")
                client.put("androidSdkVersion", 34)
                client.put("userAgent", "com.google.android.youtube/19.10.37 (Linux; U; Android 14) gzip")
                client.put("hl", "pt")
                client.put("gl", "BR")
                client.put("utcOffsetMinutes", 0)
            }
            "IOS" -> {
                client.put("clientName", "IOS")
                client.put("clientVersion", "19.29.1")
                client.put("deviceModel", "iPhone16,2")
                client.put("userAgent", "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)")
                client.put("osName", "iPhone")
                client.put("osVersion", "17.5.1.21F90")
                client.put("hl", "pt")
                client.put("gl", "BR")
                client.put("utcOffsetMinutes", 0)
            }
            else -> {
                client.put("clientName", "WEB")
                client.put("clientVersion", "2.20240918.01.00")
                client.put("hl", "pt")
                client.put("gl", "BR")
                client.put("utcOffsetMinutes", 0)
            }
        }

        context.put("client", client)
        root.put("context", context)
        root.put("videoId", videoId)

        val playbackContext = JSONObject()
        val contentPlayback = JSONObject()
        contentPlayback.put("html5Preference", "HTML5_PREF_WANTS")
        playbackContext.put("contentPlaybackContext", contentPlayback)
        root.put("playbackContext", playbackContext)

        return root
    }

    /**
     * Faz o parsing estruturado do JSON do player response do YouTube.
     */
    fun parsePlayerResponse(videoId: String, json: JSONObject): YouTubeVideoDetails? {
        try {
            val videoDetails = json.optJSONObject("videoDetails") ?: JSONObject()
            val title = videoDetails.optString("title", "Vídeo do YouTube").ifBlank { "Vídeo do YouTube" }
            val author = videoDetails.optString("author", "YouTube").ifBlank { "YouTube" }
            val channelId = videoDetails.optString("channelId").ifBlank { null }
            val durationSeconds = videoDetails.optLong("lengthSeconds", 0L)
            val formattedDuration = formatDuration(durationSeconds)

            var thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            val thumbnails = videoDetails.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            if (thumbnails != null && thumbnails.length() > 0) {
                thumbUrl = thumbnails.getJSONObject(thumbnails.length() - 1).optString("url", thumbUrl)
            }

            val streamingData = json.optJSONObject("streamingData") ?: return null
            val rawFormats = streamingData.optJSONArray("formats") ?: JSONArray()
            val rawAdaptive = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()

            val videoList = mutableListOf<YouTubeStreamFormat>()
            val audioList = mutableListOf<YouTubeStreamFormat>()

            // 1. Processa formatos combinados (Áudio + Vídeo num único arquivo)
            for (i in 0 until rawFormats.length()) {
                val fmt = rawFormats.getJSONObject(i)
                val parsed = parseFormatObject(fmt, durationSeconds, isCombined = true)
                if (parsed != null) {
                    videoList.add(parsed)
                }
            }

            // 2. Processa formatos adaptativos (DASH)
            for (i in 0 until rawAdaptive.length()) {
                val fmt = rawAdaptive.getJSONObject(i)
                val mime = fmt.optString("mimeType", "")
                if (mime.startsWith("audio/")) {
                    val parsed = parseFormatObject(fmt, durationSeconds, isCombined = false, forceAudio = true)
                    if (parsed != null) {
                        audioList.add(parsed)
                    }
                } else if (mime.startsWith("video/")) {
                    val parsed = parseFormatObject(fmt, durationSeconds, isCombined = false, forceAudio = false)
                    if (parsed != null) {
                        videoList.add(parsed)
                    }
                }
            }

            // Deduplica e ordena os vídeos por resolução decrescente (1080p, 720p, 480p, 360p, ...)
            val sortedVideos = videoList
                .distinctBy { it.qualityLabel }
                .sortedByDescending { getQualityWeight(it.qualityLabel, it.height ?: 0) }

            // Deduplica e ordena os áudios por taxa de bits decrescente
            val sortedAudios = audioList
                .distinctBy { "${it.audioCodec ?: ""}_${it.bitrate}" }
                .sortedByDescending { it.bitrate }

            val defaultVideo = sortedVideos.firstOrNull { it.qualityLabel.contains("1080") }
                ?: sortedVideos.firstOrNull { it.qualityLabel.contains("720") }
                ?: sortedVideos.firstOrNull()

            val defaultAudio = sortedAudios.firstOrNull { it.mimeType.contains("audio/mp4") || it.audioCodec?.contains("mp4a") == true }
                ?: sortedAudios.firstOrNull()

            return YouTubeVideoDetails(
                videoId = videoId,
                title = title,
                author = author,
                channelId = channelId,
                durationSeconds = durationSeconds,
                formattedDuration = formattedDuration,
                thumbnailUrl = thumbUrl,
                videoFormats = sortedVideos,
                audioFormats = sortedAudios,
                defaultVideoFormat = defaultVideo,
                defaultAudioFormat = defaultAudio
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao analisar player response: ${e.message}", e)
            return null
        }
    }

    private fun parseFormatObject(
        fmt: JSONObject,
        durationSec: Long,
        isCombined: Boolean = false,
        forceAudio: Boolean = false
    ): YouTubeStreamFormat? {
        val itag = fmt.optInt("itag", 0)
        var streamUrl = fmt.optString("url", "")
        var cipherStr: String? = null

        if (streamUrl.isBlank()) {
            cipherStr = fmt.optString("signatureCipher", "").ifBlank {
                fmt.optString("cipher", "")
            }
            if (cipherStr.isBlank()) return null
            streamUrl = cipherStr
        }

        val mimeType = fmt.optString("mimeType", if (forceAudio) "audio/mp4" else "video/mp4")
        val isAudio = forceAudio || mimeType.startsWith("audio/")
        val isVideo = !isAudio && mimeType.startsWith("video/")

        val width = fmt.optInt("width", 0).takeIf { it > 0 }
        val height = fmt.optInt("height", 0).takeIf { it > 0 }
        val fps = fmt.optInt("fps", 0).takeIf { it > 0 }
        val bitrate = fmt.optLong("bitrate", 0L)
        val averageBitrate = fmt.optLong("averageBitrate", bitrate)

        var qualityLabel = fmt.optString("qualityLabel", "")
        if (qualityLabel.isBlank()) {
            qualityLabel = when {
                height != null && height >= 2160 -> "4K 2160p"
                height != null && height >= 1440 -> "2K 1440p"
                height != null && height >= 1080 -> "1080p Full HD"
                height != null && height >= 720 -> "720p HD"
                height != null && height >= 480 -> "480p SD"
                height != null && height >= 360 -> "360p"
                height != null && height >= 240 -> "240p"
                height != null && height >= 144 -> "144p"
                isAudio -> {
                    val kbps = if (averageBitrate > 0) averageBitrate / 1000 else 128
                    "Áudio $kbps kbps"
                }
                else -> "Vídeo MP4"
            }
        } else {
            // Normaliza nomes como 1080p para 1080p Full HD
            qualityLabel = when {
                qualityLabel.startsWith("1080p") && !qualityLabel.contains("Full HD") -> "$qualityLabel Full HD"
                qualityLabel.startsWith("720p") && !qualityLabel.contains("HD") -> "$qualityLabel HD"
                qualityLabel.startsWith("480p") && !qualityLabel.contains("SD") -> "$qualityLabel SD"
                else -> qualityLabel
            }
        }

        if (fps != null && fps >= 50 && !qualityLabel.contains("${fps}fps")) {
            qualityLabel = "$qualityLabel (${fps}fps)"
        }

        // Calcula ou estima o tamanho em bytes
        val explicitLength = fmt.optLong("contentLength", 0L)
        val calculatedSize = if (explicitLength > 0) {
            explicitLength
        } else if (durationSec > 0 && averageBitrate > 0) {
            (durationSec * (averageBitrate / 8))
        } else {
            0L
        }

        val formattedSize = if (calculatedSize > 0) {
            formatBytes(calculatedSize)
        } else {
            "Estimando..."
        }

        val codecs = mimeType.substringAfter("codecs=\"", "").substringBefore("\"")

        return YouTubeStreamFormat(
            itag = itag,
            url = streamUrl,
            mimeType = mimeType,
            qualityLabel = qualityLabel,
            width = width,
            height = height,
            fps = fps,
            bitrate = averageBitrate,
            sizeBytes = calculatedSize,
            formattedSize = formattedSize,
            isAudioOnly = isAudio,
            isVideoOnly = isVideo && !isCombined,
            isCombined = isCombined,
            audioCodec = if (isAudio || isCombined) codecs else null,
            videoCodec = if (isVideo || isCombined) codecs else null,
            cipher = cipherStr
        )
    }

    private fun getQualityWeight(label: String, height: Int): Int {
        val l = label.lowercase()
        return when {
            l.contains("4k") || l.contains("2160") || height >= 2160 -> 2160
            l.contains("2k") || l.contains("1440") || height >= 1440 -> 1440
            l.contains("1080") || height >= 1080 -> 1080
            l.contains("720") || height >= 720 -> 720
            l.contains("480") || height >= 480 -> 480
            l.contains("360") || height >= 360 -> 360
            l.contains("240") || height >= 240 -> 240
            l.contains("144") || height >= 144 -> 144
            else -> height
        }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "00:00"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }
}
