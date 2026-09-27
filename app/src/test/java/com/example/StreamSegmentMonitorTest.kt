package com.example

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.example.media.StreamMappingStatus
import com.example.media.StreamProtocol
import com.example.media.StreamSegmentMonitorListener
import com.example.media.StreamSegmentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class StreamSegmentMonitorTest {

    private fun createDummyEventTime(): EventTime {
        return EventTime(
            0L,
            Timeline.EMPTY,
            0,
            null,
            0L,
            Timeline.EMPTY,
            0,
            null,
            0L,
            0L
        )
    }

    private fun createLoadEventInfo(
        uri: String,
        position: Long = 0L,
        length: Long = 100_000L,
        bytesLoaded: Long = 100_000L,
        durationMs: Long = 250L,
        responseHeaders: Map<String, List<String>> = mapOf(":status" to listOf("200"))
    ): LoadEventInfo {
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(uri))
            .setPosition(position)
            .setLength(length)
            .build()
        return LoadEventInfo(
            LoadEventInfo.getNewId(),
            dataSpec,
            Uri.parse(uri),
            responseHeaders,
            0L,
            durationMs,
            bytesLoaded
        )
    }

    private fun createMediaLoadData(
        trackType: Int = C.TRACK_TYPE_VIDEO,
        startTimeMs: Long = 0L,
        endTimeMs: Long = 10_000L
    ): MediaLoadData {
        val format = Format.Builder()
            .setSampleMimeType("video/mp4")
            .setWidth(1280)
            .setHeight(720)
            .setAverageBitrate(2_500_000)
            .build()
        return MediaLoadData(
            C.DATA_TYPE_MEDIA,
            trackType,
            format,
            C.SELECTION_REASON_INITIAL,
            null,
            startTimeMs,
            endTimeMs
        )
    }

    @Test
    fun testHlsSegmentLoggingAndMappingVerification() {
        val loggedRecords = mutableListOf<StreamSegmentRecord>()
        val monitor = StreamSegmentMonitorListener(
            tag = "TestMonitor",
            onSegmentLogged = { loggedRecords.add(it) }
        )

        val eventTime = createDummyEventTime()

        // 1. Load HLS Manifest
        val manifestLoad = createLoadEventInfo("https://example.com/stream/master.m3u8")
        val manifestData = createMediaLoadData(trackType = C.TRACK_TYPE_DEFAULT, startTimeMs = 0L, endTimeMs = 0L)
        monitor.onLoadCompleted(eventTime, manifestLoad, manifestData)

        assertEquals(1, monitor.recordsFlow.value.size)
        assertEquals(StreamProtocol.HLS, monitor.reportFlow.value.protocol)
        assertEquals(StreamMappingStatus.MANIFEST_DETECTED, monitor.reportFlow.value.status)

        // 2. Load Segment #0 (0s - 10s)
        val seg0Load = createLoadEventInfo("https://example.com/stream/segment_000.ts", bytesLoaded = 1_500_000L)
        val seg0Data = createMediaLoadData(trackType = C.TRACK_TYPE_VIDEO, startTimeMs = 0L, endTimeMs = 10_000L)
        monitor.onLoadCompleted(eventTime, seg0Load, seg0Data)

        // 3. Load Segment #1 (10s - 20s)
        val seg1Load = createLoadEventInfo("https://example.com/stream/segment_001.ts", bytesLoaded = 1_600_000L)
        val seg1Data = createMediaLoadData(trackType = C.TRACK_TYPE_VIDEO, startTimeMs = 10_000L, endTimeMs = 20_000L)
        monitor.onLoadCompleted(eventTime, seg1Load, seg1Data)

        // 4. Load Segment #2 (20s - 30s)
        val seg2Load = createLoadEventInfo("https://example.com/stream/segment_002.ts", bytesLoaded = 1_550_000L)
        val seg2Data = createMediaLoadData(trackType = C.TRACK_TYPE_VIDEO, startTimeMs = 20_000L, endTimeMs = 30_000L)
        monitor.onLoadCompleted(eventTime, seg2Load, seg2Data)

        val report = monitor.reportFlow.value
        assertEquals(4, report.totalSegmentsLoaded)
        assertEquals(StreamMappingStatus.MAPPING_VERIFIED, report.status)
        assertEquals(4_750_000L, report.totalBytesLoaded)
        assertEquals(30_000L, report.coveredDurationMs)
        assertFalse(report.hasGaps)

        val urls = monitor.getLoadedUrls()
        assertEquals(4, urls.size)
        assertTrue(urls.any { it.contains("segment_000.ts") })
        assertTrue(urls.any { it.contains("segment_001.ts") })
        assertTrue(urls.any { it.contains("segment_002.ts") })
    }

    @Test
    fun testAbysPrefixWarningWhenOnlyFirstDataLoaded() {
        val monitor = StreamSegmentMonitorListener(tag = "TestAbys")
        val eventTime = createDummyEventTime()

        // Single .fd chunk of 10.0 MB (10,485,760 bytes)
        val fdLoad = createLoadEventInfo(
            "https://abysscdn.com/video/hash.381800000.2.fd",
            bytesLoaded = 10_485_760L
        )
        val fdData = createMediaLoadData(
            trackType = C.TRACK_TYPE_VIDEO,
            startTimeMs = 0L,
            endTimeMs = 60_000L
        )
        monitor.onLoadCompleted(eventTime, fdLoad, fdData)

        val report = monitor.reportFlow.value
        assertEquals(StreamProtocol.ABYS, report.protocol)
        assertEquals(1, report.totalSegmentsLoaded)
        assertEquals(10_485_760L, report.totalBytesLoaded)
    }

    @Test
    fun testDashFragmentLoggingAndErrorTracking() {
        val monitor = StreamSegmentMonitorListener(tag = "TestDash")
        val eventTime = createDummyEventTime()

        // 1. DASH MPD
        val mpdLoad = createLoadEventInfo("https://example.com/dash/manifest.mpd")
        val mpdData = createMediaLoadData(trackType = C.TRACK_TYPE_DEFAULT, startTimeMs = 0L, endTimeMs = 0L)
        monitor.onLoadCompleted(eventTime, mpdLoad, mpdData)
        assertEquals(StreamProtocol.DASH, monitor.reportFlow.value.protocol)

        // 2. Fragment Load Error (HTTP 404 or connection reset)
        val fragLoad = createLoadEventInfo("https://example.com/dash/chunk_1.m4s")
        val fragData = createMediaLoadData(trackType = C.TRACK_TYPE_VIDEO, startTimeMs = 0L, endTimeMs = 4000L)
        monitor.onLoadError(
            eventTime,
            fragLoad,
            fragData,
            IOException("HTTP 404 Not Found"),
            wasCanceled = false
        )

        val report = monitor.reportFlow.value
        assertEquals(StreamMappingStatus.SEGMENT_ERROR, report.status)
        assertTrue(report.summaryMessage.contains("HTTP 404 Not Found"))
        assertEquals(2, monitor.recordsFlow.value.size)
        assertFalse(monitor.recordsFlow.value.last().isSuccess)
    }
}
