package com.example.util

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

class YouTubeDownloadCoordinator(
    private val context: Context,
    private val _downloads: MutableStateFlow<Map<Long, AdvancedDownloadItem>>,
    private val downloadManager: AdvancedDownloadManager
) {

    /**
     * Download de Vídeo do YouTube (DASH Remux ou Combinado Direto).
     * Executado no Application-Level CoroutineScope gerenciado pelo Foreground Service.
     */
    fun iniciarDownloadYouTube(
        title: String, 
        videoUrl: String, 
        audioUrl: String, 
        ua: String, 
        cookies: String,
        targetDirectoryPath: String? = null,
        targetTreeUri: String? = null
    ) {
        val downloadId = System.currentTimeMillis()
        val job = downloadManager.scope.launch {
            downloadManager.registerJob(downloadId, coroutineContext.job)
            
            val cacheDir = context.cacheDir
            val tempVideo = File(cacheDir, "yt_v_$downloadId.tmp")
            val tempAudio = File(cacheDir, "yt_a_$downloadId.tmp")
            
            val downloadDir = DownloadStorageHelper.resolveDestinationDirectory(
                context,
                targetDirectoryPath ?: targetTreeUri
            )
            val baseName = limparNome(title)
            var targetFileName = "$baseName.mp4"
            var finalFile = File(downloadDir, targetFileName)
            var counter = 1
            while (finalFile.exists()) {
                targetFileName = "$baseName($counter).mp4"
                finalFile = File(downloadDir, targetFileName)
                counter++
            }

            com.example.service.DownloadService.start(context)

            val inicialItem = AdvancedDownloadItem(
                id = downloadId,
                url = videoUrl,
                fileName = targetFileName,
                mimeType = "video/mp4",
                state = DownloadState.DOWNLOADING,
                downloadedBytes = 0L,
                totalBytes = 0L,
                file = finalFile
            )
            _downloads.update { it + (downloadId to inicialItem) }
            downloadManager.onDownloadStartedListener?.invoke(targetFileName)
            downloadManager.persistFullItem(inicialItem)
            downloadManager.notifyItem(inicialItem, force = true)


            var totalVideoBytes = 0L
            var totalAudioBytes = 0L
            var downloadedVideoBytes = 0L
            var downloadedAudioBytes = 0L
            var lastUpdate = 0L

            val atualizarProgressoCombinado: (Long, Long, Long, Long) -> Unit = { vidDownloaded: Long, vidTotal: Long, audDownloaded: Long, audTotal: Long ->
                synchronized(this) {
                    totalVideoBytes = vidTotal
                    totalAudioBytes = audTotal
                    downloadedVideoBytes = vidDownloaded
                    downloadedAudioBytes = audDownloaded

                    val agora = System.currentTimeMillis()
                    if (agora - lastUpdate > 300) {
                        val somaBaixados = downloadedVideoBytes + downloadedAudioBytes
                        val somaTotal = totalVideoBytes + totalAudioBytes
                        
                        _downloads.update { mapa ->
                            val updated = mapa[downloadId]?.copy(
                                downloadedBytes = somaBaixados,
                                totalBytes = if (somaTotal > 0) somaTotal else 0L,
                                state = DownloadState.DOWNLOADING
                            )
                            if (updated != null) {
                                downloadManager.notifyItem(updated)
                                downloadManager.persistProgressThrottled(downloadId, DownloadState.DOWNLOADING, somaBaixados, somaTotal)
                                mapa + (downloadId to updated)
                            } else mapa
                        }
                        lastUpdate = agora
                    }
                }
            }

            try {
                // Caso seja stream combinado (já contém áudio e vídeo)
                if (audioUrl.isBlank() || audioUrl == videoUrl) {
                    val resolvedUrl = PlayerCipherDeobfuscator.resolveStreamUrl(
                        rawUrl = videoUrl,
                        pageUrl = "https://www.youtube.com/",
                        headers = mapOf("User-Agent" to ua, "Cookie" to cookies)
                    )
                    baixarCanalMedia(resolvedUrl, finalFile, ua, cookies) { baixados, total ->
                        atualizarProgressoCombinado(baixados, total, 0L, 0L)
                    }

                    if (!finalFile.exists() || finalFile.length() < 10240) {
                        throw Exception("Ficheiro descarregado inválido ou corrompido.")
                    }

                    val finalSize = finalFile.length()
                    _downloads.update { mapa ->
                        val concluido = mapa[downloadId]?.copy(
                            state = DownloadState.COMPLETED,
                            downloadedBytes = finalSize,
                            totalBytes = finalSize,
                            file = finalFile
                        )
                        if (concluido != null) {
                            downloadManager.notifyItem(concluido, force = true)
                            downloadManager.persistFullItem(concluido)
                            mapa + (downloadId to concluido)
                        } else mapa
                    }
                    return@launch
                }

                // Desofuscação dinâmica de assinaturas e parâmetros anti-throttling 'n'
                val resolvedVideoUrl = PlayerCipherDeobfuscator.resolveStreamUrl(
                    rawUrl = videoUrl,
                    pageUrl = "https://www.youtube.com/",
                    headers = mapOf("User-Agent" to ua, "Cookie" to cookies)
                )
                val resolvedAudioUrl = PlayerCipherDeobfuscator.resolveStreamUrl(
                    rawUrl = audioUrl,
                    pageUrl = "https://www.youtube.com/",
                    headers = mapOf("User-Agent" to ua, "Cookie" to cookies)
                )

                val tarefaVideo = async {
                    baixarCanalMedia(resolvedVideoUrl, tempVideo, ua, cookies) { baixados, total ->
                        atualizarProgressoCombinado(baixados, total, downloadedAudioBytes, totalAudioBytes)
                    }
                }
                val tarefaAudio = async {
                    baixarCanalMedia(resolvedAudioUrl, tempAudio, ua, cookies) { baixados, total ->
                        atualizarProgressoCombinado(downloadedVideoBytes, totalVideoBytes, baixados, total)
                    }
                }

                tarefaVideo.await()
                tarefaAudio.await()

                if (!tempVideo.exists() || tempVideo.length() < 10240) {
                    throw Exception("Fluxo de vídeo recebido com erro ou tamanho nulo.")
                }

                _downloads.update { mapa ->
                    val proc = mapa[downloadId]?.copy(state = DownloadState.PROCESSING)
                    if (proc != null) {
                        downloadManager.notifyItem(proc, force = true)
                        downloadManager.persistProgressThrottled(downloadId, DownloadState.PROCESSING, proc.downloadedBytes, proc.totalBytes)
                        mapa + (downloadId to proc)
                    } else mapa
                }

                var sucessoMux = if (tempAudio.exists() && tempAudio.length() >= 1024) {
                    executarFFmpegMuxing(tempVideo, tempAudio, finalFile)
                } else false

                if (!sucessoMux && tempVideo.exists() && tempVideo.length() > 0) {
                    try {
                        tempVideo.copyTo(finalFile, overwrite = true)
                        sucessoMux = finalFile.exists() && finalFile.length() > 0
                    } catch (eCopy: Exception) {
                        eCopy.printStackTrace()
                    }
                }

                if (sucessoMux && finalFile.exists() && finalFile.length() >= 10240) {
                    val finalBytes = finalFile.length()
                    _downloads.update { mapa ->
                        val concluido = mapa[downloadId]?.copy(
                            state = DownloadState.COMPLETED,
                            downloadedBytes = finalBytes,
                            totalBytes = finalBytes,
                            file = finalFile
                        )
                        if (concluido != null) {
                            downloadManager.notifyItem(concluido, force = true)
                            downloadManager.persistFullItem(concluido)
                            mapa + (downloadId to concluido)
                        } else mapa
                    }
                } else {
                    throw Exception("Falha na multiplexação ou ficheiro final inferior a 10KB")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _downloads.update { mapa ->
                    val falhado = mapa[downloadId]?.copy(
                        state = DownloadState.FAILED,
                        errorMessage = e.message ?: "Erro ao descarregar do YouTube"
                    )
                    if (falhado != null) {
                        downloadManager.notifyItem(falhado, force = true)
                        downloadManager.persistFullItem(falhado)
                        mapa + (downloadId to falhado)
                    } else mapa
                }
            } finally {
                downloadManager.unregisterJob(downloadId)
                try {
                    if (tempVideo.exists()) tempVideo.delete()
                    if (tempAudio.exists()) tempAudio.delete()
                } catch (ignored: Exception) {}
            }
        }
    }

    /**
     * Download dedicado para Áudio / Música do YouTube (M4A ou MP3).
     * Executado no Application-Level CoroutineScope gerenciado pelo Foreground Service.
     */
    fun iniciarDownloadAudioYouTube(
        title: String, 
        audioUrl: String, 
        ua: String, 
        cookies: String, 
        asMp3: Boolean = false,
        targetDirectoryPath: String? = null,
        targetTreeUri: String? = null
    ) {
        val downloadId = System.currentTimeMillis()
        val job = downloadManager.scope.launch {
            downloadManager.registerJob(downloadId, coroutineContext.job)
            val downloadDir = DownloadStorageHelper.resolveDestinationDirectory(
                context,
                targetDirectoryPath ?: targetTreeUri
            )
            val baseName = limparNome(title)
            val ext = if (asMp3) "mp3" else "m4a"
            var targetFileName = "$baseName.$ext"
            var finalFile = File(downloadDir, targetFileName)
            var counter = 1
            while (finalFile.exists()) {
                targetFileName = "$baseName($counter).$ext"
                finalFile = File(downloadDir, targetFileName)
                counter++
            }

            com.example.service.DownloadService.start(context)

            val inicialItem = AdvancedDownloadItem(
                id = downloadId,
                url = audioUrl,
                fileName = targetFileName,
                mimeType = if (asMp3) "audio/mp3" else "audio/mp4",
                state = DownloadState.DOWNLOADING,
                downloadedBytes = 0L,
                totalBytes = 0L,
                file = finalFile
            )
            _downloads.update { it + (downloadId to inicialItem) }
            downloadManager.onDownloadStartedListener?.invoke(targetFileName)
            downloadManager.persistFullItem(inicialItem)
            downloadManager.notifyItem(inicialItem, force = true)

            var lastUpdate = 0L

            try {
                val resolvedAudioUrl = PlayerCipherDeobfuscator.resolveStreamUrl(
                    rawUrl = audioUrl,
                    pageUrl = "https://www.youtube.com/",
                    headers = mapOf("User-Agent" to ua, "Cookie" to cookies)
                )

                baixarCanalMedia(resolvedAudioUrl, finalFile, ua, cookies) { baixados, total ->
                    val agora = System.currentTimeMillis()
                    if (agora - lastUpdate > 300) {
                        _downloads.update { mapa ->
                            val updated = mapa[downloadId]?.copy(
                                downloadedBytes = baixados,
                                totalBytes = if (total > 0) total else baixados,
                                state = DownloadState.DOWNLOADING
                            )
                            if (updated != null) {
                                downloadManager.notifyItem(updated)
                                downloadManager.persistProgressThrottled(downloadId, DownloadState.DOWNLOADING, baixados, total)
                                mapa + (downloadId to updated)
                            } else mapa
                        }
                        lastUpdate = agora
                    }
                }

                if (!finalFile.exists() || finalFile.length() < 5120) {
                    throw Exception("Ficheiro de áudio recebido corrompido ou vazio.")
                }

                val finalBytes = finalFile.length()
                _downloads.update { mapa ->
                    val concluido = mapa[downloadId]?.copy(
                        state = DownloadState.COMPLETED,
                        downloadedBytes = finalBytes,
                        totalBytes = finalBytes,
                        file = finalFile
                    )
                    if (concluido != null) {
                        downloadManager.notifyItem(concluido, force = true)
                        downloadManager.persistFullItem(concluido)
                        mapa + (downloadId to concluido)
                    } else mapa
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    e.printStackTrace()
                    _downloads.update { mapa ->
                        val falhado = mapa[downloadId]?.copy(
                            state = DownloadState.FAILED,
                            errorMessage = e.message ?: "Erro no download de áudio"
                        )
                        if (falhado != null) {
                            downloadManager.notifyItem(falhado, force = true)
                            downloadManager.persistFullItem(falhado)
                            mapa + (downloadId to falhado)
                        } else mapa
                    }
                }
            } finally {
                downloadManager.unregisterJob(downloadId)
            }
        }
    }


    // Core de download OkHttp com suporte a Download Resumível (Range: bytes=offset-), headers dinâmicos e resolução de HTTP 403
    private fun baixarCanalMedia(url: String, destino: File, ua: String, cookies: String, onProgress: (Long, Long) -> Unit) {
        val client = okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        // Estratégias de cabeçalhos e User-Agent para contornar restrições do Google Video
        val strategies = mutableListOf<Triple<String, Boolean, String>>()

        // 1. Android VR / Native (Não envia cookies de web que causam 403)
        strategies.add(Triple("Mozilla/5.0 (Android 12; Mobile VR; Quest 3)", false, "Android_VR_Native"))
        // 2. Android App Nativo
        strategies.add(Triple("com.google.android.youtube/19.10.37 (Linux; U; Android 14; pt_BR) gzip", false, "Android_App_Native"))
        // 3. Cliente OkHttp neutro
        strategies.add(Triple("okhttp/4.12.0", false, "OkHttp_Neutral"))
        // 4. iOS App Nativo
        strategies.add(Triple("com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; pt_BR)", false, "iOS_Native"))
        // 5. Desktop Web com Referer e Cookies
        strategies.add(Triple("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36", true, "Desktop_Web_Full"))
        // 6. Mobile Web com Referer e Cookies
        strategies.add(Triple(ua.ifBlank { "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36" }, true, "Mobile_Web_Full"))

        var lastException: Exception? = null

        for ((candidateUa, includeWebHeaders, strategyName) in strategies) {
            try {
                destino.parentFile?.mkdirs()
                val existingOffset = if (destino.exists()) destino.length() else 0L

                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", candidateUa)
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity;q=1, *;q=0")

                if (includeWebHeaders) {
                    requestBuilder.header("Referer", "https://www.youtube.com/")
                    requestBuilder.header("Origin", "https://www.youtube.com")
                    requestBuilder.header("Sec-Fetch-Mode", "no-cors")
                    requestBuilder.header("Sec-Fetch-Site", "cross-site")
                    requestBuilder.header("Sec-Fetch-Dest", "video")

                    if (cookies.isNotBlank()) {
                        requestBuilder.header("Cookie", cookies)
                    }
                }

                // Injeta Range para retomada de download a partir do ponto onde parou
                if (existingOffset > 0L) {
                    requestBuilder.header("Range", "bytes=$existingOffset-")
                } else {
                    requestBuilder.header("Range", "bytes=0-")
                }

                var response = client.newCall(requestBuilder.build()).execute()

                // Se o servidor rejeitar o range específico (ex: 416 Range Not Satisfiable), recomeça do zero
                if (!response.isSuccessful && (response.code == 416 || response.code == 400)) {
                    response.close()
                    response = client.newCall(requestBuilder.header("Range", "bytes=0-").build()).execute()
                }

                if (response.code == 403 || response.code == 401) {
                    response.close()
                    continue // Tenta a próxima estratégia de User-Agent/Headers
                }

                if (!response.isSuccessful) {
                    val code = response.code
                    val errBody = response.body?.string().orEmpty()
                    response.close()
                    throw Exception("HTTP $code ao descarregar stream ($strategyName): ${errBody.take(100)}")
                }

                val contentType = response.header("Content-Type", "")?.lowercase() ?: ""
                if (contentType.contains("text/html") || contentType.contains("application/json")) {
                    val errSample = response.body?.string().orEmpty()
                    response.close()
                    continue
                }

                val body = response.body ?: run {
                    response.close()
                    throw Exception("Resposta sem corpo de mídia.")
                }

                val contentLength = body.contentLength()
                val isPartial = response.code == 206 && existingOffset > 0L
                val totalBytes = if (isPartial) {
                    if (contentLength > 0L) existingOffset + contentLength else existingOffset
                } else {
                    val crHeader = response.header("Content-Range")
                    val parsedTotalFromCr = crHeader?.substringAfterLast("/")?.toLongOrNull()
                    parsedTotalFromCr ?: if (contentLength > 0L) contentLength else 0L
                }

                var acumulado = if (isPartial) existingOffset else 0L

                java.io.RandomAccessFile(destino, "rw").use { raf ->
                    if (isPartial) {
                        raf.seek(existingOffset)
                    } else {
                        raf.setLength(0L)
                    }

                    body.byteStream().use { input ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            raf.write(buffer, 0, bytesRead)
                            acumulado += bytesRead
                            onProgress(acumulado, if (totalBytes > 0L) totalBytes else acumulado)
                        }
                    }
                }

                if (destino.length() > 0L) {
                    // Download concluído com sucesso!
                    return
                } else {
                    throw Exception("Arquivo gravado está vazio (0 bytes).")
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        throw lastException ?: Exception("Não foi possível descarregar o fluxo de vídeo (HTTP 403/Forbidden em todas as estratégias).")
    }

    private fun executarFFmpegMuxing(video: File, audio: File, saida: File): Boolean {
        return AudioVideoRemuxer.mux(video, audio, saida)
    }

    private fun limparNome(nome: String): String = nome.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
}
