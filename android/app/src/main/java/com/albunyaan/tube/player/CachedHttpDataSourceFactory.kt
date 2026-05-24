package com.albunyaan.tube.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import com.albunyaan.tube.util.HttpConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CachedHttpDataSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val simpleCache: SimpleCache,
    private val cronetDataSourceFactory: CronetDataSourceFactory,
    private val featureFlags: PlaybackFeatureFlags,
) {
    @OptIn(UnstableApi::class)
    fun create(): DataSource.Factory {
        val httpFactory: DataSource.Factory = if (featureFlags.isCronetEnabled) {
            cronetDataSourceFactory.createForAndroidUA()
        } else {
            DefaultHttpDataSource.Factory()
                .setUserAgent(HttpConstants.YOUTUBE_USER_AGENT)
                .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)
        }
        val upstream = DefaultDataSource.Factory(context.applicationContext, httpFactory)
        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    companion object {
        private const val HTTP_CONNECT_TIMEOUT_MS = 15_000
        private const val HTTP_READ_TIMEOUT_MS = 20_000
    }
}
