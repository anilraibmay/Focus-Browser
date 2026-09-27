package com.example.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Metadata extracted from an ABYS .fd (part 1) or full MP4 stream.
 */
data class AbysMovieMetadata(
    val filename: String,
    val brand: String? = null,
    val durationSeconds: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioSampleRate: Double = 0.0,
    val audioChannels: Int = 0,
    val moovBytes: Long = 0L,
    val totalDeclaredBytes: Long? = null
)

/**
 * Result of ISO-BMFF verification and integrity analysis (matching abys_verify.js).
 */
data class AbysVerificationResult(
    val isValidMp4: Boolean,
    val isTruncated: Boolean,
    val hasFtyp: Boolean,
    val hasMoov: Boolean,
    val hasMdat: Boolean,
    val actualSizeBytes: Long,
    val expectedSizeBytes: Long? = null,
    val declaredMdatBytes: Long? = null,
    val topBoxes: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val missingGaps: List<Pair<Long, Long>> = emptyList()
)

/**
 * Native engine for ABYS (abyss.to / abysscdn.com) stream decryption, token generation,
 * metadata extraction and ISO-BMFF box parsing.
 */
object AbysDedicatedEngine {

    const val PIECE_SIZE = 0x10000 // 65,536 bytes (64 KB)
    const val DEFAULT_PART_SIZE = 20971520L // 20 MB standard chunk size
    const val DEFAULT_REFERER = "https://abysscdn.com/"
    const val DEFAULT_ORIGIN = "https://abysscdn.com"

    private val ABYS_HOST_PATTERNS = listOf(
        "abysscdn.com",
        "abyss.to",
        "embedplayabyss.top",
        "trycloudflare.com"
    )

    /**
     * Computes the 32-character lowercase hexadecimal MD5 digest of a string or byte array.
     */
    fun md5Hex(input: String): String {
        return md5Hex(input.toByteArray(StandardCharsets.ISO_8859_1))
    }

    fun md5Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /**
     * Generates the 32-byte key material (ASCII bytes of md5Hex) and 16-byte initial IV/counter.
     */
    fun deriveKeyMaterial(passphrase: String): Pair<ByteArray, ByteArray> {
        val md5String = md5Hex(passphrase)
        val keyBytes = md5String.toByteArray(StandardCharsets.US_ASCII) // 32 bytes
        val ivBytes = keyBytes.copyOfRange(0, 16) // first 16 bytes
        return Pair(keyBytes, ivBytes)
    }

    /**
     * Decrypts an encrypted ABYS .fd (first data) file or byte array.
     * Only the first 0x10000 (65,536) bytes are encrypted with AES-256-CTR;
     * the remaining bytes are plaintext MP4.
     *
     * @param rawBytes Raw bytes of the .fd file or first part.
     * @param filename Basename of the .fd URL (e.g. "e63a6e9602c10ab2b0527e9d37392.232643771.4.fd").
     * @return Fully playable decrypted MP4 bytes.
     */
    fun decryptFdHeader(rawBytes: ByteArray, filename: String): ByteArray {
        if (rawBytes.isEmpty()) return rawBytes

        // If the file is already plaintext MP4 (starts with ftyp box signature), return as is
        if (rawBytes.size >= 8) {
            val tag = String(rawBytes, 4, 4, StandardCharsets.ISO_8859_1)
            if (tag == "ftyp") {
                return rawBytes
            }
        }

        val (keyBytes, ivBytes) = deriveKeyMaterial(filename)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

        val encryptedLength = min(rawBytes.size, PIECE_SIZE)
        val decryptedHead = cipher.doFinal(rawBytes, 0, encryptedLength)

        return if (rawBytes.size > PIECE_SIZE) {
            val result = ByteArray(decryptedHead.size + (rawBytes.size - PIECE_SIZE))
            System.arraycopy(decryptedHead, 0, result, 0, decryptedHead.size)
            System.arraycopy(rawBytes, PIECE_SIZE, result, decryptedHead.size, rawBytes.size - PIECE_SIZE)
            result
        } else {
            decryptedHead
        }
    }

