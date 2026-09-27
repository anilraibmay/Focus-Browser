package com.example.extractor

import android.webkit.JavascriptInterface
import android.util.Log
import org.json.JSONObject

/**
 * Defines the type of extracted video media.
 */
enum class VideoType {
    DIRECT,    // Directly downloadable video formats like MP4, FLV, WebM
    AUDIO,     // Audio-only formats like M4A, MP3, WebM Audio
    HLS,       // HLS (HTTP Live Streaming) manifests like .m3u8 requiring segment multiplexing
    DASH,      // DASH manifests like .mpd
    ABYS       // ABYS streams (.fd encrypted header / sora tokens / mp4 parts)
}

/**
 * Encapsulates the complete metadata of the extracted video stream.
 */
data class ExtractedVideo(
    val url: String,
    val headers: Map<String, String>,
    val title: String,
    val type: VideoType,
    val size: Long? = null,
    val duration: Long? = null,
    val formattedSize: String? = null,
    val formattedDuration: String? = null,
    val thumbnailUrl: String? = null,
    val quality: String? = null,
    val mediaGroupId: String? = null,
    val streamKey: String? = null,
    val estimationConfidence: String? = null,
    val estimationMethod: String? = null,
    val estimationNotes: String? = null
)

/**
 * Utilitários para extração inteligente e limpeza de títulos de mídia avançada,
 * além de normalização de URLs canônicas para evitar repetições desnecessárias.
 */
object MediaTitleHelper {
    fun cleanMediaTitle(rawTitle: String?, url: String): String {
        var title = rawTitle?.trim().orEmpty()

        // Remover sufixos comuns de branding de plataformas
        title = title
            .replace(Regex("\\s*-\\s*YouTube$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\|\\s*Facebook$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Facebook$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\|\\s*Instagram$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\|\\s*TikTok$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*/\\s*X$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Twitter$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*on\\s*Vimeo$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Dailymotion$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Twitch$", RegexOption.IGNORE_CASE), "")
            .trim()

        val isGeneric = title.isBlank() ||
                title.equals("YouTube", ignoreCase = true) ||
                title.equals("Facebook", ignoreCase = true) ||
                title.equals("Instagram", ignoreCase = true) ||
                title.equals("TikTok", ignoreCase = true) ||
                title.equals("X", ignoreCase = true) ||
                title.equals("Twitter", ignoreCase = true) ||
                title.startsWith("Video_Stream_", ignoreCase = true) ||
                title.startsWith("Video_Extracted_", ignoreCase = true) ||
                title.equals("Vídeo Extraído", ignoreCase = true) ||
                title.equals("Vídeo do Focus Browser", ignoreCase = true) ||
                title.startsWith("http://", ignoreCase = true) ||
                title.startsWith("https://", ignoreCase = true)

        if (!isGeneric) {
            // Check if title represents a series / episode (e.g. A.Shop.for.Killers.S01E01.720p.mp4)
            val parsedEp = com.example.util.AbysNameResolver.parseFields(title)
            if (parsedEp.season != null && parsedEp.episode != null && parsedEp.show.isNotBlank() && !parsedEp.show.equals("ABYS Video", ignoreCase = true)) {
                val formattedEpTitle = listOfNotNull(
                    parsedEp.show,
                    parsedEp.se,
                    parsedEp.resolution
                ).joinToString(" - ")
                if (formattedEpTitle.isNotBlank()) {
                    return formattedEpTitle
                }
            }
        }

        if (isGeneric) {
            // Tentar extrair nome legível do arquivo no caminho da URL
            try {
                val uri = android.net.Uri.parse(url)
                val lastSegment = uri.lastPathSegment.orEmpty()
                val nameWithoutExt = lastSegment.substringBeforeLast(".")
                if (nameWithoutExt.length in 3..70 && !nameWithoutExt.all { it.isDigit() || it == '-' || it == '_' }) {
                    val formattedName = nameWithoutExt
                        .replace('_', ' ')
                        .replace('-', ' ')
                        .trim()
                        .split(" ")
                        .filter { it.isNotBlank() }
                        .joinToString(" ") { word ->
                            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                        }
                    if (formattedName.isNotBlank()) {
                        return formattedName
                    }
                }
            } catch (ignored: Exception) {}
        }

        return if (title.isNotBlank()) title else "Vídeo Extraído"
    }

