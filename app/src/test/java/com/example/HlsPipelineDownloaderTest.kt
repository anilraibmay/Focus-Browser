package com.example

import com.example.util.HlsPipelineDownloader
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsPipelineDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testHlsPipelineDownloader_SequentialConcatenation() = runBlocking {
        val masterUrl = "https://cdn.example.com/live/master.m3u8"
        val mediaUrl = "https://cdn.example.com/live/720p.m3u8"
        val seg0Url = "https://cdn.example.com/live/seg0.ts"
        val seg1Url = "https://cdn.example.com/live/seg1.ts"
        val seg2Url = "https://cdn.example.com/live/seg2.ts"

        val masterContent = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            720p.m3u8
        """.trimIndent()

        val mediaContent = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:100
            #EXTINF:4.0,
            seg0.ts
            #EXTINF:4.0,
            seg1.ts
            #EXTINF:4.0,
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val seg0Bytes = "SEGMENT_ZERO_DATA_".toByteArray()
        val seg1Bytes = "SEGMENT_ONE_DATA_".toByteArray()
        val seg2Bytes = "SEGMENT_TWO_DATA_END".toByteArray()

        val mockInterceptor = Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            val (bodyBytes, mime) = when (url) {
                masterUrl -> masterContent.toByteArray() to "application/vnd.apple.mpegurl"
                mediaUrl -> mediaContent.toByteArray() to "application/vnd.apple.mpegurl"
                seg0Url -> seg0Bytes to "video/mp2t"
                seg1Url -> seg1Bytes to "video/mp2t"
                seg2Url -> seg2Bytes to "video/mp2t"
                else -> throw IllegalArgumentException("URL mock não encontrada: $url")
            }

            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bodyBytes.toResponseBody(mime.toMediaType()))
                .build()
        }

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(mockInterceptor)
            .build()

        val downloader = HlsPipelineDownloader(mockClient)
        val outputFile = File(tempFolder.root, "output_video.mp4")

        val progressReports = mutableListOf<Pair<Int, Int>>()

        downloader.download(
            url = masterUrl,
            targetFile = outputFile,
            concurrency = 2,
            maxInFlight = 3,
            onProgress = { _, _, currentSeg, totalSegs ->
                progressReports.add(currentSeg to totalSegs)
            }
        )

        // Assert file exists and concatenated bytes match exactly
        assertTrue(outputFile.exists())
        val expectedBytes = seg0Bytes + seg1Bytes + seg2Bytes
        assertArrayEquals(expectedBytes, outputFile.readBytes())

        // Assert progress reached 100% (3 of 3 segments)
        assertTrue(progressReports.isNotEmpty())
        assertEquals(3 to 3, progressReports.last())
    }

    @Test
    fun testHlsPipelineDownloader_Aes128Decryption() = runBlocking {
        val mediaUrl = "https://cdn.example.com/enc/playlist.m3u8"
        val keyUrl = "https://cdn.example.com/enc/video.key"
        val segUrl = "https://cdn.example.com/enc/seg0.ts"

        val keyBytes = "1234567890123456".toByteArray() // 16 bytes AES key
        val ivBytes = "abcdefghijklmnop".toByteArray()  // 16 bytes IV
        val ivHex = "0x" + ivBytes.joinToString("") { "%02x".format(it) }

        val plainText = "DECRYPTED_HLS_STREAM_DATA".toByteArray()

        // Encrypt plainText using AES/CBC/PKCS7Padding
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val encryptedBytes = cipher.doFinal(plainText)

        val mediaContent = """
            #EXTM3U
            #EXT-X-TARGETDURATION:5
            #EXT-X-KEY:METHOD=AES-128,URI="$keyUrl",IV=$ivHex
            #EXTINF:5.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val mockInterceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()

            val (bodyBytes, mime) = when (url) {
                mediaUrl -> mediaContent.toByteArray() to "application/vnd.apple.mpegurl"
                keyUrl -> keyBytes to "application/octet-stream"
                segUrl -> encryptedBytes to "video/mp2t"
                else -> throw IllegalArgumentException("URL mock não encontrada: $url")
            }

            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bodyBytes.toResponseBody(mime.toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder().addInterceptor(mockInterceptor).build()
        val downloader = HlsPipelineDownloader(client)
        val outputFile = File(tempFolder.root, "decrypted_video.mp4")

        downloader.download(
            url = mediaUrl,
            targetFile = outputFile,
            onProgress = { _, _, _, _ -> }
        )

        assertTrue(outputFile.exists())
        assertArrayEquals(plainText, outputFile.readBytes())
    }
}
