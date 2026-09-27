package com.example.util

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlin.math.min

/**
 * Pipeline Downloader for ABYS (.fd / abyss.to / abysscdn.com) streams.
 *
 * Implements the reinforced ABYS-Toolkit architecture:
 * 1. Multi-part sequential assembly (parts 1..N) utilizing all mirror domains and observed part URLs.
 * 2. SW-Oracle Continuous Range loop (2 MiB chunks / 20 MiB parts) ensuring 100% byte coverage.
 * 3. On-the-fly 64KB (0x10000) AES-256-CTR header decryption for part 1 / offset 0.
 * 4. Direct GET fallback for dedicated part endpoints when Range requests are not supported.
 * 5. Resilient retry loop with exponential backoff (up to 6 retries) per chunk/part.
 * 6. Post-download ISO-BMFF box verification (ftyp/moov/mdat) preventing truncated files.
 * 7. Sidecar .json metadata generation with show, season, episode, md5_id, and resolution details.
 */
class AbysPipelineDownloader(
    private val client: OkHttpClient
) {

    companion object {
        const val PIECE_SIZE = AbysDedicatedEngine.PIECE_SIZE // 65,536 bytes (64 KB)
        const val CHUNK_SIZE_2MB = 0x200000L // 2 MiB standard chunk size from abys_pull.user.js
        const val DEFAULT_PART_SIZE = AbysDedicatedEngine.DEFAULT_PART_SIZE // 20 MB standard chunk size
        private const val MAX_RETRIES = 6 // Up to 6 retries per chunk/part as defined in toolkit
        private const val BUFFER_SIZE = 65536 // 64 KB read buffer
        private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        val KNOWN_ABYS_MIRRORS = listOf(
            "abysscdn.com",
            "abyss.to",
            "embedplayabyss.top",
            "embedplay.click",
            "embedplay.one",
            "sssrr.org",
            "trycloudflare.com"
        )
    }

    /**
     * Downloads an ABYS stream or sequence of parts, decrypts the first 64KB,
     * pulls all remaining bytes continuously via multi-mirror and Range requests, and validates file integrity.
     */
    suspend fun download(
        url: String,
        targetFile: File,
        headers: Map<String, String> = emptyMap(),
        additionalPartUrls: List<String> = emptyList(),
        expectedSizeHint: Long? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        targetFile.parentFile?.mkdirs()

        val parsedFd = AbysQualityEngine.parseFromFdUrl(url)
        val headerSize = headers["X-Abys-Size"]?.toLongOrNull()?.takeIf { it > 0L }
        val slug = headers["X-Abys-Slug"]?.takeIf { it.isNotBlank() } ?: AbysNameResolver.extractSlug(url)
        val md5Id = headers["X-Abys-Md5Id"]?.takeIf { it.isNotBlank() }
        val resId = headers["X-Abys-ResId"]?.toIntOrNull() ?: parsedFd?.resId ?: 4
        val virtualOracleUrl = headers["X-Abys-Virtual-Url"]?.takeIf { it.isNotBlank() }

        // Determine all available mirrors
        val baseUri = try { java.net.URI(url) } catch (_: Throwable) { null }
        val host = baseUri?.host ?: "abysscdn.com"
        val rawMirrors = headers["X-Abys-Mirrors"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val allMirrors = (listOf(host) + rawMirrors + KNOWN_ABYS_MIRRORS).distinct()

        val extractedParts = headers["X-Abys-Parts"]?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val allObservedPartUrls = (additionalPartUrls + extractedParts).filter { it.isNotBlank() }.distinct()

        // 1. Probe the stream or virtual oracle endpoint to get the exact total byte length
        val probeUrl = virtualOracleUrl ?: url
        val probedTotal = probeExactTotalBytes(probeUrl, headers)
        var totalBytes = expectedSizeHint?.takeIf { it > 0L }
            ?: probedTotal
            ?: headerSize
            ?: parsedFd?.sizeBytes?.takeIf { it > 0L }
            ?: 0L

        // Check if we are dealing with a direct SW-Oracle Virtual URL (e.g. /mp4/<res>/<size> or /part/...)
        val isVirtualOracleEndpoint = url.contains("/mp4/") || url.contains("/part/") || url.contains("/r2/") || !virtualOracleUrl.isNullOrBlank()
        val effectiveOracleUrl = if (url.contains("/mp4/") || url.contains("/part/")) url else virtualOracleUrl

        var currentFileLength = targetFile.length()
        val passFilename = AbysDedicatedEngine.extractFilename(url)

        // 2. Step A: Initial Head Download and Decryption (Offset 0 / First 64KB)
        if (currentFileLength < PIECE_SIZE) {
            val headDownloadUrl = if (!effectiveOracleUrl.isNullOrBlank()) effectiveOracleUrl else url
            downloadAndDecryptHead(
                url = headDownloadUrl,
                targetFile = targetFile,
                passFilename = passFilename,
                headers = headers,
                onProgressUpdate = { _, _ ->
                    onProgress(targetFile.length(), if (totalBytes > 0L) totalBytes else 0L)
                }
            )
            currentFileLength = targetFile.length()
        }

        // Check if part 1 revealed total declared size from ISO-BMFF mdat/moov boxes
        if (totalBytes <= currentFileLength && currentFileLength >= PIECE_SIZE) {
            try {
                val headBytes = ByteArray(minOf(currentFileLength, 1024 * 1024L).toInt())
                RandomAccessFile(targetFile, "r").use { raf -> raf.readFully(headBytes) }
                val meta = AbysDedicatedEngine.parseMovieMetadata(headBytes, passFilename)
                if (meta.totalDeclaredBytes != null && meta.totalDeclaredBytes > currentFileLength) {
                    totalBytes = meta.totalDeclaredBytes
                }
            } catch (_: Exception) {}
        }

        val reportedTotalBytes = if (totalBytes > 0L) totalBytes else currentFileLength
        onProgress(currentFileLength, reportedTotalBytes)

        // 3. Step B: Continuous Pulling of Remaining Chunks / Multi-Parts
        if (totalBytes > currentFileLength) {
            // Decide chunk strategy: 2 MiB for SW-Oracle Range Pulling, or effective Part Size (20 MB) for multi-part CDN
            val use2MbChunks = isVirtualOracleEndpoint || (!effectiveOracleUrl.isNullOrBlank())
            val chunkSize = if (use2MbChunks) {
                CHUNK_SIZE_2MB
            } else {
                val p1Size = if (currentFileLength > 0L) currentFileLength else DEFAULT_PART_SIZE
                if (p1Size in 1_000_000L..50_000_000L) p1Size else DEFAULT_PART_SIZE
            }

            val totalChunks = Math.ceil(totalBytes.toDouble() / chunkSize).toInt()
            val startChunkIndex = (currentFileLength / chunkSize).toInt()

            for (chunkIdx in startChunkIndex until totalChunks) {
                coroutineContext.ensureActive()
                val startOffset = chunkIdx * chunkSize
                val endOffset = minOf((chunkIdx + 1) * chunkSize - 1, totalBytes - 1)
                val expectedChunkLen = endOffset - startOffset + 1

                if (targetFile.length() >= (endOffset + 1)) {
                    continue // Chunk already downloaded
                }

                val partNumber = (startOffset / DEFAULT_PART_SIZE).toInt() + 1

                val candidateUrls = generateComprehensivePartCandidates(
                    basePart1Url = url,
                    virtualOracleUrl = effectiveOracleUrl,
                    host = host,
                    resId = resId,
                    totalBytes = totalBytes,
                    slug = slug,
                    md5Id = md5Id,
                    part = partNumber,
                    mirrors = allMirrors,
                    parsedFd = parsedFd,
                    observedUrls = allObservedPartUrls
                )

                var chunkDownloaded = false

                for (retry in 0 until MAX_RETRIES) {
                    coroutineContext.ensureActive()
                    for (candUrl in candidateUrls) {
                        coroutineContext.ensureActive()
                        try {
                            val bytesWritten = downloadRangeChunk(
                                url = candUrl,
                                targetFile = targetFile,
                                headers = headers,
                                startOffset = startOffset,
                                endOffset = endOffset
                            )
                            if (bytesWritten >= expectedChunkLen || (bytesWritten > 0L && targetFile.length() >= (endOffset + 1))) {
                                chunkDownloaded = true
                                break
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        }
                    }
                    if (chunkDownloaded) break

                    // If range chunk failed across all candidates on this retry, try direct GET on part-specific candidates
                    if (!chunkDownloaded && partNumber >= 2) {
                        for (candUrl in candidateUrls) {
                            coroutineContext.ensureActive()
                            if (candUrl.contains(".$partNumber.") || candUrl.contains("part$partNumber") || candUrl.contains("/$partNumber")) {
                                try {
                                    val fileLenBefore = targetFile.length()
                                    val bytesWritten = downloadDirectPartStream(
                                        url = candUrl,
                                        targetFile = targetFile,
                                        headers = headers,
                                        startOffset = startOffset,
                                        maxBytesToRead = expectedChunkLen
                                    )
                                    if (bytesWritten > 0L || targetFile.length() > fileLenBefore) {
                                        chunkDownloaded = true
                                        break
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    if (chunkDownloaded) break
                    delay(250L * (retry + 1))
                }

                if (chunkDownloaded) {
                    onProgress(targetFile.length(), totalBytes)
                } else {
                    // Final fallback: attempt downloadRemainingStream
                    for (candUrl in candidateUrls) {
                        coroutineContext.ensureActive()
                        try {
                            val fileLenBefore = targetFile.length()
                            downloadRemainingStream(candUrl, targetFile, headers, startOffset)
                            if (targetFile.length() > fileLenBefore) {
                                chunkDownloaded = true
                                onProgress(targetFile.length(), totalBytes)
                                break
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // 4. ISO-BMFF Verification & Integrity Audit (abys_verify.js logic)
        val verifyResult = AbysDedicatedEngine.verifyMp4Integrity(targetFile, if (totalBytes > 0L) totalBytes else null)
        if (!verifyResult.isValidMp4) {
            if (verifyResult.isTruncated || targetFile.length() < PIECE_SIZE) {
                val warnMsg = verifyResult.warnings.joinToString("; ").ifBlank { "Ficheiro MP4 truncado ou corrompido" }
                throw Exception("Falha de integridade ABYS: $warnMsg")
            }
        }

        // 5. Generate Sidecar .json metadata alongside the .mp4 file
        try {
            val title = headers["X-Abys-Title"] ?: targetFile.nameWithoutExtension
            val metadata = AbysNameResolver.resolveMetadata(
                title = title,
                slug = slug,
                md5Id = md5Id,
                userId = headers["X-Abys-UserId"]
            )
            writeSidecarJson(targetFile, metadata, verifyResult, url)
        } catch (_: Exception) {}

        onProgress(targetFile.length(), targetFile.length())
    }

    /**
     * Probes the exact total byte size via Range: bytes=0-0 or Content-Range.
     */
    private suspend fun probeExactTotalBytes(url: String, headers: Map<String, String>): Long? = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank() && !k.equals("Origin", ignoreCase = true) && !k.startsWith("X-Abys-", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }
            if (!headers.containsKey("Referer")) {
                val host = try { java.net.URI(url).host } catch (_: Throwable) { null }
                reqBuilder.header("Referer", if (!host.isNullOrBlank()) "https://$host/" else AbysDedicatedEngine.DEFAULT_REFERER)
            }
            reqBuilder.header("User-Agent", DEFAULT_UA)
            reqBuilder.header("Range", "bytes=0-0")

            client.newCall(reqBuilder.build()).execute().use { response ->
                val cr = response.header("Content-Range")
                if (!cr.isNullOrBlank()) {
                    val total = cr.substringAfterLast("/").trim().toLongOrNull()
                    if (total != null && total > 0L) return@withContext total
                }
                val cl = response.body?.contentLength()
                if (cl != null && cl > 65536L) return@withContext cl
            }
        } catch (_: Exception) {}
        null
    }

    /**
     * Downloads and decrypts the first 64KB (piece) of an ABYS .fd file.
     */
    private suspend fun downloadAndDecryptHead(
        url: String,
        targetFile: File,
        passFilename: String,
        headers: Map<String, String>,
        onProgressUpdate: (written: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank() && !k.equals("Referer", ignoreCase = true) && !k.equals("Origin", ignoreCase = true) && !k.startsWith("X-Abys-", ignoreCase = true)) {
                reqBuilder.header(k, v)
            }
        }
        val host = try { java.net.URI(url).host } catch (_: Throwable) { null }
        reqBuilder.header("Referer", headers["Referer"] ?: if (!host.isNullOrBlank()) "https://$host/" else AbysDedicatedEngine.DEFAULT_REFERER)
        reqBuilder.header("User-Agent", DEFAULT_UA)
        reqBuilder.header("Accept", "*/*")

        client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw Exception("HTTP ${response.code} ao descarregar parte 1 do ABYS")
            }
            val body = response.body ?: throw Exception("Corpo vazio ao descarregar parte 1")
            val totalBytes = body.contentLength().takeIf { it > 0L } ?: 0L

            body.byteStream().use { inputStream ->
                FileOutputStream(targetFile, false).use { fos ->
                    val headBuffer = ByteArray(PIECE_SIZE)
                    var headRead = 0
                    while (headRead < PIECE_SIZE) {
                        coroutineContext.ensureActive()
                        val r = inputStream.read(headBuffer, headRead, PIECE_SIZE - headRead)
                        if (r == -1) break
                        headRead += r
                    }

                    if (headRead > 0) {
                        val rawHead = if (headRead == PIECE_SIZE) headBuffer else headBuffer.copyOf(headRead)
                        // If head already starts with ftyp, it's plaintext; otherwise decrypt with AES-256-CTR
                        val decrypted = if (rawHead.size >= 8 && String(rawHead, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1) == "ftyp") {
                            rawHead
                        } else {
                            AbysDedicatedEngine.decryptFdHeader(rawHead, passFilename)
                        }
                        fos.write(decrypted)
                        onProgressUpdate(decrypted.size.toLong(), totalBytes)
                    }

                    val buffer = ByteArray(BUFFER_SIZE)
                    var read = 0
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        fos.write(buffer, 0, read)
                        onProgressUpdate(read.toLong(), totalBytes)
                    }
                    fos.flush()
                }
            }
        }
    }

    /**
     * Pulls a specific Range byte slice [startOffset..endOffset] from an endpoint and writes it to targetFile.
     */
    private suspend fun downloadRangeChunk(
        url: String,
        targetFile: File,
        headers: Map<String, String>,
        startOffset: Long,
        endOffset: Long
    ): Long = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank() && !k.equals("Referer", ignoreCase = true) && !k.equals("Origin", ignoreCase = true) && !k.startsWith("X-Abys-", ignoreCase = true)) {
                reqBuilder.header(k, v)
            }
        }
        val host = try { java.net.URI(url).host } catch (_: Throwable) { null }
        reqBuilder.header("Referer", headers["Referer"] ?: if (!host.isNullOrBlank()) "https://$host/" else AbysDedicatedEngine.DEFAULT_REFERER)
        reqBuilder.header("User-Agent", DEFAULT_UA)
        reqBuilder.header("Range", "bytes=$startOffset-$endOffset")

        client.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code != 206 && response.code != 200) {
                return@withContext 0L
            }
            val body = response.body ?: return@withContext 0L
            val expectedLength = endOffset - startOffset + 1

            var writtenThisChunk = 0L
            body.byteStream().use { inputStream ->
                RandomAccessFile(targetFile, "rw").use { raf ->
                    raf.seek(startOffset)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read = 0
                    while (writtenThisChunk < expectedLength) {
                        val bytesToRead = min(buffer.size.toLong(), expectedLength - writtenThisChunk).toInt()
                        read = inputStream.read(buffer, 0, bytesToRead)
                        if (read == -1) break
                        coroutineContext.ensureActive()
                        raf.write(buffer, 0, read)
                        writtenThisChunk += read
                    }
                }
            }
            return@withContext writtenThisChunk
        }
    }

    /**
     * Downloads a dedicated part URL directly (GET without Range) and writes up to maxBytesToRead starting at startOffset.
     */
    private suspend fun downloadDirectPartStream(
        url: String,
        targetFile: File,
        headers: Map<String, String>,
        startOffset: Long,
        maxBytesToRead: Long
    ): Long = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank() && !k.equals("Referer", ignoreCase = true) && !k.equals("Origin", ignoreCase = true) && !k.startsWith("X-Abys-", ignoreCase = true)) {
                reqBuilder.header(k, v)
            }
        }
        val host = try { java.net.URI(url).host } catch (_: Throwable) { null }
        reqBuilder.header("Referer", headers["Referer"] ?: if (!host.isNullOrBlank()) "https://$host/" else AbysDedicatedEngine.DEFAULT_REFERER)
        reqBuilder.header("User-Agent", DEFAULT_UA)

        client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) return@withContext 0L
            val body = response.body ?: return@withContext 0L

            var written = 0L
            body.byteStream().use { inputStream ->
                RandomAccessFile(targetFile, "rw").use { raf ->
                    raf.seek(startOffset)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read = 0
                    while (written < maxBytesToRead && inputStream.read(buffer, 0, min(buffer.size.toLong(), maxBytesToRead - written).toInt()).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        raf.write(buffer, 0, read)
                        written += read
                    }
                }
            }
            return@withContext written
        }
    }

    private suspend fun downloadRemainingStream(
        url: String,
        targetFile: File,
        headers: Map<String, String>,
        startOffset: Long
    ) = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank() && !k.equals("Referer", ignoreCase = true) && !k.equals("Origin", ignoreCase = true) && !k.startsWith("X-Abys-", ignoreCase = true)) {
                reqBuilder.header(k, v)
            }
        }
        val host = try { java.net.URI(url).host } catch (_: Throwable) { null }
        reqBuilder.header("Referer", headers["Referer"] ?: if (!host.isNullOrBlank()) "https://$host/" else AbysDedicatedEngine.DEFAULT_REFERER)
        reqBuilder.header("User-Agent", DEFAULT_UA)

        client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) return@withContext
            val body = response.body ?: return@withContext
            body.byteStream().use { inputStream ->
                RandomAccessFile(targetFile, "rw").use { raf ->
                    raf.seek(startOffset)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read = 0
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        raf.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    /**
     * Generates a comprehensive list of candidate URLs for part P across observed URLs,
     * virtual endpoints (/mp4/, /part/), mirror domains, direct CDN part patterns, and /sora/ tokens.
     */
    private fun generateComprehensivePartCandidates(
        basePart1Url: String,
        virtualOracleUrl: String?,
        host: String,
        resId: Int,
        totalBytes: Long,
        slug: String?,
        md5Id: String?,
        part: Int,
        mirrors: List<String>,
        parsedFd: AbysFdInfo?,
        observedUrls: List<String>
    ): List<String> {
        val list = mutableListOf<String>()
        val sizeStr = totalBytes.toString()
        val targetHosts = (listOf(host) + mirrors + KNOWN_ABYS_MIRRORS).distinct()

        // 1. If explicit SW-Oracle virtual URL is available, prioritize it
        if (!virtualOracleUrl.isNullOrBlank()) {
            list.add(virtualOracleUrl)
            for (h in targetHosts) {
                try {
                    val u = java.net.URI(virtualOracleUrl)
                    list.add("${u.scheme ?: "https"}://$h${u.rawPath}${if (u.rawQuery != null) "?${u.rawQuery}" else ""}")
                } catch (_: Throwable) {}
            }
        }

        // 2. Check observed URLs from browser session (fristDatas, sources, network events)
        val directIndexed = if (part >= 2 && (part - 2) < observedUrls.size) observedUrls[part - 2] else null
        if (!directIndexed.isNullOrBlank()) {
            list.add(directIndexed)
        }

        val matchingObserved = observedUrls.filter { u ->
            u.contains(".$part.", ignoreCase = true) ||
            u.contains("part$part", ignoreCase = true) ||
            u.contains("_part$part", ignoreCase = true) ||
            u.contains("/$part", ignoreCase = true) ||
            u.contains("part=$part", ignoreCase = true)
        }
        list.addAll(matchingObserved)

        // Generate mirrored versions of all matching observed URLs
        for (obs in matchingObserved) {
            try {
                val obsUri = java.net.URI(obs)
                for (h in targetHosts) {
                    list.add("${obsUri.scheme ?: "https"}://$h${obsUri.rawPath}${if (obsUri.rawQuery != null) "?${obsUri.rawQuery}" else ""}")
                }
            } catch (_: Throwable) {}
        }

        // 3. SW-Oracle Virtual Endpoints with Range support (/mp4/<res>/<size>?v=<slug>)
        for (h in targetHosts) {
            if (!slug.isNullOrBlank()) {
                if (!md5Id.isNullOrBlank()) {
                    list.add("https://$h/mp4/$md5Id/$resId/$sizeStr?v=$slug")
                    list.add("https://$h/part/$md5Id/$resId/$sizeStr?v=$slug")
                }
                list.add("https://$h/mp4/$resId/$sizeStr?v=$slug")
                list.add("https://$h/part/$resId/$sizeStr?v=$slug")
                list.add("https://$h/r2/$resId/$sizeStr?v=$slug")
                list.add("https://$h/mp4/$resId/$sizeStr?v=$slug&part=$part")
            }
            if (!md5Id.isNullOrBlank()) {
                list.add("https://$h/mp4/$md5Id/$resId/$sizeStr")
                list.add("https://$h/part/$md5Id/$resId/$sizeStr")
            }
            list.add("https://$h/mp4/$resId/$sizeStr")
            list.add("https://$h/part/$resId/$sizeStr")
            list.add("https://$h/r2/$resId/$sizeStr")
            list.add("https://$h/mp4/$resId/$sizeStr?part=$part")
        }

        // 4. Direct CDN Part Filename Variants derived from Part 1 URL
        if (basePart1Url.contains(".fd")) {
            val baseWithoutFd = basePart1Url.substringBeforeLast(".fd")
            list.add("$baseWithoutFd.$part.bin")
            list.add("$baseWithoutFd.$part.fd")
            list.add("$baseWithoutFd.$part.part")
            list.add("$baseWithoutFd.part$part.bin")
            list.add("$baseWithoutFd.$part")

            val baseReplaced1 = baseWithoutFd.replace(Regex("""\.1$"""), ".$part")
            if (baseReplaced1 != baseWithoutFd) {
                list.add("$baseReplaced1.bin")
                list.add("$baseReplaced1.fd")
                list.add("$baseReplaced1.part")
            }
        }

        // 5. Dedicated CDN paths and mirrors
        for (h in targetHosts) {
            if (parsedFd != null) {
                list.add("https://$h/video/${parsedFd.hash}.$sizeStr.$resId.$part.bin")
                list.add("https://$h/video/${parsedFd.hash}.$sizeStr.$resId.$part.fd")
                list.add("https://$h/video/${parsedFd.hash}.$sizeStr.$resId.$part.part")
                list.add("https://$h/video/${parsedFd.hash}.$sizeStr.$resId.$part")
            }
            list.add("https://$h/part/$resId/$sizeStr/$part")
            list.add("https://$h/parts/part$part.bin")
            list.add("https://$h/parts/$part.bin")

            // 6. /sora/ virtual tokens
            val pathWithSlug = if (!slug.isNullOrBlank()) "/mp4/$resId/$sizeStr?v=$slug&part=$part" else "/mp4/$resId/$sizeStr?part=$part"
            val soraToken = AbysDedicatedEngine.generateSoraToken(pathWithSlug, sizeStr)
            list.add("https://$h/sora/$sizeStr/$soraToken")
        }

        // 7. Base URL with Range fallback
        list.add(basePart1Url)

        return list.distinct()
    }

    private fun writeSidecarJson(
        mp4File: File,
        metadata: AbysResolvedMetadata,
        verifyResult: AbysVerificationResult,
        sourceUrl: String
    ) {
        val sidecarFile = File(mp4File.parentFile, "${mp4File.nameWithoutExtension}.json")
        val jsonStr = buildString {
            append("{\n")
            append("  \"output\": \"${mp4File.name}\",\n")
            append("  \"bytes\": ${mp4File.length()},\n")
            append("  \"slug\": \"${metadata.slug.orEmpty()}\",\n")
            append("  \"md5_id\": \"${metadata.md5Id.orEmpty()}\",\n")
            append("  \"user_id\": \"${metadata.userId.orEmpty()}\",\n")
            append("  \"show\": \"${metadata.parsed.show.replace("\"", "\\\"")}\",\n")
            append("  \"season\": ${metadata.parsed.season ?: "null"},\n")
            append("  \"episode\": ${metadata.parsed.episode ?: "null"},\n")
            append("  \"se\": \"${metadata.parsed.se.orEmpty()}\",\n")
            append("  \"resolution\": \"${metadata.parsed.resolution.orEmpty()}\",\n")
            append("  \"original_title\": \"${metadata.originalTitle.orEmpty().replace("\"", "\\\"")}\",\n")
            append("  \"source_url\": \"${sourceUrl.replace("\"", "\\\"")}\",\n")
            append("  \"is_valid_mp4\": ${verifyResult.isValidMp4},\n")
            append("  \"has_ftyp\": ${verifyResult.hasFtyp},\n")
            append("  \"has_moov\": ${verifyResult.hasMoov},\n")
            append("  \"has_mdat\": ${verifyResult.hasMdat},\n")
            append("  \"declared_mdat_bytes\": ${verifyResult.declaredMdatBytes ?: "null"}\n")
            append("}")
        }
        sidecarFile.writeText(jsonStr)
    }
}
