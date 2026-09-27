package com.example.util

import com.example.extractor.ExtractedVideo
import com.example.extractor.VideoType
import com.example.model.ExtractedMediaGroup
import com.example.model.MediaStreamOption
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Data representation of resolution information extracted from an ABYS .fd URL.
 */
data class AbysFdInfo(
    val hash: String,
    val sizeBytes: Long,
    val resId: Int,
    val resolutionLabel: String,
    val formattedSize: String
)

/**
 * Represents a single video quality / resolution variant from an ABYS media payload.
 */
data class AbysQualityStream(
    val resId: Int,
    val label: String,
    val codec: String,
    val sizeBytes: Long,
    val formattedSize: String,
    val estimatedBitrateMbps: Double? = null,
    val partSize: Long? = null,
    val directFdUrl: String,
    val slug: String? = null,
    val md5Id: String? = null,
    val userId: String? = null,
    val mirrors: List<String> = emptyList(),
    val observedPartUrls: List<String> = emptyList()
)

/**
 * Complete media bundle grouping all detected resolutions, mirrors and first-data parts of an ABYS video.
 */
data class AbysMediaBundle(
    val title: String? = null,
    val slug: String? = null,
    val md5Id: String? = null,
    val userId: String? = null,
    val durationSeconds: Double? = null,
    val streams: List<AbysQualityStream> = emptyList()
)

/**
 * Native engine for recognizing and structuring ABYS (abyss.to / abysscdn.com) resolutions,
 * codecs, stream sizes and mirrors, inspired by abys_qualities.js.
 */
object AbysQualityEngine {

    /**
     * Map of ABYS internal resolution IDs (res_id) to standardized quality labels.
     */
    val RES_NAMES: Map<Int, String> = mapOf(
        0 to "240p",
        1 to "240p",
        2 to "360p",
        3 to "480p",
        4 to "720p HD",
        5 to "1080p Full HD",
        6 to "1440p 2K",
        7 to "4K 2160p"
    )

    private val FD_URL_REGEX = Regex("""([a-fA-F0-9]{20,40})\.(\d+)\.(\d+)\.fd""", RegexOption.IGNORE_CASE)

    fun getResolutionLabel(resId: Int): String {
        return RES_NAMES[resId] ?: "res$resId"
    }

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return "Tamanho sob consulta"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1000.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    fun calculateBitrateMbps(bytes: Long?, durationSecs: Double?): Double? {
        if (bytes == null || bytes <= 0 || durationSecs == null || durationSecs <= 0.0) return null
        val bits = bytes * 8.0
        val mbps = (bits / durationSecs) / 1_000_000.0
        return (mbps * 100.0).roundToInt() / 100.0
    }

    /**
     * Parses resolution and total byte size directly from an ABYS .fd URL filename.
     * Example: "e63a6e9602c10ab2b0527e9d37392.232643771.4.fd"
     * -> res_id: 4 ("720p HD"), sizeBytes: 232643771 ("221.9 MB").
     */
    fun parseFromFdUrl(url: String): AbysFdInfo? {
        val match = FD_URL_REGEX.find(url) ?: return null
        val hash = match.groupValues[1]
        val sizeBytes = match.groupValues[2].toLongOrNull() ?: return null
        val resId = match.groupValues[3].toIntOrNull() ?: return null
        val label = getResolutionLabel(resId)
        val formatted = formatBytes(sizeBytes)
        return AbysFdInfo(
            hash = hash,
            sizeBytes = sizeBytes,
            resId = resId,
            resolutionLabel = label,
            formattedSize = formatted
        )
    }

