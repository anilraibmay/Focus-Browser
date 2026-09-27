package com.example.util

import org.json.JSONObject
import java.util.Base64
import java.util.Locale

/**
 * Metadata parsed from an ABYS series/episode/movie title or player payload.
 */
data class AbysParsedEpisode(
    val show: String,
    val season: Int? = null,
    val episode: Int? = null,
    val pattern: String? = null,
    val resolution: String? = null,
    val se: String? = null,
    val originalTitle: String? = null,
    val rest: String = ""
)

/**
 * Fully resolved metadata structure for ABYS streams, matching abys_name.js.
 */
data class AbysResolvedMetadata(
    val slug: String? = null,
    val md5Id: String? = null,
    val userId: String? = null,
    val originalTitle: String? = null,
    val parsed: AbysParsedEpisode,
    val suggestedFileName: String,
    val suggestedFolder: String
)

/**
 * Resolver for authentic Series, Season, Episode, Resolution and Release naming
 * extracted from ABYS (abyss.to / abysscdn.com) server-rendered <title> and datas base64 payloads.
 * Implements the algorithms from abys_name.js and abys_capture.user.js v2.
 */
object AbysNameResolver {

    /**
     * Extracts slug (?v=...) from a URL or raw slug string.
     */
    fun extractSlug(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val vMatch = Regex("""[?&]v=([A-Za-z0-9_-]+)""").find(input)
        if (vMatch != null) return vMatch.groupValues[1]

        val trimmed = input.trim()
        if (Regex("""^[A-Za-z0-9_-]{6,}$""").matches(trimmed) && !trimmed.startsWith("http", ignoreCase = true)) {
            return trimmed
        }
        return null
    }