    /**
     * Retorna uma chave de agrupamento da MÍDIA MESTRE (Pai), permitindo associar
     * todas as resoluções (1080p, 720p, 480p, áudios) que pertencem ao mesmo vídeo.
     */
    fun getMediaGroupKey(url: String, title: String): String {
        return try {
            val uriHost = try { java.net.URI(url).host?.lowercase().orEmpty() } catch (_: Throwable) { "" }
            val uriPath = try { java.net.URI(url).path.orEmpty() } catch (_: Throwable) { "" }

            when {
                uriHost.contains("googlevideo.com") && uriPath.contains("videoplayback") -> {
                    val idMatch = Regex("[?&](id|docid)=([^&#]+)").find(url)?.groupValues?.get(2).orEmpty()
                    if (idMatch.isNotEmpty()) "yt:$idMatch" else "yt:${cleanMediaTitle(title, url).hashCode()}"
                }
                uriHost.contains("fbcdn.net") || uriHost.contains("facebook.com") -> {
                    val fbId = Regex("/v/t[0-9.]+-?[0-9]+/([0-9_]+)").find(uriPath)?.groupValues?.get(1)
                    if (!fbId.isNullOrEmpty()) "fb:$fbId" else "fb:$uriHost$uriPath"
                }
                uriHost.contains("cdninstagram.com") || uriHost.contains("instagram.com") -> {
                    val igId = Regex("/([0-9_]+)_[0-9]+_[0-9]+_[an]\\.mp4").find(uriPath)?.groupValues?.get(1)
                    if (!igId.isNullOrEmpty()) "ig:$igId" else "ig:$uriHost$uriPath"
                }
                uriHost.contains("tiktok.com") || uriHost.contains("byteoversea.com") || uriHost.contains("ibytedtos.com") -> {
                    val ttId = uriPath.substringBeforeLast("/").substringAfterLast("/")
                    if (ttId.isNotEmpty()) "tt:$ttId" else "tt:$uriHost$uriPath"
                }
                url.contains(".m3u8") -> {
                    val basePath = uriPath.substringBeforeLast("/")
                    "hls:$uriHost$basePath"
                }
                url.contains(".mpd") -> {
                    val basePath = uriPath.substringBeforeLast("/")
                    "dash:$uriHost$basePath"
                }
                com.example.util.AbysDedicatedEngine.isAbysUrl(url) -> {
                    val slug = Regex("[?&]v=([^&#]+)").find(url)?.groupValues?.get(1)
                    if (!slug.isNullOrBlank()) {
                        "abys:$slug"
                    } else {
                        val baseName = com.example.util.AbysDedicatedEngine.extractFilename(url)
                            .replace(Regex("\\.[0-9]+\\.fd$", RegexOption.IGNORE_CASE), ".fd")
                        "abys:$baseName"
                    }
                }
                else -> {
                    val lastSegment = uriPath.substringAfterLast("/")
                    val baseName = lastSegment.substringBeforeLast(".")
                        .replace(Regex("[_-]?(2160p|1440p|1080p|720p|480p|360p|240p|144p|hd|fhd|sd)", RegexOption.IGNORE_CASE), "")
                    val parentPath = uriPath.substringBeforeLast("/")
                    "dir:$uriHost$parentPath/$baseName"
                }
            }
        } catch (e: Exception) {
            "media:${url.hashCode()}"
        }
    }

    /**
     * Retorna uma chave canônica de identificação do stream para evitar que requisições
     * de chunks, ranges ou tokens dinâmicos gerem vídeos duplicados na tela.
     */
    fun getCanonicalKey(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path.orEmpty()

            if (host.contains("googlevideo.com") && path.contains("videoplayback")) {
                val id = uri.getQueryParameter("id") ?: uri.getQueryParameter("docid") ?: ""
                val itag = uri.getQueryParameter("itag") ?: ""
                if (id.isNotEmpty() && itag.isNotEmpty()) "yt:$id:$itag"
                else if (id.isNotEmpty()) "yt:$id"
                else "$host$path"
            } else if (host.contains("fbcdn.net")) {
                "$host$path"
            } else if (host.contains("cdninstagram.com")) {
                "$host$path"
            } else if (host.contains("tiktok.com") || host.contains("byteoversea.com") || host.contains("ibytedtos.com")) {
                "$host$path"
            } else if (host.contains("vimeocdn.com")) {
                "$host$path"
            } else if (com.example.util.AbysDedicatedEngine.isAbysUrl(url)) {
                val abysFd = com.example.util.AbysQualityEngine.parseFromFdUrl(url)
                if (abysFd != null) {
                    "abys:${abysFd.hash}:${abysFd.resId}"
                } else {
                    "abys:${com.example.util.AbysDedicatedEngine.extractFilename(url)}"
                }
            } else {
                // Normaliza o caminho ignorando fragmentos de chunk (ex: chunk_1.ts, seg-1.m4s)
                val cleanPath = path
                    .replace(Regex("(/|_|-)seg(ment)?[_-]?[0-9]+.*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("(/|_|-)chunk[_-]?[0-9]+.*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("(/|_|-)frag[_-]?[0-9]+.*", RegexOption.IGNORE_CASE), "")

                // Coletar apenas parâmetros de query estáveis (ignorando tokens de range, sessão e expiração)
                val volatileParams = setOf(
                    "range", "bytestart", "byteend", "rn", "rbuf", "sq", "alr", "ump", "sgoap",
                    "expire", "expires", "expiry", "token", "sig", "signature", "key",
                    "t", "_", "timestamp", "ts", "session_id", "sid", "nonce", "random",
                    "cb", "cache_buster", "p", "seg"
                )

                val queryNames = uri.queryParameterNames.filter { !volatileParams.contains(it.lowercase()) }.sorted()
                if (queryNames.isEmpty()) {
                    "$host$cleanPath"
                } else {
                    val stableQuery = queryNames.joinToString("&") { key ->
                        "$key=${uri.getQueryParameter(key)}"
                    }
                    "$host$cleanPath?$stableQuery"
                }
            }
        } catch (e: Exception) {
            url
        }
    }

