package com.example

import com.example.extractor.ExtractedVideo
import com.example.extractor.VideoType
import com.example.util.AbysDedicatedEngine
import com.example.util.AbysNameResolver
import com.example.util.VideoFileNameGenerator
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class AbysVerificationAndNameTest {

    @Test
    fun testAbysNameResolver_ShopForKillers() {
        val rawTitle = "A.Shop.for.Killers.S01E01.720p.MP4.LEG.BaixarSeriesMP4.pm.mp4"
        val parsed = AbysNameResolver.parseFields(rawTitle)

        assertEquals("A Shop for Killers", parsed.show)
        assertEquals(1, parsed.season)
        assertEquals(1, parsed.episode)
        assertEquals("S01E01", parsed.se)
        assertEquals("720p", parsed.resolution)

        val meta = AbysNameResolver.resolveMetadata(rawTitle, slug = "oQDjHTSeQ", md5Id = "23557934")
        assertEquals("A Shop for Killers - S01E01 - 720p.mp4", meta.suggestedFileName)
        assertEquals("A Shop for Killers", meta.suggestedFolder)
    }

    @Test
    fun testAbysNameResolver_LojaParaAssassinos() {
        val rawTitle = "Loja.Para.Assassinos.S01E01.Murthehelp.1080p.DSNP.WEB-DL.DDP5.1.H.264.DUAL-SiGLA_EMBEDPLAY.mp4"
        val parsed = AbysNameResolver.parseFields(rawTitle)

        assertEquals("Loja Para Assassinos", parsed.show)
        assertEquals(1, parsed.season)
        assertEquals(1, parsed.episode)
        assertEquals("S01E01", parsed.se)
        assertEquals("1080p", parsed.resolution)

        val meta = AbysNameResolver.resolveMetadata(rawTitle, slug = "2q7oyEYTh")
        assertEquals("Loja Para Assassinos - S01E01 - 1080p.mp4", meta.suggestedFileName)
    }

    @Test
    fun testAbysNameResolver_NxNN_and_PortuguesePatterns() {
        val title1 = "Game.of.Thrones.2x05.HDTV.mp4"
        val parsed1 = AbysNameResolver.parseFields(title1)
        assertEquals("Game of Thrones", parsed1.show)
        assertEquals(2, parsed1.season)
        assertEquals(5, parsed1.episode)
        assertEquals("S02E05", parsed1.se)

        val title2 = "Vikings Temporada 3 Episodio 4 1080p"
        val parsed2 = AbysNameResolver.parseFields(title2)
        assertEquals("Vikings", parsed2.show)
        assertEquals(3, parsed2.season)
        assertEquals(4, parsed2.episode)
        assertEquals("S03E04", parsed2.se)
        assertEquals("1080p", parsed2.resolution)
    }

    @Test
    fun testAbysNameResolver_DatasBase64Extraction() {
        val jsonPayload = """{"slug":"oQDjHTSeQ","md5_id":"23557934","user_id":"9133"}"""
        val b64 = Base64.getEncoder().encodeToString(jsonPayload.toByteArray())
        val mockHtml = """
            <!doctype html>
            <html>
            <head><title>A.Shop.for.Killers.S01E01.720p.MP4</title></head>
            <body>
            <script>
            const datas = "$b64";
            </script>
            </body>
            </html>
        """.trimIndent()

        val extractedTitle = AbysNameResolver.extractTitleFromHtml(mockHtml)
        assertEquals("A.Shop.for.Killers.S01E01.720p.MP4", extractedTitle)

        val datasMap = AbysNameResolver.extractDatasFromHtml(mockHtml)
        assertNotNull(datasMap)
        assertEquals("oQDjHTSeQ", datasMap?.get("slug"))
        assertEquals("23557934", datasMap?.get("md5_id"))
        assertEquals("9133", datasMap?.get("user_id"))
    }

    @Test
    fun testAbysVerify_ValidMp4() {
        val buffer = ByteArray(1024)
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)

        // ftyp box (32 bytes)
        bb.putInt(32)
        bb.put("ftyp".toByteArray(Charsets.ISO_8859_1))
        bb.put("mp42".toByteArray(Charsets.ISO_8859_1))
        bb.position(32)

        // moov box (128 bytes)
        bb.putInt(128)
        bb.put("moov".toByteArray(Charsets.ISO_8859_1))
        bb.position(32 + 128)

        // mdat box (declared 800 bytes)
        bb.putInt(800)
        bb.put("mdat".toByteArray(Charsets.ISO_8859_1))

        val result = AbysDedicatedEngine.verifyMp4BufferIntegrity(
            buffer = buffer,
            actualTotalSize = 1000L,
            expectedTotalBytes = 1000L
        )

        assertTrue("Should be valid MP4", result.isValidMp4)
        assertTrue(result.hasFtyp)
        assertTrue(result.hasMoov)
        assertTrue(result.hasMdat)
        assertFalse(result.isTruncated)
    }

    @Test
    fun testAbysVerify_TruncatedFirstPartOnly() {
        val buffer = ByteArray(1024)
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)

        // ftyp box (32 bytes)
        bb.putInt(32)
        bb.put("ftyp".toByteArray(Charsets.ISO_8859_1))
        bb.position(32)

        // moov box (128 bytes)
        bb.putInt(128)
        bb.put("moov".toByteArray(Charsets.ISO_8859_1))
        bb.position(32 + 128)

        // mdat box declaring 232,000,000 bytes (e.g. 232 MB full movie)
        bb.putInt(232_000_000)
        bb.put("mdat".toByteArray(Charsets.ISO_8859_1))

        // But actual file size on disk is only 20,971,520 bytes (Part 1 only!)
        val result = AbysDedicatedEngine.verifyMp4BufferIntegrity(
            buffer = buffer,
            actualTotalSize = 20_971_520L,
            expectedTotalBytes = 232_160_160L
        )

        assertFalse("Should be invalid due to truncation", result.isValidMp4)
        assertTrue("Should be flagged as truncated", result.isTruncated)
        assertTrue(result.warnings.any { it.contains("TRUNCADO") })
    }

    @Test
    fun testAbysVerify_GapDetection() {
        val buffer = ByteArray(1024)
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(32)
        bb.put("ftyp".toByteArray(Charsets.ISO_8859_1))

        // Chunks with gap between 2000 and 3000
        val chunks = listOf(
            Pair(0L, 1999L),
            Pair(3000L, 5000L)
        )

        val result = AbysDedicatedEngine.verifyMp4BufferIntegrity(
            buffer = buffer,
            actualTotalSize = 5001L,
            expectedTotalBytes = 5001L,
            chunks = chunks
        )

        assertFalse(result.isValidMp4)
        assertEquals(1, result.missingGaps.size)
        assertEquals(2000L, result.missingGaps[0].first)
        assertEquals(2999L, result.missingGaps[0].second)
    }

    @Test
    fun testVideoFileNameGenerator_AbysFormatting() {
        val video = ExtractedVideo(
            url = "https://abysscdn.com/9/5/b/e63a6e9602c10ab2b0527e9d37392.232643771.4.fd",
            headers = mapOf("X-Abys-Slug" to "oQDjHTSeQ"),
            title = "A.Shop.for.Killers.S01E01.720p.MP4.LEG.BaixarSeriesMP4.pm.mp4",
            type = VideoType.ABYS,
            quality = "720p HD"
        )

        val names = VideoFileNameGenerator.generateVideoFileNames(
            pageTitle = "Assistir A Shop for Killers",
            videos = listOf(video),
            existingNames = emptySet()
        )

        assertEquals(1, names.size)
        assertEquals("A Shop for Killers - S01E01 - 720p.mp4", names[0])
    }
}
