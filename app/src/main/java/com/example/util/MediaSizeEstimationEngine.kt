package com.example.util

import android.util.Log
import com.example.extractor.VideoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Níveis qualitativos de confiança segundo a Metodologia de Estimativa de Tamanho.
 */
enum class EstimationConfidence(val label: String) {
    EXACT("Exacto"),
    HIGH("Alta"),
    MEDIUM("Média"),
    LOW("Baixa")
}

/**
 * Métodos da metodologia segundo a ordem de prioridade estrita.
 */
enum class EstimationMethod(val label: String) {
    EXACT_BYTERANGE("BYTERANGE no manifesto"),
    EXACT_SEGMENT_MEASUREMENT("Medição total de segmentos"),
    AVERAGE_BANDWIDTH("AVERAGE-BANDWIDTH"),
    ADAPTIVE_SAMPLING("Amostragem adaptativa"),
    BANDWIDTH_FALLBACK("BANDWIDTH declarado"),
    DIRECT_CONTENT_LENGTH("Content-Length HTTP"),
    HEURISTIC_FALLBACK("Heurística de bitrate")
}

/**
 * Resultado completo e estruturado da estimativa de mídia HLS / DASH / Direta.
 */
data class MediaEstimationResult(
    val sizeBytes: Long,
    val durationMs: Long?,
    val formattedSize: String,
    val formattedDuration: String,
    val format: String,
    val representationLabel: String,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val method: EstimationMethod,
    val confidence: EstimationConfidence,
    val analyzedSegmentsCount: Int = 0,
    val additionalRequestsCount: Int = 0,
    val notes: String = "",
    val probedQuality: String? = null
)

/**
 * Motor central que implementa fielmente a "Metodologia para Estimativa de Tamanho de Mídia HLS/DASH".
 *
 * Princípio Geral:
 * 1. Bytes determináveis directamente no manifesto (#EXT-X-BYTERANGE) -> EXACTO
 * 2. Tamanho de todos os segmentos obtido via HTTP -> EXACTO
 * 3. AVERAGE-BANDWIDTH ou taxa média equivalente -> ESTIMATIVA (ALTA CONFIANÇA)
 * 4. Amostragem adaptativa dos segmentos (distribuída em 10%, 30%, 50%, 70%, 90%) -> REFINAMENTO
 * 5. BANDWIDTH declarado -> FALLBACK DE ESTIMATIVA (MÉDIA / BAIXA CONFIANÇA)
 * 6. Tratamento rigoroso de representações independentes de áudio e vídeo
 */
object MediaSizeEstimationEngine {
    private const val TAG = "MediaSizeEstimationEngine"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun estimateHlsStream(
        playlistUrl: String,
        headers: Map<String, String>,
        explicitDurationMs: Long? = null,
        explicitQuality: String? = null
    ): MediaEstimationResult = withContext(Dispatchers.IO) {
        var additionalRequests = 0
        try {
            // 1. Descarregar o manifesto inicial
            val initialContent = fetchString(playlistUrl, headers)
            additionalRequests++

            var targetMediaPlaylistUrl = playlistUrl
            var targetMediaContent = initialContent
            var averageBandwidth: Long? = null
            var peakBandwidth: Long? = null
            var selectedResolution: String? = explicitQuality
            var separateAudioUrl: String? = null
            var audioAverageBandwidth: Long? = null
            var audioBandwidth: Long? = null

            val lines = initialContent.lines()
            val isMasterPlaylist = lines.any { it.trim().startsWith("#EXT-X-STREAM-INF") }

            if (isMasterPlaylist) {
                // Seleção criteriosa da variante na Master Playlist
                var highestBw = -1L
                var bestVariantRelativeUrl: String? = null
                var matchedAudioGroupId: String? = null

                var i = 0
                while (i < lines.size) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF:")) {
                        val avgBwMatch = Regex("""AVERAGE-BANDWIDTH=(\d+)""").find(line)
                        val bwMatch = Regex("""BANDWIDTH=(\d+)""").find(line)
                        val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)

                        val currentAvgBw = avgBwMatch?.groupValues?.get(1)?.toLongOrNull()
                        val currentBw = bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        val effectiveBw = currentAvgBw ?: currentBw

                        val audioGroupMatch = Regex("""AUDIO=["']?([^"',\s]+)["']?""").find(line)
                        val audioGroupId = audioGroupMatch?.groupValues?.get(1)

                        val w = resMatch?.groupValues?.get(1)?.toIntOrNull()
                        val h = resMatch?.groupValues?.get(2)?.toIntOrNull()
                        val resLabel = if (w != null && h != null && w > 0 && h > 0) {
                            com.example.extractor.VideoQualityHelper.getQualityFromResolution(w, h)
                        } else null

                        // Procura próxima linha que contém a URL da variante
                        var j = i + 1
                        while (j < lines.size && (lines[j].trim().isEmpty() || lines[j].trim().startsWith("#"))) {
                            j++
                        }

                        if (j < lines.size) {
                            val variantUri = lines[j].trim()
                            if (effectiveBw > highestBw || bestVariantRelativeUrl == null) {
                                highestBw = effectiveBw
                                bestVariantRelativeUrl = variantUri
                                averageBandwidth = currentAvgBw
                                peakBandwidth = currentBw
                                selectedResolution = resLabel ?: selectedResolution
                                matchedAudioGroupId = audioGroupId
                            }
                        }
                    }
                    i++
                }

