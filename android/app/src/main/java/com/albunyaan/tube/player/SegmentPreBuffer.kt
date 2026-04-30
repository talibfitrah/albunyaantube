package com.albunyaan.tube.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class SegmentPreBuffer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: SimpleCache,
    private val httpFactory: DataSource.Factory
) {
    companion object {
        private const val TAG = "SegmentPreBuffer"
        private const val DEFAULT_DURATION_MS = 3_000L
        private const val BYTES_PER_MS_ESTIMATE = 500L // ~4 Mbps at lowest quality
    }

    private val isLowRamDevice: Boolean by lazy {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }

    suspend fun preBuffer(videoUrl: String, durationMs: Long = DEFAULT_DURATION_MS) {
        if (videoUrl.isBlank()) return
        if (isLowRamDevice) return
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(videoUrl)
                val bytesToCache = durationMs * BYTES_PER_MS_ESTIMATE
                val dataSpec = DataSpec.Builder()
                    .setUri(uri)
                    .setLength(bytesToCache)
                    .build()
                val cacheDataSourceFactory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(httpFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                val writer = CacheWriter(
                    cacheDataSourceFactory.createDataSource() as CacheDataSource,
                    dataSpec,
                    null,
                    null
                )
                writer.cache()
                Log.d(TAG, "Pre-buffered ${bytesToCache}B for $uri")
            } catch (e: Exception) {
                Log.d(TAG, "Pre-buffer failed for $videoUrl (non-fatal): ${e.message}")
            }
        }
    }
}