    /**
     * Fusão inteligente não-destrutiva de metadados entre dois registros de um mesmo stream
     * Preenche dados faltantes (thumbnail, duração, tamanho real do Content-Length, título limpo).
     */
    fun mergeVideoData(existing: ExtractedVideo, incoming: ExtractedVideo): ExtractedVideo {
        val mergedHeaders = existing.headers.toMutableMap()
        incoming.headers.forEach { (k, v) ->
            if (v.isNotBlank() && (!mergedHeaders.containsKey(k) || mergedHeaders[k].isNullOrBlank())) {
                mergedHeaders[k] = v
            }
        }

        val isExistingGeneric = existing.title.isBlank() ||
                existing.title == "Vídeo Extraído" ||
                existing.title.startsWith("Video_Stream_") ||
                existing.title.startsWith("Video_Extracted_") ||
                existing.title.startsWith("http")

        val isIncomingBetter = incoming.title.isNotBlank() &&
                incoming.title != "Vídeo Extraído" &&
                !incoming.title.startsWith("Video_") &&
                !incoming.title.startsWith("http")

        val resolvedTitle = if (isExistingGeneric && isIncomingBetter) incoming.title else existing.title

        val resolvedThumb = existing.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: incoming.thumbnailUrl?.takeIf { it.isNotBlank() }

        val resolvedSize = existing.size?.takeIf { it > 0 } ?: incoming.size
        val resolvedDuration = existing.duration?.takeIf { it > 0 } ?: incoming.duration

        val resolvedFormattedSize = when {
            existing.size != null && existing.size > 0 && existing.formattedSize?.startsWith("~") == false -> existing.formattedSize
            incoming.size != null && incoming.size > 0 && incoming.formattedSize?.startsWith("~") == false -> incoming.formattedSize
            existing.formattedSize != null && existing.formattedSize != "Tamanho sob consulta" -> existing.formattedSize
            else -> incoming.formattedSize ?: existing.formattedSize
        }

        val resolvedFormattedDuration = when {
            existing.duration != null && existing.duration > 0 && existing.formattedDuration != "Duração indisponível" -> existing.formattedDuration
            incoming.duration != null && incoming.duration > 0 && incoming.formattedDuration != "Duração indisponível" -> incoming.formattedDuration
            else -> existing.formattedDuration ?: incoming.formattedDuration
        }

        val resolvedQuality = existing.quality ?: incoming.quality
        val resolvedGroupId = existing.mediaGroupId ?: incoming.mediaGroupId ?: getMediaGroupKey(existing.url, resolvedTitle)
        val resolvedStreamKey = existing.streamKey ?: incoming.streamKey ?: getCanonicalKey(existing.url)

        val resolvedType = if (existing.type == VideoType.DIRECT && incoming.type != VideoType.DIRECT) {
            incoming.type
        } else {
            existing.type
        }

        val resolvedConfidence = when {
            existing.estimationConfidence == "Exacto" -> existing.estimationConfidence
            incoming.estimationConfidence == "Exacto" -> incoming.estimationConfidence
            existing.estimationConfidence == "Alta" -> existing.estimationConfidence
            incoming.estimationConfidence == "Alta" -> incoming.estimationConfidence
            else -> incoming.estimationConfidence ?: existing.estimationConfidence
        }

        val resolvedMethod = incoming.estimationMethod ?: existing.estimationMethod
        val resolvedNotes = incoming.estimationNotes ?: existing.estimationNotes

        return existing.copy(
            headers = mergedHeaders,
            title = resolvedTitle,
            type = resolvedType,
            size = resolvedSize,
            duration = resolvedDuration,
            formattedSize = resolvedFormattedSize,
            formattedDuration = resolvedFormattedDuration,
            thumbnailUrl = resolvedThumb,
            quality = resolvedQuality,
            mediaGroupId = resolvedGroupId,
            streamKey = resolvedStreamKey,
            estimationConfidence = resolvedConfidence,
            estimationMethod = resolvedMethod,
            estimationNotes = resolvedNotes
        )
    }
}

/**
 * Converte e organiza a lista bruta de streams em grupos de mídias estruturados (ExtractedMediaGroup),
 * eliminando repetições de HLS/DASH e agrupando resoluções (1080p, 720p, áudios) por vídeo.
 */
