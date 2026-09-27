package com.example.media

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * High-performance local caching manager for ExoPlayer media streams.
 *
 * Caches HLS, DASH, ABYS and Progressive MP4 segments in an LRU store (512 MB).
 * Ensures smooth, uninterrupted playback during network drops, speeds up seeking,
 * and preserves downloaded buffer progress.
 */
@OptIn(UnstableApi::class)
object ExoMediaCacheManager {

    private const val TAG = "ExoMediaCacheManager"
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L // 512 MB LRU cache

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    @Synchronized
    fun getCache(context: Context): Cache {
        val current = simpleCache
        if (current != null) return current

        val cacheDir = File(context.applicationContext.cacheDir, "exo_media_cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val dbProvider = StandaloneDatabaseProvider(context.applicationContext)
        databaseProvider = dbProvider

        val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
        val newCache = SimpleCache(cacheDir, evictor, dbProvider)
        simpleCache = newCache
        Log.i(TAG, "ExoMediaCache initialized at ${cacheDir.absolutePath} (max 512MB)")
        return newCache
    }

    /**
     * Builds a caching [DataSource.Factory] wrapping [upstreamFactory].
     * Configured with [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR] so playback never fails
     * if local cache read/write encounters temporary I/O anomalies.
     */
    fun createCacheDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory
    ): DataSource.Factory {
        val cache = getCache(context)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Total space in bytes currently occupied by cached media segments.
     */
    fun getCachedBytes(): Long {
        return simpleCache?.cacheSpace ?: 0L
    }

    /**
     * Releases cache resources.
     */
    @Synchronized
    fun release() {
        try {
            simpleCache?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing simpleCache: ${e.message}")
        }
        simpleCache = null
        databaseProvider = null
    }
}
