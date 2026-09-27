package com.example

import com.example.extractor.VideoQualityHelper
import com.example.util.AbysQualityEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AbysQualityEngineTest {

    @Test
    fun testParseFromFdUrl() {
        val url = "https://abysscdn.com/9/5/b/e63a6e9602c10ab2b0527e9d37392.232643771.4.fd"
        val info = AbysQualityEngine.parseFromFdUrl(url)

        assertNotNull(info)
        assertEquals("e63a6e9602c10ab2b0527e9d37392", info!!.hash)
        assertEquals(232643771L, info.sizeBytes)
        assertEquals(4, info.resId)
        assertEquals("720p HD", info.resolutionLabel)
        assertEquals("221.9 MB", info.formattedSize)

        // Test detection via VideoQualityHelper
        val detected = VideoQualityHelper.detectQualityFromUrl(url)
        assertEquals("720p HD", detected)
    }

    @Test
    fun testParse1080pAnd4kFdUrls() {
        val url1080p = "https://abysscdn.com/video/abc12345678901234567890123456789.512000000.5.fd"
        val info1080 = AbysQualityEngine.parseFromFdUrl(url1080p)
        assertNotNull(info1080)
        assertEquals(5, info1080!!.resId)
        assertEquals("1080p Full HD", info1080.resolutionLabel)

        val url4k = "https://abysscdn.com/video/abc12345678901234567890123456789.1500000000.7.fd"
        val info4k = AbysQualityEngine.parseFromFdUrl(url4k)
        assertNotNull(info4k)
        assertEquals(7, info4k!!.resId)
        assertEquals("4K 2160p", info4k.resolutionLabel)
    }

    @Test
    fun testParseMediaJsonPayload() {
        val sampleJson = """
            {
              "sources": [
                { "res_id": 3, "label": "480p", "codec": "avc1", "size": 120000000, "sub": "s1" },
                { "res_id": 4, "label": "720p", "codec": "avc1", "size": 232643771, "sub": "s1" },
                { "res_id": 5, "label": "1080p", "codec": "avc1", "size": 512000000, "sub": "s2" }
              ],
              "fristDatas": [
                { "res_id": 3, "codec": "avc1", "size": 120000000, "partSize": 20971520, "url": "https://abysscdn.com/video/hash.120000000.3.fd" },
                { "res_id": 4, "codec": "avc1", "size": 232643771, "partSize": 20971520, "url": "https://abysscdn.com/video/hash.232643771.4.fd" },
                { "res_id": 5, "codec": "avc1", "size": 512000000, "partSize": 20971520, "url": "https://abysscdn.com/video/hash.512000000.5.fd" }
              ]
            }
        """.trimIndent()

        val bundle = AbysQualityEngine.parseMediaJson(sampleJson, pageTitle = "Test Anime Ep 01", explicitDurationSecs = 1440.0)
        assertNotNull(bundle)
        assertEquals(3, bundle!!.streams.size)

        // Ordered descending by resolution (1080p, 720p, 480p)
        val s1080 = bundle.streams[0]
        assertEquals(5, s1080.resId)
        assertEquals("1080p", s1080.label)
        assertEquals("https://abysscdn.com/video/hash.512000000.5.fd", s1080.directFdUrl)
        assertNotNull(s1080.estimatedBitrateMbps)
        assertTrue(s1080.estimatedBitrateMbps!! > 0)

        val s720 = bundle.streams[1]
        assertEquals(4, s720.resId)
        assertEquals("720p", s720.label)

        val s480 = bundle.streams[2]
        assertEquals(3, s480.resId)
        assertEquals("480p", s480.label)

        // Conversion to Focus ExtractedMediaGroup
        val mediaGroup = AbysQualityEngine.convertToMediaGroup(bundle, pageTitle = "Test Anime Ep 01")
        assertEquals(3, mediaGroup.streams.size)
        assertEquals("Test Anime Ep 01", mediaGroup.title)
        assertEquals("1080p", mediaGroup.streams[0].resolutionLabel)
        assertEquals("720p", mediaGroup.streams[1].resolutionLabel)
        assertEquals("480p", mediaGroup.streams[2].resolutionLabel)
    }
}