object MediaGroupHelper {
    fun buildMediaGroups(
        videos: List<ExtractedVideo>,
        pageTitle: String? = null,
        pageUrl: String? = null
    ): List<com.example.model.ExtractedMediaGroup> {
        if (videos.isEmpty()) return emptyList()

        val groupsMap = LinkedHashMap<String, MutableList<ExtractedVideo>>()
        for (v in videos) {
            val gid = v.mediaGroupId ?: MediaTitleHelper.getMediaGroupKey(v.url, v.title)
            groupsMap.getOrPut(gid) { mutableListOf() }.add(v)
        }

        // Consolidated group map merging duplicate records that belong to the same canonical stream/title
        val consolidatedGroups = LinkedHashMap<String, MutableList<ExtractedVideo>>()
        for ((_, groupVideos) in groupsMap) {
            val primary = groupVideos.firstOrNull() ?: continue
            val cleanTitle = MediaTitleHelper.cleanMediaTitle(primary.title, primary.url).lowercase().trim()
            val hasSpecificTitle = cleanTitle.isNotBlank() && cleanTitle != "vídeo extraído" &&
                    !cleanTitle.startsWith("video_") && !cleanTitle.startsWith("http")
            val groupKey = if (hasSpecificTitle) "title:$cleanTitle" else MediaTitleHelper.getMediaGroupKey(primary.url, primary.title)
            consolidatedGroups.getOrPut(groupKey) { mutableListOf() }.addAll(groupVideos)
        }

        val result = mutableListOf<com.example.model.ExtractedMediaGroup>()

        for ((groupId, groupVideos) in consolidatedGroups) {
            val groupTitle = groupVideos.map { it.title }
                .firstOrNull { it.isNotBlank() && it != "Vídeo Extraído" && !it.startsWith("Video_") && !it.startsWith("http") }
                ?: pageTitle?.takeIf { it.isNotBlank() && it != "Nova guia" && it != "about:blank" }
                ?: groupVideos.firstOrNull()?.title
                ?: "Vídeo Detectado"

            val groupThumbnail = groupVideos.firstNotNullOfOrNull { it.thumbnailUrl?.takeIf { t -> t.isNotBlank() } }
            val groupDuration = groupVideos.firstNotNullOfOrNull { it.duration?.takeIf { d -> d > 0 } }
            val groupFormattedDuration = groupVideos.firstNotNullOfOrNull {
                it.formattedDuration?.takeIf { f -> f.isNotBlank() && f != "Duração indisponível" }
            }

            val videoOptions = mutableListOf<com.example.model.MediaStreamOption>()
            val audioOptions = mutableListOf<com.example.model.MediaStreamOption>()
            val seenResolutionKeys = mutableSetOf<String>()

            for (video in groupVideos) {
                val isAudio = video.type == VideoType.AUDIO
                val rawQuality = video.quality ?: VideoQualityHelper.detectQualityFromUrl(video.url)
                val formatBadge = when (video.type) {
                    VideoType.ABYS -> "ABYS"
                    VideoType.HLS -> "HLS"
                    VideoType.DASH -> "DASH"
                    VideoType.AUDIO -> if (video.url.contains(".mp3")) "MP3" else "M4A"
                    VideoType.DIRECT -> if (video.url.contains(".webm")) "WEBM" else "MP4"
                }

                val resolutionLabel = if (isAudio) {
                    "Áudio"
                } else {
                    extractResolutionLabel(rawQuality, video.url)
                }

                val qualityLabel = if (isAudio) {
                    if (rawQuality != null && !rawQuality.contains("Áudio", ignoreCase = true)) {
                        "Áudio $rawQuality ($formatBadge)"
                    } else {
                        "Áudio $formatBadge"
                    }
                } else {
                    if (rawQuality != null && (rawQuality.contains("HD", ignoreCase = true) || rawQuality.contains("SD", ignoreCase = true) || rawQuality.contains("p", ignoreCase = true))) {
                        rawQuality
                    } else if (resolutionLabel != "Vídeo") {
                        resolutionLabel
                    } else {
                        "Vídeo ($formatBadge)"
                    }
                }

                val dedupKey = if (isAudio) {
                    "audio:${formatBadge.lowercase()}"
                } else {
                    "video:${resolutionLabel.lowercase()}:${formatBadge.lowercase()}"
                }

                val opt = com.example.model.MediaStreamOption(
                    rawVideo = video,
                    qualityLabel = qualityLabel,
                    resolutionLabel = resolutionLabel,
                    formatBadge = formatBadge,
                    sizeBytes = video.size,
                    formattedSize = video.formattedSize ?: "Tamanho sob consulta",
                    isAudioOnly = isAudio,
                    isAdaptiveVideoOnly = video.url.contains("googlevideo.com") && (video.url.contains("mime=video") || (video.quality != null && !isAudio)),
                    estimationConfidence = video.estimationConfidence,
                    estimationMethod = video.estimationMethod,
                    estimationNotes = video.estimationNotes
                )

                if (seenResolutionKeys.add(dedupKey)) {
                    if (isAudio) {
                        audioOptions.add(opt)
                    } else {
                        videoOptions.add(opt)
                    }
                } else {
                    val targetList = if (isAudio) audioOptions else videoOptions
                    val idx = targetList.indexOfFirst {
                        (if (isAudio) "audio:${it.formatBadge.lowercase()}"
                        else "video:${it.resolutionLabel.lowercase()}:${it.formatBadge.lowercase()}") == dedupKey
                    }
                    if (idx != -1) {
                        val existingOpt = targetList[idx]
                        val incomingSize = video.size ?: 0L
                        val existingSize = existingOpt.sizeBytes ?: 0L
                        // Upgrade if incoming has size while existing doesn't, or if incoming is larger than 20MB chunk
                        if ((existingSize <= 0 && incomingSize > 0) || (existingSize <= 20971520L && incomingSize > 20971520L)) {
                            targetList[idx] = existingOpt.copy(
                                rawVideo = video,
                                sizeBytes = video.size,
                                formattedSize = video.formattedSize ?: existingOpt.formattedSize,
                                estimationConfidence = video.estimationConfidence ?: existingOpt.estimationConfidence,
                                estimationMethod = video.estimationMethod ?: existingOpt.estimationMethod,
                                estimationNotes = video.estimationNotes ?: existingOpt.estimationNotes
                            )
                        }
                    }
                }
            }

            val rawSortedVideoOptions = videoOptions.sortedByDescending { getResolutionWeight(it.resolutionLabel) }
            val sortedAudioOptions = audioOptions.sortedByDescending { it.sizeBytes ?: 0L }

            // Se houver resoluções específicas identificadas (ex: 1080p, 720p, 480p),
            // ocultar a opção genérica redundante "HLS Adaptativo" / "DASH Adaptativo"
            val hasSpecificResolutions = rawSortedVideoOptions.any { 
                it.resolutionLabel != "Vídeo" && !it.qualityLabel.contains("Adaptativo", ignoreCase = true) 
            }

            val sortedVideoOptions = if (hasSpecificResolutions) {
                rawSortedVideoOptions.filter { opt ->
                    !opt.qualityLabel.contains("Adaptativo", ignoreCase = true) &&
                    opt.resolutionLabel != "Vídeo"
                }.ifEmpty { rawSortedVideoOptions }
            } else {
                rawSortedVideoOptions
            }

            val allStreams = sortedVideoOptions + sortedAudioOptions

            val preferredDefault = sortedVideoOptions.firstOrNull { it.resolutionLabel == "1080p" || it.resolutionLabel == "1080p Full HD" }
                ?: sortedVideoOptions.firstOrNull { it.resolutionLabel == "720p" || it.resolutionLabel == "720p HD" }
                ?: sortedVideoOptions.firstOrNull()
                ?: allStreams.firstOrNull()

            result.add(
                com.example.model.ExtractedMediaGroup(
                    id = groupId,
                    title = groupTitle,
                    pageTitle = pageTitle,
                    pageUrl = pageUrl,
                    thumbnailUrl = groupThumbnail,
                    durationMs = groupDuration,
                    formattedDuration = groupFormattedDuration,
                    streams = allStreams,
                    videoStreams = sortedVideoOptions,
                    audioStreams = sortedAudioOptions,
                    selectedStreamId = preferredDefault?.id
                )
            )
        }

        return result
    }

