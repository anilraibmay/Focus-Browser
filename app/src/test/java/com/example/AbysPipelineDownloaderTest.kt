package com.example

import com.example.util.AbysDedicatedEngine
import com.example.util.AbysPipelineDownloader
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class AbysPipelineDownloaderTest {

    private var server: HttpServer? = null
    private var serverPort: Int = 0
    private var tempDir: File? = null

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "abys_dl_test_${System.currentTimeMillis()}").apply { mkdirs() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server!!.address.port
        server!!.start()
    }

    @After
    fun tearDown() {
        server?.stop(0)
        tempDir?.deleteRecursively()
    }

    private fun createEncryptedAbysPayload(filename: String, totalSize: Int = 100_000): Pair<ByteArray, ByteArray> {
        val original = ByteArray(totalSize) { (it % 251).toByte() }
        original[4] = 'f'.code.toByte()
        original[5] = 't'.code.toByte()
        original[6] = 'y'.code.toByte()
        original[7] = 'p'.code.toByte()

        val (key, iv) = AbysDedicatedEngine.deriveKeyMaterial(filename)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        val encryptedHead = cipher.doFinal(original, 0, AbysDedicatedEngine.PIECE_SIZE)
        val encryptedPayload = ByteArray(totalSize)
        System.arraycopy(encryptedHead, 0, encryptedPayload, 0, encryptedHead.size)
        System.arraycopy(original, AbysDedicatedEngine.PIECE_SIZE, encryptedPayload, AbysDedicatedEngine.PIECE_SIZE, totalSize - AbysDedicatedEngine.PIECE_SIZE)

        return Pair(original, encryptedPayload)
    }

    @Test
    fun testDownloadAndDecryptSingleAbysStream() = runBlocking {
        val filename = "e63a6e9602c10ab2b0527e9d37392.232643771.4.fd"
        val (original, encryptedPayload) = createEncryptedAbysPayload(filename, 100_000)

        server!!.createContext("/$filename") { exchange ->
            exchange.sendResponseHeaders(200, encryptedPayload.size.toLong())
            exchange.responseBody.use { it.write(encryptedPayload) }
        }

        val url = "http://127.0.0.1:$serverPort/$filename"
        val targetFile = File(tempDir, "output_movie.mp4")
        val downloader = AbysPipelineDownloader(OkHttpClient())

        var lastReportedDownloaded = 0L
        downloader.download(
            url = url,
            targetFile = targetFile,
            onProgress = { downloaded, _ ->
                lastReportedDownloaded = downloaded
            }
        )

        assertTrue(targetFile.exists())
        assertEquals(100_000L, targetFile.length())
        assertEquals(100_000L, lastReportedDownloaded)

        val downloadedBytes = targetFile.readBytes()
        assertEquals("ftyp", String(downloadedBytes, 4, 4, StandardCharsets.ISO_8859_1))
        assertArrayEquals(original, downloadedBytes)
    }

    @Test
    fun testMultiPartConcatenatedAssembly() = runBlocking {
        val part1Name = "movie_part1.4.fd"
        val (part1Original, part1Encrypted) = createEncryptedAbysPayload(part1Name, 80_000)

        val part2Original = ByteArray(30_000) { ((it + 7) % 251).toByte() }

        server!!.createContext("/$part1Name") { exchange ->
            exchange.sendResponseHeaders(200, part1Encrypted.size.toLong())
            exchange.responseBody.use { it.write(part1Encrypted) }
        }

        server!!.createContext("/part2.bin") { exchange ->
            exchange.sendResponseHeaders(200, part2Original.size.toLong())
            exchange.responseBody.use { it.write(part2Original) }
        }

        val part1Url = "http://127.0.0.1:$serverPort/$part1Name"
        val part2Url = "http://127.0.0.1:$serverPort/part2.bin"
        val targetFile = File(tempDir, "assembled_movie.mp4")

        val downloader = AbysPipelineDownloader(OkHttpClient())
        downloader.download(
            url = part1Url,
            targetFile = targetFile,
            additionalPartUrls = listOf(part2Url),
            onProgress = { _, _ -> }
        )

        assertTrue(targetFile.exists())
        assertEquals(110_000L, targetFile.length())

        val downloadedBytes = targetFile.readBytes()
        assertEquals("ftyp", String(downloadedBytes, 4, 4, StandardCharsets.ISO_8859_1))

        // First 80,000 bytes match decrypted part 1
        val part1Result = downloadedBytes.copyOfRange(0, 80_000)
        assertArrayEquals(part1Original, part1Result)

        // Trailing 30,000 bytes match part 2
        val part2Result = downloadedBytes.copyOfRange(80_000, 110_000)
        assertArrayEquals(part2Original, part2Result)
    }

    @Test
    fun testVirtualRangeContinuousAssembly() = runBlocking {
        val totalSize = 120_000
        val filename = "e63a6e9602c10ab2b0527e9d37392.120000.4.fd"
        val (original, encryptedPayload) = createEncryptedAbysPayload(filename, totalSize)

        // Part 1 (.fd endpoint) provides first 65,536 bytes
        val part1Bytes = encryptedPayload.copyOfRange(0, 65_536)
        server!!.createContext("/$filename") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range == "bytes=0-0") {
                exchange.responseHeaders.set("Content-Range", "bytes 0-0/$totalSize")
                exchange.sendResponseHeaders(206, 1)
                exchange.responseBody.use { it.write(byteArrayOf(part1Bytes[0])) }
            } else {
                exchange.sendResponseHeaders(200, part1Bytes.size.toLong())
                exchange.responseBody.use { it.write(part1Bytes) }
            }
        }

        // Virtual SW endpoint /mp4/4/120000 handles Range requests for the remaining chunks
        server!!.createContext("/mp4/4/120000") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range != null && range.startsWith("bytes=")) {
                val parts = range.removePrefix("bytes=").split("-")
                val start = parts[0].toInt()
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toInt() else (totalSize - 1)
                val slice = original.copyOfRange(start, end + 1)
                exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$totalSize")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                exchange.responseBody.use { it.write(slice) }
            } else {
                exchange.sendResponseHeaders(200, original.size.toLong())
                exchange.responseBody.use { it.write(original) }
            }
        }

        val url = "http://127.0.0.1:$serverPort/$filename"
        val targetFile = File(tempDir, "virtual_range_movie.mp4")

        val downloader = AbysPipelineDownloader(OkHttpClient())
        downloader.download(
            url = url,
            targetFile = targetFile,
            headers = mapOf(
                "X-Abys-Size" to totalSize.toString(),
                "X-Abys-ResId" to "4"
            ),
            expectedSizeHint = totalSize.toLong(),
            onProgress = { _, _ -> }
        )

        assertTrue(targetFile.exists())
        assertEquals(totalSize.toLong(), targetFile.length())

        val downloadedBytes = targetFile.readBytes()
        assertEquals("ftyp", String(downloadedBytes, 4, 4, StandardCharsets.ISO_8859_1))
        assertArrayEquals(original, downloadedBytes)
    }

    @Test
    fun testSwOracleVirtualEndpointPull() = runBlocking {
        // Simulating the ABYS Service Worker exposing the full 467,000 bytes virtual MP4
        val totalSize = 467_000
        val original = ByteArray(totalSize) { (it % 253).toByte() }
        original[4] = 'f'.code.toByte()
        original[5] = 't'.code.toByte()
        original[6] = 'y'.code.toByte()
        original[7] = 'p'.code.toByte()

        server!!.createContext("/mp4/md5hash123/4/467000") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range == "bytes=0-0") {
                exchange.responseHeaders.set("Content-Range", "bytes 0-0/$totalSize")
                exchange.sendResponseHeaders(206, 1)
                exchange.responseBody.use { it.write(byteArrayOf(original[0])) }
            } else if (range != null && range.startsWith("bytes=")) {
                val parts = range.removePrefix("bytes=").split("-")
                val start = parts[0].toInt()
                val end = minOf(if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toInt() else (totalSize - 1), totalSize - 1)
                val slice = original.copyOfRange(start, end + 1)
                exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$totalSize")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                exchange.responseBody.use { it.write(slice) }
            } else {
                exchange.sendResponseHeaders(200, original.size.toLong())
                exchange.responseBody.use { it.write(original) }
            }
        }

        val virtualUrl = "http://127.0.0.1:$serverPort/mp4/md5hash123/4/467000?v=testslug"
        val targetFile = File(tempDir, "sw_oracle_full.mp4")

        val downloader = AbysPipelineDownloader(OkHttpClient())
        downloader.download(
            url = virtualUrl,
            targetFile = targetFile,
            headers = mapOf(
                "X-Abys-Virtual-Url" to virtualUrl,
                "X-Abys-Size" to totalSize.toString(),
                "X-Abys-ResId" to "4",
                "X-Abys-Slug" to "testslug"
            ),
            expectedSizeHint = totalSize.toLong(),
            onProgress = { _, _ -> }
        )

        assertTrue(targetFile.exists())
        assertEquals(totalSize.toLong(), targetFile.length())

        val downloadedBytes = targetFile.readBytes()
        assertEquals("ftyp", String(downloadedBytes, 4, 4, StandardCharsets.ISO_8859_1))
        assertArrayEquals(original, downloadedBytes)
    }

    @Test
    fun testMultiMirrorCandidateFailover() = runBlocking {
        val part1Name = "movie_mirror.4.fd"
        val (part1Original, part1Encrypted) = createEncryptedAbysPayload(part1Name, 70_000)
        val part2Original = ByteArray(40_000) { ((it * 3) % 251).toByte() }

        // Server responds to part 1
        server!!.createContext("/$part1Name") { exchange ->
            exchange.sendResponseHeaders(200, part1Encrypted.size.toLong())
            exchange.responseBody.use { it.write(part1Encrypted) }
        }

        // Failing mirror for part 2 returns 404
        server!!.createContext("/fail_part2.bin") { exchange ->
            exchange.sendResponseHeaders(404, 0)
            exchange.responseBody.close()
        }

        // Healthy mirror for part 2 returns 200 OK
        server!!.createContext("/healthy_part2.bin") { exchange ->
            exchange.sendResponseHeaders(200, part2Original.size.toLong())
            exchange.responseBody.use { it.write(part2Original) }
        }

        val part1Url = "http://127.0.0.1:$serverPort/$part1Name"
        val failPart2Url = "http://127.0.0.1:$serverPort/fail_part2.bin"
        val healthyPart2Url = "http://127.0.0.1:$serverPort/healthy_part2.bin"
        val targetFile = File(tempDir, "mirror_failover.mp4")

        val downloader = AbysPipelineDownloader(OkHttpClient())
        downloader.download(
            url = part1Url,
            targetFile = targetFile,
            additionalPartUrls = listOf(failPart2Url, healthyPart2Url),
            onProgress = { _, _ -> }
        )

        assertTrue(targetFile.exists())
        assertEquals(110_000L, targetFile.length())

        val downloadedBytes = targetFile.readBytes()
        assertEquals("ftyp", String(downloadedBytes, 4, 4, StandardCharsets.ISO_8859_1))
        val part1Result = downloadedBytes.copyOfRange(0, 70_000)
        assertArrayEquals(part1Original, part1Result)
        val part2Result = downloadedBytes.copyOfRange(70_000, 110_000)
        assertArrayEquals(part2Original, part2Result)
    }
}
