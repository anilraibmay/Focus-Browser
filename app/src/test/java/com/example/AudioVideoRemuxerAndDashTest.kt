package com.example

import com.example.util.AudioVideoRemuxer
import com.example.util.DashPipelineDownloader
import com.arthenica.ffmpegkit.FFmpegKit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioVideoRemuxerAndDashTest {

    @Test
    fun testAudioVideoRemuxer_handlesInvalidInputsGracefully() {
        val nonExistentVideo = File("non_existent_video.mp4")
        val nonExistentAudio = File("non_existent_audio.aac")
        val outputFile = File("non_existent_out.mp4")

        val result = AudioVideoRemuxer.mux(nonExistentVideo, nonExistentAudio, outputFile)
        assertFalse("Mux deve retornar false quando entradas não existem", result)
    }

    @Test
    fun testAudioVideoRemuxer_handlesEmptyFilesGracefully() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: ".")
        val emptyVideo = File(tempDir, "empty_test_v.mp4").apply {
            createNewFile()
            deleteOnExit()
        }
        val emptyAudio = File(tempDir, "empty_test_a.aac").apply {
            createNewFile()
            deleteOnExit()
        }
        val output = File(tempDir, "empty_test_out.mp4").apply {
            deleteOnExit()
        }

        val result = AudioVideoRemuxer.mux(emptyVideo, emptyAudio, output)
        assertFalse("Mux deve retornar false para arquivos vazios", result)
    }

    @Test
    fun testFFmpegKit_classIsAccessibleAndLoaded() {
        // Verifica que o wrapper e classes do FFmpegKit estão presentes e acessíveis
        assertNotNull(FFmpegKit::class.java)
    }

    @Test
    fun testDashIsoDurationParsing() {
        val downloader = DashPipelineDownloader()
        assertEquals(3750.0, downloader.parseIsoDuration("PT1H2M30S"), 0.001)
        assertEquals(300.0, downloader.parseIsoDuration("PT5M"), 0.001)
        assertEquals(45.5, downloader.parseIsoDuration("PT45.5S"), 0.001)
        assertEquals(0.0, downloader.parseIsoDuration(""), 0.001)
        assertEquals(0.0, downloader.parseIsoDuration(null), 0.001)
    }

    @Test
    fun testDashMpdParsing_SegmentTimelineAndTimeVariable() {
        val downloader = DashPipelineDownloader()
        val mockMpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT1M0S">
                <Period>
                    <AdaptationSet mimeType="video/mp4" contentType="video">
                        <SegmentTemplate initialization="init-video.mp4" media="chunk-video-${'$'}Time${'$'}.m4s" timescale="1000">
                            <SegmentTimeline>
                                <S t="0" d="2000" r="2" />
                                <S t="6000" d="4000" />
                            </SegmentTimeline>
                        </SegmentTemplate>
                        <Representation id="v1080" bandwidth="4500000" width="1920" height="1080" codecs="avc1.640028" />
                    </AdaptationSet>
                    <AdaptationSet mimeType="audio/mp4" contentType="audio">
                        <SegmentTemplate initialization="init-audio.mp4" media="chunk-audio-${'$'}Time${'$'}.m4s" timescale="1000">
                            <SegmentTimeline>
                                <S t="0" d="2000" r="1" />
                            </SegmentTimeline>
                        </SegmentTemplate>
                        <Representation id="a128" bandwidth="128000" codecs="mp4a.40.2" />
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()

        val (videoRep, audioRep) = downloader.parseMpd("https://stream.example.com/live/manifest.mpd", mockMpd)
        assertNotNull("Representação de vídeo deve ser extraída", videoRep)
        assertNotNull("Representação de áudio deve ser extraída", audioRep)

        assertEquals("v1080", videoRep!!.id)
        assertEquals(1920, videoRep.width)
        assertEquals(1080, videoRep.height)
        assertEquals(60.0, videoRep.durationSecs, 0.001)

        val videoUrls = downloader.resolveSegmentUrls(videoRep, "https://stream.example.com/live/manifest.mpd")
        // init + 4 segmentos (t=0, t=2000, t=4000, t=6000) = 5 URLs no total
        assertEquals(5, videoUrls.size)
        assertEquals("https://stream.example.com/live/init-video.mp4", videoUrls[0])
        assertEquals("https://stream.example.com/live/chunk-video-0.m4s", videoUrls[1])
        assertEquals("https://stream.example.com/live/chunk-video-2000.m4s", videoUrls[2])
        assertEquals("https://stream.example.com/live/chunk-video-4000.m4s", videoUrls[3])
        assertEquals("https://stream.example.com/live/chunk-video-6000.m4s", videoUrls[4])
    }

    @Test
    fun testDashMpdParsing_SegmentListAndBaseUrl() {
        val downloader = DashPipelineDownloader()
        val mockMpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
                <BaseURL>https://cdn.example.com/dash/</BaseURL>
                <Period>
                    <AdaptationSet mimeType="video/mp4" contentType="video">
                        <Representation id="v720" bandwidth="2500000" width="1280" height="720">
                            <SegmentList>
                                <Initialization sourceURL="init-720p.mp4"/>
                                <SegmentURL media="segment-1.m4s"/>
                                <SegmentURL media="segment-2.m4s"/>
                                <SegmentURL media="segment-3.m4s"/>
                            </SegmentList>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()

        val (videoRep, _) = downloader.parseMpd("https://origin.example.com/manifest.mpd", mockMpd)
        assertNotNull(videoRep)
        assertEquals("v720", videoRep!!.id)

        val urls = downloader.resolveSegmentUrls(videoRep, "https://origin.example.com/manifest.mpd")
        assertEquals(4, urls.size)
        assertEquals("https://cdn.example.com/dash/init-720p.mp4", urls[0])
        assertEquals("https://cdn.example.com/dash/segment-1.m4s", urls[1])
        assertEquals("https://cdn.example.com/dash/segment-2.m4s", urls[2])
        assertEquals("https://cdn.example.com/dash/segment-3.m4s", urls[3])
    }
}
