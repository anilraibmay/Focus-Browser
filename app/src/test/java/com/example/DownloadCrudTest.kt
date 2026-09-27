package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.database.AppDatabase
import com.example.database.DownloadDao
import com.example.database.DownloadEntity
import com.example.util.AdvancedDownloadItem
import com.example.util.AdvancedDownloadManager
import com.example.util.DownloadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadCrudTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var downloadDao: DownloadDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        downloadDao = database.downloadDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testRoomDownloadDaoInsertAndRetrieve() = runBlocking {
        val entity = DownloadEntity(
            id = 100L,
            fileName = "sample.mp4",
            url = "https://example.com/sample.mp4",
            filePath = "/fake/path/sample.mp4",
            downloadedBytes = 1024L,
            totalBytes = 2048L,
            state = "COMPLETED",
            mimeType = "video/mp4",
            createdAtMs = System.currentTimeMillis()
        )

        downloadDao.insertDownload(entity)
        val all = downloadDao.getAllDownloads().first()

        assertEquals(1, all.size)
        assertEquals(100L, all[0].id)
        assertEquals("sample.mp4", all[0].fileName)
        assertEquals("COMPLETED", all[0].state)
    }

    @Test
    fun testRoomDownloadDaoRename() = runBlocking {
        val entity = DownloadEntity(
            id = 200L,
            fileName = "old_name.pdf",
            url = "https://example.com/document.pdf",
            filePath = "/fake/path/old_name.pdf",
            downloadedBytes = 500L,
            totalBytes = 500L,
            state = "COMPLETED",
            mimeType = "application/pdf"
        )
        downloadDao.insertDownload(entity)

        downloadDao.updateDownloadFileName(200L, "new_name.pdf", "/fake/path/new_name.pdf")
        val updated = downloadDao.getDownloadById(200L)

        assertNotNull(updated)
        assertEquals("new_name.pdf", updated?.fileName)
        assertEquals("/fake/path/new_name.pdf", updated?.filePath)
    }

    @Test
    fun testRoomDownloadDaoDeleteAndClear() = runBlocking {
        val e1 = DownloadEntity(
            id = 1L,
            fileName = "f1.png",
            url = "http://u1",
            filePath = null,
            state = "COMPLETED",
            mimeType = "image/png",
            downloadedBytes = 100L,
            totalBytes = 100L
        )
        val e2 = DownloadEntity(
            id = 2L,
            fileName = "f2.png",
            url = "http://u2",
            filePath = null,
            state = "DOWNLOADING",
            mimeType = "image/png",
            downloadedBytes = 50L,
            totalBytes = 100L
        )

        downloadDao.insertDownload(e1)
        downloadDao.insertDownload(e2)

        assertEquals(2, downloadDao.getAllDownloads().first().size)

        // Delete single by id
        downloadDao.deleteDownloadById(1L)
        assertEquals(1, downloadDao.getAllDownloads().first().size)
        assertNull(downloadDao.getDownloadById(1L))

        // Clear all
        downloadDao.clearAllDownloads()
        val remaining = downloadDao.getAllDownloads().first()
        assertEquals(0, remaining.size)
    }

    @Test
    fun testAdvancedDownloadManagerRenameAndDelete() = runBlocking {
        val manager = AdvancedDownloadManager.getInstance(context)

        val tempFile = File(context.cacheDir, "test_file.txt").apply {
            writeText("Hello, Downloads CRUD!")
        }

        val item = AdvancedDownloadItem(
            id = 999L,
            fileName = "test_file.txt",
            url = "https://example.com/test_file.txt",
            file = tempFile,
            downloadedBytes = tempFile.length(),
            totalBytes = tempFile.length(),
            state = DownloadState.COMPLETED,
            mimeType = "text/plain"
        )

        AppDatabase.getDatabase(context).downloadDao().insertDownload(
            DownloadEntity(
                id = 999L,
                fileName = "test_file.txt",
                url = "https://example.com/test_file.txt",
                filePath = tempFile.absolutePath,
                downloadedBytes = tempFile.length(),
                totalBytes = tempFile.length(),
                state = "COMPLETED",
                mimeType = "text/plain"
            )
        )
        manager._downloads.value = mapOf(999L to item)

        // Test Rename
        val renameResult = manager.renameDownload(999L, "renamed_file.txt")
        assertTrue("Rename failed: ${renameResult.exceptionOrNull()?.message}", renameResult.isSuccess)
        assertEquals("renamed_file.txt", renameResult.getOrNull())

        val renamedItem = manager._downloads.value[999L]
        assertNotNull(renamedItem)
        assertEquals("renamed_file.txt", renamedItem?.fileName)
        assertEquals("renamed_file.txt", renamedItem?.file?.name)

        // Test Delete with file
        manager.deleteDownload(999L, deleteFileFromDisk = true)
        val afterDelete = manager._downloads.value[999L]
        assertNull(afterDelete)
    }

    @Test
    fun testNotificationDeduplicationWhenServiceRunning() {
        val manager = AdvancedDownloadManager.getInstance(context)
        val item = AdvancedDownloadItem(
            id = 123L,
            fileName = "movie.mp4",
            url = "https://example.com/movie.mp4",
            file = null,
            downloadedBytes = 500,
            totalBytes = 1000,
            state = DownloadState.DOWNLOADING,
            mimeType = "video/mp4"
        )
        manager._downloads.value = mapOf(123L to item)

        // Simula DownloadService em execução
        com.example.service.DownloadService.isRunning = true
        try {
            com.example.util.DownloadNotificationHelper.updateNotification(context, item, force = true)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val activeNotifications = notificationManager.activeNotifications
            
            // Não deve haver notificação com o ID individual (1123), pois foi cancelada para evitar duplicação
            val hasIndividual = activeNotifications.any { it.id == (123 % 100000).toInt() + 1000 }
            org.junit.Assert.assertFalse("ID individual não deve existir quando o foreground service gerencia a notificação", hasIndividual)
        } finally {
            com.example.service.DownloadService.isRunning = false
        }
    }
}