    /**
     * Generates a Service Worker /sora/<sizeId>/<token> token for a given path and sizeId.
     * Token = base64( base64( AES_CTR(pathnameAndQuery, key=md5hex(sizeId)) ).replace(/=/g,'') ).replace(/=/g,'')
     */
    fun generateSoraToken(pathnameAndQuery: String, sizeId: String): String {
        val (keyBytes, ivBytes) = deriveKeyMaterial(sizeId)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

        val inputBytes = pathnameAndQuery.toByteArray(StandardCharsets.ISO_8859_1)
        val encrypted = cipher.doFinal(inputBytes)

        val b64Inner = Base64.getEncoder().encodeToString(encrypted)
            .replace("=", "")
        val b64Outer = Base64.getEncoder().encodeToString(b64Inner.toByteArray(StandardCharsets.ISO_8859_1))
            .replace("=", "")

        return b64Outer
    }

    /**
     * Decrypts a Service Worker /sora/ token back to its original path and query.
     */
    fun decryptSoraToken(token: String, sizeId: String): String {
        val decodedOuter = Base64.getDecoder().decode(padBase64(token))
        val innerString = String(decodedOuter, StandardCharsets.ISO_8859_1)
        val encrypted = Base64.getDecoder().decode(padBase64(innerString))

        val (keyBytes, ivBytes) = deriveKeyMaterial(sizeId)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, StandardCharsets.ISO_8859_1)
    }

    private fun padBase64(b64: String): String {
        val missing = (4 - (b64.length % 4)) % 4
        return b64 + "=".repeat(missing)
    }

    /**
     * Checks whether a given URL belongs to an authentic ABYS stream payload (.fd, /sora/, /mp4/{id}/{res}/{size}).
     * Excludes telemetry, challenge endpoints (cdn-cgi/rum), scripts, stylesheets and web pages.
     */
    fun isAbysUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()

        // Exclude Cloudflare telemetry, analytics, beacons, scripts, stylesheets and web documents
        if (lower.contains("cdn-cgi/") ||
            lower.contains("/rum") ||
            lower.contains("analytics") ||
            lower.contains("beacon") ||
            lower.contains("telemetry") ||
            lower.contains("/log") ||
            lower.contains("/report") ||
            lower.contains(".js") ||
            lower.contains(".css") ||
            lower.contains(".html") ||
            lower.contains(".htm") ||
            lower.contains(".php") ||
            lower.contains(".png") ||
            lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".svg") ||
            lower.contains("/favicon")
        ) {
            return false
        }

        // Direct encrypted binary chunk (.fd)
        if (lower.contains(".fd")) {
            return true
        }

        // Sora decrypted stream endpoint
        if (lower.contains("/sora/")) {
            return true
        }

        // Structured ABYS MP4 endpoint (/mp4/...)
        if (lower.contains("/mp4/") && (lower.contains("abyss") || lower.contains("embedplay") || lower.contains("abysscdn") || lower.contains("top") || Regex("""/mp4/\d+""", RegexOption.IGNORE_CASE).containsMatchIn(lower))) {
            return true
        }

        // Direct ABYS CDN chunk or stream path
        if ((lower.contains("abysscdn.com") || lower.contains("abyss.to") || lower.contains("embedplayabyss")) &&
            (lower.contains("/stream") || lower.contains("/video") || lower.contains("/play") || lower.contains("/part") || lower.contains(".bin") || lower.contains("token=") || lower.contains("v="))) {
            return true
        }

        return false
    }

    /**
     * Extracts the canonical filename from an ABYS URL to use as the decryption passphrase.
     */
    fun extractFilename(url: String): String {
        val cleanUrl = url.substringBefore('?').substringBefore('#')
        return cleanUrl.substringAfterLast('/').ifBlank { "stream.fd" }
    }

    /**
     * Formats duration in milliseconds into a standard HH:MM:SS or MM:SS representation.
     */
    fun formatDuration(durationMs: Long?): String? {
        if (durationMs == null || durationMs <= 0L) return null
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Probes and extracts the real duration in milliseconds from an ABYS .fd stream URL.
     * Checks in-memory cache first, or fetches and decrypts the first 64KB on-the-fly.
     */
    suspend fun probeDurationMs(
        url: String,
        headers: Map<String, String> = emptyMap(),
        client: OkHttpClient = OkHttpClient()
    ): Long? = probeStreamDetails(url, headers, client)?.durationMs

    /**
     * Formats bytes into a human-readable string (e.g. "1.2 GB", "850 MB").
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    /**
     * Complete probe result for an ABYS stream.
     */
    data class AbysStreamProbe(
        val durationMs: Long?,
        val totalBytes: Long?,
        val formattedSize: String?,
        val formattedDuration: String?,
        val width: Int,
        val height: Int,
        val resolutionLabel: String
    )

    /**
     * Probes an ABYS stream by requesting the first 64KB with Range: bytes=0-65535.
     * Extracts Content-Range / total bytes, decrypts the 64KB MP4 header, and parses moov metadata.
     */
    suspend fun probeStreamDetails(
        url: String,
        headers: Map<String, String> = emptyMap(),
        client: OkHttpClient = OkHttpClient()
    ): AbysStreamProbe? = withContext(Dispatchers.IO) {
        try {
            val filename = extractFilename(url)

            // 1. Check in-memory cache
            val cachedHeader = com.example.media.AbysCipherDataSource.getCachedHeader(filename)
            if (cachedHeader != null && cachedHeader.isNotEmpty()) {
                val meta = parseMovieMetadata(cachedHeader, filename)
                val durMs = if (meta.durationSeconds > 0.0) (meta.durationSeconds * 1000).toLong() else null
                val totalBytes = meta.totalDeclaredBytes
                val formattedSize = totalBytes?.let { formatBytes(it) }
                val formattedDur = formatDuration(durMs)
                val resLabel = if (meta.height > 0) "${meta.height}p" else "ABYS HD"
                return@withContext AbysStreamProbe(
                    durationMs = durMs,
                    totalBytes = totalBytes,
                    formattedSize = formattedSize,
                    formattedDuration = formattedDur,
                    width = meta.width,
                    height = meta.height,
                    resolutionLabel = resLabel
                )
            }

            // 2. Fetch the first 64KB with Range header
            val reqBuilder = Request.Builder().url(url)
            reqBuilder.header("Range", "bytes=0-${PIECE_SIZE - 1}")
            headers.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank() && !k.equals("Origin", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }
            if (!headers.containsKey("Referer")) {
                val host = try { java.net.URI(url).host } catch (_: Throwable) { null }
                reqBuilder.header("Referer", if (!host.isNullOrBlank()) "https://$host/" else DEFAULT_REFERER)
            }
            if (!headers.containsKey("User-Agent")) {
                reqBuilder.header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) return@withContext null

                // Extract total size from Content-Range (e.g. bytes 0-65535/104857600)
                val contentRange = response.header("Content-Range")
                val totalBytesFromRange = contentRange?.substringAfterLast("/")?.trim()?.toLongOrNull()
                val contentLength = response.body?.contentLength()?.takeIf { it > 0 }

                val bodyBytes = response.body?.bytes() ?: return@withContext null
                if (bodyBytes.isEmpty()) return@withContext null

                val decryptedHead = decryptFdHeader(bodyBytes, filename)
                com.example.media.AbysCipherDataSource.cacheDecryptedHeader(filename, decryptedHead)

                val meta = parseMovieMetadata(decryptedHead, filename)
                val parsedFd = AbysQualityEngine.parseFromFdUrl(url)
                val totalBytes = parsedFd?.sizeBytes
                    ?: meta.totalDeclaredBytes
                    ?: if (totalBytesFromRange != null && totalBytesFromRange > 20971520L) totalBytesFromRange else null
                    ?: totalBytesFromRange
                    ?: contentLength
                val durMs = if (meta.durationSeconds > 0.0) (meta.durationSeconds * 1000).toLong() else null
                val formattedSize = totalBytes?.let { formatBytes(it) } ?: parsedFd?.formattedSize
                val formattedDur = formatDuration(durMs)
                val resLabel = parsedFd?.resolutionLabel ?: if (meta.height > 0) "${meta.height}p" else "ABYS HD"

                AbysStreamProbe(
                    durationMs = durMs,
                    totalBytes = totalBytes,
                    formattedSize = formattedSize,
                    formattedDuration = formattedDur,
                    width = meta.width,
                    height = meta.height,
                    resolutionLabel = resLabel
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses metadata (duration, width, height, codecs) from a decrypted MP4 byte buffer.
     */
    fun parseMovieMetadata(buffer: ByteArray, filename: String = "movie.mp4"): AbysMovieMetadata {
        var brand: String? = null
        var durationSeconds = 0.0
        var width = 0
        var height = 0
        var videoCodec: String? = null
        var audioCodec: String? = null
        var audioSampleRate = 0.0
        var audioChannels = 0
        var moovBytes = 0L
        var totalDeclaredBytes: Long? = null

        val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
        var offset = 0

        while (offset + 8 <= buffer.size) {
            byteBuffer.position(offset)
            var size = byteBuffer.int.toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            byteBuffer.get(typeBytes)
            val type = String(typeBytes, StandardCharsets.ISO_8859_1)

            var headerSize = 8
            if (size == 1L && offset + 16 <= buffer.size) {
                size = byteBuffer.long
                headerSize = 16
            } else if (size == 0L) {
                size = (buffer.size - offset).toLong()
            }

            if (size < headerSize || offset + size > buffer.size + 1000000000L) {
                break
            }

            when (type) {
                "ftyp" -> {
                    if (offset + headerSize + 4 <= buffer.size) {
                        val brandBytes = ByteArray(4)
                        byteBuffer.position(offset + headerSize)
                        byteBuffer.get(brandBytes)
                        brand = String(brandBytes, StandardCharsets.ISO_8859_1)
                    }
                }
                "moov" -> {
                    moovBytes = size
                    val moovEnd = min(buffer.size, (offset + size).toInt())
                    parseMoov(buffer, offset + headerSize, moovEnd).let { moovData ->
                        durationSeconds = moovData.durationSeconds
                        width = moovData.width
                        height = moovData.height
                        videoCodec = moovData.videoCodec
                        audioCodec = moovData.audioCodec
                        audioSampleRate = moovData.audioSampleRate
                        audioChannels = moovData.audioChannels
                    }
                }
                "mdat" -> {
                    totalDeclaredBytes = offset + size
                    break
                }
            }

            offset += size.toInt()
            if (size <= 0) break
        }

        return AbysMovieMetadata(
            filename = filename,
            brand = brand,
            durationSeconds = durationSeconds,
            width = width,
            height = height,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            audioSampleRate = audioSampleRate,
            audioChannels = audioChannels,
            moovBytes = moovBytes,
            totalDeclaredBytes = totalDeclaredBytes
        )
    }

    private data class ParsedMoov(
        val durationSeconds: Double,
        val width: Int,
        val height: Int,
        val videoCodec: String?,
        val audioCodec: String?,
        val audioSampleRate: Double,
        val audioChannels: Int
    )

    private fun parseMoov(buffer: ByteArray, startOffset: Int, endOffset: Int): ParsedMoov {
        var durationSeconds = 0.0
        var width = 0
        var height = 0
        var videoCodec: String? = null
        var audioCodec: String? = null
        var audioSampleRate = 0.0
        var audioChannels = 0

        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
        var offset = startOffset

        while (offset + 8 <= endOffset) {
            bb.position(offset)
            val size = bb.int.toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            bb.get(typeBytes)
            val type = String(typeBytes, StandardCharsets.ISO_8859_1)

            val headerSize = 8
            if (size < headerSize || offset + size > endOffset) {
                break
            }

            if (type == "mvhd" && offset + 24 <= endOffset) {
                val version = buffer[offset + 8].toInt() and 0xFF
                if (version == 1 && offset + 40 <= endOffset) {
                    val timescale = bb.getInt(offset + 28).toLong() and 0xFFFFFFFFL
                    val duration = bb.getLong(offset + 32)
                    if (timescale > 0) {
                        durationSeconds = duration.toDouble() / timescale
                    }
                } else if (version == 0 && offset + 28 <= endOffset) {
                    val timescale = bb.getInt(offset + 20).toLong() and 0xFFFFFFFFL
                    val duration = bb.getInt(offset + 24).toLong() and 0xFFFFFFFFL
                    if (timescale > 0) {
                        durationSeconds = duration.toDouble() / timescale
                    }
                }
            } else if (type == "trak") {
                val trakEnd = min(endOffset, (offset + size).toInt())
                val trakData = parseTrak(buffer, offset + headerSize, trakEnd)
                if (trakData.handler == "vide") {
                    if (trakData.width > 0) width = trakData.width
                    if (trakData.height > 0) height = trakData.height
                    if (trakData.codec != null) videoCodec = trakData.codec
                } else if (trakData.handler == "soun") {
                    if (trakData.codec != null) audioCodec = trakData.codec
                    if (trakData.sampleRate > 0) audioSampleRate = trakData.sampleRate
                    if (trakData.channels > 0) audioChannels = trakData.channels
                }
            }

            offset += size.toInt()
            if (size <= 0) break
        }

        return ParsedMoov(
            durationSeconds = durationSeconds,
            width = width,
            height = height,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            audioSampleRate = audioSampleRate,
            audioChannels = audioChannels
        )
    }

    private data class ParsedTrak(
        val handler: String?,
        val codec: String?,
        val width: Int,
        val height: Int,
        val channels: Int,
        val sampleRate: Double
    )

    private fun parseTrak(buffer: ByteArray, startOffset: Int, endOffset: Int): ParsedTrak {
        var handler: String? = null
        var codec: String? = null
        var width = 0
        var height = 0
        var channels = 0
        var sampleRate = 0.0

        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
        var offset = startOffset

        while (offset + 8 <= endOffset) {
            bb.position(offset)
            val size = bb.int.toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            bb.get(typeBytes)
            val type = String(typeBytes, StandardCharsets.ISO_8859_1)

            if (size < 8 || offset + size > endOffset) break

            if (type == "mdia") {
                val mdiaEnd = min(endOffset, (offset + size).toInt())
                var subOffset = offset + 8
                while (subOffset + 8 <= mdiaEnd) {
                    bb.position(subOffset)
                    val subSize = bb.int.toLong() and 0xFFFFFFFFL
                    val subTypeBytes = ByteArray(4)
                    bb.get(subTypeBytes)
                    val subType = String(subTypeBytes, StandardCharsets.ISO_8859_1)

                    if (subSize < 8 || subOffset + subSize > mdiaEnd) break

                    if (subType == "hdlr" && subOffset + 16 <= mdiaEnd) {
                        val hdlrBytes = ByteArray(4)
                        bb.position(subOffset + 16)
                        bb.get(hdlrBytes)
                        handler = String(hdlrBytes, StandardCharsets.ISO_8859_1)
                    } else if (subType == "minf") {
                        val minfEnd = min(mdiaEnd, (subOffset + subSize).toInt())
                        var minfOff = subOffset + 8
                        while (minfOff + 8 <= minfEnd) {
                            bb.position(minfOff)
                            val mSize = bb.int.toLong() and 0xFFFFFFFFL
                            val mTypeBytes = ByteArray(4)
                            bb.get(mTypeBytes)
                            val mType = String(mTypeBytes, StandardCharsets.ISO_8859_1)

                            if (mSize < 8 || minfOff + mSize > minfEnd) break

                            if (mType == "stbl") {
                                val stblEnd = min(minfEnd, (minfOff + mSize).toInt())
                                var stblOff = minfOff + 8
                                while (stblOff + 8 <= stblEnd) {
                                    bb.position(stblOff)
                                    val sSize = bb.int.toLong() and 0xFFFFFFFFL
                                    val sTypeBytes = ByteArray(4)
                                    bb.get(sTypeBytes)
                                    val sType = String(sTypeBytes, StandardCharsets.ISO_8859_1)

                                    if (sSize < 8 || stblOff + sSize > stblEnd) break

                                    if (sType == "stsd" && stblOff + 16 <= stblEnd) {
                                        val entryCount = bb.getInt(stblOff + 12)
                                        if (entryCount > 0 && stblOff + 24 <= stblEnd) {
                                            val cBytes = ByteArray(4)
                                            bb.position(stblOff + 20)
                                            bb.get(cBytes)
                                            codec = String(cBytes, StandardCharsets.ISO_8859_1)

                                            if (codec == "avc1" || codec == "hvc1" || codec == "av01") {
                                                if (stblOff + 48 <= stblEnd) {
                                                    width = bb.getShort(stblOff + 48).toInt() and 0xFFFF
                                                    height = bb.getShort(stblOff + 50).toInt() and 0xFFFF
                                                }
                                            } else if (codec == "mp4a") {
                                                if (stblOff + 44 <= stblEnd) {
                                                    channels = bb.getShort(stblOff + 40).toInt() and 0xFFFF
                                                    val rawRate = bb.getInt(stblOff + 48).toLong() and 0xFFFFFFFFL
                                                    sampleRate = rawRate.toDouble() / 65536.0
                                                }
                                            }
                                        }
                                    }
                                    stblOff += sSize.toInt()
                                    if (sSize <= 0) break
                                }
                            }
                            minfOff += mSize.toInt()
                            if (mSize <= 0) break
                        }
                    }
                    subOffset += subSize.toInt()
                    if (subSize <= 0) break
                }
            }
            offset += size.toInt()
            if (size <= 0) break
        }

        return ParsedTrak(
            handler = handler,
            codec = codec,
            width = width,
            height = height,
            channels = channels,
            sampleRate = sampleRate
        )
    }

    /**
     * Verifies the integrity of an MP4 file or ABYS downloaded stream (matching abys_verify.js).
     * Inspects top-level boxes (ftyp, moov, mdat) and verifies whether mdat declared size exceeds
     * actual file size (detecting the "1st part only / truncated" symptom).
     */
    fun verifyMp4Integrity(
        file: java.io.File,
        expectedTotalBytes: Long? = null,
        chunks: List<Pair<Long, Long>>? = null
    ): AbysVerificationResult {
        if (!file.exists()) {
            return AbysVerificationResult(
                isValidMp4 = false,
                isTruncated = false,
                hasFtyp = false,
                hasMoov = false,
                hasMdat = false,
                actualSizeBytes = 0L,
                expectedSizeBytes = expectedTotalBytes,
                warnings = listOf("Ficheiro não existe em disco: ${file.name}")
            )
        }

        val fileSize = file.length()
        val headLength = minOf(fileSize, 8L * 1024L * 1024L).toInt() // Read up to 8MB
        val headBuffer = ByteArray(headLength)

        try {
            java.io.FileInputStream(file).use { fis ->
                var readTotal = 0
                while (readTotal < headLength) {
                    val r = fis.read(headBuffer, readTotal, headLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
            }
        } catch (e: Exception) {
            return AbysVerificationResult(
                isValidMp4 = false,
                isTruncated = false,
                hasFtyp = false,
                hasMoov = false,
                hasMdat = false,
                actualSizeBytes = fileSize,
                expectedSizeBytes = expectedTotalBytes,
                warnings = listOf("Erro ao ler cabeçalho do ficheiro: ${e.message}")
            )
        }

        return verifyMp4BufferIntegrity(
            buffer = headBuffer,
            actualTotalSize = fileSize,
            expectedTotalBytes = expectedTotalBytes,
            chunks = chunks
        )
    }

    /**
     * Inspects ISO-BMFF top boxes from a byte buffer.
     */
    fun verifyMp4BufferIntegrity(
        buffer: ByteArray,
        actualTotalSize: Long = buffer.size.toLong(),
        expectedTotalBytes: Long? = null,
        chunks: List<Pair<Long, Long>>? = null
    ): AbysVerificationResult {
        var hasFtyp = false
        var hasMoov = false
        var hasMdat = false
        val topBoxes = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var declaredMdatBytes: Long? = null
        var isTruncated = false

        val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
        var offset = 0
        var guard = 0

        while (offset + 8 <= buffer.size && guard++ < 4096) {
            byteBuffer.position(offset)
            var size = byteBuffer.int.toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            byteBuffer.get(typeBytes)
            val type = String(typeBytes, StandardCharsets.ISO_8859_1)

            topBoxes.add(type)
            if (type == "ftyp") hasFtyp = true
            if (type == "moov") hasMoov = true
            if (type == "mdat") {
                hasMdat = true
                declaredMdatBytes = size
                if (size > 0L && (offset + size) > actualTotalSize) {
                    isTruncated = true
                    warnings.add("mdat declara $size bytes em offset $offset (total=${offset + size}) > ficheiro real $actualTotalSize bytes (TRUNCADO)")
                }
                break
            }

            var headerSize = 8
            if (size == 1L && offset + 16 <= buffer.size) {
                size = byteBuffer.long
                headerSize = 16
            } else if (size == 0L) {
                size = (actualTotalSize - offset)
            }

            if (size < headerSize) {
                warnings.add("Box '$type' com tamanho inválido ($size) no offset $offset")
                break
            }

            offset += size.toInt()
            if (size <= 0) break
        }

        if (!hasFtyp) {
            warnings.add("Falta box ftyp (cabeçalho MP4 ausente ou não decifrado)")
        }

        val missingGaps = mutableListOf<Pair<Long, Long>>()
        if (chunks != null && chunks.isNotEmpty()) {
            val sorted = chunks.sortedBy { it.first }
            val targetSize = expectedTotalBytes ?: actualTotalSize
            var cur = 0L
            for ((s, e) in sorted) {
                if (s > cur) {
                    missingGaps.add(Pair(cur, s - 1))
                }
                cur = maxOf(cur, e + 1)
            }
            if (cur < targetSize) {
                missingGaps.add(Pair(cur, targetSize - 1))
            }
            if (missingGaps.isNotEmpty()) {
                warnings.add("Existem ${missingGaps.size} intervalo(s) de bytes não descarregados (gaps)")
            }
        }

        if (expectedTotalBytes != null && actualTotalSize < expectedTotalBytes) {
            warnings.add("Tamanho do ficheiro ($actualTotalSize B) é inferior ao esperado ($expectedTotalBytes B)")
        }

        val isValid = hasFtyp && !isTruncated && missingGaps.isEmpty() && (expectedTotalBytes == null || actualTotalSize >= expectedTotalBytes)

        return AbysVerificationResult(
            isValidMp4 = isValid,
            isTruncated = isTruncated,
            hasFtyp = hasFtyp,
            hasMoov = hasMoov,
            hasMdat = hasMdat,
            actualSizeBytes = actualTotalSize,
            expectedSizeBytes = expectedTotalBytes,
            declaredMdatBytes = declaredMdatBytes,
            topBoxes = topBoxes,
            warnings = warnings,
            missingGaps = missingGaps
        )
    }
}

