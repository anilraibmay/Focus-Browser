package com.example

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.example.media.AbysCipherDataSource
import com.example.util.AbysDedicatedEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class AbysDataSourceTest {

    /**
     * In-memory fake DataSource simulating an HTTP endpoint.
     */
    private class FakeDataSource(private val data: ByteArray) : DataSource {
        private var position: Long = 0L
        private var bytesRemaining: Long = 0L
        private var uri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            position = dataSpec.position
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                (data.size - position).coerceAtLeast(0L)
            }
            return bytesRemaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRemaining <= 0 || position >= data.size) {
                return C.RESULT_END_OF_INPUT
            }
            val toRead = min(length.toLong(), bytesRemaining).toInt()
            val available = (data.size - position).toInt()
            val actualRead = min(toRead, available)
            if (actualRead <= 0) return C.RESULT_END_OF_INPUT

            System.arraycopy(data, position.toInt(), buffer, offset, actualRead)
            position += actualRead
            bytesRemaining -= actualRead
            return actualRead
        }

        override fun getUri(): Uri? = uri
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun close() {}
    }

    private fun createEncryptedAbysPayload(filename: String, totalSize: Int = 100_000): Pair<ByteArray, ByteArray> {
        val original = ByteArray(totalSize) { (it % 251).toByte() }
        // Embed standard MP4 header markers in the plaintext payload
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
    fun testAbysStreamFullPlaybackDecryption() {
        AbysCipherDataSource.clearHeaderCache()
        val filename = "e63a6e9602c10ab2b0527e9d37392.232643771.4.fd"
        val (original, encryptedPayload) = createEncryptedAbysPayload(filename, 100_000)

        val uri = Uri.parse("https://abysscdn.com/9/5/b/$filename")
        val mainSource = FakeDataSource(encryptedPayload)
        val headSource = FakeDataSource(encryptedPayload)
        val abysDataSource = AbysCipherDataSource(mainSource, headSource)

        val dataSpec = DataSpec(uri)
        abysDataSource.open(dataSpec)

        val resultBuffer = ByteArray(original.size)
        var totalRead = 0
        val chunk = ByteArray(4096)

        while (totalRead < original.size) {
            val read = abysDataSource.read(chunk, 0, min(chunk.size, original.size - totalRead))
            if (read == C.RESULT_END_OF_INPUT) break
            System.arraycopy(chunk, 0, resultBuffer, totalRead, read)
            totalRead += read
        }
        abysDataSource.close()

        assertEquals(original.size, totalRead)
        assertArrayEquals(original, resultBuffer)
        assertEquals("ftyp", String(resultBuffer, 4, 4, StandardCharsets.ISO_8859_1))
    }

    @Test
    fun testAbysSeekPastEncryptedHeader() {
        AbysCipherDataSource.clearHeaderCache()
        val filename = "seek_test_stream.fd"
        val (original, encryptedPayload) = createEncryptedAbysPayload(filename, 120_000)

        val uri = Uri.parse("https://abysscdn.com/video/$filename")
        val mainSource = FakeDataSource(encryptedPayload)
        val headSource = FakeDataSource(encryptedPayload)
        val abysDataSource = AbysCipherDataSource(mainSource, headSource)

        // Seek directly to offset 80,000 (well past 64KB)
        val seekOffset = 80_000L
        val seekLen = 5_000L
        val dataSpec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(seekOffset)
            .setLength(seekLen)
            .build()

        abysDataSource.open(dataSpec)
        val readBuffer = ByteArray(seekLen.toInt())
        var totalRead = 0
        while (totalRead < seekLen) {
            val r = abysDataSource.read(readBuffer, totalRead, (seekLen - totalRead).toInt())
            if (r == C.RESULT_END_OF_INPUT) break
            totalRead += r
        }
        abysDataSource.close()

        assertEquals(seekLen.toInt(), totalRead)
        val expectedSlice = original.copyOfRange(seekOffset.toInt(), (seekOffset + seekLen).toInt())
        assertArrayEquals(expectedSlice, readBuffer)
    }

    @Test
    fun testAbysReadSpanningBoundary() {
        AbysCipherDataSource.clearHeaderCache()
        val filename = "boundary_stream.fd"
        val (original, encryptedPayload) = createEncryptedAbysPayload(filename, 100_000)

        val uri = Uri.parse("https://abysscdn.com/video/$filename")
        val mainSource = FakeDataSource(encryptedPayload)
        val headSource = FakeDataSource(encryptedPayload)
        val abysDataSource = AbysCipherDataSource(mainSource, headSource)

        // Read from 64,000 to 67,000 (crosses 65,536 boundary)
        val startOffset = 64_000L
        val readLength = 3_000L
        val dataSpec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(startOffset)
            .setLength(readLength)
            .build()

        abysDataSource.open(dataSpec)
        val readBuffer = ByteArray(readLength.toInt())
        var totalRead = 0
        while (totalRead < readLength) {
            val r = abysDataSource.read(readBuffer, totalRead, (readLength - totalRead).toInt())
            if (r == C.RESULT_END_OF_INPUT) break
            totalRead += r
        }
        abysDataSource.close()

        assertEquals(readLength.toInt(), totalRead)
        val expectedSlice = original.copyOfRange(startOffset.toInt(), (startOffset + readLength).toInt())
        assertArrayEquals(expectedSlice, readBuffer)
    }

    @Test
    fun testNonAbysPassthrough() {
        val plainData = ByteArray(50_000) { (it % 128).toByte() }
        val uri = Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")

        val mainSource = FakeDataSource(plainData)
        val headSource = FakeDataSource(plainData)
        val abysDataSource = AbysCipherDataSource(mainSource, headSource)

        abysDataSource.open(DataSpec(uri))
        val readBuffer = ByteArray(plainData.size)
        var totalRead = 0
        while (totalRead < plainData.size) {
            val r = abysDataSource.read(readBuffer, totalRead, plainData.size - totalRead)
            if (r == C.RESULT_END_OF_INPUT) break
            totalRead += r
        }
        abysDataSource.close()

        assertEquals(plainData.size, totalRead)
        assertArrayEquals(plainData, readBuffer)
    }
}