                // Identificar grupo de áudio separado (#EXT-X-MEDIA:TYPE=AUDIO)
                if (matchedAudioGroupId != null) {
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("#EXT-X-MEDIA:") && trimmed.contains("TYPE=AUDIO")) {
                            val groupMatch = Regex("""GROUP-ID=["']?([^"',\s]+)["']?""").find(trimmed)
                            if (groupMatch?.groupValues?.get(1) == matchedAudioGroupId) {
                                val uriMatch = Regex("""URI=["']?([^"',\s]+)["']?""").find(trimmed)
                                if (uriMatch != null) {
                                    separateAudioUrl = resolveUrl(playlistUrl, uriMatch.groupValues[1])
                                }
                                val audioAvgMatch = Regex("""AVERAGE-BANDWIDTH=(\d+)""").find(trimmed)
                                val audioBwMatch = Regex("""BANDWIDTH=(\d+)""").find(trimmed)
                                audioAverageBandwidth = audioAvgMatch?.groupValues?.get(1)?.toLongOrNull()
                                audioBandwidth = audioBwMatch?.groupValues?.get(1)?.toLongOrNull()
                                break
                            }
                        }
                    }
                }

                if (bestVariantRelativeUrl != null) {
                    targetMediaPlaylistUrl = resolveUrl(playlistUrl, bestVariantRelativeUrl)
                    targetMediaContent = fetchString(targetMediaPlaylistUrl, headers)
                    additionalRequests++
                }
            }

            // Analisar a Media Playlist (segmentos, durações e byteranges)
            val mediaLines = targetMediaContent.lines()
            var totalDurationSecs = 0.0
            val byteRanges = mutableListOf<Long>()
            val segmentUrls = mutableListOf<Pair<String, Double>>() // url, durationSecs

            var pendingDuration = 0.0
            for (line in mediaLines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXTINF:")) {
                    val durStr = trimmed.substringAfter("#EXTINF:").substringBefore(",").trim()
                    val dur = durStr.toDoubleOrNull() ?: 0.0
                    pendingDuration = dur
                    totalDurationSecs += dur
                } else if (trimmed.startsWith("#EXT-X-BYTERANGE:")) {
                    val rangePart = trimmed.substringAfter("#EXT-X-BYTERANGE:").trim()
                    val length = rangePart.substringBefore("@").toLongOrNull()
                    if (length != null && length > 0) {
                        byteRanges.add(length)
                    }
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val fullSegUrl = resolveUrl(targetMediaPlaylistUrl, trimmed)
                    segmentUrls.add(fullSegUrl to (if (pendingDuration > 0) pendingDuration else 5.0))
                    pendingDuration = 0.0
                }
            }

            val finalDurationMs = if (totalDurationSecs > 0) {
                (totalDurationSecs * 1000).toLong()
            } else {
                explicitDurationMs
            }

            val repLabel = selectedResolution ?: com.example.extractor.VideoQualityHelper.detectQualityFromUrl(playlistUrl) ?: "HLS Adaptativo"

            // -------------------------------------------------------------
            // MÉTODO 1 — Bytes determináveis diretamente no manifesto (#EXT-X-BYTERANGE)
            // -------------------------------------------------------------
            if (byteRanges.isNotEmpty() && byteRanges.size >= segmentUrls.size && segmentUrls.isNotEmpty()) {
                val totalBytes = byteRanges.sum()
                return@withContext MediaEstimationResult(
                    sizeBytes = totalBytes,
                    durationMs = finalDurationMs,
                    formattedSize = formatBytes(totalBytes),
                    formattedDuration = formatDuration(finalDurationMs),
                    format = "HLS",
                    representationLabel = repLabel,
                    hasVideo = true,
                    hasAudio = true,
                    method = EstimationMethod.EXACT_BYTERANGE,
                    confidence = EstimationConfidence.EXACT,
                    analyzedSegmentsCount = byteRanges.size,
                    additionalRequestsCount = additionalRequests,
                    notes = "Tamanho exato obtido pela soma de ${byteRanges.size} BYTERANGEs declarados no manifesto.",
                    probedQuality = selectedResolution
                )
            }

            // -------------------------------------------------------------
            // MÉTODO 2 — Medição de todos os segmentos por HTTP (apenas se poucos segmentos <= 8)
            // -------------------------------------------------------------
            if (segmentUrls.isNotEmpty() && segmentUrls.size in 1..8) {
                var exactSum = 0L
                var allMeasured = true
                for ((segUrl, _) in segmentUrls) {
                    val segBytes = measureSegmentSize(segUrl, headers)
                    additionalRequests++
                    if (segBytes != null && segBytes > 0) {
                        exactSum += segBytes
                    } else {
                        allMeasured = false
                        break
                    }
                }
                if (allMeasured && exactSum > 0) {
                    return@withContext MediaEstimationResult(
                        sizeBytes = exactSum,
                        durationMs = finalDurationMs,
                        formattedSize = formatBytes(exactSum),
                        formattedDuration = formatDuration(finalDurationMs),
                        format = "HLS",
                        representationLabel = repLabel,
                        hasVideo = true,
                        hasAudio = true,
                        method = EstimationMethod.EXACT_SEGMENT_MEASUREMENT,
                        confidence = EstimationConfidence.EXACT,
                        analyzedSegmentsCount = segmentUrls.size,
                        additionalRequestsCount = additionalRequests,
                        notes = "Tamanho exato obtido por medição HTTP de todos os ${segmentUrls.size} segmentos.",
                        probedQuality = selectedResolution
                    )
                }
            }

            // -------------------------------------------------------------
            // MÉTODO 3 — AVERAGE-BANDWIDTH (Estimativa Principal)
            // -------------------------------------------------------------
            val effectiveDurationSec = if (totalDurationSecs > 0) totalDurationSecs else (finalDurationMs?.let { it / 1000.0 } ?: 0.0)

            if (averageBandwidth != null && averageBandwidth > 0 && effectiveDurationSec > 0) {
                val totalBitrate = averageBandwidth + (audioAverageBandwidth ?: 0L)
                val estimatedBytes = (totalBitrate * effectiveDurationSec / 8.0).toLong()
                return@withContext MediaEstimationResult(
                    sizeBytes = estimatedBytes,
                    durationMs = finalDurationMs,
                    formattedSize = "~${formatBytes(estimatedBytes)}",
                    formattedDuration = formatDuration(finalDurationMs),
                    format = "HLS",
                    representationLabel = repLabel,
                    hasVideo = true,
                    hasAudio = true,
                    method = EstimationMethod.AVERAGE_BANDWIDTH,
                    confidence = EstimationConfidence.HIGH,
                    analyzedSegmentsCount = segmentUrls.size,
                    additionalRequestsCount = additionalRequests,
                    notes = "Estimativa calculada via AVERAGE-BANDWIDTH ($totalBitrate bps) e duração total.",
                    probedQuality = selectedResolution
                )
            }

            // -------------------------------------------------------------
            // MÉTODO 4 — Amostragem Adaptativa dos Segmentos (10%, 30%, 50%, 70%, 90%)
            // -------------------------------------------------------------
            if (segmentUrls.isNotEmpty() && effectiveDurationSec > 0) {
                val samplingResult = executeAdaptiveSampling(segmentUrls, headers, effectiveDurationSec)
                additionalRequests += samplingResult.probedRequestsCount

                if (samplingResult.estimatedBytes > 0) {
                    val conf = if (samplingResult.variabilityLow) EstimationConfidence.HIGH else EstimationConfidence.MEDIUM
                    return@withContext MediaEstimationResult(
                        sizeBytes = samplingResult.estimatedBytes,
                        durationMs = finalDurationMs,
                        formattedSize = "~${formatBytes(samplingResult.estimatedBytes)}",
                        formattedDuration = formatDuration(finalDurationMs),
                        format = "HLS",
                        representationLabel = repLabel,
                        hasVideo = true,
                        hasAudio = true,
                        method = EstimationMethod.ADAPTIVE_SAMPLING,
                        confidence = conf,
                        analyzedSegmentsCount = samplingResult.sampleCount,
                        additionalRequestsCount = additionalRequests,
                        notes = "Estimativa obtida por amostragem adaptativa distribuída (${samplingResult.sampleCount} amostras).",
                        probedQuality = selectedResolution
                    )
                }
            }

            // -------------------------------------------------------------
            // MÉTODO 5 — BANDWIDTH declarado como fallback
            // -------------------------------------------------------------
            if (peakBandwidth != null && peakBandwidth > 0 && effectiveDurationSec > 0) {
                val totalBw = peakBandwidth + (audioBandwidth ?: 0L)
                val estimatedBytes = (totalBw * effectiveDurationSec / 8.0).toLong()
                return@withContext MediaEstimationResult(
                    sizeBytes = estimatedBytes,
                    durationMs = finalDurationMs,
                    formattedSize = "~${formatBytes(estimatedBytes)}",
                    formattedDuration = formatDuration(finalDurationMs),
                    format = "HLS",
                    representationLabel = repLabel,
                    hasVideo = true,
                    hasAudio = true,
                    method = EstimationMethod.BANDWIDTH_FALLBACK,
                    confidence = EstimationConfidence.MEDIUM,
                    analyzedSegmentsCount = segmentUrls.size,
                    additionalRequestsCount = additionalRequests,
                    notes = "Estimativa calculada a partir do BANDWIDTH de pico declarado ($totalBw bps).",
                    probedQuality = selectedResolution
                )
            }

            // Fallback heurístico caso o manifesto não declare taxas de bits
            val heuristicBytes = calculateHeuristicBytes(selectedResolution, effectiveDurationSec)
            return@withContext MediaEstimationResult(
                sizeBytes = heuristicBytes,
                durationMs = finalDurationMs,
                formattedSize = if (heuristicBytes > 0) "~${formatBytes(heuristicBytes)}" else "Tamanho sob consulta",
                formattedDuration = formatDuration(finalDurationMs),
                format = "HLS",
                representationLabel = repLabel,
                hasVideo = true,
                hasAudio = true,
                method = EstimationMethod.HEURISTIC_FALLBACK,
                confidence = EstimationConfidence.LOW,
                analyzedSegmentsCount = segmentUrls.size,
                additionalRequestsCount = additionalRequests,
                notes = "Estimativa aproximada baseada na resolução típica de reprodução.",
                probedQuality = selectedResolution
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro na estimativa HLS: ${e.message}", e)
            val fallbackBytes = calculateHeuristicBytes(explicitQuality, (explicitDurationMs ?: 0L) / 1000.0)
            return@withContext MediaEstimationResult(
                sizeBytes = fallbackBytes,
                durationMs = explicitDurationMs,
                formattedSize = if (fallbackBytes > 0) "~${formatBytes(fallbackBytes)}" else "Tamanho sob consulta",
                formattedDuration = formatDuration(explicitDurationMs),
                format = "HLS",
                representationLabel = explicitQuality ?: "HLS",
                hasVideo = true,
                hasAudio = true,
                method = EstimationMethod.HEURISTIC_FALLBACK,
                confidence = EstimationConfidence.LOW,
                analyzedSegmentsCount = 0,
                additionalRequestsCount = additionalRequests,
                notes = "Não foi possível analisar o manifesto completamente (${e.message}).",
                probedQuality = explicitQuality
            )
        }
    }

    /**
     * Estimativa para fluxos DASH (.mpd) seguindo a mesma metodologia.
     */
    suspend fun estimateDashStream(
        mpdUrl: String,
        headers: Map<String, String>,
        explicitDurationMs: Long? = null,
        explicitQuality: String? = null
    ): MediaEstimationResult = withContext(Dispatchers.IO) {
        var additionalRequests = 0
        try {
            val mpdXml = fetchString(mpdUrl, headers)
            additionalRequests++

            val parserInfo = parseDashManifest(mpdUrl, mpdXml)
            val videoRep = parserInfo.bestVideoRep
            val audioRep = parserInfo.bestAudioRep
            val totalDurationSec = if (parserInfo.mediaPresentationDurationSec > 0) {
                parserInfo.mediaPresentationDurationSec
            } else {
                (explicitDurationMs ?: 0L) / 1000.0
            }

            val finalDurationMs = if (totalDurationSec > 0) (totalDurationSec * 1000).toLong() else explicitDurationMs

            val videoBw = videoRep?.bandwidth ?: 0L
            val audioBw = audioRep?.bandwidth ?: 0L
            val totalBw = videoBw + audioBw

            val repLabel = if (videoRep != null && videoRep.width > 0 && videoRep.height > 0) {
                com.example.extractor.VideoQualityHelper.getQualityFromResolution(videoRep.width, videoRep.height)
            } else {
                explicitQuality ?: "DASH Adaptativo"
            }

            if (totalBw > 0 && totalDurationSec > 0) {
                val estimatedBytes = (totalBw * totalDurationSec / 8.0).toLong()
                return@withContext MediaEstimationResult(
                    sizeBytes = estimatedBytes,
                    durationMs = finalDurationMs,
                    formattedSize = "~${formatBytes(estimatedBytes)}",
                    formattedDuration = formatDuration(finalDurationMs),
                    format = "DASH",
                    representationLabel = repLabel,
                    hasVideo = videoRep != null,
                    hasAudio = audioRep != null,
                    method = EstimationMethod.AVERAGE_BANDWIDTH,
                    confidence = EstimationConfidence.HIGH,
                    analyzedSegmentsCount = 0,
                    additionalRequestsCount = additionalRequests,
                    notes = "Estimativa calculada via bandwidth das representações de vídeo e áudio ($totalBw bps).",
                    probedQuality = repLabel
                )
            }

            val heuristicBytes = calculateHeuristicBytes(repLabel, totalDurationSec)
            return@withContext MediaEstimationResult(
                sizeBytes = heuristicBytes,
                durationMs = finalDurationMs,
                formattedSize = if (heuristicBytes > 0) "~${formatBytes(heuristicBytes)}" else "Tamanho sob consulta",
                formattedDuration = formatDuration(finalDurationMs),
                format = "DASH",
                representationLabel = repLabel,
                hasVideo = true,
                hasAudio = true,
                method = EstimationMethod.HEURISTIC_FALLBACK,
                confidence = EstimationConfidence.LOW,
                analyzedSegmentsCount = 0,
                additionalRequestsCount = additionalRequests,
                notes = "Estimativa heurística para manifesto DASH.",
                probedQuality = repLabel
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro na estimativa DASH: ${e.message}", e)
            val fallbackBytes = calculateHeuristicBytes(explicitQuality, (explicitDurationMs ?: 0L) / 1000.0)
            return@withContext MediaEstimationResult(
                sizeBytes = fallbackBytes,
                durationMs = explicitDurationMs,
                formattedSize = if (fallbackBytes > 0) "~${formatBytes(fallbackBytes)}" else "Tamanho sob consulta",
                formattedDuration = formatDuration(explicitDurationMs),
                format = "DASH",
                representationLabel = explicitQuality ?: "DASH",
                hasVideo = true,
                hasAudio = true,
                method = EstimationMethod.HEURISTIC_FALLBACK,
                confidence = EstimationConfidence.LOW,
                analyzedSegmentsCount = 0,
                additionalRequestsCount = additionalRequests,
                notes = "Falha ao analisar MPD: ${e.message}",
                probedQuality = explicitQuality
            )
        }
    }

    /**
     * Estimativa para links diretos (MP4, MP3, WebM, FLV).
     */
    suspend fun estimateDirectStream(
        url: String,
        headers: Map<String, String>,
        explicitDurationMs: Long? = null,
        explicitQuality: String? = null,
        isAudio: Boolean = false
    ): MediaEstimationResult = withContext(Dispatchers.IO) {
        var sizeBytes: Long? = null
        var additionalRequests = 0

        try {
            val reqBuilder = Request.Builder().url(url).head()
            headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                additionalRequests++
                if (resp.isSuccessful) {
                    sizeBytes = resp.header("Content-Length")?.toLongOrNull()
                } else {
                    val getReq = Request.Builder().url(url).header("Range", "bytes=0-1").apply {
                        headers.forEach { (k, v) -> addHeader(k, v) }
                    }.build()
                    httpClient.newCall(getReq).execute().use { getResp ->
                        additionalRequests++
                        val cr = getResp.header("Content-Range")
                        if (cr != null) {
                            sizeBytes = cr.substringAfterLast("/").toLongOrNull()
                        }
                        if (sizeBytes == null) {
                            sizeBytes = getResp.header("Content-Length")?.toLongOrNull()
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        val repLabel = explicitQuality ?: com.example.extractor.VideoQualityHelper.detectQualityFromUrl(url) ?: if (isAudio) "Áudio Direto" else "Vídeo Direto"

        if (sizeBytes != null && sizeBytes!! > 0) {
            return@withContext MediaEstimationResult(
                sizeBytes = sizeBytes!!,
                durationMs = explicitDurationMs,
                formattedSize = formatBytes(sizeBytes!!),
                formattedDuration = formatDuration(explicitDurationMs),
                format = if (isAudio) "Áudio" else "Direto",
                representationLabel = repLabel,
                hasVideo = !isAudio,
                hasAudio = true,
                method = EstimationMethod.DIRECT_CONTENT_LENGTH,
                confidence = EstimationConfidence.EXACT,
                analyzedSegmentsCount = 1,
                additionalRequestsCount = additionalRequests,
                notes = "Tamanho exato obtido via cabeçalho Content-Length HTTP.",
                probedQuality = repLabel
            )
        }

        val totalDurationSec = (explicitDurationMs ?: 0L) / 1000.0
        val heuristicBytes = calculateHeuristicBytes(repLabel, totalDurationSec, isAudio)

        return@withContext MediaEstimationResult(
            sizeBytes = heuristicBytes,
            durationMs = explicitDurationMs,
            formattedSize = if (heuristicBytes > 0) "~${formatBytes(heuristicBytes)}" else "Tamanho sob consulta",
            formattedDuration = formatDuration(explicitDurationMs),
            format = if (isAudio) "Áudio" else "Direto",
            representationLabel = repLabel,
            hasVideo = !isAudio,
            hasAudio = true,
            method = EstimationMethod.HEURISTIC_FALLBACK,
            confidence = EstimationConfidence.LOW,
            analyzedSegmentsCount = 0,
            additionalRequestsCount = additionalRequests,
            notes = "Content-Length não disponível. Estimativa por taxa de bits média.",
            probedQuality = repLabel
        )
    }

    // ---------------------------------------------------------------------------------------------
    // HELPERS DE AMOSTRAGEM ADAPTATIVA E PARSING
    // ---------------------------------------------------------------------------------------------

    private data class SamplingComputation(
        val estimatedBytes: Long,
        val sampleCount: Int,
        val probedRequestsCount: Int,
        val variabilityLow: Boolean
    )

    private fun executeAdaptiveSampling(
        segments: List<Pair<String, Double>>,
        headers: Map<String, String>,
        totalDurationSec: Double
    ): SamplingComputation {
        val totalSegs = segments.size
        if (totalSegs == 0) return SamplingComputation(0L, 0, 0, false)

        // Mídia Curta (< 30 segs ou <= 5 segs): 3 amostras (10%, 50%, 90%)
        // Mídia Média: 5 amostras (10%, 30%, 50%, 70%, 90%)
        // Mídia Longa: 7 amostras
        val targetPercentages = when {
            totalSegs <= 6 -> listOf(0.1, 0.5, 0.9)
            totalSegs <= 50 -> listOf(0.1, 0.3, 0.5, 0.7, 0.9)
            else -> listOf(0.05, 0.20, 0.35, 0.50, 0.65, 0.80, 0.95)
        }

        val sampledIndices = targetPercentages.map { pct ->
            ((totalSegs - 1) * pct).toInt().coerceIn(0, totalSegs - 1)
        }.distinct()

        var sampleBytesSum = 0L
        var sampleDurSum = 0.0
        val sampleBitrates = mutableListOf<Double>()
        var requestsCount = 0

        for (idx in sampledIndices) {
            val (url, duration) = segments[idx]
            val bytes = measureSegmentSize(url, headers)
            requestsCount++
            if (bytes != null && bytes > 0 && duration > 0) {
                sampleBytesSum += bytes
                sampleDurSum += duration
                val bitrate = (bytes * 8.0) / duration
                sampleBitrates.add(bitrate)
            }
        }

        if (sampleBytesSum <= 0 || sampleDurSum <= 0) {
            return SamplingComputation(0L, 0, requestsCount, false)
        }

        val sampleRate = sampleBytesSum / sampleDurSum
        val estimatedBytes = (sampleRate * totalDurationSec).toLong()

        // Avaliar variabilidade entre amostras
        val avgBitrate = sampleBitrates.average()
        val maxDiff = sampleBitrates.maxOfOrNull { abs(it - avgBitrate) } ?: 0.0
        val variabilityLow = if (avgBitrate > 0) (maxDiff / avgBitrate) < 0.25 else false

        return SamplingComputation(
            estimatedBytes = estimatedBytes,
            sampleCount = sampleBitrates.size,
            probedRequestsCount = requestsCount,
            variabilityLow = variabilityLow
        )
    }

    private fun measureSegmentSize(url: String, headers: Map<String, String>): Long? {
        return try {
            val req = Request.Builder().url(url).head()
            headers.forEach { (k, v) -> req.addHeader(k, v) }
            httpClient.newCall(req.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.header("Content-Length")?.toLongOrNull()
                } else {
                    val getReq = Request.Builder().url(url).header("Range", "bytes=0-1").apply {
                        headers.forEach { (k, v) -> addHeader(k, v) }
                    }.build()
                    httpClient.newCall(getReq).execute().use { getResp ->
                        val cr = getResp.header("Content-Range")
                        cr?.substringAfterLast("/")?.toLongOrNull() ?: getResp.header("Content-Length")?.toLongOrNull()
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class DashManifestSummary(
        val mediaPresentationDurationSec: Double,
        val bestVideoRep: com.example.util.DashRepresentation?,
        val bestAudioRep: com.example.util.DashRepresentation?
    )

    private fun parseDashManifest(baseUrl: String, xmlContent: String): DashManifestSummary {
        var durationSec = 0.0
        val videoReps = mutableListOf<com.example.util.DashRepresentation>()
        val audioReps = mutableListOf<com.example.util.DashRepresentation>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))

            var eventType = parser.eventType
            var currentMimeType = ""
            var currentContentType = ""
            var currentTemplateInit: String? = null
            var currentTemplateMedia: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tag.equals("MPD", ignoreCase = true)) {
                            val durAttr = parser.getAttributeValue(null, "mediaPresentationDuration")
                            if (!durAttr.isNullOrBlank()) {
                                durationSec = parseIsoDuration(durAttr)
                            }
                        } else if (tag.equals("AdaptationSet", ignoreCase = true)) {
                            currentMimeType = parser.getAttributeValue(null, "mimeType") ?: ""
                            currentContentType = parser.getAttributeValue(null, "contentType") ?: ""
                        } else if (tag.equals("SegmentTemplate", ignoreCase = true)) {
                            currentTemplateInit = parser.getAttributeValue(null, "initialization")
                            currentTemplateMedia = parser.getAttributeValue(null, "media")
                        } else if (tag.equals("Representation", ignoreCase = true)) {
                            val id = parser.getAttributeValue(null, "id") ?: "rep_${System.currentTimeMillis()}"
                            val bandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                            val width = parser.getAttributeValue(null, "width")?.toIntOrNull() ?: 0
                            val height = parser.getAttributeValue(null, "height")?.toIntOrNull() ?: 0
                            val codecs = parser.getAttributeValue(null, "codecs")
                            val mime = parser.getAttributeValue(null, "mimeType") ?: currentMimeType
                            val isVideo = currentContentType.equals("video", ignoreCase = true) ||
                                    mime.contains("video", ignoreCase = true) ||
                                    width > 0 || height > 0

                            val rep = com.example.util.DashRepresentation(
                                id = id,
                                mimeType = mime,
                                bandwidth = bandwidth,
                                width = width,
                                height = height,
                                codecs = codecs,
                                baseUrl = null,
                                initUrl = currentTemplateInit,
                                mediaTemplate = currentTemplateMedia,
                                startNumber = 1,
                                isVideo = isVideo
                            )
                            if (isVideo) videoReps.add(rep) else audioReps.add(rep)
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar MPD XML: ${e.message}", e)
        }

        val bestVideo = videoReps.maxByOrNull { it.bandwidth }
        val bestAudio = audioReps.maxByOrNull { it.bandwidth }

        return DashManifestSummary(
            mediaPresentationDurationSec = durationSec,
            bestVideoRep = bestVideo,
            bestAudioRep = bestAudio
        )
    }

    private fun parseIsoDuration(isoDuration: String): Double {
        return try {
            val d = isoDuration.trim().uppercase(Locale.US)
            var totalSecs = 0.0
            val timePart = d.substringAfter("T", "")

            val hours = Regex("(\\d+(?:\\.\\d+)?)H").find(timePart)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val minutes = Regex("(\\d+(?:\\.\\d+)?)M").find(timePart)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val seconds = Regex("(\\d+(?:\\.\\d+)?)S").find(timePart)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

            totalSecs += hours * 3600 + minutes * 60 + seconds
            totalSecs
        } catch (e: Exception) {
            0.0
        }
    }

    private fun calculateHeuristicBytes(quality: String?, durationSec: Double, isAudio: Boolean = false): Long {
        if (durationSec <= 0) return 0L
        val q = quality?.lowercase() ?: ""
        val estimatedBitrateKbps: Long = when {
            isAudio -> 128L
            q.contains("2160") || q.contains("4k") -> 14000L
            q.contains("1440") || q.contains("2k") -> 7500L
            q.contains("1080") -> 3200L
            q.contains("720") -> 1600L
            q.contains("480") -> 850L
            q.contains("360") -> 450L
            q.contains("240") -> 280L
            q.contains("144") -> 160L
            else -> 1200L
        }
        return (durationSec * (estimatedBitrateKbps * 1000.0 / 8.0)).toLong()
    }

    private fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String {
        return try {
            val baseUri = URI(baseUrl)
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
                if (lastSlash != -1) baseUrl.substring(0, lastSlash + 1) + relativeOrAbsolute else relativeOrAbsolute
            }
        }
    }

    private fun fetchString(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url)
        headers.forEach { (k, v) -> req.addHeader(k, v) }
        val resp = httpClient.newCall(req.build()).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw Exception("HTTP ${resp.code} ao obter manifesto")
        }
        return resp.body?.string() ?: throw Exception("Manifesto vazio")
    }

    private fun formatBytes(bytes: Long): String {
        return com.example.ui.formatFileSize(bytes)
    }

    private fun formatDuration(durationMs: Long?): String {
        if (durationMs == null || durationMs <= 0) return "Duração indisponível"
        val totalSecs = durationMs / 1000
        val hours = totalSecs / 3600
        val minutes = (totalSecs % 3600) / 60
        val seconds = totalSecs % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
