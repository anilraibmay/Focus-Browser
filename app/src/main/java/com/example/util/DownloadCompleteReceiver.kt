package com.example.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

class DownloadCompleteReceiver(private val callback: (String) -> Unit) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE && context != null) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (downloadId != -1L) {
                // Post-process in background
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = dm.query(query)
                        
                        cursor?.use { c ->
                            if (c.moveToFirst()) {
                                val statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                val status = if (statusIndex >= 0) c.getInt(statusIndex) else -1
                                
                                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                    val uriIndex = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                    val uriString = if (uriIndex >= 0) c.getString(uriIndex) else null
                                    val titleIndex = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                                    val title = if (titleIndex >= 0) c.getString(titleIndex) else "Ficheiro"
                                    
                                    if (!uriString.isNullOrEmpty()) {
                                        val uri = Uri.parse(uriString)
                                        val hash = calculateSHA256(context, uri)
                                        withContext(Dispatchers.Main) {
                                            callback("Download Concluído: $title\nSHA-256: $hash")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun calculateSHA256(context: Context, uri: Uri): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                val buffer = ByteArray(8192)
                var bytesRead = stream.read(buffer)
                while (bytesRead != -1) {
                    md.update(buffer, 0, bytesRead)
                    bytesRead = stream.read(buffer)
                }
            }
            val digest = md.digest()
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "Erro ao calcular Hash"
        }
    }
}
