package com.example

import com.example.util.AbysDedicatedEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AbysEngineTest {

    @Test
    fun testMd5Hex() {
        val hash = AbysDedicatedEngine.md5Hex("hello")
        assertEquals("5d41402abc4b2a76b9719d911017c592", hash)
    }

    @Test
    fun testDeriveKeyMaterial() {
        val filename = "e63a6e9602c10ab2b0527e9d37392.232643771.4.fd"
        val (key, iv) = AbysDedicatedEngine.deriveKeyMaterial(filename)
        assertEquals(32, key.size)
        assertEquals(16, iv.size)
        assertArrayEquals(key.copyOfRange(0, 16), iv)
    }

    @Test
    fun testAesCtrRoundtrip() {
        val filename = "test_video_part1.fd"
        val (key, iv) = AbysDedicatedEngine.deriveKeyMaterial(filename)

        // Generate synthetic 100KB payload (64KB header + 36KB tail)
        val original = ByteArray(100_000) { (it % 256).toByte() }
        // Put "ftyp" tag at offset 4 in the decrypted content to mimic MP4
        original[4] = 'f'.code.toByte()
        original[5] = 't'.code.toByte()
        original[6] = 'y'.code.toByte()
        original[7] = 'p'.code.toByte()

        // Encrypt the first 64KB with AES-256-CTR
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encryptedHead = cipher.doFinal(original, 0, AbysDedicatedEngine.PIECE_SIZE)

        val encryptedFd = ByteArray(original.size)
        System.arraycopy(encryptedHead, 0, encryptedFd, 0, encryptedHead.size)
        System.arraycopy(original, AbysDedicatedEngine.PIECE_SIZE, encryptedFd, AbysDedicatedEngine.PIECE_SIZE, original.size - AbysDedicatedEngine.PIECE_SIZE)

        // Assert that the encrypted version does NOT have "ftyp" in the first 8 bytes
        assertFalse(String(encryptedFd, 4, 4, StandardCharsets.ISO_8859_1) == "ftyp")

        // Decrypt using AbysDedicatedEngine
        val decrypted = AbysDedicatedEngine.decryptFdHeader(encryptedFd, filename)

        // Verify full byte equality
        assertEquals(original.size, decrypted.size)
        assertArrayEquals(original, decrypted)
        assertEquals("ftyp", String(decrypted, 4, 4, StandardCharsets.ISO_8859_1))
    }

    @Test
    fun testIsAbysUrl() {
        assertTrue(AbysDedicatedEngine.isAbysUrl("https://abysscdn.com/9/5/b/e63a6e9602c10ab2b0527e9d37392.232643771.4.fd"))
        assertTrue(AbysDedicatedEngine.isAbysUrl("https://abyss.to/sora/232643771/abc123token"))
        assertTrue(AbysDedicatedEngine.isAbysUrl("https://embedplayabyss.top/mp4/23557934/4/232643771?v=oQDjHTSeQ"))
        assertFalse(AbysDedicatedEngine.isAbysUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(AbysDedicatedEngine.isAbysUrl("https://google.com"))
    }

    @Test
    fun testExtractFilename() {
        assertEquals(
            "e63a6e9602c10ab2b0527e9d37392.232643771.4.fd",
            AbysDedicatedEngine.extractFilename("https://abysscdn.com/9/5/b/e63a6e9602c10ab2b0527e9d37392.232643771.4.fd?token=123")
        )
    }

    @Test
    fun testAbysMediaGroupingAndCanonicalKey() {
        val url = "https://abysscdn.com/9/5/b/e63a6e9602c10ab2b0527e9d37392.232643771.4.fd?token=abc"
        val groupKey = com.example.extractor.MediaTitleHelper.getMediaGroupKey(url, "Abys Movie")
        assertEquals("abys:e63a6e9602c10ab2b0527e9d37392.232643771.fd", groupKey)

        val slugUrl = "https://embedplayabyss.top/mp4/23557934/4/232643771?v=oQDjHTSeQ"
        val slugGroupKey = com.example.extractor.MediaTitleHelper.getMediaGroupKey(slugUrl, "Abys Stream")
        assertEquals("abys:oQDjHTSeQ", slugGroupKey)
    }

    @Test
    fun testSoraTokenRoundtrip() {
        val path = "/mp4/23557934/4/232643771?v=oQDjHTSeQ"
        val sizeId = "232643771"
        val token = AbysDedicatedEngine.generateSoraToken(path, sizeId)
        assertTrue(token.isNotEmpty())
        val decrypted = AbysDedicatedEngine.decryptSoraToken(token, sizeId)
        assertEquals(path, decrypted)
    }

    @Test
    fun testExtractDurationFromMvhdVersion0() {
        val stream = java.io.ByteArrayOutputStream()
        val ftypBytes = byteArrayOf(
            0, 0, 0, 16,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0, 2, 0
        )
        stream.write(ftypBytes)

        val mvhdData = java.nio.ByteBuffer.allocate(108).order(java.nio.ByteOrder.BIG_ENDIAN)
        mvhdData.putInt(108) // size
        mvhdData.put("mvhd".toByteArray(StandardCharsets.ISO_8859_1))
        mvhdData.put(0.toByte()) // version = 0
        mvhdData.put(byteArrayOf(0, 0, 0)) // flags
        mvhdData.putInt(0) // creation time
        mvhdData.putInt(0) // modification time
        mvhdData.putInt(1000) // timescale = 1000
        mvhdData.putInt(1440000) // duration = 1440000 (1440 seconds = 24 min)
        mvhdData.putInt(0x00010000) // rate 1.0
        mvhdData.putShort(0x0100) // volume 1.0
        mvhdData.put(ByteArray(10)) // reserved
        mvhdData.put(ByteArray(36)) // matrix
        mvhdData.put(ByteArray(24)) // pre-defined
        mvhdData.putInt(2) // next_track_id

        val mvhdBytes = mvhdData.array()
        val moovHeader = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.BIG_ENDIAN)
        moovHeader.putInt(8 + mvhdBytes.size)
        moovHeader.put("moov".toByteArray(StandardCharsets.ISO_8859_1))

        stream.write(moovHeader.array())
        stream.write(mvhdBytes)

        val fullBytes = stream.toByteArray()
        val meta = AbysDedicatedEngine.parseMovieMetadata(fullBytes)

        assertEquals(1440.0, meta.durationSeconds, 0.001)
        val formatted = AbysDedicatedEngine.formatDuration((meta.durationSeconds * 1000).toLong())
        assertEquals("24:00", formatted)
    }

    @Test
    fun testExtractDurationFromMvhdVersion1() {
        val stream = java.io.ByteArrayOutputStream()
        val ftypBytes = byteArrayOf(
            0, 0, 0, 16,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0, 2, 0
        )
        stream.write(ftypBytes)

        val mvhdData = java.nio.ByteBuffer.allocate(120).order(java.nio.ByteOrder.BIG_ENDIAN)
        mvhdData.putInt(120) // size
        mvhdData.put("mvhd".toByteArray(StandardCharsets.ISO_8859_1))
        mvhdData.put(1.toByte()) // version = 1
        mvhdData.put(byteArrayOf(0, 0, 0)) // flags
        mvhdData.putLong(0L) // creation time
        mvhdData.putLong(0L) // modification time
        mvhdData.putInt(600) // timescale = 600
        mvhdData.putLong(3696000L) // duration = 3696000 (6160 seconds = 1h 42m 40s)
        mvhdData.putInt(0x00010000)
        mvhdData.putShort(0x0100)
        mvhdData.put(ByteArray(10))
        mvhdData.put(ByteArray(36))
        mvhdData.put(ByteArray(24))
        mvhdData.putInt(2)

        val mvhdBytes = mvhdData.array()
        val moovHeader = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.BIG_ENDIAN)
        moovHeader.putInt(8 + mvhdBytes.size)
        moovHeader.put("moov".toByteArray(StandardCharsets.ISO_8859_1))

        stream.write(moovHeader.array())
        stream.write(mvhdBytes)

        val fullBytes = stream.toByteArray()
        val meta = AbysDedicatedEngine.parseMovieMetadata(fullBytes)

        assertEquals(6160.0, meta.durationSeconds, 0.001)
        val formatted = AbysDedicatedEngine.formatDuration((meta.durationSeconds * 1000).toLong())
        assertEquals("01:42:40", formatted)
    }
}
