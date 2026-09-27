package com.example.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.example.util.AbysDedicatedEngine
import com.example.util.AbysQualityEngine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Custom Media3 DataSource for seamless in-memory decryption, multi-part assembly,
 * and continuous streaming of ABYS streams (.fd / /sora/ / /mp4/).
 *
 * ABYS streams split videos into ~10MB/20MB parts and encrypt strictly the first 64KB of Part 1 with AES-256-CTR.
 * This DataSource transparently:
 * 1. Manages multi-part virtual streaming (Part 1, 2, 3... N) across the whole movie timeline.
 * 2. Transparently decrypts and caches the 64KB MP4 header for Part 1.
 * 3. Switches parts on-the-fly when playback crosses part boundaries without interrupting ExoPlayer.
 * 4. Passes through standard non-ABYS media streams with zero overhead.
 */
@OptIn(UnstableApi::class)
class AbysCipherDataSource(
    private val upstream: DataSource,
    private val headerDataSource: DataSource
) : DataSource {

    companion object {
        const val PIECE_SIZE = AbysDedicatedEngine.PIECE_SIZE // 65,536 bytes (64 KB)
        const val DEFAULT_PART_SIZE = AbysDedicatedEngine.DEFAULT_PART_SIZE // 10,485,760 bytes (10 MB)

        // Bounded in-memory cache for decrypted 64KB headers keyed by stream filename/URL
        private val decryptedHeaderCache = ConcurrentHashMap<String, ByteArray>()

        fun clearHeaderCache() {
            decryptedHeaderCache.clear()
        }

        fun cacheDecryptedHeader(key: String, headerBytes: ByteArray) {
            decryptedHeaderCache[key] = headerBytes
        }

        fun getCachedHeader(key: String): ByteArray? {
            return decryptedHeaderCache[key]
        }
    }

    private var currentDataSpec: DataSpec? = null
    private var isAbys: Boolean = false
    private var streamFilename: String = ""
    private var streamPosition: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var cachedHeader: ByteArray? = null
    private var localFallbackDataSource: DataSource? = null

    // Multi-part streaming context
    private var totalMovieSize: Long = 0L
    private var partSize: Long = DEFAULT_PART_SIZE
    private var currentPartIndex: Int = 1
    private var totalParts: Int = 1
    private var partBytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var streamSlug: String? = null
    private var streamResId: Int = 2
    private var streamHash: String? = null
    private var streamHost: String = "abysscdn.com"
    private var observedParts: List<String> = emptyList()
    private var mirrorHosts: List<String> = emptyList()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
        headerDataSource.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        currentDataSpec = dataSpec
        val uriStr = dataSpec.uri.toString()
        val scheme = dataSpec.uri.scheme?.lowercase()
        val isHttp = scheme == "http" || scheme == "https"
        isAbys = isHttp && AbysDedicatedEngine.isAbysUrl(uriStr)

        if (!isAbys) {
            return try {
                upstream.open(dataSpec)
            } catch (e: Exception) {
                if (scheme == "file") {
                    val fds = androidx.media3.datasource.FileDataSource()
                    localFallbackDataSource = fds
                    fds.open(dataSpec)
                } else {
                    throw e
                }
            }
        }

        // Initialize ABYS multi-part stream parameters
        streamFilename = AbysDedicatedEngine.extractFilename(uriStr)
        streamPosition = dataSpec.position
        bytesRemaining = dataSpec.length

        val parsedFd = AbysQualityEngine.parseFromFdUrl(uriStr)
        val headerSize = dataSpec.httpRequestHeaders["X-Abys-Size"]?.toLongOrNull()?.takeIf { it > 0L }
        totalMovieSize = headerSize ?: parsedFd?.sizeBytes ?: 0L

        streamSlug = dataSpec.httpRequestHeaders["X-Abys-Slug"]?.takeIf { it.isNotBlank() }
        streamResId = dataSpec.httpRequestHeaders["X-Abys-ResId"]?.toIntOrNull() ?: parsedFd?.resId ?: 2
        streamHash = parsedFd?.hash
        streamHost = dataSpec.uri.host ?: "abysscdn.com"

        val declaredPartSize = dataSpec.httpRequestHeaders["X-Abys-PartSize"]?.toLongOrNull()?.takeIf { it > 0L }
        partSize = declaredPartSize ?: DEFAULT_PART_SIZE

        observedParts = dataSpec.httpRequestHeaders["X-Abys-Parts"]?.split(";")?.filter { it.isNotBlank() } ?: emptyList()
        mirrorHosts = dataSpec.httpRequestHeaders["X-Abys-Mirrors"]?.split(",")?.filter { it.isNotBlank() } ?: listOf(streamHost)

        totalParts = if (totalMovieSize > 0L && partSize > 0L) {
            Math.ceil(totalMovieSize.toDouble() / partSize.toDouble()).toInt().coerceAtLeast(1)
        } else {
            1
        }

        // Determine which part streamPosition falls into
        currentPartIndex = (streamPosition / partSize).toInt() + 1
        val localOffset = streamPosition - (currentPartIndex - 1) * partSize

        openPart(currentPartIndex, localOffset, dataSpec)

        if (streamPosition < PIECE_SIZE && currentPartIndex == 1) {
            cachedHeader = getOrFetchDecryptedHeader(dataSpec, streamFilename)
        }

        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (totalMovieSize > 0L && streamPosition < totalMovieSize) {
            totalMovieSize - streamPosition
        } else {
            C.LENGTH_UNSET.toLong()
        }
    }

    @Throws(IOException::class)
    private fun openPart(partIndex: Int, localOffset: Long, baseSpec: DataSpec): Long {
        val candidates = buildCandidateSpecsForPart(partIndex, localOffset, baseSpec)
        var lastException: IOException? = null
        var openedLen: Long = C.LENGTH_UNSET.toLong()
        var openedSpec: DataSpec? = null

        for (candidateSpec in candidates) {
            try {
                try { upstream.close() } catch (_: Throwable) {}
                openedLen = upstream.open(candidateSpec)
                openedSpec = candidateSpec
                lastException = null
                break
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                lastException = e
                if (e.responseCode == 416 && localOffset > 0L) {
                    openedLen = 0L
                    partBytesRemaining = 0L
                    openedSpec = candidateSpec
                    lastException = null
                    break
                }
                continue
            } catch (e: IOException) {
                lastException = e
                continue
            }
        }

        if (lastException != null && openedSpec == null) {
            if (partIndex > 1) {
                partBytesRemaining = 0L
                return 0L
            }
            throw lastException
        }

        partBytesRemaining = if (openedLen != C.LENGTH_UNSET.toLong() && openedLen > 0L) {
            openedLen
        } else {
            partSize - localOffset
        }

        return openedLen
    }

    private fun buildCandidateSpecsForPart(
        partIndex: Int,
        localOffset: Long,
        baseSpec: DataSpec
    ): List<DataSpec> {
        val candidateUrls = mutableListOf<String>()

        // 1. If Part 1, use primary URI first
        if (partIndex == 1) {
            candidateUrls.add(baseSpec.uri.toString())
        }

        // 2. Check observed parts from WebView network traffic
        if (observedParts.isNotEmpty()) {
            for (obs in observedParts) {
                val obsPart = Regex("""[._](\d+)\.(bin|part|fd)""", RegexOption.IGNORE_CASE).find(obs)?.groupValues?.get(1)?.toIntOrNull()
                if (obsPart == partIndex) {
                    candidateUrls.add(obs)
                }
            }
        }

        // 3. Service Worker /sora/ tokens for part N
        if (totalMovieSize > 0L) {
            val sizeStr = totalMovieSize.toString()
            val pathWithSlug = if (!streamSlug.isNullOrBlank()) "/mp4/$streamResId/$sizeStr?v=$streamSlug&part=$partIndex" else "/mp4/$streamResId/$sizeStr?part=$partIndex"
            val soraToken = AbysDedicatedEngine.generateSoraToken(pathWithSlug, sizeStr)
            candidateUrls.add("https://$streamHost/sora/$sizeStr/$soraToken")

            for (m in mirrorHosts) {
                candidateUrls.add("https://$m/sora/$sizeStr/$soraToken")
            }

            if (partIndex == 1) {
                val pathDirect = if (!streamSlug.isNullOrBlank()) "/mp4/$streamResId/$sizeStr?v=$streamSlug" else "/mp4/$streamResId/$sizeStr"
                val soraTokenDirect = AbysDedicatedEngine.generateSoraToken(pathDirect, sizeStr)
                candidateUrls.add("https://$streamHost/sora/$sizeStr/$soraTokenDirect")
            }
        }

        // 4. Structured /mp4/ routes
        if (totalMovieSize > 0L) {
            val sizeStr = totalMovieSize.toString()
            val mp4Url = if (!streamSlug.isNullOrBlank()) "https://$streamHost/mp4/$streamResId/$sizeStr?v=$streamSlug&part=$partIndex" else "https://$streamHost/mp4/$streamResId/$sizeStr?part=$partIndex"
            candidateUrls.add(mp4Url)
        }

        // 5. Binary chunk filenames (.bin, .part, .fd)
        if (!streamHash.isNullOrBlank() && totalMovieSize > 0L) {
            candidateUrls.add("https://$streamHost/video/$streamHash.$totalMovieSize.$streamResId.$partIndex.bin")
            candidateUrls.add("https://$streamHost/video/$streamHash.$totalMovieSize.$streamResId.$partIndex.part")
            candidateUrls.add("https://$streamHost/video/$streamHash.$totalMovieSize.$streamResId.$partIndex.fd")
        }

        val defaultBrowserUa = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        val cookie = try {
            android.webkit.CookieManager.getInstance().getCookie(baseSpec.uri.toString())
        } catch (_: Throwable) { null }

        val initialHeaders = baseSpec.httpRequestHeaders.toMutableMap()
        if (!initialHeaders.containsKey("User-Agent")) initialHeaders["User-Agent"] = defaultBrowserUa
        if (!cookie.isNullOrBlank() && !initialHeaders.containsKey("Cookie")) initialHeaders["Cookie"] = cookie

        val candidateHeadersList = listOf(
            initialHeaders.toMutableMap().apply { put("Referer", AbysDedicatedEngine.DEFAULT_REFERER); remove("Origin") },
            initialHeaders.toMutableMap().apply { put("Referer", "https://$streamHost/"); remove("Origin") },
            initialHeaders.toMutableMap().apply { put("Referer", "https://embedplayabyss.top/"); remove("Origin") },
            initialHeaders.toMutableMap().apply { remove("Referer"); remove("Origin") }
        ).distinct()

        val specs = mutableListOf<DataSpec>()
        for (u in candidateUrls.distinct()) {
            val isContinuous = u.contains("/mp4/") && !u.contains("part=")
            val effPos = if (isContinuous) streamPosition else localOffset
            for (h in candidateHeadersList) {
                specs.add(
                    baseSpec.buildUpon()
                        .setUri(Uri.parse(u))
                        .setPosition(effPos)
                        .setLength(C.LENGTH_UNSET.toLong())
                        .setHttpRequestHeaders(h)
                        .build()
                )
            }
        }
        return specs
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        if (!isAbys) {
            localFallbackDataSource?.let { return it.read(buffer, offset, length) }
            return upstream.read(buffer, offset, length)
        }

        if (bytesRemaining == 0L || (totalMovieSize > 0L && streamPosition >= totalMovieSize)) {
            return C.RESULT_END_OF_INPUT
        }

        val toRead = if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            min(length.toLong(), bytesRemaining).toInt()
        } else {
            length
        }

        var bytesRead = upstream.read(buffer, offset, toRead)

        // If current part reached EOF, transition seamlessly to the next part!
        if (bytesRead == C.RESULT_END_OF_INPUT || bytesRead <= 0) {
            if (currentPartIndex < totalParts && (totalMovieSize == 0L || streamPosition < totalMovieSize)) {
                currentPartIndex++
                val nextOffset = 0L
                currentDataSpec?.let { spec ->
                    openPart(currentPartIndex, nextOffset, spec)
                    bytesRead = upstream.read(buffer, offset, toRead)
                }
            }
        }

        if (bytesRead == C.RESULT_END_OF_INPUT || bytesRead <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        // Seamlessly substitute the first 64KB with the decrypted MP4 header bytes
        if (streamPosition < PIECE_SIZE) {
            val header = cachedHeader ?: currentDataSpec?.let { getOrFetchDecryptedHeader(it, streamFilename) }
            if (header != null && header.isNotEmpty()) {
                for (i in 0 until bytesRead) {
                    val absPos = streamPosition + i
                    if (absPos < PIECE_SIZE && absPos < header.size) {
                        buffer[offset + i] = header[absPos.toInt()]
                    }
                }
            }
        }

        streamPosition += bytesRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        if (partBytesRemaining != C.LENGTH_UNSET.toLong()) {
            partBytesRemaining -= bytesRead
        }

        return bytesRead
    }

    override fun getUri(): Uri? {
        return localFallbackDataSource?.uri ?: upstream.uri ?: currentDataSpec?.uri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return localFallbackDataSource?.responseHeaders ?: upstream.responseHeaders
    }

    @Throws(IOException::class)
    override fun close() {
        cachedHeader = null
        try {
            localFallbackDataSource?.close()
        } catch (_: Throwable) {}
        localFallbackDataSource = null
        try {
            upstream.close()
        } finally {
            currentDataSpec = null
        }
    }

    @Throws(IOException::class)
    private fun getOrFetchDecryptedHeader(baseSpec: DataSpec, filename: String): ByteArray {
        val cacheKey = filename.ifBlank { baseSpec.uri.toString() }
        decryptedHeaderCache[cacheKey]?.let { return it }

        val headSpec = baseSpec.buildUpon()
            .setPosition(0L)
            .setLength(PIECE_SIZE.toLong())
            .build()

        try {
            try { headerDataSource.close() } catch (_: Throwable) {}
            headerDataSource.open(headSpec)
            val baos = ByteArrayOutputStream(PIECE_SIZE)
            val tempBuffer = ByteArray(8192)
            var totalRead = 0

            while (totalRead < PIECE_SIZE) {
                val toRead = min(tempBuffer.size, PIECE_SIZE - totalRead)
                val read = headerDataSource.read(tempBuffer, 0, toRead)
                if (read == C.RESULT_END_OF_INPUT) break
                baos.write(tempBuffer, 0, read)
                totalRead += read
            }

            val rawBytes = baos.toByteArray()
            val decrypted = AbysDedicatedEngine.decryptFdHeader(rawBytes, filename)
            decryptedHeaderCache[cacheKey] = decrypted
            return decrypted
        } catch (_: Exception) {
            return ByteArray(0)
        } finally {
            try {
                headerDataSource.close()
            } catch (_: Throwable) {}
        }
    }
}
