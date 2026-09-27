package com.example.database

import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val downloadDao: DownloadDao) {
    val allDownloads: Flow<List<DownloadEntity>> = downloadDao.getAllDownloads()

    suspend fun getAllDownloadsList(): List<DownloadEntity> = downloadDao.getAllDownloadsList()

    suspend fun getDownloadById(id: Long): DownloadEntity? = downloadDao.getDownloadById(id)

    suspend fun insertDownload(download: DownloadEntity): Long = downloadDao.insertDownload(download)

    suspend fun updateDownload(download: DownloadEntity) = downloadDao.updateDownload(download)

    suspend fun updateFileName(id: Long, newFileName: String, newFilePath: String?) =
        downloadDao.updateDownloadFileName(id, newFileName, newFilePath)

    suspend fun updateProgress(id: Long, state: String, downloadedBytes: Long, totalBytes: Long) =
        downloadDao.updateDownloadProgress(id, state, downloadedBytes, totalBytes)

    suspend fun updateState(id: Long, state: String) = downloadDao.updateDownloadState(id, state)

    suspend fun deleteDownload(download: DownloadEntity) = downloadDao.deleteDownload(download)

    suspend fun deleteById(id: Long) = downloadDao.deleteDownloadById(id)

    suspend fun clearAll() = downloadDao.clearAllDownloads()
}
