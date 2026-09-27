package com.example.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource

/**
 * Factory creating [AbysCipherDataSource] instances with local Media3 segment caching.
 * Correctly delegates local URIs (file://, content://, asset://) to Media3's [DefaultDataSource],
 * while routing ABYS remote streams through [AbysCipherDataSource] and caching downloaded chunks locally.
 */
@OptIn(UnstableApi::class)
class AbysDataSourceFactory(
    private val baseFactory: DataSource.Factory,
    private val context: Context? = null,
    private val enableCache: Boolean = true
) : DataSource.Factory {

    private val upstreamFactory: DataSource.Factory = if (context != null) {
        val defaultFactory = DefaultDataSource.Factory(context, baseFactory)
        if (enableCache) {
            ExoMediaCacheManager.createCacheDataSourceFactory(context, defaultFactory)
        } else {
            defaultFactory
        }
    } else {
        baseFactory
    }

    override fun createDataSource(): DataSource {
        return AbysCipherDataSource(
            upstream = upstreamFactory.createDataSource(),
            headerDataSource = baseFactory.createDataSource()
        )
    }
}

