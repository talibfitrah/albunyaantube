package com.albunyaan.tube.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import com.albunyaan.tube.data.extractor.ExtractionClient
import com.albunyaan.tube.data.extractor.ResolvedStreams
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the [DataSource.Factory] used to fetch DASH/HLS/progressive segments, with the
 * User-Agent matched to the extraction client that minted the URLs (avoids client/UA-mismatch 403s),
 * wrapped in the shared [SimpleCache]. Handles `data:` (inline MPD), `http(s):` (segments) via
 * [DefaultDataSource].
 */
@OptIn(UnstableApi::class)
@Singleton
class SegmentDataSourceFactoryProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cronetDataSourceFactory: CronetDataSourceFactory,
    private val simpleCache: SimpleCache,
) {
    fun forStreams(streams: ResolvedStreams): DataSource.Factory = forClient(streams.extractionClient)

    fun forClient(client: ExtractionClient): DataSource.Factory {
        val http = if (client.usesIosUserAgent()) cronetDataSourceFactory.createForIosUA()
                   else cronetDataSourceFactory.createForAndroidUA()
        val cached = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // DefaultDataSource routes data: (inline MPD) to DataSchemeDataSource and http(s): to `cached`.
        return DefaultDataSource.Factory(context, cached)
    }
}
