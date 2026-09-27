package com.example.util

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encapsula as informações de criptografia HLS (#EXT-X-KEY).
 * Suporta AES-128 com chaves remotas, IVs explícitos e IVs derivados do número de sequência.
 */
data class HlsKeyInfo(
    val method: String,
    val keyUrl: String,
    val iv: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HlsKeyInfo
        if (method != other.method) return false
        if (keyUrl != other.keyUrl) return false
        if (iv != null) {
            if (other.iv == null) return false
            if (!iv.contentEquals(other.iv)) return false
        } else if (other.iv != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + keyUrl.hashCode()
        result = 31 * result + (iv?.contentHashCode() ?: 0)
        return result
    }
}

data class HlsByteRange(
    val length: Long,
    val offset: Long
)

data class HlsSegment(
    val index: Int,
    val url: String,
    val sequenceNumber: Long,
    val durationSecs: Double = 0.0,
    val keyInfo: HlsKeyInfo? = null,
    val byteRange: HlsByteRange? = null,
    val isInitSegment: Boolean = false
)

data class HlsMediaStream(
    val playlistUrl: String,
    val playlistContent: String,
    val audioPlaylistUrl: String? = null
)

class HlsPipelineDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
) {

    suspend fun download(
        url: String,
        targetFile: File,
        headers: Map<String, String> = emptyMap(),
        concurrency: Int = 3,
        maxInFlight: Int = 6,
        onProgress: (downloadedBytes: Long, totalEstimatedBytes: Long, currentSegment: Int, totalSegments: Int) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            // 1. Resolver fluxos de mídia (resolvendo Master Playlist e encontrando fluxos de Vídeo e Áudio separado)
            val streamInfo = resolveMediaStreams(url, headers)

            val videoSegments = parseMediaPlaylist(streamInfo.playlistUrl, streamInfo.playlistContent)
            if (videoSegments.isEmpty()) {
                throw IllegalStateException("Nenhum segmento de vídeo encontrado no manifesto HLS.")
            }

            val audioSegments = streamInfo.audioPlaylistUrl?.let { audioUrl ->
                try {
                    val audioContent = fetchString(audioUrl, headers)
                    parseMediaPlaylist(audioUrl, audioContent)
                } catch (e: Exception) {
                    null
                }
            }

            targetFile.parentFile?.mkdirs()

            // Se existir faixa de áudio separada do vídeo, baixamos cada uma e unimos com MediaMuxer
            if (!audioSegments.isNullOrEmpty()) {
                downloadAndRemuxSeparateTracks(
                    videoSegments = videoSegments,
                    audioSegments = audioSegments,
                    targetFile = targetFile,
                    headers = headers,
                    concurrency = concurrency,
                    maxInFlight = maxInFlight,
                    onProgress = onProgress
                )
            } else {
                // Fluxo padrão multiplexado (TS único ou fMP4 já combinado)
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                downloadSegmentsToFile(
                    segments = videoSegments,
                    targetFile = targetFile,
                    headers = headers,
                    concurrency = concurrency,
                    maxInFlight = maxInFlight,
                    progressMultiplier = 1f,
                    progressOffset = 0L,
                    totalSegmentsOverall = videoSegments.size,
                    segmentOffset = 0,
                    onProgress = onProgress
                )
            }
        }
    }

    private suspend fun downloadAndRemuxSeparateTracks(
        videoSegments: List<HlsSegment>,
        audioSegments: List<HlsSegment>,
        targetFile: File,
        headers: Map<String, String>,
        concurrency: Int,
        maxInFlight: Int,
        onProgress: (downloadedBytes: Long, totalEstimatedBytes: Long, currentSegment: Int, totalSegments: Int) -> Unit
    ) {
        val tempDir = targetFile.parentFile ?: File(".")
        val tempVideoFile = File(tempDir, "${targetFile.nameWithoutExtension}_temp_v.mp4")
        val tempAudioFile = File(tempDir, "${targetFile.nameWithoutExtension}_temp_a.mp4")

        try {
            if (tempVideoFile.exists()) tempVideoFile.delete()
            if (tempAudioFile.exists()) tempAudioFile.delete()

            val totalOverallSegments = videoSegments.size + audioSegments.size

            // 1. Download faixa de vídeo (com descriptografia sob demanda integrada)
            downloadSegmentsToFile(
                segments = videoSegments,
                targetFile = tempVideoFile,
                headers = headers,
                concurrency = concurrency,
                maxInFlight = maxInFlight,
                progressMultiplier = 0.7f,
                progressOffset = 0L,
                totalSegmentsOverall = totalOverallSegments,
                segmentOffset = 0,
                onProgress = { down, est, curr, _ ->
                    onProgress(down, est, curr, totalOverallSegments)
                }
            )

            val videoBytes = tempVideoFile.length()

            // 2. Download faixa de áudio (com descriptografia sob demanda integrada)
            downloadSegmentsToFile(
                segments = audioSegments,
                targetFile = tempAudioFile,
                headers = headers,
                concurrency = concurrency,
                maxInFlight = maxInFlight,
                progressMultiplier = 0.3f,
                progressOffset = videoBytes,
                totalSegmentsOverall = totalOverallSegments,
                segmentOffset = videoSegments.size,
                onProgress = { down, est, curr, _ ->
                    onProgress(videoBytes + down, videoBytes + est, videoSegments.size + curr, totalOverallSegments)
                }
            )

            // 3. Remuxing usando AudioVideoRemuxer (MediaMuxer)
            val muxSuccess = AudioVideoRemuxer.mux(
                videoFile = tempVideoFile,
                audioFile = tempAudioFile,
                outputFile = targetFile
            )

            if (!muxSuccess || !targetFile.exists() || targetFile.length() == 0L) {
                // Fallback: se o remuxing falhar, preserva o arquivo de vídeo original
                tempVideoFile.copyTo(targetFile, overwrite = true)
            }
        } finally {
            try {
                if (tempVideoFile.exists()) tempVideoFile.delete()
            } catch (ignored: Throwable) {}
            try {
                if (tempAudioFile.exists()) tempAudioFile.delete()
            } catch (ignored: Throwable) {}
        }
    }

    private suspend fun downloadSegmentsToFile(
        segments: List<HlsSegment>,
        targetFile: File,
        headers: Map<String, String>,
        concurrency: Int,
        maxInFlight: Int,
        progressMultiplier: Float = 1f,
        progressOffset: Long = 0L,
        totalSegmentsOverall: Int = segments.size,
        segmentOffset: Int = 0,
        onProgress: (downloadedBytes: Long, totalEstimatedBytes: Long, currentSegment: Int, totalSegments: Int) -> Unit
    ) {
        val totalSegments = segments.size
        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val deferredSegments = arrayOfNulls<CompletableDeferred<ByteArray>>(totalSegments)
        val semaphore = Semaphore(concurrency)
        var currentWritingIndex = 0

        coroutineScope {
            // Producer Coroutine: Baixa e descriptografa blocos sob demanda em paralelo
            val producerJob = launch(Dispatchers.IO) {
                for (seg in segments) {
                    ensureActive()

                    // Controle de vazão: limita blocos em memória
                    while (seg.index - currentWritingIndex > maxInFlight) {
                        delay(40)
                        ensureActive()
                    }

                    val deferred = CompletableDeferred<ByteArray>()
                    deferredSegments[seg.index] = deferred

                    launch {
                        semaphore.withPermit {
                            ensureActive()
                            try {
                                val data = downloadAndDecryptSegmentWithRetry(seg, headers, keyCache)
                                deferred.complete(data)
                            } catch (t: Throwable) {
                                deferred.completeExceptionally(t)
                            }
                        }
                    }
                }
            }

            // Consumer Coroutine: Escreve sequencialmente os dados prontos no arquivo final
            val outputStream = FileOutputStream(targetFile, true)
            var totalBytesWritten = 0L
            var lastProgressUpdate = 0L

            try {
                for (i in 0 until totalSegments) {
                    ensureActive()
                    currentWritingIndex = i

                    // Aguarda até o segmento i ser concluído
                    while (deferredSegments[i] == null) {
                        delay(15)
                        ensureActive()
                    }

                    val segmentBytes = deferredSegments[i]!!.await()
                    deferredSegments[i] = null // Libera memória imediatamente

                    outputStream.write(segmentBytes)
                    totalBytesWritten += segmentBytes.size

                    val completedSegments = i + 1
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 300 || completedSegments == totalSegments) {
                        val avgSegSize = totalBytesWritten / completedSegments
                        val estimatedTotal = avgSegSize * totalSegments
                        onProgress(
                            progressOffset + totalBytesWritten,
                            progressOffset + estimatedTotal,
                            segmentOffset + completedSegments,
                            totalSegmentsOverall
                        )
                        lastProgressUpdate = now
                    }
                }
            } finally {
                try {
                    outputStream.flush()
                    outputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                producerJob.cancel()
            }
        }
    }

    private suspend fun resolveMediaStreams(
        initialUrl: String,
        headers: Map<String, String>
    ): HlsMediaStream {
        var currentUrl = initialUrl
        var content = fetchString(currentUrl, headers)
        var separateAudioUrl: String? = null

        // Se for Master Playlist com variantes
        if (content.contains("#EXT-X-STREAM-INF")) {
            val lines = content.lines()
            var highestBandwidth = -1L
            var bestVariantUrl: String? = null
            var matchedAudioGroupId: String? = null

            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val bandwidthMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                    val bw = bandwidthMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                    val audioGroupMatch = Regex("""AUDIO=["']?([^"',\s]+)["']?""").find(line)
                    val audioGroupId = audioGroupMatch?.groupValues?.get(1)

                    // Encontra a próxima linha de URL não-vazia
                    var j = i + 1
                    while (j < lines.size && (lines[j].trim().isEmpty() || lines[j].trim().startsWith("#"))) {
                        j++
                    }
                    if (j < lines.size) {
                        val variantUri = lines[j].trim()
                        if (bw > highestBandwidth || bestVariantUrl == null) {
                            highestBandwidth = bw
                            bestVariantUrl = variantUri
                            matchedAudioGroupId = audioGroupId
                        }
                    }
                }
                i++
            }

            // Checar se há EXT-X-MEDIA:TYPE=AUDIO com URI correspondente ao grupo
            if (matchedAudioGroupId != null) {
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXT-X-MEDIA:") && trimmed.contains("TYPE=AUDIO")) {
                        val groupMatch = Regex("""GROUP-ID=["']?([^"',\s]+)["']?""").find(trimmed)
                        if (groupMatch?.groupValues?.get(1) == matchedAudioGroupId) {
                            val uriMatch = Regex("""URI=["']?([^"',\s]+)["']?""").find(trimmed)
                            if (uriMatch != null) {
                                separateAudioUrl = resolveUrl(initialUrl, uriMatch.groupValues[1])
                                break
                            }
                        }
                    }
                }
            }

            if (bestVariantUrl != null) {
                currentUrl = resolveUrl(currentUrl, bestVariantUrl)
                content = fetchString(currentUrl, headers)
            }
        }

        return HlsMediaStream(
            playlistUrl = currentUrl,
            playlistContent = content,
            audioPlaylistUrl = separateAudioUrl
        )
    }

    private fun parseMediaPlaylist(playlistUrl: String, content: String): List<HlsSegment> {
        val segments = mutableListOf<HlsSegment>()
        val lines = content.lines()

        var mediaSequence = 0L
        var currentKeyInfo: HlsKeyInfo? = null
        var currentByteRangeLength: Long? = null
        var currentByteRangeOffset: Long = 0L
        var segmentIndex = 0
        var mediaSegmentCount = 0L

        var currentDuration = 0.0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = trimmed.substringAfter("#EXT-X-MEDIA-SEQUENCE:").trim().toLongOrNull() ?: 0L
            } else if (trimmed.startsWith("#EXT-X-KEY:")) {
                currentKeyInfo = parseKeyTag(trimmed, playlistUrl)
            } else if (trimmed.startsWith("#EXT-X-MAP:")) {
                val uriMatch = Regex("""URI=["']?([^"',\s]+)["']?""").find(trimmed)
                if (uriMatch != null) {
                    val initUri = uriMatch.groupValues[1]
                    val resolvedInit = resolveUrl(playlistUrl, initUri)
                    segments.add(
                        HlsSegment(
                            index = segmentIndex++,
                            url = resolvedInit,
                            sequenceNumber = mediaSequence,
                            keyInfo = currentKeyInfo,
                            isInitSegment = true
                        )
                    )
                }
            } else if (trimmed.startsWith("#EXT-X-BYTERANGE:")) {
                val valPart = trimmed.substringAfter("#EXT-X-BYTERANGE:").trim()
                val parts = valPart.split("@")
                currentByteRangeLength = parts[0].toLongOrNull()
                if (parts.size > 1) {
                    currentByteRangeOffset = parts[1].toLongOrNull() ?: currentByteRangeOffset
                }
            } else if (trimmed.startsWith("#EXTINF:")) {
                val durStr = trimmed.substringAfter("#EXTINF:").substringBefore(",").trim()
                currentDuration = durStr.toDoubleOrNull() ?: 0.0
            } else if (!trimmed.startsWith("#")) {
                // Segment URL (Media Segment)
                val resolvedSegmentUrl = resolveUrl(playlistUrl, trimmed)
                val byteRange = if (currentByteRangeLength != null) {
                    val range = HlsByteRange(currentByteRangeLength, currentByteRangeOffset)
                    currentByteRangeOffset += currentByteRangeLength
                    currentByteRangeLength = null
                    range
                } else null

                // Número de sequência do segmento de mídia para IV conforme RFC 8216
                val seqNum = mediaSequence + mediaSegmentCount
                mediaSegmentCount++

                segments.add(
                    HlsSegment(
                        index = segmentIndex++,
                        url = resolvedSegmentUrl,
                        sequenceNumber = seqNum,
                        durationSecs = currentDuration,
                        keyInfo = currentKeyInfo,
                        byteRange = byteRange,
                        isInitSegment = false
                    )
                )
                currentDuration = 0.0
            }
        }

        return segments
    }

    /**
     * Faz o parsing robusto da tag #EXT-X-KEY, suportando múltiplos formatos de atributos e IV.
     */
    private fun parseKeyTag(keyLine: String, playlistUrl: String): HlsKeyInfo? {
        val attributes = parseTagAttributes(keyLine.removePrefix("#EXT-X-KEY:"))
        val method = attributes["METHOD"]?.uppercase() ?: "NONE"

        if (method == "NONE") return null

        val rawKeyUrl = attributes["URI"] ?: return null
        val resolvedKeyUrl = resolveUrl(playlistUrl, rawKeyUrl)

        val rawIv = attributes["IV"]
        val iv = if (!rawIv.isNullOrBlank()) {
            parseHexIv(rawIv)
        } else {
            null
        }

        return HlsKeyInfo(
            method = method,
            keyUrl = resolvedKeyUrl,
            iv = iv
        )
    }

    /**
     * Utilitário para parsear atributos com ou sem aspas de tags HLS
     */
    private fun parseTagAttributes(attrString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex("""([A-Z0-9-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^,]*))""")
        val matches = regex.findAll(attrString)
        for (match in matches) {
            val key = match.groupValues[1]
            val value = when {
                match.groupValues[2].isNotEmpty() -> match.groupValues[2]
                match.groupValues[3].isNotEmpty() -> match.groupValues[3]
                else -> match.groupValues[4].trim()
            }
            result[key] = value
        }
        return result
    }

    /**
     * Converte representação hexadecimal de IV em array de 16 bytes (128 bits).
     */
    private fun parseHexIv(hexStr: String): ByteArray {
        val clean = hexStr.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
        val result = ByteArray(16)
        for (i in 0 until 16) {
            val byteStr = clean.substring(i * 2, i * 2 + 2)
            result[i] = (byteStr.toIntOrNull(16) ?: 0).toByte()
        }
        return result
    }

    /**
     * Calcula o IV padrão com base no Media Sequence Number conforme RFC 8216:
     * 16 octetos em representação binária big-endian do número de sequência, preenchido à esquerda com zeros.
     */
    private fun computeSequenceIv(sequenceNumber: Long): ByteArray {
        val iv = ByteArray(16)
        ByteBuffer.wrap(iv, 8, 8).putLong(sequenceNumber)
        return iv
    }

    /**
     * Baixa o segmento e aplica a descriptografia AES-128 sob demanda caso necessário.
     */
    private suspend fun downloadAndDecryptSegmentWithRetry(
        segment: HlsSegment,
        headers: Map<String, String>,
        keyCache: ConcurrentHashMap<String, ByteArray>
    ): ByteArray {
        var lastError: Throwable? = null

        for (attempt in 1..3) {
            try {
                val reqBuilder = Request.Builder().url(segment.url)
                headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

                if (segment.byteRange != null) {
                    val start = segment.byteRange.offset
                    val end = start + segment.byteRange.length - 1
                    reqBuilder.addHeader("Range", "bytes=$start-$end")
                }

                val response = client.newCall(reqBuilder.build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw Exception("HTTP ${response.code} ao baixar segmento ${segment.index}")
                }

                val bodyBytes = response.body?.bytes() ?: throw Exception("Segmento vazio")

                // 2. Descriptografia sob demanda AES-128
                val keyInfo = segment.keyInfo
                if (keyInfo != null && (keyInfo.method.equals("AES-128", ignoreCase = true) || keyInfo.method.contains("AES", ignoreCase = true))) {
                    val keyBytes = keyCache.getOrPut(keyInfo.keyUrl) {
                        fetchEncryptionKey(keyInfo.keyUrl, headers)
                    }

                    val iv = keyInfo.iv ?: computeSequenceIv(segment.sequenceNumber)
                    return decryptAes128(bodyBytes, keyBytes, iv)
                }

                return bodyBytes
            } catch (t: Throwable) {
                lastError = t
                if (attempt < 3) {
                    delay(250L * attempt)
                }
            }
        }

        throw lastError ?: Exception("Falha após 3 tentativas no segmento ${segment.index}")
    }

    /**
     * Baixa a chave de criptografia remota de 16 bytes com suporte a formatos binários, textuais ou hex.
     */
    private fun fetchEncryptionKey(keyUrl: String, headers: Map<String, String>): ByteArray {
        val kReq = Request.Builder().url(keyUrl)
        headers.forEach { (k, v) -> kReq.addHeader(k, v) }
        val kResp = client.newCall(kReq.build()).execute()
        if (!kResp.isSuccessful) {
            kResp.close()
            throw Exception("Falha ao descarregar chave AES-128: HTTP ${kResp.code} em $keyUrl")
        }
        val rawBytes = kResp.body?.bytes() ?: throw Exception("Chave AES vazia")

        return when {
            rawBytes.size == 16 -> rawBytes
            rawBytes.size == 32 && isHexAscii(rawBytes) -> {
                parseHexIv(String(rawBytes, Charsets.UTF_8).trim())
            }
            rawBytes.size > 16 -> {
                // Remove trailing newlines ou espaços caso existam
                val trimmed = String(rawBytes, Charsets.UTF_8).trim()
                if (trimmed.length == 32 && trimmed.matches(Regex("^[0-9a-fA-F]+$"))) {
                    parseHexIv(trimmed)
                } else {
                    rawBytes.copyOfRange(0, 16)
                }
            }
            else -> rawBytes
        }
    }

    private fun isHexAscii(bytes: ByteArray): Boolean {
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F') {
                return false
            }
        }
        return true
    }

    /**
     * Executa a descriptografia AES-128 em modo CBC com padding PKCS7 / PKCS5,
     * possuindo fallback de tolerância caso o fluxo já esteja alinhado a blocos.
     */
    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        if (data.isEmpty()) return data

        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)

        return try {
            val cipher = try {
                Cipher.getInstance("AES/CBC/PKCS7Padding")
            } catch (_: Exception) {
                Cipher.getInstance("AES/CBC/PKCS5Padding")
            }
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            cipher.doFinal(data)
        } catch (e: Exception) {
            // Fallback para streams que já vêm sem padding ou com alinhamento manual
            if (data.size % 16 == 0) {
                try {
                    val noPaddingCipher = Cipher.getInstance("AES/CBC/NoPadding")
                    noPaddingCipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                    noPaddingCipher.doFinal(data)
                } catch (_: Exception) {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    private fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String {
        return try {
            val baseUri = URI(baseUrl)
            val resolvedUri = baseUri.resolve(relativeOrAbsolute)

            // Se a URL do segmento não possui parâmetros de consulta, mas a playlist base possui (ex: token/assinatura), preserva
            if (resolvedUri.query.isNullOrEmpty() && !baseUri.query.isNullOrEmpty()) {
                val str = resolvedUri.toString()
                if (str.contains("?")) "$str&${baseUri.query}" else "$str?${baseUri.query}"
            } else {
                resolvedUri.toString()
            }
        } catch (e: Exception) {
            if (relativeOrAbsolute.startsWith("http://") || relativeOrAbsolute.startsWith("https://")) {
                relativeOrAbsolute
            } else {
                val lastSlash = baseUrl.lastIndexOf('/')
                if (lastSlash != -1) {
                    baseUrl.substring(0, lastSlash + 1) + relativeOrAbsolute
                } else {
                    relativeOrAbsolute
                }
            }
        }
    }

    private fun fetchString(url: String, headers: Map<String, String>): String {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        val resp = client.newCall(reqBuilder.build()).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw Exception("Falha ao descarregar manifesto HLS: HTTP ${resp.code}")
        }
        return resp.body?.string() ?: throw Exception("Manifesto HLS vazio")
    }
}
