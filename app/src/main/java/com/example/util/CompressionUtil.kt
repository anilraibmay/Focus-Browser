package com.example.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Utility for in-memory GZIP compression and decompression
 * of large blobs (e.g. WebView saved state bundles and caches).
 */
object CompressionUtil {

    // Standard GZIP magic numbers (0x1f, 0x8b)
    private const val GZIP_MAGIC_0: Byte = 0x1f
    private const val GZIP_MAGIC_1: Byte = 0x8b.toByte()

    /**
     * Compresses the provided byte array using GZIP.
     * Returns an empty array if input is empty or null.
     */
    fun compress(data: ByteArray?): ByteArray? {
        if (data == null || data.isEmpty()) return data
        return try {
            val byteOut = ByteArrayOutputStream(data.size / 2)
            GZIPOutputStream(byteOut).use { gzipOut ->
                gzipOut.write(data)
                gzipOut.finish()
            }
            byteOut.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            data // fallback to original data if compression fails
        }
    }

    /**
     * Decompresses the provided byte array using GZIP.
     * If the input is not GZIP-compressed (e.g., legacy uncompressed records),
     * it gracefully returns the original data unchanged.
     */
    fun decompress(compressedData: ByteArray?): ByteArray? {
        if (compressedData == null || compressedData.size < 2) return compressedData
        
        // Detect GZIP magic header (0x1f 0x8b)
        if (compressedData[0] != GZIP_MAGIC_0 || compressedData[1] != GZIP_MAGIC_1) {
            // Already uncompressed or legacy data
            return compressedData
        }

        return try {
            val byteIn = ByteArrayInputStream(compressedData)
            val gzipIn = GZIPInputStream(byteIn)
            val byteOut = ByteArrayOutputStream(compressedData.size * 2)
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (gzipIn.read(buffer).also { bytesRead = it } != -1) {
                byteOut.write(buffer, 0, bytesRead)
            }
            gzipIn.close()
            byteOut.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            compressedData // fallback
        }
    }
}
