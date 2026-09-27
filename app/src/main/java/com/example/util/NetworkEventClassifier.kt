package com.example.util

import com.example.model.NetworkEventType

object NetworkEventClassifier {
    fun classify(url: String, headers: Map<String, String> = emptyMap()): NetworkEventType {
        val lowerUrl = url.lowercase()
        val acceptHeader = headers["accept"]?.lowercase() ?: ""
        val contentType = headers["content-type"]?.lowercase() ?: ""

        // 1. Streaming Manifests
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd") ||
            lowerUrl.contains("master.m3u8") || lowerUrl.contains("index.m3u8") ||
            lowerUrl.contains("playlist.m3u8") || lowerUrl.contains("/manifest") ||
            acceptHeader.contains("application/vnd.apple.mpegurl") ||
            acceptHeader.contains("application/x-mpegurl") ||
            acceptHeader.contains("application/dash+xml") ||
            contentType.contains("application/vnd.apple.mpegurl") ||
            contentType.contains("application/dash+xml")
        ) {
            return NetworkEventType.MANIFEST
        }

        // 2. Stream Segments & Chunks
        if (lowerUrl.contains(".ts") || lowerUrl.contains(".m4s") ||
            lowerUrl.contains("/seg-") || lowerUrl.contains("segment") ||
            lowerUrl.contains("fragment") || lowerUrl.contains("/chunks/") ||
            lowerUrl.contains("range=") || lowerUrl.contains("sq=") ||
            lowerUrl.contains("frag=")
        ) {
            return NetworkEventType.SEGMENT
        }

        // 3. Videos
        if (lowerUrl.contains(".mp4") || lowerUrl.contains(".webm") ||
            lowerUrl.contains(".mkv") || lowerUrl.contains(".avi") ||
            lowerUrl.contains(".flv") || lowerUrl.contains(".mov") ||
            lowerUrl.contains(".3gp") || lowerUrl.contains("googlevideo.com/videoplayback") ||
            acceptHeader.contains("video/") || contentType.contains("video/")
        ) {
            return NetworkEventType.VIDEO
        }

        // 4. Audio
        if (lowerUrl.contains(".mp3") || lowerUrl.contains(".aac") ||
            lowerUrl.contains(".m4a") || lowerUrl.contains(".ogg") ||
            lowerUrl.contains(".wav") || lowerUrl.contains(".flac") ||
            lowerUrl.contains(".opus") || acceptHeader.contains("audio/") ||
            contentType.contains("audio/")
        ) {
            return NetworkEventType.AUDIO
        }

        // 5. XHR / API / GraphQL / JSON
        if (lowerUrl.contains("/api/") || lowerUrl.contains("/graphql") ||
            lowerUrl.contains(".json") || lowerUrl.contains("/ajax/") ||
            acceptHeader.contains("application/json") || contentType.contains("application/json") ||
            headers["x-requested-with"]?.equals("xmlhttprequest", ignoreCase = true) == true
        ) {
            return NetworkEventType.XHR
        }

        // 6. Scripts
        if (lowerUrl.contains(".js") || lowerUrl.contains("/js/") ||
            acceptHeader.contains("javascript") || contentType.contains("javascript")
        ) {
            return NetworkEventType.SCRIPT
        }

        // 7. Images
        if (lowerUrl.contains(".png") || lowerUrl.contains(".jpg") ||
            lowerUrl.contains(".jpeg") || lowerUrl.contains(".webp") ||
            lowerUrl.contains(".svg") || lowerUrl.contains(".gif") ||
            lowerUrl.contains(".ico") || lowerUrl.contains(".bmp") ||
            acceptHeader.contains("image/") || contentType.contains("image/")
        ) {
            return NetworkEventType.IMAGE
        }

        return NetworkEventType.OTHER
    }
}
