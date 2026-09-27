package com.example.media

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Protocol classification for media streams.
 */
enum class StreamProtocol {
    HLS,
    DASH,
    ABYS,
    PROGRESSIVE_MP4,
    UNKNOWN
}

/**
 * Real-time validation status for stream segment mapping.
 */
enum class StreamMappingStatus {
    INITIALIZING,
    MANIFEST_DETECTED,
    FETCHING_SEGMENTS,
    MAPPING_VERIFIED,
    PREFIX_ONLY_WARNING,
    SEGMENT_ERROR
}

/**
 * Record of an individual loaded fragment/segment or manifest in ExoPlayer.
 */
data class StreamSegmentRecord(
    val id: Long,
    val uri: String,
    val protocol: StreamProtocol,
    val trackType: String,
    val mediaStartTimeMs: Long,
    val mediaEndTimeMs: Long,
    val durationMs: Long,
    val byteOffset: Long,
    val bytesLoaded: Long,
    val loadDurationMs: Long,
    val httpStatus: Int,
    val isSuccess: Boolean,
    val errorMessage: String? = null,
    val formatDescription: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Comprehensive mapping telemetry report evaluating whether the full stream
 * is contiguously mapped or if only a partial prefix is available.
 */
data class StreamMappingReport(
    val protocol: StreamProtocol = StreamProtocol.UNKNOWN,
    val status: StreamMappingStatus = StreamMappingStatus.INITIALIZING,
    val manifestUrl: String? = null,
    val totalSegmentsLoaded: Int = 0,
    val totalBytesLoaded: Long = 0L,
    val minLoadedMediaTimeMs: Long = 0L,
    val maxLoadedMediaTimeMs: Long = 0L,
    val coveredDurationMs: Long = 0L,
    val totalExpectedDurationMs: Long = 0L,
    val hasGaps: Boolean = false,
    val summaryMessage: String = "Aguardando carregamento de segmentos...",
    val lastSegmentUrl: String? = null
)

/**
 * ExoPlayer [AnalyticsListener] that intercepts, logs and validates fragment/segment loading
 * for HLS (.m3u8, .ts, .m4s), DASH (.mpd, .m4s), ABYS (.fd, /sora/) and Progressive streams.
 *
 * Provides real-time logcat diagnostics and reactive StateFlow telemetry for UI inspection.
 */
@OptIn(UnstableApi::class)
class StreamSegmentMonitorListener(
    private val tag: String = "StreamSegmentMonitor",
    private val onSegmentLogged: ((StreamSegmentRecord) -> Unit)? = null,
    private val onReportUpdated: ((StreamMappingReport) -> Unit)? = null
) : AnalyticsListener {

    private val segmentCounter = AtomicLong(0L)
    private val _records = CopyOnWriteArrayList<StreamSegmentRecord>()
    private val _recordsFlow = MutableStateFlow<List<StreamSegmentRecord>>(emptyList())
    val recordsFlow: StateFlow<List<StreamSegmentRecord>> = _recordsFlow.asStateFlow()

    private val _reportFlow = MutableStateFlow(StreamMappingReport())
    val reportFlow: StateFlow<StreamMappingReport> = _reportFlow.asStateFlow()

    private var totalStreamDurationMs: Long = 0L
    private var detectedProtocol: StreamProtocol = StreamProtocol.UNKNOWN
    private var manifestUri: String? = null

    // Track intervals for gap detection [startMs, endMs]
    private val loadedIntervals = CopyOnWriteArrayList<Pair<Long, Long>>()

    override fun onLoadStarted(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        val uriStr = loadEventInfo.dataSpec.uri.toString()
        val protocol = detectProtocol(uriStr, mediaLoadData)
        val trackDesc = getTrackTypeString(mediaLoadData.trackType)

        Log.d(tag, "⏳ [LOAD STARTED] Protocol: $protocol | Track: $trackDesc | URL: $uriStr | Range: ${loadEventInfo.dataSpec.position}-${if (loadEventInfo.dataSpec.length != C.LENGTH_UNSET.toLong()) loadEventInfo.dataSpec.position + loadEventInfo.dataSpec.length else "end"}")
    }

    override fun onLoadCompleted(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        val uriStr = loadEventInfo.dataSpec.uri.toString()
        val protocol = detectProtocol(uriStr, mediaLoadData)
        if (detectedProtocol == StreamProtocol.UNKNOWN && protocol != StreamProtocol.UNKNOWN) {
            detectedProtocol = protocol
        }

        val trackDesc = getTrackTypeString(mediaLoadData.trackType)
        val startMs = if (mediaLoadData.mediaStartTimeMs != C.TIME_UNSET) mediaLoadData.mediaStartTimeMs else 0L
        val endMs = if (mediaLoadData.mediaEndTimeMs != C.TIME_UNSET) mediaLoadData.mediaEndTimeMs else 0L
        val durationMs = if (endMs > startMs) endMs - startMs else 0L

        val httpStatus = extractHttpStatus(loadEventInfo)
        val formatDesc = mediaLoadData.trackFormat?.let { fmt ->
            "${fmt.sampleMimeType ?: "unknown"}, ${fmt.width}x${fmt.height}, ${fmt.bitrate}bps"
        }

        val record = StreamSegmentRecord(
            id = segmentCounter.incrementAndGet(),
            uri = uriStr,
            protocol = protocol,
            trackType = trackDesc,
            mediaStartTimeMs = startMs,
            mediaEndTimeMs = endMs,
            durationMs = durationMs,
            byteOffset = loadEventInfo.dataSpec.position,
            bytesLoaded = loadEventInfo.bytesLoaded,
            loadDurationMs = loadEventInfo.loadDurationMs,
            httpStatus = httpStatus,
            isSuccess = true,
            formatDescription = formatDesc
        )

        _records.add(record)
        // Keep in-memory list bounded to latest 200 items for memory efficiency
        if (_records.size > 200) {
            _records.removeAt(0)
        }
        _recordsFlow.value = _records.toList()

        // Track timeline coverage for non-manifest segments
        if (durationMs > 0L) {
            loadedIntervals.add(Pair(startMs, endMs))
        }

        if (isManifest(uriStr)) {
            manifestUri = uriStr
        }

        val updatedReport = computeUpdatedReport(record)
        _reportFlow.value = updatedReport

        Log.i(
            tag,
            "✅ [SEGMENT LOADED] #${record.id} [${record.protocol}] URL: ${record.uri} | " +
                    "Bytes: ${record.bytesLoaded} (${formatBytes(record.bytesLoaded)}) | " +
                    "Timeline: ${formatTime(record.mediaStartTimeMs)} -> ${formatTime(record.mediaEndTimeMs)} | " +
                    "Duration: ${record.loadDurationMs}ms | Status: ${record.httpStatus} | " +
                    "Validation: ${updatedReport.status}"
        )

        onSegmentLogged?.invoke(record)
        onReportUpdated?.invoke(updatedReport)
    }

    override fun onLoadError(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        val uriStr = loadEventInfo.dataSpec.uri.toString()
        val protocol = detectProtocol(uriStr, mediaLoadData)
        val trackDesc = getTrackTypeString(mediaLoadData.trackType)
        val httpStatus = extractHttpStatus(loadEventInfo)

        val record = StreamSegmentRecord(
            id = segmentCounter.incrementAndGet(),
            uri = uriStr,
            protocol = protocol,
            trackType = trackDesc,
            mediaStartTimeMs = if (mediaLoadData.mediaStartTimeMs != C.TIME_UNSET) mediaLoadData.mediaStartTimeMs else 0L,
            mediaEndTimeMs = if (mediaLoadData.mediaEndTimeMs != C.TIME_UNSET) mediaLoadData.mediaEndTimeMs else 0L,
            durationMs = 0L,
            byteOffset = loadEventInfo.dataSpec.position,
            bytesLoaded = loadEventInfo.bytesLoaded,
            loadDurationMs = loadEventInfo.loadDurationMs,
            httpStatus = httpStatus,
            isSuccess = false,
            errorMessage = error.message ?: if (wasCanceled) "Carregamento cancelado" else "Erro de I/O desconhecido"
        )

        _records.add(record)
        _recordsFlow.value = _records.toList()

        val updatedReport = _reportFlow.value.copy(
            status = if (wasCanceled) _reportFlow.value.status else StreamMappingStatus.SEGMENT_ERROR,
            summaryMessage = if (wasCanceled) "Segmento cancelado pelo player" else "Falha ao carregar segmento: ${error.message}",
            lastSegmentUrl = uriStr
        )
        _reportFlow.value = updatedReport

        if (wasCanceled) {
            Log.d(tag, "⏹ [SEGMENT CANCELED] URL: $uriStr | Bytes: ${loadEventInfo.bytesLoaded}")
        } else {
            Log.e(tag, "❌ [SEGMENT ERROR] URL: $uriStr | Error: ${error.message} | HTTP Status: $httpStatus", error)
        }

        onSegmentLogged?.invoke(record)
        onReportUpdated?.invoke(updatedReport)
    }

    override fun onTimelineChanged(eventTime: EventTime, reason: Int) {
        val timeline = eventTime.timeline
        if (!timeline.isEmpty) {
            val window = Timeline.Window()
            timeline.getWindow(0, window)
            if (window.durationMs != C.TIME_UNSET && window.durationMs > 0L) {
                totalStreamDurationMs = window.durationMs
                _reportFlow.update { current ->
                    current.copy(
                        totalExpectedDurationMs = totalStreamDurationMs,
                        status = evaluateMappingStatus(current.totalSegmentsLoaded, current.coveredDurationMs, totalStreamDurationMs)
                    )
                }
                Log.d(tag, "⏱ [TIMELINE UPDATED] Total Duration: ${formatTime(totalStreamDurationMs)} (${totalStreamDurationMs}ms)")
            }
        }
    }

    private fun computeUpdatedReport(latestRecord: StreamSegmentRecord): StreamMappingReport {
        val totalCount = _records.count { it.isSuccess }
        val totalBytes = _records.filter { it.isSuccess }.sumOf { it.bytesLoaded }

        // Compute merged timeline coverage and gap detection
        val sortedIntervals = loadedIntervals.sortedBy { it.first }
        var minTime = Long.MAX_VALUE
        var maxTime = 0L
        var totalCovered = 0L
        var hasGaps = false

        if (sortedIntervals.isNotEmpty()) {
            minTime = sortedIntervals.first().first
            maxTime = sortedIntervals.maxOf { it.second }

            var currentMergeStart = sortedIntervals[0].first
            var currentMergeEnd = sortedIntervals[0].second

            for (i in 1 until sortedIntervals.size) {
                val next = sortedIntervals[i]
                if (next.first <= currentMergeEnd + 200) { // allow 200ms timestamp jitter
                    currentMergeEnd = max(currentMergeEnd, next.second)
                } else {
                    totalCovered += (currentMergeEnd - currentMergeStart)
                    hasGaps = true
                    currentMergeStart = next.first
                    currentMergeEnd = next.second
                }
            }
            totalCovered += (currentMergeEnd - currentMergeStart)
        } else {
            minTime = 0L
            maxTime = 0L
            totalCovered = 0L
        }

        val status = evaluateMappingStatus(totalCount, totalCovered, totalStreamDurationMs)
        val summary = generateSummaryMessage(status, totalCount, totalBytes, totalCovered, totalStreamDurationMs)

        return StreamMappingReport(
            protocol = detectedProtocol,
            status = status,
            manifestUrl = manifestUri,
            totalSegmentsLoaded = totalCount,
            totalBytesLoaded = totalBytes,
            minLoadedMediaTimeMs = if (minTime == Long.MAX_VALUE) 0L else minTime,
            maxLoadedMediaTimeMs = maxTime,
            coveredDurationMs = totalCovered,
            totalExpectedDurationMs = totalStreamDurationMs,
            hasGaps = hasGaps,
            summaryMessage = summary,
            lastSegmentUrl = latestRecord.uri
        )
    }

    private fun evaluateMappingStatus(
        segmentCount: Int,
        coveredMs: Long,
        expectedDurationMs: Long
    ): StreamMappingStatus {
        if (segmentCount == 0) return StreamMappingStatus.INITIALIZING

        if (detectedProtocol == StreamProtocol.ABYS) {
            // If ABYS has only loaded 1 part and bytes <= 21MB while movie is large
            val abysBytes = _records.filter { it.isSuccess }.sumOf { it.bytesLoaded }
            return if (segmentCount == 1 && abysBytes <= 20_971_520L && expectedDurationMs > 120_000L) {
                StreamMappingStatus.PREFIX_ONLY_WARNING
            } else if (segmentCount >= 2 || abysBytes > 20_971_520L) {
                StreamMappingStatus.MAPPING_VERIFIED
            } else {
                StreamMappingStatus.FETCHING_SEGMENTS
            }
        }

        if (expectedDurationMs > 0L) {
            val progressRatio = coveredMs.toDouble() / expectedDurationMs.toDouble()
            return when {
                progressRatio >= 0.85 -> StreamMappingStatus.MAPPING_VERIFIED
                segmentCount >= 2 -> StreamMappingStatus.FETCHING_SEGMENTS
                manifestUri != null -> StreamMappingStatus.MANIFEST_DETECTED
                else -> StreamMappingStatus.INITIALIZING
            }
        }

        return if (segmentCount >= 3) {
            StreamMappingStatus.MAPPING_VERIFIED
        } else if (manifestUri != null) {
            StreamMappingStatus.MANIFEST_DETECTED
        } else {
            StreamMappingStatus.FETCHING_SEGMENTS
        }
    }

    private fun generateSummaryMessage(
        status: StreamMappingStatus,
        count: Int,
        bytes: Long,
        coveredMs: Long,
        expectedMs: Long
    ): String {
        val bytesStr = formatBytes(bytes)
        val timelineStr = if (expectedMs > 0L) {
            "${formatTime(coveredMs)} de ${formatTime(expectedMs)} (${(coveredMs * 100 / expectedMs)}%)"
        } else {
            formatTime(coveredMs)
        }

        return when (status) {
            StreamMappingStatus.INITIALIZING -> "Iniciando monitoramento de fluxo..."
            StreamMappingStatus.MANIFEST_DETECTED -> "Manifesto de streaming detectado. Carregando fragmentos..."
            StreamMappingStatus.FETCHING_SEGMENTS -> "Carregando fragmentos: $count segmentos ($bytesStr) • Linha do tempo: $timelineStr"
            StreamMappingStatus.MAPPING_VERIFIED -> "Fluxo completo mapeado com sucesso! ($count segmentos, $bytesStr, $timelineStr)"
            StreamMappingStatus.PREFIX_ONLY_WARNING -> "⚠️ Alerta: Apenas o prefixo inicial foi carregado ($bytesStr). Fluxo subsequente pendente."
            StreamMappingStatus.SEGMENT_ERROR -> "Erro detectado no carregamento de fragmentos de rede."
        }
    }

    private fun detectProtocol(uri: String, mediaLoadData: MediaLoadData): StreamProtocol {
        val lower = uri.lowercase()
        return when {
            lower.contains(".m3u8") || lower.contains(".ts") || lower.contains("/hls") ||
                    lower.contains("playlist.m3u8") || lower.contains("chunklist") -> StreamProtocol.HLS
            lower.contains(".mpd") || lower.contains("/dash") ||
                    (lower.contains(".m4s") && !lower.contains("hls")) -> StreamProtocol.DASH
            com.example.util.AbysDedicatedEngine.isAbysUrl(uri) -> StreamProtocol.ABYS
            lower.contains(".mp4") || lower.contains(".m4v") || lower.contains(".webm") -> StreamProtocol.PROGRESSIVE_MP4
            else -> detectedProtocol
        }
    }

    private fun isManifest(uri: String): Boolean {
        val lower = uri.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains("manifest")
    }

    private fun extractHttpStatus(loadEventInfo: LoadEventInfo): Int {
        val headers = loadEventInfo.responseHeaders
        for (entry in headers.entries) {
            if (entry.key.equals(":status", ignoreCase = true) || entry.key.equals("status", ignoreCase = true)) {
                val line = entry.value.firstOrNull() ?: continue
                val match = Regex("""(?:HTTP/\d(?:\.\d)?\s+)?(\d{3})""").find(line)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull() ?: 200
                }
            }
        }
        return 200
    }

    private fun getTrackTypeString(trackType: Int): String {
        return when (trackType) {
            C.TRACK_TYPE_DEFAULT -> "Default/Manifest"
            C.TRACK_TYPE_AUDIO -> "Audio"
            C.TRACK_TYPE_VIDEO -> "Video"
            C.TRACK_TYPE_TEXT -> "Subtitles"
            C.TRACK_TYPE_METADATA -> "Metadata"
            C.TRACK_TYPE_CAMERA_MOTION -> "Camera Motion"
            C.TRACK_TYPE_IMAGE -> "Image/Thumbnail"
            C.TRACK_TYPE_NONE -> "None"
            else -> "Track($trackType)"
        }
    }

    fun getLoadedUrls(): List<String> {
        return _records.map { it.uri }.distinct()
    }

    fun clear() {
        segmentCounter.set(0L)
        _records.clear()
        _recordsFlow.value = emptyList()
        loadedIntervals.clear()
        totalStreamDurationMs = 0L
        detectedProtocol = StreamProtocol.UNKNOWN
        manifestUri = null
        _reportFlow.value = StreamMappingReport()
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(java.util.Locale.US, "%.1f KB", kb)
                else -> "$bytes B"
            }
        }

        fun formatTime(ms: Long): String {
            if (ms <= 0L) return "00:00"
            val totalSeconds = ms / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }
}