    /**
     * Cleans HTML entities and special characters from titles.
     */
    fun fixTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&#x27;", "'", ignoreCase = true)
            .trim()
    }

    /**
     * Detects season and episode numbers from title string (e.g. S01E01, 1x01, Temporada 1 Episodio 2, Ep 12).
     */
    fun parseSeasonEpisode(name: String): Triple<Int?, Int?, String?> {
        val s = name.replace('_', ' ')

        // 1. SxxExx pattern (e.g. S01E01, s1e2, S02E15)
        val sxxExxMatch = Regex("""[Ss](\d{1,3})[\s._-]*[Ee](\d{1,4})""").find(s)
        if (sxxExxMatch != null) {
            val season = sxxExxMatch.groupValues[1].toIntOrNull()
            val episode = sxxExxMatch.groupValues[2].toIntOrNull()
            return Triple(season, episode, "SxxExx")
        }

        // 2. NxNN pattern (e.g. 1x01, 2x15)
        val nxNnMatch = Regex("""\b(\d{1,3})[xX](\d{1,4})\b""").find(s)
        if (nxNnMatch != null) {
            val season = nxNnMatch.groupValues[1].toIntOrNull()
            val episode = nxNnMatch.groupValues[2].toIntOrNull()
            return Triple(season, episode, "NxNN")
        }

        // 3. Temporada X Episodio Y pattern
        val tempEpMatch = Regex("""(?:temporada|season|t)\s*(\d{1,3})[\s._-]*(?:epis[oó]dio|episode|ep|e)\s*(\d{1,4})""", RegexOption.IGNORE_CASE).find(s)
        if (tempEpMatch != null) {
            val season = tempEpMatch.groupValues[1].toIntOrNull()
            val episode = tempEpMatch.groupValues[2].toIntOrNull()
            return Triple(season, episode, "temporada/episodio")
        }

        // 4. Ep NN pattern (assumes season 1)
        val epMatch = Regex("""(?:epis[oó]dio|episode|epizod|\bep)\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE).find(s)
        if (epMatch != null) {
            val episode = epMatch.groupValues[1].toIntOrNull()
            return Triple(1, episode, "Ep NN (temporada suposta=1)")
        }

        return Triple(null, null, null)
    }

    /**
     * Detects standard video resolution tags in title strings (2160p, 1080p, 720p, 480p, 4K, HD, etc.).
     */
    fun parseResolution(name: String): String? {
        val match = Regex("""\b(2160p|1440p|1080p|720p|576p|480p|360p|240p|4K|UHD|HD|SD)\b""", RegexOption.IGNORE_CASE).find(name)
        return match?.groupValues?.get(1)
    }

    /**
     * Parses structured fields (Show name, Season, Episode, Resolution, remainder) from a raw title string.
     */
    fun parseFields(rawTitle: String): AbysParsedEpisode {
        val fixed = fixTitle(rawTitle)
        val base = fixed.replace(Regex("""\.(mp4|mkv|m4v|avi|webm|ts)$""", RegexOption.IGNORE_CASE), "")

        val (season, episode, pattern) = parseSeasonEpisode(base)

        // Find the cut point where the season/episode tag starts
        val cutRegex = Regex("""[Ss]\d{1,3}[\s._-]*[Ee]\d{1,4}|\b\d{1,3}[xX]\d{1,4}\b|(?:temporada|season)\s*\d{1,3}|(?:epis[oó]dio|episode|\bep)\s*\d{1,4}""", RegexOption.IGNORE_CASE)
        val cutMatch = cutRegex.find(base)
        val cutIndex = cutMatch?.range?.first ?: -1

        var show = if (cutIndex > 0) base.substring(0, cutIndex) else base
        show = show
            .replace(Regex("""[._]+"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .replace(Regex("""[-_\s]+$"""), "")
            .trim()

        var rest = ""
        if (cutIndex > 0) {
            val afterCut = base.substring(cutIndex)
            rest = afterCut
                .replace(Regex("""^[Ss]\d{1,3}[\s._-]*[Ee]\d{1,4}"""), "")
                .replace(Regex("""^\d{1,3}[xX]\d{1,4}"""), "")
                .replace(Regex("""^[._\-\s]+"""), "")
                .replace(Regex("""[._]+"""), " ")
                .trim()
        }

        val se = if (season != null && episode != null) {
            String.format(Locale.US, "S%02dE%02d", season, episode)
        } else null

        val res = parseResolution(base)

        return AbysParsedEpisode(
            show = if (show.isNotBlank()) show else "ABYS Video",
            season = season,
            episode = episode,
            pattern = pattern,
            resolution = res,
            se = se,
            originalTitle = fixed,
            rest = rest
        )
    }

    /**
     * Resolves complete metadata and builds human-friendly filenames and folder names.
     */
    fun resolveMetadata(
        title: String?,
        slug: String? = null,
        md5Id: String? = null,
        userId: String? = null,
        overrideResolution: String? = null
    ): AbysResolvedMetadata {
        val parsed = parseFields(title ?: slug ?: "ABYS Video")
        val finalRes = overrideResolution ?: parsed.resolution

        val parts = mutableListOf<String>()
        val showName = sanitizeFileNamePart(parsed.show)
        if (showName.isNotBlank()) {
            parts.add(showName)
        } else if (!md5Id.isNullOrBlank()) {
            parts.add("ABYS_$md5Id")
        } else if (!slug.isNullOrBlank()) {
            parts.add("ABYS_$slug")
        } else {
            parts.add("ABYS_Video")
        }

        if (parsed.se != null) {
            parts.add(parsed.se)
        }

        if (!finalRes.isNullOrBlank()) {
            parts.add(finalRes)
        }

        val suggestedFileName = parts.joinToString(" - ") + ".mp4"
        val suggestedFolder = sanitizeFileNamePart(parsed.show.ifBlank { "ABYS" })

        return AbysResolvedMetadata(
            slug = slug,
            md5Id = md5Id,
            userId = userId,
            originalTitle = title,
            parsed = parsed,
            suggestedFileName = suggestedFileName,
            suggestedFolder = suggestedFolder
        )
    }

    /**
     * Formats an ABYS video filename cleanly (e.g. "A Shop for Killers - S01E01 - 720p.mp4").
     */
    fun formatFileName(
        rawTitle: String?,
        fallbackResolution: String? = null,
        slug: String? = null
    ): String {
        val meta = resolveMetadata(
            title = rawTitle,
            slug = slug,
            overrideResolution = fallbackResolution
        )
        return meta.suggestedFileName
    }

    /**
     * Extracts the base64 "datas" variable from an ABYS HTML document.
     */
    fun extractDatasFromHtml(html: String): Map<String, String>? {
        val match = Regex("""const\s+datas\s*=\s*"([^"]+)"""").find(html) ?: return null
        val b64 = match.groupValues[1]
        return try {
            val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
            val map = mutableMapOf<String, String>()
            val fieldRegex = Regex(""""([^"]+)"\s*:\s*(?:"([^"]*)"|([0-9]+|true|false|null))""")
            fieldRegex.findAll(decoded).forEach { m ->
                val key = m.groupValues[1]
                val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
                map[key] = value
            }
            if (map.isNotEmpty()) map else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts original title from <title> tag in HTML document if valid.
     */
    fun extractTitleFromHtml(html: String): String? {
        val match = Regex("""<title>([^<]*)</title>""", RegexOption.IGNORE_CASE).find(html) ?: return null
        val title = fixTitle(match.groupValues[1])
        if (title.isBlank() || title.contains("Just a moment", ignoreCase = true) || title.contains("Cloudflare", ignoreCase = true)) {
            return null
        }
        return title
    }

    private fun sanitizeFileNamePart(input: String): String {
        return input
            .replace(Regex("""[<>:"/\\|?*\x00-\x1f]"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }
}
