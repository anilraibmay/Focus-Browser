package com.example.util

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.URI

data class DashRepresentation(
    val id: String,
    val mimeType: String,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val codecs: String?,
    val baseUrl: String?,
    val initUrl: String?,
    val mediaTemplate: String?,
    val startNumber: Long,
    val timescale: Long = 1L,
    val segmentDuration: Long = 0L,
    val timelineSegments: List<Pair<Long, Long>> = emptyList(), // (time, duration)
    val segmentUrls: List<String> = emptyList(),
    val durationSecs: Double = 0.0,
    val isVideo: Boolean = true
)

/**
 * Downloader em pipeline especializado para manifestos DASH (.mpd).
 * Realiza o parsing de manifestos MPD, seleciona os streams de maior resolução/bitrate
 * para vídeo e a melhor faixa de áudio, descarrega os segmentos concorrentemente
 * e combina ambas as faixas em um arquivo MP4 final de alta qualidade via FFmpegKit (AudioVideoRemuxer).
 */
class DashPipelineDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "DashPipelineDownloader"
    }

    suspend fun download(
        mpdUrl: String,
        targetFile: File,
        headers: Map<String, String> = emptyMap(),
        concurrency: Int = 3,
        onProgress: (downloadedBytes: Long, totalEstimatedBytes: Long, currentChunk: Int, totalChunks: Int) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            targetFile.parentFile?.mkdirs()

            // 1. Tentar primeiro remuxing/download direto via FFmpegKit se o MPD for acessível publicamente
            val tempDir = targetFile.parentFile ?: File(".")
            val baseName = targetFile.nameWithoutExtension + "_dash_" + System.currentTimeMillis()
            val tempOutput = File(tempDir, "${baseName}_direct.mp4")

            try {
                val headerArgs = if (headers.isNotEmpty()) {
                    val formatted = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" }
                    "-headers \"$formatted\" "
                } else ""

                val directCmd = "-y $headerArgs-i \"$mpdUrl\" -c:v copy -c:a aac -b:a 192k -movflags +faststart \"${tempOutput.absolutePath}\""
                Log.d(TAG, "Tentando download DASH direto via FFmpeg: $directCmd")
                val session = com.arthenica.ffmpegkit.FFmpegKit.execute(directCmd)

                if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode) && tempOutput.exists() && tempOutput.length() > 1024) {
                    tempOutput.copyTo(targetFile, overwrite = true)
                    tempOutput.delete()
                    val len = targetFile.length()
                    onProgress(len, len, 100, 100)
                    Log.i(TAG, "Download DASH concluído diretamente via FFmpegKit.")
                    return@withContext
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Download direto DASH via FFmpegKit não completou: ${t.message}. Alternando para pipeline de segmentos.")
            } finally {
                if (tempOutput.exists()) tempOutput.delete()
            }

            // 2. Pipeline de Segmentos: Baixar e analisar o manifesto MPD
            val mpdXml = fetchString(mpdUrl, headers)
            val (videoRep, audioRep) = parseMpd(mpdUrl, mpdXml)

            if (videoRep == null && audioRep == null) {
                throw IllegalStateException("Nenhum stream de vídeo ou áudio compatível foi encontrado no arquivo MPD.")
            }

            val tempVideoFile = File(tempDir, "${baseName}_video.m4v")
            val tempAudioFile = File(tempDir, "${baseName}_audio.m4a")

            try {
                if (tempVideoFile.exists()) tempVideoFile.delete()
                if (tempAudioFile.exists()) tempAudioFile.delete()

                val videoUrls = videoRep?.let { resolveSegmentUrls(it, mpdUrl) } ?: emptyList()
                val audioUrls = audioRep?.let { resolveSegmentUrls(it, mpdUrl) } ?: emptyList()

                val totalSegments = videoUrls.size + audioUrls.size
                var overallDownloadedBytes = 0L

                // A. Baixar faixa de vídeo de alta resolução
                if (videoUrls.isNotEmpty()) {
                    downloadUrlsSequentially(
                        urls = videoUrls,
                        targetFile = tempVideoFile,
                        headers = headers,
                        concurrency = concurrency,
                        onProgress = { down, est, curr, _ ->
                            overallDownloadedBytes = down
                            onProgress(down, est, curr, totalSegments)
                        }
                    )
                }

                val videoBytes = tempVideoFile.length()

                // B. Baixar faixa de áudio
                if (audioUrls.isNotEmpty()) {
                    downloadUrlsSequentially(
                        urls = audioUrls,
                        targetFile = tempAudioFile,
                        headers = headers,
                        concurrency = concurrency,
                        onProgress = { down, est, curr, _ ->
                            onProgress(videoBytes + down, videoBytes + est, videoUrls.size + curr, totalSegments)
                        }
                    )
                }

                // C. Combinar vídeo de alta resolução e áudio em MP4 via FFmpegKit / MediaMuxer
                if (tempVideoFile.exists() && tempVideoFile.length() > 0 && tempAudioFile.exists() && tempAudioFile.length() > 0) {
                    val muxSuccess = AudioVideoRemuxer.mux(tempVideoFile, tempAudioFile, targetFile)
                    if (!muxSuccess || !targetFile.exists() || targetFile.length() == 0L) {
                        tempVideoFile.copyTo(targetFile, overwrite = true)
                    }
                } else if (tempVideoFile.exists() && tempVideoFile.length() > 0) {
                    tempVideoFile.copyTo(targetFile, overwrite = true)
                } else if (tempAudioFile.exists() && tempAudioFile.length() > 0) {
                    tempAudioFile.copyTo(targetFile, overwrite = true)
                }

                val finalLen = targetFile.length()
                onProgress(finalLen, finalLen, totalSegments, totalSegments)
            } finally {
                if (tempVideoFile.exists()) tempVideoFile.delete()
                if (tempAudioFile.exists()) tempAudioFile.delete()
            }
        }
    }

    internal fun resolveSegmentUrls(rep: DashRepresentation, baseUrl: String): List<String> {
        val result = mutableListOf<String>()
        val effectiveBase = if (!rep.baseUrl.isNullOrBlank()) resolveUrl(baseUrl, rep.baseUrl) else baseUrl

        // 1. Adicionar segmento de inicialização (moov/ftyp)
        rep.initUrl?.let {
            val resolvedInit = it
                .replace("\$RepresentationID\$", rep.id)
                .replace("\$Bandwidth\$", rep.bandwidth.toString())
            result.add(resolveUrl(effectiveBase, resolvedInit))
        }

        // 2. Se houver lista explícita de segmentos (<SegmentList>)
        if (rep.segmentUrls.isNotEmpty()) {
            rep.segmentUrls.forEach {
                result.add(resolveUrl(effectiveBase, it))
            }
            return result
        }

        // 3. Se houver SegmentTimeline com timestamps precisos (<SegmentTimeline>)
        if (rep.timelineSegments.isNotEmpty() && rep.mediaTemplate != null) {
            var currentNumber = rep.startNumber
            for ((time, _) in rep.timelineSegments) {
                val mediaUrl = rep.mediaTemplate
                    .replace("\$Time\$", time.toString())
                    .replace("\$Number\$", currentNumber.toString())
                    .replace(Regex("\\\$Number%0(\\d+)d\\\$")) { m ->
                        val digits = m.groupValues[1].toIntOrNull() ?: 1
                        currentNumber.toString().padStart(digits, '0')
                    }
                    .replace("\$RepresentationID\$", rep.id)
                    .replace("\$Bandwidth\$", rep.bandwidth.toString())

                result.add(resolveUrl(effectiveBase, mediaUrl))
                currentNumber++
            }
            return result
        }

        // 4. Se houver SegmentTemplate baseado em contagem sequencial
        if (rep.mediaTemplate != null) {
            val count = when {
                rep.segmentDuration > 0 && rep.timescale > 0 && rep.durationSecs > 0 -> {
                    val segSecs = rep.segmentDuration.toDouble() / rep.timescale.toDouble()
                    if (segSecs > 0) Math.max(1, Math.ceil(rep.durationSecs / segSecs).toInt()) else 30
                }
                rep.durationSecs > 0 -> Math.max(1, Math.ceil(rep.durationSecs / 5.0).toInt())
                else -> 30
            }

            for (i in 0 until count) {
                val num = rep.startNumber + i
                val mediaUrl = rep.mediaTemplate
                    .replace("\$Number\$", num.toString())
                    .replace(Regex("\\\$Number%0(\\d+)d\\\$")) { m ->
                        val digits = m.groupValues[1].toIntOrNull() ?: 1
                        num.toString().padStart(digits, '0')
                    }
                    .replace("\$RepresentationID\$", rep.id)
                    .replace("\$Bandwidth\$", rep.bandwidth.toString())

                result.add(resolveUrl(effectiveBase, mediaUrl))
            }
            return result
        }

        // 5. Fallback para arquivo contínuo único (ex: byte-range ou URL base direta)
        if (rep.baseUrl != null) {
            result.add(resolveUrl(baseUrl, rep.baseUrl))
        }

        return result
    }

    private suspend fun downloadUrlsSequentially(
        urls: List<String>,
        targetFile: File,
        headers: Map<String, String>,
        concurrency: Int,
        onProgress: (downloadedBytes: Long, totalEstimated: Long, currentChunk: Int, totalChunks: Int) -> Unit
    ) {
        val fos = FileOutputStream(targetFile, true)
        val semaphore = Semaphore(concurrency)
        var totalBytesWritten = 0L

        try {
            urls.forEachIndexed { index, url ->
                val bytes = semaphore.withPermit {
                    fetchBytes(url, headers)
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    fos.write(bytes)
                    totalBytesWritten += bytes.size
                }

                val completed = index + 1
                val avg = if (completed > 0) totalBytesWritten / completed else 0L
                onProgress(totalBytesWritten, avg * urls.size, completed, urls.size)
            }
        } finally {
            try {
                fos.flush()
                fos.close()
            } catch (ignored: Throwable) {}
        }
    }

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        val resp = client.newCall(reqBuilder.build()).execute()
        return if (resp.isSuccessful) resp.body?.bytes() else null
    }

    private fun fetchString(url: String, headers: Map<String, String>): String {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        val resp = client.newCall(reqBuilder.build()).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw Exception("HTTP ${resp.code} ao baixar manifesto DASH")
        }
        return resp.body?.string() ?: throw Exception("Manifesto DASH vazio")
    }

    /**
     * Converte duração ISO-8601 (ex: "PT1H2M30.5S", "PT300S") para segundos.
     */
    internal fun parseIsoDuration(durationStr: String?): Double {
        if (durationStr.isNullOrBlank()) return 0.0
        return try {
            val regex = Regex("""PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?""", RegexOption.IGNORE_CASE)
            val match = regex.find(durationStr.trim()) ?: return 0.0
            val hours = match.groups[1]?.value?.toDoubleOrNull() ?: 0.0
            val minutes = match.groups[2]?.value?.toDoubleOrNull() ?: 0.0
            val seconds = match.groups[3]?.value?.toDoubleOrNull() ?: 0.0
            (hours * 3600.0) + (minutes * 60.0) + seconds
        } catch (_: Exception) {
            0.0
        }
    }

    /**
     * Parser XML completo e resiliente para manifestos DASH MPD.
     */
    internal fun parseMpd(baseUrl: String, xmlContent: String): Pair<DashRepresentation?, DashRepresentation?> {
        val videoRepresentations = mutableListOf<DashRepresentation>()
        val audioRepresentations = mutableListOf<DashRepresentation>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))

            var eventType = parser.eventType
            var mpdDurationSecs = 0.0
            var mpdBaseUrl: String? = null
            var periodBaseUrl: String? = null
            var adaptBaseUrl: String? = null

            var currentMimeType = ""
            var currentContentType = ""
            var currentTemplateInit: String? = null
            var currentTemplateMedia: String? = null
            var currentStartNumber: Long = 1
            var currentTimescale: Long = 1
            var currentSegmentDuration: Long = 0
            var currentTimeline = mutableListOf<Pair<Long, Long>>() // (t, d)
            var currentSegmentListUrls = mutableListOf<String>()
            var currentSegmentListInit: String? = null

            var inRepresentation = false
            var repId = ""
            var repBandwidth = 0L
            var repWidth = 0
            var repHeight = 0
            var repCodecs: String? = null
            var repMime: String = ""
            var repBaseUrl: String? = null
            var repInitUrl: String? = null
            var repMediaTemplate: String? = null
            var repStartNumber: Long = 1L
            var repTimescale: Long = 1L
            var repSegmentDuration: Long = 0L
            var repTimeline = mutableListOf<Pair<Long, Long>>()
            var repSegmentUrls = mutableListOf<String>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name ?: ""

                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when {
                            tag.equals("MPD", ignoreCase = true) -> {
                                val durationAttr = parser.getAttributeValue(null, "mediaPresentationDuration")
                                mpdDurationSecs = parseIsoDuration(durationAttr)
                            }
                            tag.equals("BaseURL", ignoreCase = true) -> {
                                val text = parser.nextText().trim()
                                if (text.isNotEmpty()) {
                                    if (inRepresentation) repBaseUrl = resolveUrl(repBaseUrl ?: adaptBaseUrl ?: periodBaseUrl ?: mpdBaseUrl ?: baseUrl, text)
                                    else if (adaptBaseUrl != null) adaptBaseUrl = resolveUrl(adaptBaseUrl!!, text)
                                    else if (periodBaseUrl != null) periodBaseUrl = resolveUrl(periodBaseUrl!!, text)
                                    else mpdBaseUrl = resolveUrl(baseUrl, text)
                                }
                            }
                            tag.equals("Period", ignoreCase = true) -> {
                                periodBaseUrl = mpdBaseUrl
                            }
                            tag.equals("AdaptationSet", ignoreCase = true) -> {
                                adaptBaseUrl = periodBaseUrl
                                currentMimeType = parser.getAttributeValue(null, "mimeType") ?: ""
                                currentContentType = parser.getAttributeValue(null, "contentType") ?: ""
                                currentTemplateInit = null
                                currentTemplateMedia = null
                                currentStartNumber = 1
                                currentTimescale = 1
                                currentSegmentDuration = 0
                                currentTimeline = mutableListOf()
                                currentSegmentListUrls = mutableListOf()
                                currentSegmentListInit = null
                            }
                            tag.equals("SegmentTemplate", ignoreCase = true) -> {
                                val sInit = parser.getAttributeValue(null, "initialization")
                                val sMedia = parser.getAttributeValue(null, "media")
                                val sStart = parser.getAttributeValue(null, "startNumber")?.toLongOrNull()
                                val sTime = parser.getAttributeValue(null, "timescale")?.toLongOrNull()
                                val sDur = parser.getAttributeValue(null, "duration")?.toLongOrNull()

                                if (inRepresentation) {
                                    if (sInit != null) repInitUrl = sInit
                                    if (sMedia != null) repMediaTemplate = sMedia
                                    if (sStart != null) repStartNumber = sStart
                                    if (sTime != null) repTimescale = sTime
                                    if (sDur != null) repSegmentDuration = sDur
                                } else {
                                    if (sInit != null) currentTemplateInit = sInit
                                    if (sMedia != null) currentTemplateMedia = sMedia
                                    if (sStart != null) currentStartNumber = sStart
                                    if (sTime != null) currentTimescale = sTime
                                    if (sDur != null) currentSegmentDuration = sDur
                                }
                            }
                            tag.equals("SegmentList", ignoreCase = true) -> {
                                if (inRepresentation) repSegmentUrls = mutableListOf()
                                else currentSegmentListUrls = mutableListOf()
                            }
                            tag.equals("Initialization", ignoreCase = true) -> {
                                val sourceUrl = parser.getAttributeValue(null, "sourceURL")
                                if (!sourceUrl.isNullOrBlank()) {
                                    if (inRepresentation) repInitUrl = sourceUrl
                                    else currentSegmentListInit = sourceUrl
                                }
                            }
                            tag.equals("SegmentURL", ignoreCase = true) -> {
                                val media = parser.getAttributeValue(null, "media")
                                if (!media.isNullOrBlank()) {
                                    if (inRepresentation) repSegmentUrls.add(media)
                                    else currentSegmentListUrls.add(media)
                                }
                            }
                            tag.equals("S", ignoreCase = true) -> {
                                val targetTimeline = if (inRepresentation) repTimeline else currentTimeline
                                val defaultDur = if (inRepresentation) repSegmentDuration else currentSegmentDuration
                                val d = parser.getAttributeValue(null, "d")?.toLongOrNull() ?: defaultDur
                                val t = parser.getAttributeValue(null, "t")?.toLongOrNull()
                                val r = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: 0

                                var currentTime = t ?: if (targetTimeline.isNotEmpty()) {
                                    val last = targetTimeline.last()
                                    last.first + last.second
                                } else 0L

                                targetTimeline.add(Pair(currentTime, d))
                                if (r > 0) {
                                    for (repeatIdx in 1..r) {
                                        currentTime += d
                                        targetTimeline.add(Pair(currentTime, d))
                                    }
                                }
                            }
                            tag.equals("Representation", ignoreCase = true) -> {
                                inRepresentation = true
                                repId = parser.getAttributeValue(null, "id") ?: "rep_${System.currentTimeMillis()}"
                                repBandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                                repWidth = parser.getAttributeValue(null, "width")?.toIntOrNull() ?: 0
                                repHeight = parser.getAttributeValue(null, "height")?.toIntOrNull() ?: 0
                                repCodecs = parser.getAttributeValue(null, "codecs")
                                repMime = parser.getAttributeValue(null, "mimeType") ?: currentMimeType
                                repBaseUrl = adaptBaseUrl ?: periodBaseUrl ?: mpdBaseUrl
                                repInitUrl = parser.getAttributeValue(null, "initialization") ?: currentTemplateInit ?: currentSegmentListInit
                                repMediaTemplate = parser.getAttributeValue(null, "media") ?: currentTemplateMedia
                                repStartNumber = currentStartNumber
                                repTimescale = currentTimescale
                                repSegmentDuration = currentSegmentDuration
                                repTimeline = currentTimeline.toMutableList()
                                repSegmentUrls = currentSegmentListUrls.toMutableList()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tag.equals("Representation", ignoreCase = true) && inRepresentation) {
                            val isVideo = currentContentType.equals("video", ignoreCase = true) ||
                                    repMime.contains("video", ignoreCase = true) ||
                                    repWidth > 0 || repHeight > 0

                            val rep = DashRepresentation(
                                id = repId,
                                mimeType = repMime,
                                bandwidth = repBandwidth,
                                width = repWidth,
                                height = repHeight,
                                codecs = repCodecs,
                                baseUrl = repBaseUrl,
                                initUrl = repInitUrl,
                                mediaTemplate = repMediaTemplate,
                                startNumber = repStartNumber,
                                timescale = repTimescale,
                                segmentDuration = repSegmentDuration,
                                timelineSegments = repTimeline.toList(),
                                segmentUrls = repSegmentUrls.toList(),
                                durationSecs = mpdDurationSecs,
                                isVideo = isVideo
                            )

                            if (isVideo) {
                                videoRepresentations.add(rep)
                            } else {
                                audioRepresentations.add(rep)
                            }
                            inRepresentation = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao analisar MPD XML: ${e.message}", e)
        }

        // Escolher a representação de vídeo com maior resolução (largura x altura) ou maior bandwidth
        val bestVideo = videoRepresentations.maxByOrNull { (it.width.toLong() * it.height.toLong()) + it.bandwidth }
        // Escolher a melhor representação de áudio
        val bestAudio = audioRepresentations.maxByOrNull { it.bandwidth }

        return Pair(bestVideo, bestAudio)
    }

    internal fun resolveUrl(baseUrl: String, path: String): String {
        return try {
            val uri = URI(baseUrl).resolve(path)
            uri.toString()
        } catch (e: Exception) {
            if (path.startsWith("http://") || path.startsWith("https://")) path
            else "$baseUrl/$path"
        }
    }
}
