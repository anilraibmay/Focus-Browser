package com.example.util

import android.content.Context
import android.os.Environment
import com.example.database.AppDatabase
import com.example.database.DownloadEntity
import com.example.service.DownloadService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException

enum class DownloadState {
    PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, BLOCKED_EXTENSION, PROCESSING, CANCELLED
}

data class AdvancedDownloadItem(
    val id: Long,
    val url: String,
    val fileName: String,
    val mimeType: String,
    val state: DownloadState,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val file: File?,
    val errorMessage: String? = null,
    val userAgent: String = "",
    val cookies: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)

class AdvancedDownloadManager private constructor(private val context: Context) {
    
    private val client = OkHttpClient()
    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadCounter = 0L
    private val downloadDao by lazy { AppDatabase.getDatabase(context).downloadDao() }
    
    val _downloads = MutableStateFlow<Map<Long, AdvancedDownloadItem>>(emptyMap())
    val downloads: StateFlow<List<AdvancedDownloadItem>> = _downloads.map { 
        it.values.toList().sortedWith(compareByDescending<AdvancedDownloadItem> { item -> item.createdAtMs }.thenByDescending { item -> item.id }) 
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    
    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    fun registerJob(id: Long, job: Job) {
        jobs[id] = job
    }

    fun unregisterJob(id: Long) {
        jobs.remove(id)
    }

    var onDownloadStartedListener: ((fileName: String) -> Unit)? = null

    
    // Extensions policy
    val blockedExtensions = setOf("exe", "bat", "cmd", "sh", "msi", "app")
    
    init {
        // Limpeza de arquivos temporários órfãos de sessões passadas
        scope.launch {
            try {
                PlayerCipherDeobfuscator.limparArquivosTemporariosOrfaos()
            } catch (_: Exception) {}
        }

        // Load persisted downloads from local database
        scope.launch {
            try {
                val savedList = downloadDao.getAllDownloadsList()
                if (savedList.isNotEmpty()) {
                    val maxId = savedList.maxOfOrNull { it.id } ?: 0L
                    if (maxId > downloadCounter) {
                        downloadCounter = maxId
                    }
                    val map = savedList.associate { entity ->
                        val targetFile = entity.filePath?.let { File(it) } ?: run {
                            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            File(downloadDir, entity.fileName)
                        }
                        val rawState = try {
                            DownloadState.valueOf(entity.state)
                        } catch (e: Exception) {
                            DownloadState.FAILED
                        }
                        // If app was killed while downloading, mark as PAUSED so user can resume
                        val resolvedState = if (rawState == DownloadState.DOWNLOADING || rawState == DownloadState.PENDING || rawState == DownloadState.PROCESSING) {
                            DownloadState.PAUSED
                        } else {
                            rawState
                        }
                        entity.id to AdvancedDownloadItem(
                            id = entity.id,
                            url = entity.url,
                            fileName = entity.fileName,
                            mimeType = entity.mimeType,
                            state = resolvedState,
                            downloadedBytes = entity.downloadedBytes,
                            totalBytes = entity.totalBytes,
                            file = targetFile,
                            errorMessage = entity.errorMessage,
                            userAgent = entity.userAgent,
                            cookies = entity.cookies,
                            createdAtMs = entity.createdAtMs
                        )
                    }
                    _downloads.value = map
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    companion object {
        @Volatile
        private var instance: AdvancedDownloadManager? = null
        
        fun getInstance(context: Context): AdvancedDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: AdvancedDownloadManager(context.applicationContext).also { instance = it }
            }
        }
    }

    internal fun persistProgressThrottled(id: Long, state: DownloadState, downloadedBytes: Long, totalBytes: Long) {
        scope.launch {
            try {
                downloadDao.updateDownloadProgress(id, state.name, downloadedBytes, totalBytes)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    internal fun persistFullItem(item: AdvancedDownloadItem) {
        scope.launch {
            try {
                val entity = DownloadEntity(
                    id = item.id,
                    url = item.url,
                    fileName = item.fileName,
                    mimeType = item.mimeType,
                    state = item.state.name,
                    downloadedBytes = item.downloadedBytes,
                    totalBytes = item.totalBytes,
                    filePath = item.file?.absolutePath,
                    errorMessage = item.errorMessage,
                    userAgent = item.userAgent,
                    cookies = item.cookies,
                    createdAtMs = item.createdAtMs
                )
                downloadDao.insertDownload(entity)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    internal fun notifyItem(item: AdvancedDownloadItem, force: Boolean = false) {
        DownloadNotificationHelper.updateNotification(context, item, force)
    }

    fun startYouTubeDualStreamDownload(
        title: String,
        videoUrl: String,
        audioUrl: String,
        ua: String = "",
        cookies: String = "",
        targetDirectoryPath: String? = null,
        targetTreeUri: String? = null
    ) {
        val coordinator = YouTubeDownloadCoordinator(context, _downloads, this)
        coordinator.iniciarDownloadYouTube(title, videoUrl, audioUrl, ua, cookies, targetDirectoryPath, targetTreeUri)
    }

    fun startYouTubeAudioDownload(
        title: String,
        audioUrl: String,
        ua: String = "",
        cookies: String = "",
        asMp3: Boolean = false,
        targetDirectoryPath: String? = null,
        targetTreeUri: String? = null
    ) {
        val coordinator = YouTubeDownloadCoordinator(context, _downloads, this)
        coordinator.iniciarDownloadAudioYouTube(title, audioUrl, ua, cookies, asMp3, targetDirectoryPath, targetTreeUri)
    }

    fun startDownload(
        url: String, 
        fileName: String, 
        mimeType: String, 
        userAgent: String = "", 
        cookies: String = "",
        headers: Map<String, String> = emptyMap(),
        targetDirectoryPath: String? = null,
        targetTreeUri: String? = null
    ) {
        val isHls = url.contains(".m3u8", ignoreCase = true) || 
                    mimeType.contains("mpegurl", ignoreCase = true) ||
                    mimeType.contains("m3u8", ignoreCase = true)

        val isDash = url.contains(".mpd", ignoreCase = true) ||
                     mimeType.contains("dash+xml", ignoreCase = true) ||
                     mimeType.contains("mpd", ignoreCase = true)

        val resolvedFileName = if (isHls) {
            if (fileName.endsWith(".m3u8", ignoreCase = true)) {
                fileName.substringBeforeLast(".m3u8") + ".mp4"
            } else if (!fileName.endsWith(".mp4", ignoreCase = true) && !fileName.endsWith(".ts", ignoreCase = true)) {
                "$fileName.mp4"
            } else {
                fileName
            }
        } else if (isDash) {
            if (fileName.endsWith(".mpd", ignoreCase = true)) {
                fileName.substringBeforeLast(".mpd") + ".mp4"
            } else if (!fileName.endsWith(".mp4", ignoreCase = true)) {
                "$fileName.mp4"
            } else {
                fileName
            }
        } else if (AbysDedicatedEngine.isAbysUrl(url)) {
            if (fileName.endsWith(".fd", ignoreCase = true)) {
                fileName.substringBeforeLast(".fd") + ".mp4"
            } else if (!fileName.endsWith(".mp4", ignoreCase = true)) {
                "$fileName.mp4"
            } else {
                fileName
            }
        } else {
            fileName
        }

        val resolvedMimeType = if (isHls || isDash || AbysDedicatedEngine.isAbysUrl(url)) "video/mp4" else mimeType
        val extension = resolvedFileName.substringAfterLast('.', "").lowercase()
        val id = ++downloadCounter
        
        // Encode any extra headers into cookies payload if needed, or serialize
        val effectiveCookies = if (headers.isNotEmpty() && !cookies.startsWith("{")) {
            val allMap = headers.toMutableMap()
            if (cookies.isNotEmpty() && !allMap.containsKey("Cookie")) {
                allMap["Cookie"] = cookies
            }
            if (userAgent.isNotEmpty() && !allMap.containsKey("User-Agent")) {
                allMap["User-Agent"] = userAgent
            }
            org.json.JSONObject(allMap as Map<*, *>).toString()
        } else {
            cookies
        }

        if (blockedExtensions.contains(extension)) {
            val blockedItem = AdvancedDownloadItem(
                id = id, 
                url = url, 
                fileName = resolvedFileName, 
                mimeType = resolvedMimeType, 
                state = DownloadState.BLOCKED_EXTENSION, 
                downloadedBytes = 0, 
                totalBytes = 0, 
                file = null, 
                errorMessage = "Extensão de ficheiro bloqueada por segurança",
                userAgent = userAgent,
                cookies = effectiveCookies,
                createdAtMs = System.currentTimeMillis()
            )
            _downloads.update { it + (id to blockedItem) }
            persistFullItem(blockedItem)
            return
        }
        
        val downloadDir = DownloadStorageHelper.resolveDestinationDirectory(
            context,
            targetDirectoryPath ?: targetTreeUri
        )
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }

        // Se já existir arquivo em disco ou no histórico de downloads ativos com este nome,
        // organiza como páginatítulo(1).mp4, páginatítulo(2).mp4, etc.
        val baseName = resolvedFileName.substringBeforeLast('.', "")
        val ext = resolvedFileName.substringAfterLast('.', "")
        var uniqueFileName = resolvedFileName
        var counter = 1
        while (File(downloadDir, uniqueFileName).exists() || _downloads.value.values.any { 
            it.fileName.equals(uniqueFileName, ignoreCase = true) && 
            it.state != DownloadState.CANCELLED && 
            it.state != DownloadState.FAILED 
        }) {
            uniqueFileName = if (ext.isNotEmpty()) "$baseName($counter).$ext" else "$baseName($counter)"
            counter++
        }

        val file = File(downloadDir, uniqueFileName)
        
        val initialItem = AdvancedDownloadItem(
            id = id, 
            url = url, 
            fileName = uniqueFileName, 
            mimeType = resolvedMimeType, 
            state = DownloadState.PENDING, 
            downloadedBytes = 0, 
            totalBytes = 0, 
            file = file,
            userAgent = userAgent,
            cookies = effectiveCookies,
            createdAtMs = System.currentTimeMillis()
        )
        _downloads.update { it + (id to initialItem) }
        onDownloadStartedListener?.invoke(uniqueFileName)
        persistFullItem(initialItem)
        notifyItem(initialItem, force = true)
        
        resumeDownload(id)
    }

    fun pauseDownload(id: Long) {
        jobs[id]?.cancel()
        jobs.remove(id)
        _downloads.update { current ->
            val item = current[id]
            if (item != null && (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.PENDING)) {
                val paused = item.copy(state = DownloadState.PAUSED)
                persistFullItem(paused)
                notifyItem(paused, force = true)
                current + (id to paused)
            } else current
        }
    }

    fun resumeDownload(id: Long) {
        val item = _downloads.value[id] ?: return
        if (item.state == DownloadState.COMPLETED || item.state == DownloadState.DOWNLOADING) return

        // Assure background execution survives tab switching and app backgrounding
        try {
            DownloadService.start(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val isHls = item.url.contains(".m3u8", ignoreCase = true) || 
                    item.mimeType.contains("mpegurl", ignoreCase = true) ||
                    item.mimeType.contains("m3u8", ignoreCase = true)

        val resolvedHeaders = parseHeaders(item)

        if (isHls) {
            val job = scope.launch(Dispatchers.IO) {
                try {
                    val activeItem = item.copy(state = DownloadState.DOWNLOADING, errorMessage = null)
                    _downloads.update { it + (id to activeItem) }
                    persistFullItem(activeItem)
                    notifyItem(activeItem, force = true)

                    val hlsDownloader = HlsPipelineDownloader(client)
                    val targetFile = item.file ?: File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        item.fileName
                    )

                    var lastDbSync = System.currentTimeMillis()
                    hlsDownloader.download(
                        url = item.url,
                        targetFile = targetFile,
                        headers = resolvedHeaders,
                        onProgress = { downloadedBytes, totalEstimated, currentSeg, totalSegs ->
                            _downloads.update { current ->
                                val currentItem = current[id] ?: return@update current
                                val updated = currentItem.copy(
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalEstimated,
                                    state = DownloadState.DOWNLOADING
                                )
                                notifyItem(updated)
                                current + (id to updated)
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastDbSync > 2000) {
                                persistProgressThrottled(id, DownloadState.DOWNLOADING, downloadedBytes, totalEstimated)
                                lastDbSync = now
                            }
                        }
                    )

                    val finalLength = targetFile.length()
                    val completedItem = item.copy(
                        state = DownloadState.COMPLETED,
                        downloadedBytes = finalLength,
                        totalBytes = finalLength,
                        file = targetFile
                    )
                    _downloads.update { current -> current + (id to completedItem) }
                    persistFullItem(completedItem)
                    notifyItem(completedItem, force = true)
                    jobs.remove(id)
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        val failedItem = item.copy(
                            state = DownloadState.FAILED,
                            errorMessage = e.message ?: "Erro no download HLS"
                        )
                        _downloads.update { current -> current + (id to failedItem) }
                        persistFullItem(failedItem)
                        notifyItem(failedItem, force = true)
                    }
                }
            }
            jobs[id] = job
            return
        }

        val isDash = item.url.contains(".mpd", ignoreCase = true) || 
                     item.mimeType.contains("dash+xml", ignoreCase = true) ||
                     item.mimeType.contains("mpd", ignoreCase = true)

        if (isDash) {
            val job = scope.launch(Dispatchers.IO) {
                try {
                    val activeItem = item.copy(state = DownloadState.DOWNLOADING, errorMessage = null)
                    _downloads.update { it + (id to activeItem) }
                    persistFullItem(activeItem)
                    notifyItem(activeItem, force = true)

                    val dashDownloader = DashPipelineDownloader(client)
                    val targetFile = item.file ?: File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        item.fileName
                    )

                    var lastDbSync = System.currentTimeMillis()
                    dashDownloader.download(
                        mpdUrl = item.url,
                        targetFile = targetFile,
                        headers = resolvedHeaders,
                        onProgress = { downloadedBytes, totalEstimated, currentChunk, totalChunks ->
                            _downloads.update { current ->
                                val currentItem = current[id] ?: return@update current
                                val updated = currentItem.copy(
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalEstimated,
                                    state = DownloadState.DOWNLOADING
                                )
                                notifyItem(updated)
                                current + (id to updated)
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastDbSync > 2000) {
                                persistProgressThrottled(id, DownloadState.DOWNLOADING, downloadedBytes, totalEstimated)
                                lastDbSync = now
                            }
                        }
                    )

                    val finalLength = targetFile.length()
                    val completedItem = item.copy(
                        state = DownloadState.COMPLETED,
                        downloadedBytes = finalLength,
                        totalBytes = finalLength,
                        file = targetFile
                    )
                    _downloads.update { current -> current + (id to completedItem) }
                    persistFullItem(completedItem)
                    notifyItem(completedItem, force = true)
                    jobs.remove(id)
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        val failedItem = item.copy(
                            state = DownloadState.FAILED,
                            errorMessage = e.message ?: "Erro no download DASH"
                        )
                        _downloads.update { current -> current + (id to failedItem) }
                        persistFullItem(failedItem)
                        notifyItem(failedItem, force = true)
                    }
                }
            }
            jobs[id] = job
            return
        }

        val isAbys = AbysDedicatedEngine.isAbysUrl(item.url)

        if (isAbys) {
            val job = scope.launch(Dispatchers.IO) {
                try {
                    val activeItem = item.copy(state = DownloadState.DOWNLOADING, errorMessage = null)
                    _downloads.update { it + (id to activeItem) }
                    persistFullItem(activeItem)
                    notifyItem(activeItem, force = true)

                    val abysDownloader = AbysPipelineDownloader(client)
                    val targetFile = item.file ?: File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        item.fileName
                    )

                    val additionalParts = resolvedHeaders["X-Abys-Parts"]?.split(";")?.filter { it.isNotBlank() && it != item.url } ?: emptyList()

                    var lastDbSync = System.currentTimeMillis()
                    abysDownloader.download(
                        url = item.url,
                        targetFile = targetFile,
                        headers = resolvedHeaders,
                        additionalPartUrls = additionalParts,
                        expectedSizeHint = item.totalBytes.takeIf { it > 0L },
                        onProgress = { downloadedBytes, totalEstimated ->
                            _downloads.update { current ->
                                val currentItem = current[id] ?: return@update current
                                val updated = currentItem.copy(
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = if (totalEstimated > 0L) totalEstimated else currentItem.totalBytes,
                                    state = DownloadState.DOWNLOADING
                                )
                                notifyItem(updated)
                                current + (id to updated)
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastDbSync > 2000) {
                                persistProgressThrottled(id, DownloadState.DOWNLOADING, downloadedBytes, totalEstimated)
                                lastDbSync = now
                            }
                        }
                    )

                    val finalLength = targetFile.length()
                    if (item.totalBytes > 0L && finalLength < (item.totalBytes * 0.90)) {
                        val failedItem = item.copy(
                            state = DownloadState.FAILED,
                            downloadedBytes = finalLength,
                            errorMessage = "Download incompleto: transferido ${AbysQualityEngine.formatBytes(finalLength)} de ${AbysQualityEngine.formatBytes(item.totalBytes)}. O servidor CDN forneceu apenas a Parte 1."
                        )
                        _downloads.update { current -> current + (id to failedItem) }
                        persistFullItem(failedItem)
                        notifyItem(failedItem, force = true)
                        jobs.remove(id)
                        return@launch
                    }

                    val completedItem = item.copy(
                        state = DownloadState.COMPLETED,
                        downloadedBytes = finalLength,
                        totalBytes = finalLength,
                        file = targetFile
                    )
                    _downloads.update { current -> current + (id to completedItem) }
                    persistFullItem(completedItem)
                    notifyItem(completedItem, force = true)
                    jobs.remove(id)
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        val failedItem = item.copy(
                            state = DownloadState.FAILED,
                            errorMessage = e.message ?: "Erro no download ABYS"
                        )
                        _downloads.update { current -> current + (id to failedItem) }
                        persistFullItem(failedItem)
                        notifyItem(failedItem, force = true)
                    }
                }
            }
            jobs[id] = job
            return
        }

        val job = scope.launch(Dispatchers.IO) {
            try {
                val activeItem = item.copy(state = DownloadState.DOWNLOADING, errorMessage = null)
                _downloads.update { it + (id to activeItem) }
                persistFullItem(activeItem)
                notifyItem(activeItem, force = true)
                
                val targetFile = item.file ?: throw IllegalStateException("Destino do arquivo não definido")
                val currentDownloaded = if (targetFile.exists()) targetFile.length() else 0L

                // Desofuscação dinâmica de assinaturas e parâmetros anti-throttling 'n' (base.js)
                val resolvedDownloadUrl = PlayerCipherDeobfuscator.resolveStreamUrl(
                    rawUrl = item.url,
                    headers = resolvedHeaders
                )

                val requestBuilder = Request.Builder().url(resolvedDownloadUrl)
                    
                resolvedHeaders.forEach { (key, value) ->
                    if (key.isNotBlank() && value.isNotBlank()) {
                        requestBuilder.header(key, value)
                    }
                }

                val isYouTubeStream = resolvedDownloadUrl.contains("googlevideo.com", ignoreCase = true) ||
                                     resolvedDownloadUrl.contains("youtube.com", ignoreCase = true)

                if (isYouTubeStream) {
                    if (!resolvedHeaders.containsKey("Referer")) {
                        requestBuilder.header("Referer", "https://www.youtube.com/")
                    }
                    if (!resolvedHeaders.containsKey("Origin")) {
                        requestBuilder.header("Origin", "https://www.youtube.com")
                    }
                    if (!resolvedHeaders.containsKey("Accept")) {
                        requestBuilder.header("Accept", "*/*")
                    }
                }

                if (currentDownloaded > 0) {
                    requestBuilder.header("Range", "bytes=$currentDownloaded-")
                } else if (isYouTubeStream && !resolvedHeaders.containsKey("Range")) {
                    requestBuilder.header("Range", "bytes=0-")
                }
                    
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Erro no servidor: HTTP ${response.code}")
                    }

                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    if (contentType.contains("text/html") || contentType.contains("text/plain")) {
                        throw Exception("Servidor retornou resposta inválida ($contentType) em vez de vídeo")
                    }
                    
                    val body = response.body ?: throw Exception("Resposta vazia do servidor")
                    val contentLength = body.contentLength()
                    val isPartial = response.code == 206 && currentDownloaded > 0
                    val totalBytes = if (isPartial) {
                        if (contentLength > 0) currentDownloaded + contentLength else currentDownloaded
                    } else {
                        if (contentLength > 0) contentLength else 0L
                    }
                    
                    val append = isPartial
                    var downloaded = if (append) currentDownloaded else 0L
                    
                    _downloads.update { it + (id to item.copy(totalBytes = totalBytes, downloadedBytes = downloaded)) }
                    
                    targetFile.parentFile?.mkdirs()
                    RandomAccessFile(targetFile, if (append) "rw" else "rwd").use { randomAccessFile ->
                        if (append) {
                            randomAccessFile.seek(downloaded)
                        } else {
                            randomAccessFile.setLength(0)
                        }
                        
                        body.byteStream().use { inputStream ->
                            val buffer = ByteArray(32 * 1024)
                            var bytesRead: Int
                            var lastUpdate = System.currentTimeMillis()
                            var lastDbSync = System.currentTimeMillis()
                            
                            while (isActive) {
                                bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break
                                randomAccessFile.write(buffer, 0, bytesRead)
                                downloaded += bytesRead
                                
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 300) {
                                    val updated = item.copy(downloadedBytes = downloaded, totalBytes = totalBytes, state = DownloadState.DOWNLOADING)
                                    _downloads.update { it + (id to updated) }
                                    notifyItem(updated)
                                    lastUpdate = now
                                }
                                if (now - lastDbSync > 2000) {
                                    persistProgressThrottled(id, DownloadState.DOWNLOADING, downloaded, totalBytes)
                                    lastDbSync = now
                                }
                            }
                        }
                    }
                    
                    if (isActive) { // completed naturally
                        val finalLength = targetFile.length()
                        val isMediaFile = item.mimeType.contains("video") || item.fileName.endsWith(".mp4") || item.fileName.endsWith(".mkv") || item.fileName.endsWith(".webm")
                        if (isMediaFile && finalLength < 65536) {
                            targetFile.delete()
                            throw Exception("Arquivo de vídeo incompleto ou corrompido (apenas ${finalLength} bytes recebidos)")
                        }

                        val completedItem = item.copy(
                            state = DownloadState.COMPLETED,
                            downloadedBytes = finalLength,
                            totalBytes = finalLength,
                            file = targetFile
                        )
                        _downloads.update { it + (id to completedItem) }
                        persistFullItem(completedItem)
                        notifyItem(completedItem, force = true)
                        jobs.remove(id)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    val failedItem = item.copy(state = DownloadState.FAILED, errorMessage = e.message)
                    _downloads.update { it + (id to failedItem) }
                    persistFullItem(failedItem)
                    notifyItem(failedItem, force = true)
                }
            }
        }
        jobs[id] = job
    }

    fun cancelDownload(id: Long) {
        jobs[id]?.cancel()
        jobs.remove(id)
        DownloadNotificationHelper.cancelNotification(context, id)
        _downloads.update { current ->
            val item = current[id]
            if (item != null) {
                // Delete partial file if not completed
                if (item.state != DownloadState.COMPLETED) {
                    try {
                        item.file?.delete()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                val cancelledItem = item.copy(state = DownloadState.CANCELLED)
                persistFullItem(cancelledItem)
                current + (id to cancelledItem)
            } else current
        }
    }

    fun deleteDownload(id: Long, deleteFileFromDisk: Boolean = true) {
        jobs[id]?.cancel()
        jobs.remove(id)
        DownloadNotificationHelper.cancelNotification(context, id)
        val item = _downloads.value[id]
        if (item != null) {
            if (deleteFileFromDisk) {
                try {
                    item.file?.let {
                        if (it.exists()) it.delete()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            _downloads.update { it - id }
            scope.launch {
                try {
                    downloadDao.deleteDownloadById(id)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun renameDownload(id: Long, newNameRaw: String): Result<String> {
        val item = _downloads.value[id] ?: return Result.failure(Exception("Download não encontrado"))
        val trimmed = newNameRaw.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(Exception("O nome do ficheiro não pode estar vazio"))
        }

        // Clean invalid filesystem characters
        val invalidChars = listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        if (invalidChars.any { trimmed.contains(it) }) {
            return Result.failure(Exception("O nome contém caracteres inválidos (/ \\ : * ? \" < > |)"))
        }

        // Check file extension: ensure extension from original file is preserved if user omitted it
        val originalExt = item.fileName.substringAfterLast('.', "")
        val hasNewExt = trimmed.contains('.') && trimmed.substringAfterLast('.').isNotEmpty()
        val finalFileName = if (!hasNewExt && originalExt.isNotEmpty()) {
            "$trimmed.$originalExt"
        } else {
            trimmed
        }

        val originalFile = item.file
        val targetDir = originalFile?.parentFile ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val newFile = File(targetDir, finalFileName)

        if (originalFile != null && originalFile.exists()) {
            if (newFile.exists() && newFile.absolutePath != originalFile.absolutePath) {
                return Result.failure(Exception("Já existe um ficheiro com o nome '$finalFileName'"))
            }
            val renamed = try {
                if (originalFile.renameTo(newFile)) {
                    true
                } else {
                    originalFile.copyTo(newFile, overwrite = true)
                    originalFile.delete()
                    true
                }
            } catch (e: Exception) {
                false
            }
            if (!renamed) {
                return Result.failure(Exception("Não foi possível renomear o ficheiro no armazenamento"))
            }
        }

        val updatedItem = item.copy(fileName = finalFileName, file = newFile)
        _downloads.update { it + (id to updatedItem) }
        
        scope.launch {
            try {
                downloadDao.updateDownloadFileName(id, finalFileName, newFile.absolutePath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return Result.success(finalFileName)
    }

    private fun parseHeaders(item: AdvancedDownloadItem): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        if (item.cookies.startsWith("{") && item.cookies.endsWith("}")) {
            try {
                val json = org.json.JSONObject(item.cookies)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = json.optString(k, "")
                    if (v.isNotBlank()) {
                        result[k] = v
                    }
                }
            } catch (_: Exception) {
                if (item.cookies.isNotEmpty()) {
                    result["Cookie"] = item.cookies
                }
            }
        } else if (item.cookies.isNotEmpty()) {
            result["Cookie"] = item.cookies
        }

        if (item.userAgent.isNotEmpty() && !result.containsKey("User-Agent")) {
            result["User-Agent"] = item.userAgent
        }

        if (!result.containsKey("User-Agent")) {
            result["User-Agent"] = "Mozilla/5.0 (Linux; Android 10; Mobile)"
        }

        return result
    }

    fun clearCompletedDownloads(deleteFilesFromDisk: Boolean = false) {
        val completedIds = _downloads.value.values
            .filter { it.state == DownloadState.COMPLETED || it.state == DownloadState.CANCELLED || it.state == DownloadState.FAILED }
            .map { it.id }
        
        completedIds.forEach { id ->
            deleteDownload(id, deleteFilesFromDisk)
        }
    }
}