    private fun extractResolutionLabel(quality: String?, url: String): String {
        val q = (quality ?: VideoQualityHelper.detectQualityFromUrl(url) ?: "").lowercase()
        return when {
            q.contains("2160") || q.contains("4k") -> "4K 2160p"
            q.contains("1440") || q.contains("2k") -> "2K 1440p"
            q.contains("1080") -> "1080p Full HD"
            q.contains("720") -> "720p HD"
            q.contains("480") -> "480p SD"
            q.contains("360") -> "360p"
            q.contains("240") -> "240p"
            q.contains("144") -> "144p"
            else -> "Vídeo"
        }
    }

    private fun getResolutionWeight(resLabel: String): Int {
        return when {
            resLabel.contains("2160") || resLabel.contains("4K") -> 2160
            resLabel.contains("1440") || resLabel.contains("2K") -> 1440
            resLabel.contains("1080") -> 1080
            resLabel.contains("720") -> 720
            resLabel.contains("480") -> 480
            resLabel.contains("360") -> 360
            resLabel.contains("240") -> 240
            resLabel.contains("144") -> 144
            else -> 100
        }
    }
}

/**
 * Utilitário para reconhecimento de qualidade e resolução de vídeo (incluindo 480p, 720p, 1080p, etc.)
 * a partir de itags do YouTube, padrões de URL e dimensões de vídeo.
 */
object VideoQualityHelper {
    fun detectQualityFromUrl(url: String): String? {
        // 0. ABYS .fd URLs (e.g. <hash>.<size>.<res_id>.fd)
        val abysFd = com.example.util.AbysQualityEngine.parseFromFdUrl(url)
        if (abysFd != null) {
            return abysFd.resolutionLabel
        }

        val lower = url.lowercase()

        // 1. YouTube itags
        if (lower.contains("googlevideo.com/videoplayback")) {
            val uri = try { android.net.Uri.parse(url) } catch (e: Exception) { null }
            val itag = uri?.getQueryParameter("itag")
            return when (itag) {
                "37", "137", "248", "399" -> "1080p Full HD"
                "22", "136", "247", "398" -> "720p HD"
                "135", "244", "397" -> "480p"
                "18", "134", "243", "396" -> "360p"
                "133", "242", "395" -> "240p"
                "160", "278", "394" -> "144p"
                "313", "401" -> "4K 2160p"
                "271", "400" -> "1440p 2K"
                "140" -> "Áudio M4A (128 kbps)"
                "251" -> "Áudio Opus (160 kbps)"
                "250" -> "Áudio Opus (70 kbps)"
                "249" -> "Áudio Opus (50 kbps)"
                "139" -> "Áudio M4A (48 kbps)"
                else -> null
            }
        }

        // 2. Padrões de resolução em URLs, caminhos e nomes de arquivos
        return when {
            lower.contains("2160p") || lower.contains("4k") || lower.contains("3840x2160") -> "4K 2160p"
            lower.contains("1440p") || lower.contains("2560x1440") -> "1440p 2K"
            lower.contains("1080p") || lower.contains("1920x1080") || lower.contains("fhd") -> "1080p Full HD"
            lower.contains("720p") || lower.contains("1280x720") || lower.contains("hd") -> "720p HD"
            lower.contains("480p") || lower.contains("854x480") || lower.contains("848x480") || lower.contains("640x480") ||
                    lower.contains("_480.") || lower.contains("-480.") || lower.contains("/480/") || lower.contains("480_") -> "480p"
            lower.contains("360p") || lower.contains("640x360") || lower.contains("_360.") || lower.contains("-360.") || lower.contains("/360/") -> "360p"
            lower.contains("240p") || lower.contains("426x240") || lower.contains("_240.") -> "240p"
            lower.contains("144p") || lower.contains("256x144") -> "144p"
            else -> null
        }
    }

    fun getQualityFromResolution(width: Int, height: Int): String {
        val h = minOf(width, height)
        return when {
            h >= 2160 -> "4K 2160p"
            h >= 1440 -> "1440p 2K"
            h >= 1080 -> "1080p Full HD"
            h >= 720 -> "720p HD"
            h >= 480 -> "480p"
            h >= 360 -> "360p"
            h >= 240 -> "240p"
            h >= 144 -> "144p"
            else -> "${h}p"
        }
    }
}

/**
 * Contract for extensible or site-specific video scrapers and extractors.
 */
interface VideoExtractor {
    fun extract(pageUrl: String, htmlSource: String, callback: (ExtractedVideo?) -> Unit)
}

/**
 * Manager class orchestrating web scraping and networking sniffers
 * to detect high-quality streams for background download workflows.
 */