    /**
     * Parses the media JSON payload returned or used by the ABYS web player.
     * Supports formats:
     * - {"mp4": {"sources": [...], "fristDatas": [...]}}
     * - {"data": {"mp4": {"sources": [...], "fristDatas": [...]}}}
     * - {"sources": [...], "fristDatas": [...]}
     */
    fun parseMediaJson(
        jsonStr: String,
        pageTitle: String? = null,
        explicitDurationSecs: Double? = null
    ): AbysMediaBundle? {
        try {
            val root = JSONObject(jsonStr.trim())
            val mp4Obj = when {
                root.has("mp4") -> root.optJSONObject("mp4")
                root.has("data") && root.optJSONObject("data")?.has("mp4") == true ->
                    root.optJSONObject("data")?.optJSONObject("mp4")
                root.has("sources") -> root
                else -> null
            } ?: return null

            val sourcesArr = mp4Obj.optJSONArray("sources") ?: return null
            val fristArr = mp4Obj.optJSONArray("fristDatas")
                ?: mp4Obj.optJSONArray("fristdata")
                ?: mp4Obj.optJSONArray("firstDatas")

            val durationSecs = explicitDurationSecs
                ?: mp4Obj.optDouble("duration", 0.0).takeIf { it > 0.0 }
                ?: root.optDouble("duration", 0.0).takeIf { it > 0.0 }

            val explicitSlug = root.optString("slug").takeIf { it.isNotBlank() }
                ?: root.optString("v").takeIf { it.isNotBlank() }
                ?: root.optString("id").takeIf { it.isNotBlank() }
                ?: mp4Obj.optString("slug").takeIf { it.isNotBlank() }
                ?: mp4Obj.optString("v").takeIf { it.isNotBlank() }

            val explicitMd5Id = root.optString("md5_id").takeIf { it.isNotBlank() }
                ?: root.optString("md5").takeIf { it.isNotBlank() }
                ?: mp4Obj.optString("md5_id").takeIf { it.isNotBlank() }
                ?: mp4Obj.optString("md5").takeIf { it.isNotBlank() }

            val explicitUserId = root.optString("user_id").takeIf { it.isNotBlank() }
                ?: mp4Obj.optString("user_id").takeIf { it.isNotBlank() }

            // Map fristDatas by key: "$res_id/$codec/$size" or "$res_id/$size"
            val fristByKey = mutableMapOf<String, JSONObject>()
            if (fristArr != null) {
                for (i in 0 until fristArr.length()) {
                    val f = fristArr.optJSONObject(i) ?: continue
                    val rId = f.optInt("res_id", -1)
                    val c = f.optString("codec", "")
                    val s = f.optLong("size", 0L)
                    if (rId >= 0) {
                        fristByKey["$rId/$c/$s"] = f
                        fristByKey["$rId/$s"] = f
                        fristByKey["$rId"] = f
                    }
                }
            }

            val qualityStreams = mutableListOf<AbysQualityStream>()
            val seenKeys = mutableSetOf<String>()

            for (i in 0 until sourcesArr.length()) {
                val s = sourcesArr.optJSONObject(i) ?: continue
                val resId = s.optInt("res_id", -1)
                if (resId < 0) continue

                val rawLabel = s.optString("label").takeIf { it.isNotBlank() }
                val label = rawLabel ?: getResolutionLabel(resId)
                val codec = s.optString("codec", "avc1")
                val size = s.optLong("size", 0L)
                val sub = s.optString("sub", "").takeIf { it.isNotBlank() }

                val directStreamUrl = s.optString("src").takeIf { it.isNotBlank() }
                    ?: s.optString("url").takeIf { it.isNotBlank() }
                    ?: s.optString("file").takeIf { it.isNotBlank() }

                val fristMatch = fristByKey["$resId/$codec/$size"]
                    ?: fristByKey["$resId/$size"]
                    ?: fristByKey["$resId"]

                val url = directStreamUrl
                    ?: fristMatch?.optString("url")?.takeIf { it.isNotBlank() }
                    ?: continue
                val partSize = fristMatch?.optLong("partSize", 0L)?.takeIf { it > 0L }

                val dedupKey = "$resId/$label/$size"
                if (!seenKeys.add(dedupKey)) continue

                val mirrors = mutableListOf<String>()
                if (sub != null) {
                    mirrors.add("$sub.sssrr.org")
                    mirrors.add("abysscdn.com")
                }

                qualityStreams.add(
                    AbysQualityStream(
                        resId = resId,
                        label = label,
                        codec = codec,
                        sizeBytes = size,
                        formattedSize = formatBytes(size),
                        estimatedBitrateMbps = calculateBitrateMbps(size, durationSecs),
                        partSize = partSize,
                        directFdUrl = url,
                        slug = explicitSlug,
                        md5Id = explicitMd5Id,
                        userId = explicitUserId,
                        mirrors = mirrors
                    )
                )
            }

            if (qualityStreams.isEmpty()) return null

            // Sort by resolution descending (highest resolution first)
            qualityStreams.sortByDescending { it.resId }

            return AbysMediaBundle(
                title = pageTitle,
                slug = explicitSlug,
                md5Id = explicitMd5Id,
                userId = explicitUserId,
                durationSeconds = durationSecs,
                streams = qualityStreams
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Converts an [AbysMediaBundle] into a list of [ExtractedVideo] items for the Focus browser pipeline.
     */
    fun convertToExtractedVideos(
        bundle: AbysMediaBundle,
        baseHeaders: Map<String, String> = emptyMap(),
        pageTitle: String? = null
    ): List<ExtractedVideo> {
        val title = bundle.title ?: pageTitle ?: "Vídeo ABYS"
        val durationMs = bundle.durationSeconds?.let { (it * 1000).toLong() }
        val durationFormatted = durationMs?.let { ms ->
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }

        val firstFd = bundle.streams.firstOrNull()?.directFdUrl
        val groupId = if (!firstFd.isNullOrBlank()) {
            com.example.extractor.MediaTitleHelper.getMediaGroupKey(firstFd, title)
        } else {
            "abys_group_${AbysDedicatedEngine.md5Hex(title)}"
        }

        return bundle.streams.map { s ->
            val bitrateTag = s.estimatedBitrateMbps?.let { " • $it Mbps" } ?: ""
            val streamKey = com.example.extractor.MediaTitleHelper.getCanonicalKey(s.directFdUrl)

            val enrichedHeaders = baseHeaders.toMutableMap()
            if (!enrichedHeaders.containsKey("Referer")) enrichedHeaders["Referer"] = AbysDedicatedEngine.DEFAULT_REFERER
            if (!s.slug.isNullOrBlank()) enrichedHeaders["X-Abys-Slug"] = s.slug
            if (!s.md5Id.isNullOrBlank()) enrichedHeaders["X-Abys-Md5Id"] = s.md5Id
            if (!s.userId.isNullOrBlank()) enrichedHeaders["X-Abys-UserId"] = s.userId
            enrichedHeaders["X-Abys-ResId"] = s.resId.toString()
            if (s.sizeBytes > 0L) enrichedHeaders["X-Abys-Size"] = s.sizeBytes.toString()
            if (s.partSize != null && s.partSize > 0L) enrichedHeaders["X-Abys-PartSize"] = s.partSize.toString()
            if (s.mirrors.isNotEmpty()) enrichedHeaders["X-Abys-Mirrors"] = s.mirrors.joinToString(",")
            if (s.observedPartUrls.isNotEmpty()) enrichedHeaders["X-Abys-Parts"] = s.observedPartUrls.joinToString(";")

            ExtractedVideo(
                url = s.directFdUrl,
                title = "$title (${s.label})",
                type = VideoType.ABYS,
                size = s.sizeBytes,
                duration = durationMs,
                formattedSize = s.formattedSize,
                formattedDuration = durationFormatted,
                headers = enrichedHeaders,
                quality = s.label + bitrateTag,
                mediaGroupId = groupId,
                streamKey = streamKey
            )
        }
    }

    /**
     * Converts an [AbysMediaBundle] into a clean unified [ExtractedMediaGroup] for the Focus browser UI,
     * presenting a single card with resolution chips/options (e.g. 1080p, 720p, 480p, 360p).
     */
    fun convertToMediaGroup(
        bundle: AbysMediaBundle,
        baseHeaders: Map<String, String> = emptyMap(),
        pageTitle: String? = null,
        pageUrl: String? = null
    ): ExtractedMediaGroup {
        val extractedVideos = convertToExtractedVideos(bundle, baseHeaders, pageTitle)
        val title = bundle.title ?: pageTitle ?: "Filme / Episódio ABYS"
        val durationMs = bundle.durationSeconds?.let { (it * 1000).toLong() }
        val durationFormatted = durationMs?.let { ms ->
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }

        val groupId = extractedVideos.firstOrNull()?.mediaGroupId ?: "abys_group_${System.currentTimeMillis()}"

        val streamOptions = extractedVideos.mapIndexed { index, v ->
            val stream = bundle.streams.getOrNull(index)
            val resLabel = stream?.label ?: v.quality ?: "HD"
            val cleanResolution = resLabel.substringBefore(' ')
            val bitrateNotes = stream?.estimatedBitrateMbps?.let { "Bitrate estimado: $it Mbps" }

            MediaStreamOption(
                rawVideo = v,
                qualityLabel = resLabel,
                resolutionLabel = cleanResolution,
                formatBadge = "ABYS",
                sizeBytes = v.size,
                formattedSize = v.formattedSize ?: "Tamanho sob consulta",
                isAudioOnly = false,
                isAdaptiveVideoOnly = false,
                estimationConfidence = "Exacto",
                estimationMethod = "ABYS Media Manifest",
                estimationNotes = bitrateNotes
            )
        }

        return ExtractedMediaGroup(
            id = groupId,
            title = title,
            pageTitle = pageTitle,
            pageUrl = pageUrl,
            durationMs = durationMs,
            formattedDuration = durationFormatted,
            streams = streamOptions,
            videoStreams = streamOptions,
            audioStreams = emptyList(),
            selectedStreamId = streamOptions.firstOrNull()?.id
        )
    }
}
