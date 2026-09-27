package com.example

import android.content.Context
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.example.media.AbysDataSourceFactory
import com.example.media.ExoMediaCacheManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExoMediaCacheManagerTest {

    @After
    fun tearDown() {
        ExoMediaCacheManager.release()
    }

    @Test
    fun testCacheInitializationAndFactoryCreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = ExoMediaCacheManager.getCache(context)
        assertNotNull(cache)

        val testData = ByteArray(1024) { (it % 128).toByte() }
        val upstreamFactory = { ByteArrayDataSource(testData) }

        val cachingFactory = ExoMediaCacheManager.createCacheDataSourceFactory(context, upstreamFactory)
        assertNotNull(cachingFactory)

        val dataSource = cachingFactory.createDataSource()
        assertNotNull(dataSource)

        val abysFactory = AbysDataSourceFactory(
            baseFactory = upstreamFactory,
            context = context,
            enableCache = true
        )
        val abysDataSource = abysFactory.createDataSource()
        assertNotNull(abysDataSource)
    }
}