class VideoExtractionManager(
    private val onVideoExtractedListener: (String, ExtractedVideo) -> Unit
) {
    private val TAG = "VideoExtractionMgr"

    /**
     * Bridges JavaScript DOM scraping calls from the WebView container.
     */
    inner class WebAppInterface(private val tabId: String) {
        @JavascriptInterface
        fun onVideoFound(url: String, title: String, headersJson: String?) {
            processExtractedVideo(url, title, headersJson, null, null, null, null)
        }

        @JavascriptInterface
        fun onVideoFound(url: String, title: String, headersJson: String?, posterUrl: String?) {
            processExtractedVideo(url, title, headersJson, posterUrl, null, null, null)
        }

        @JavascriptInterface
        fun onVideoFound(
            url: String,
            title: String,
            headersJson: String?,
            posterUrl: String?,
            durationSeconds: Double,
            videoWidth: Int,
            videoHeight: Int
        ) {
            val durationMs = if (durationSeconds > 0) (durationSeconds * 1000).toLong() else null
            val quality = if (videoWidth > 0 && videoHeight > 0) {
                VideoQualityHelper.getQualityFromResolution(videoWidth, videoHeight)
            } else null
            processExtractedVideo(url, title, headersJson, posterUrl, durationMs, quality, null)
        }

        @JavascriptInterface
        fun onVideoFound(
            url: String,
            title: String,
            headersJson: String?,
            posterUrl: String?,
            durationSeconds: Double,
            videoWidth: Int,
            videoHeight: Int,
            mimeType: String?
        ) {
            val durationMs = if (durationSeconds > 0) (durationSeconds * 1000).toLong() else null
            val quality = if (videoWidth > 0 && videoHeight > 0) {
                VideoQualityHelper.getQualityFromResolution(videoWidth, videoHeight)
            } else null
            processExtractedVideo(url, title, headersJson, posterUrl, durationMs, quality, null)
        }

        /**
         * Gancho em tempo real para eventos nativos de mídia do DOM (play, loadedmetadata, canplay).
         * Garante captura imediata quando o utilizador toca em "Play" ou quando metadados são resolvidos.
         */
        @JavascriptInterface
        fun onMediaEvent(
            eventType: String,
            url: String,
            durationSeconds: Double,
            videoWidth: Int,
            videoHeight: Int,
            posterUrl: String?
        ) {
            val durationMs = if (durationSeconds > 0) (durationSeconds * 1000).toLong() else null
            val quality = if (videoWidth > 0 && videoHeight > 0) {
                VideoQualityHelper.getQualityFromResolution(videoWidth, videoHeight)
            } else null
            processExtractedVideo(url, "", null, posterUrl, durationMs, quality, null)
        }

        @JavascriptInterface
        fun onAbysMediaBundleFound(mediaJson: String, pageTitle: String?, posterUrl: String?) {
            onAbysMediaBundleFound(mediaJson, pageTitle, posterUrl, null, null)
        }

        @JavascriptInterface
        fun onAbysMediaBundleFound(
            mediaJson: String,
            pageTitle: String?,
            posterUrl: String?,
            pageUrl: String?,
            observedUrlsJson: String?
        ) {
            try {
                val bundle = com.example.util.AbysQualityEngine.parseMediaJson(mediaJson, pageTitle)
                if (bundle != null && bundle.streams.isNotEmpty()) {
                    val observedUrls = mutableListOf<String>()
                    if (!observedUrlsJson.isNullOrBlank()) {
                        try {
                            val arr = org.json.JSONArray(observedUrlsJson)
                            for (i in 0 until arr.length()) {
                                val u = arr.optString(i)
                                if (u.isNotBlank()) observedUrls.add(u)
                            }
                        } catch (_: Exception) {}
                    }

                    val baseHeaders = mutableMapOf<String, String>(
                        "Referer" to com.example.util.AbysDedicatedEngine.DEFAULT_REFERER
                    )
                    if (!pageUrl.isNullOrBlank()) {
                        val uri = try { java.net.URI(pageUrl) } catch (_: Throwable) { null }
                        val host = uri?.host
                        if (!host.isNullOrBlank()) {
                            baseHeaders["Origin"] = "${uri.scheme ?: "https"}://$host"
                        }
                    }

                    val enrichedBundle = if (observedUrls.isNotEmpty()) {
                        bundle.copy(
                            streams = bundle.streams.map { s ->
                                s.copy(observedPartUrls = (s.observedPartUrls + observedUrls).distinct())
                            }
                        )
                    } else bundle

                    val videos = com.example.util.AbysQualityEngine.convertToExtractedVideos(
                        bundle = enrichedBundle,
                        baseHeaders = baseHeaders,
                        pageTitle = pageTitle
                    )
                    for (v in videos) {
                        val finalV = if (!posterUrl.isNullOrBlank()) v.copy(thumbnailUrl = posterUrl) else v
                        onVideoExtractedListener(tabId, finalV)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse ABYS media bundle from JS: ${e.message}", e)
            }
        }

        /**
         * Gancho directo para detecção do SW-Oracle do ABYS dentro do WebView.
         * Recebe a URL virtual (/mp4/..., /part/...), tamanho total de 467MB, metadados e espelhos.
         */
        @JavascriptInterface
        fun onAbysVirtualUrlDetected(
            virtualUrl: String,
            totalBytes: Long,
            resId: Int,
            slug: String?,
            md5Id: String?,
            pageTitle: String?,
            posterUrl: String?,
            pageUrl: String?,
            observedPartsJson: String?
        ) {
            try {
                if (virtualUrl.isBlank()) return
                val observedUrls = mutableListOf<String>()
                if (!observedPartsJson.isNullOrBlank()) {
                    try {
                        val arr = org.json.JSONArray(observedPartsJson)
                        for (i in 0 until arr.length()) {
                            val u = arr.optString(i)
                            if (u.isNotBlank()) observedUrls.add(u)
                        }
                    } catch (_: Exception) {}
                }

                val headers = mutableMapOf<String, String>(
                    "X-Abys-Virtual-Url" to virtualUrl,
                    "X-Abys-ResId" to resId.toString()
                )
                if (totalBytes > 0L) headers["X-Abys-Size"] = totalBytes.toString()
                if (!slug.isNullOrBlank()) headers["X-Abys-Slug"] = slug
                if (!md5Id.isNullOrBlank()) headers["X-Abys-Md5Id"] = md5Id
                if (observedUrls.isNotEmpty()) headers["X-Abys-Parts"] = observedUrls.joinToString(";")

                if (!pageUrl.isNullOrBlank()) {
                    val uri = try { java.net.URI(pageUrl) } catch (_: Throwable) { null }
                    val host = uri?.host
                    if (!host.isNullOrBlank()) {
                        headers["Referer"] = pageUrl
                        headers["Origin"] = "${uri.scheme ?: "https"}://$host"
                    }
                }
                if (!headers.containsKey("Referer")) {
                    val host = try { java.net.URI(virtualUrl).host } catch (_: Throwable) { null }
                    headers["Referer"] = if (!host.isNullOrBlank()) "https://$host/" else com.example.util.AbysDedicatedEngine.DEFAULT_REFERER
                }

                val title = com.example.extractor.MediaTitleHelper.cleanMediaTitle(pageTitle, virtualUrl)
                val qualityLabel = com.example.util.AbysQualityEngine.getResolutionLabel(resId)
                val streamKey = "abys:${slug ?: md5Id ?: virtualUrl.hashCode()}:$resId"
                val mediaGroupId = "abys:${slug ?: md5Id ?: title.hashCode()}"

                val video = ExtractedVideo(
                    url = virtualUrl,
                    headers = headers,
                    title = "$title ($qualityLabel)",
                    type = VideoType.ABYS,
                    size = if (totalBytes > 0L) totalBytes else null,
                    formattedSize = if (totalBytes > 0L) com.example.util.AbysQualityEngine.formatBytes(totalBytes) else null,
                    thumbnailUrl = posterUrl?.ifBlank { null },
                    quality = qualityLabel,
                    mediaGroupId = mediaGroupId,
                    streamKey = streamKey,
                    estimationConfidence = "Exacto (SW-Oracle)",
                    estimationMethod = "SW-Oracle Session",
                    estimationNotes = "Fluxo Completo via Service Worker do ABYS"
                )
                onVideoExtractedListener(tabId, video)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process ABYS virtual URL from SW-oracle: ${e.message}", e)
            }
        }

        private fun processExtractedVideo(
            url: String,
            title: String,
            headersJson: String?,
            posterUrl: String?,
            probedDurationMs: Long?,
            explicitQuality: String?,
            explicitSize: Long?
        ) {
            Log.d(TAG, "JS Bridge detected video url='$url' on tabId='$tabId' with title='$title' poster='$posterUrl' dur=$probedDurationMs")
            if (url.isBlank()) return

            val lowerUrl = url.lowercase()

            // Reject if it's a web page, telemetry, script, style, image, or ad
            if (lowerUrl.contains("cdn-cgi/") ||
                lowerUrl.contains("/rum") ||
                lowerUrl.contains("analytics") ||
                lowerUrl.contains("beacon") ||
                lowerUrl.contains("telemetry") ||
                lowerUrl.contains("/serie/") ||
                lowerUrl.contains("/filme/") ||
                lowerUrl.contains("/movie/") ||
                lowerUrl.contains("/embed/") ||
                lowerUrl.endsWith(".html") ||
                lowerUrl.endsWith(".htm") ||
                lowerUrl.endsWith(".php") ||
                lowerUrl.endsWith(".js") ||
                lowerUrl.endsWith(".css") ||
                lowerUrl.endsWith(".jpg") ||
                lowerUrl.endsWith(".jpeg") ||
                lowerUrl.endsWith(".png") ||
                lowerUrl.endsWith(".webp")
            ) {
                return
            }

            val headers = mutableMapOf<String, String>()
            if (!headersJson.isNullOrBlank()) {
                try {
                    val json = JSONObject(headersJson)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        headers[key] = json.getString(key)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse JS headers mapping: ${e.message}", e)
                }
            }

            // Fallback headers if not passed
            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = "Mozilla/5.0 (Linux; Android 10)"
            }

            if (com.example.util.AbysDedicatedEngine.isAbysUrl(url)) {
                if (!headers.containsKey("Referer")) headers["Referer"] = com.example.util.AbysDedicatedEngine.DEFAULT_REFERER
            }

            val type = when {
                com.example.util.AbysDedicatedEngine.isAbysUrl(url) -> VideoType.ABYS
                url.contains(".m3u8") -> VideoType.HLS
                url.contains(".mpd") -> VideoType.DASH
                url.contains(".mp3") || url.contains(".m4a") || url.contains(".aac") || url.contains(".ogg") ||
                    url.contains(".wav") || url.contains("mime=audio") || url.contains("itag=140") ||
                    url.contains("itag=251") || url.contains("itag=250") || url.contains("itag=249") || url.contains("itag=139") -> VideoType.AUDIO
                else -> VideoType.DIRECT
            }

            // For DIRECT type, ensure it actually represents a media file
            if (type == VideoType.DIRECT) {
                val isDirectMedia = lowerUrl.contains(".mp4") ||
                        lowerUrl.contains(".webm") ||
                        lowerUrl.contains(".m4v") ||
                        lowerUrl.contains(".mov") ||
                        lowerUrl.contains(".3gp") ||
                        lowerUrl.contains("googlevideo.com") ||
                        lowerUrl.contains("mime=video") ||
                        lowerUrl.contains("format=mp4") ||
                        lowerUrl.contains("video/mp4")
                if (!isDirectMedia) return
            }

            val cleanedTitle = MediaTitleHelper.cleanMediaTitle(title, url)
            val abysFd = if (type == VideoType.ABYS) com.example.util.AbysQualityEngine.parseFromFdUrl(url) else null
            val detectedQuality = explicitQuality ?: abysFd?.resolutionLabel ?: VideoQualityHelper.detectQualityFromUrl(url)
            val resolvedSize = explicitSize ?: abysFd?.sizeBytes
            val resolvedFormattedSize = abysFd?.formattedSize

            val formattedDur = if (probedDurationMs != null && probedDurationMs > 0) {
                val totalSeconds = probedDurationMs / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                if (hours > 0) {
                    String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
                }
            } else null

            val streamKey = MediaTitleHelper.getCanonicalKey(url)
            val mediaGroupId = MediaTitleHelper.getMediaGroupKey(url, cleanedTitle)

            val video = ExtractedVideo(
                url = url,
                headers = headers,
                title = cleanedTitle,
                type = type,
                size = resolvedSize,
                formattedSize = resolvedFormattedSize,
                duration = probedDurationMs,
                formattedDuration = formattedDur,
                thumbnailUrl = posterUrl?.ifBlank { null },
                quality = detectedQuality,
                mediaGroupId = mediaGroupId,
                streamKey = streamKey
            )
            onVideoExtractedListener(tabId, video)
        }
    }

    /**
     * Handles background traffic intercepts from Network (e.g., .m3u8, .mpd streaming manifests).
     */
    fun onNetworkVideoDetected(tabId: String, url: String, requestHeaders: Map<String, String>, pageTitle: String?) {
        Log.d(TAG, "Network Intercept detected video url='$url' on tabId='$tabId'")
        
        // Remove range and chunk-varying properties for streaming video ONLY where appropriate (e.g. googlevideo playback range chunking)
        // Never strip authentication/signature parameters (bytestart, token, sig, cpn) required by CDNs (YouTube, Facebook, Instagram, TikTok, etc.)
        var cleanUrl = url
        var detectedThumbnail: String? = null

        if (cleanUrl.contains("googlevideo.com/videoplayback")) {
            cleanUrl = cleanUrl
                .replace(Regex("&range=[0-9]+-[0-9]+"), "")
                .replace(Regex("&rn=[0-9]+"), "")
                .replace(Regex("&rbuf=[0-9]+"), "")
                .replace(Regex("&sq=[0-9]+"), "")
                .replace(Regex("&alr=[^&]*"), "")
                .replace(Regex("&ump=[^&]*"), "")
                .replace(Regex("&sgoap=[^&]*"), "")
        }

        val enrichedHeaders = requestHeaders.toMutableMap()
        if (com.example.util.AbysDedicatedEngine.isAbysUrl(cleanUrl)) {
            if (!enrichedHeaders.containsKey("Referer")) enrichedHeaders["Referer"] = com.example.util.AbysDedicatedEngine.DEFAULT_REFERER
        }

        val type = when {
            com.example.util.AbysDedicatedEngine.isAbysUrl(cleanUrl) -> VideoType.ABYS
            cleanUrl.contains(".m3u8") -> VideoType.HLS
            cleanUrl.contains(".mpd") -> VideoType.DASH
            cleanUrl.contains(".mp3") || cleanUrl.contains(".m4a") || cleanUrl.contains(".aac") || cleanUrl.contains(".ogg") ||
                cleanUrl.contains(".wav") || cleanUrl.contains("mime=audio") || cleanUrl.contains("itag=140") ||
                cleanUrl.contains("itag=251") || cleanUrl.contains("itag=250") || cleanUrl.contains("itag=249") || cleanUrl.contains("itag=139") -> VideoType.AUDIO
            else -> VideoType.DIRECT
        }

        val abysFd = if (type == VideoType.ABYS) com.example.util.AbysQualityEngine.parseFromFdUrl(cleanUrl) else null
        val qualityLabel = abysFd?.resolutionLabel ?: VideoQualityHelper.detectQualityFromUrl(cleanUrl)
        val extractedSize = abysFd?.sizeBytes
        val formattedSize = abysFd?.formattedSize
        val cleanedTitle = MediaTitleHelper.cleanMediaTitle(pageTitle, cleanUrl)

        val streamKey = MediaTitleHelper.getCanonicalKey(cleanUrl)
        val mediaGroupId = MediaTitleHelper.getMediaGroupKey(cleanUrl, cleanedTitle)

        val video = ExtractedVideo(
            url = cleanUrl,
            headers = enrichedHeaders,
            title = cleanedTitle,
            type = type,
            size = extractedSize,
            formattedSize = formattedSize,
            thumbnailUrl = detectedThumbnail,
            quality = qualityLabel,
            mediaGroupId = mediaGroupId,
            streamKey = streamKey
        )
        onVideoExtractedListener(tabId, video)
    }

    /**
     * Limpa o estado e cache de extração para a aba informada ao navegar para uma nova URL.
     */
    fun clearTab(tabId: String) {
        Log.d(TAG, "Limpando estado de extração multimídia para tabId='$tabId'")
    }
}
