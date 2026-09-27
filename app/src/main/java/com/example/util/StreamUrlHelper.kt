package com.example.util

import android.net.Uri

enum class DetectedStreamType(
    val label: String,
    val badge: String,
    val isAudio: Boolean
) {
    HLS("Stream Adaptativo HLS (.m3u8)", "HLS", false),
    DASH("Stream Adaptativo DASH (.mpd)", "DASH", false),
    MP4("Vídeo MP4", "MP4", false),
    WEBM("Vídeo WebM", "WEBM", false),
    MKV("Vídeo Matroska (.mkv)", "MKV", false),
    DIRECT_VIDEO("Vídeo Direto", "VÍDEO", false),
    AUDIO("Faixa de Áudio", "ÁUDIO", true)
}

data class DemoStreamSample(
    val title: String,
    val url: String,
    val description: String,
    val type: DetectedStreamType
)

object StreamUrlHelper {

    val DEMO_SAMPLES = listOf(
        DemoStreamSample(
            title = "Big Buck Bunny (HLS Multirresolução)",
            url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            description = "Stream HLS adaptativo de alta qualidade",
            type = DetectedStreamType.HLS
        ),
        DemoStreamSample(
            title = "Tears of Steel (DASH)",
            url = "https://dash.akamaized.net/dash2go/bps/tos.mpd",
            description = "Manifesto DASH adaptativo com múltiplos bitrates",
            type = DetectedStreamType.DASH
        ),
        DemoStreamSample(
            title = "Big Buck Bunny (MP4 1080p)",
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            description = "Vídeo MP4 direto de alta definição",
            type = DetectedStreamType.MP4
        ),
        DemoStreamSample(
            title = "Elephants Dream (WebM)",
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
            description = "Filme aberto renderizado em alta fidelidade",
            type = DetectedStreamType.MP4
        )
    )

    fun detectStreamType(url: String?): DetectedStreamType? {
        if (url.isNullOrBlank()) return null
        val clean = url.trim()
        val path = try {
            Uri.parse(clean).path?.lowercase() ?: clean.substringBefore('?').substringBefore('#').lowercase()
        } catch (_: Exception) {
            clean.substringBefore('?').substringBefore('#').lowercase()
        }

        return when {
            path.endsWith(".m3u8") || clean.contains("manifest/hls", ignoreCase = true) || clean.contains(".m3u8?", ignoreCase = true) ->
                DetectedStreamType.HLS

            path.endsWith(".mpd") || clean.contains(".mpd?", ignoreCase = true) || clean.contains("/dash/", ignoreCase = true) ->
                DetectedStreamType.DASH

            path.endsWith(".mp4") || clean.contains("mime=video%2Fmp4", ignoreCase = true) ->
                DetectedStreamType.MP4

            path.endsWith(".webm") || clean.contains("mime=video%2Fwebm", ignoreCase = true) ->
                DetectedStreamType.WEBM

            path.endsWith(".mkv") ->
                DetectedStreamType.MKV

            path.endsWith(".mov") || path.endsWith(".avi") || path.endsWith(".ts") || path.endsWith(".m4v") || path.endsWith(".flv") ->
                DetectedStreamType.DIRECT_VIDEO

            path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac") || path.endsWith(".wav") ||
                    path.endsWith(".ogg") || path.endsWith(".opus") || path.endsWith(".flac") ->
                DetectedStreamType.AUDIO

            else -> null
        }
    }

    fun isDirectStreamUrl(url: String?): Boolean {
        return detectStreamType(url) != null
    }
}
