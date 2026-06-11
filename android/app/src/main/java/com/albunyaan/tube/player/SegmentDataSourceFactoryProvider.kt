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
    fun forStreams(streams: ResolvedStreams): DataSource.Factory =
        // Live: skip the segment cache to avoid serving a stale manifest window (matches the
        // pre-convergence factory's live-uncached behavior). VOD: cache for fast seek/replay.
        forClient(streams.extractionClient, cache = !streams.isLive)

    fun forClient(client: ExtractionClient, cache: Boolean = true): DataSource.Factory {
        val http = if (client.usesIosUserAgent()) cronetDataSourceFactory.createForIosUA()
                   else cronetDataSourceFactory.createForAndroidUA()
        val upstream: DataSource.Factory = if (cache) {
            CacheDataSource.Factory()
                .setCache(simpleCache)
                .setUpstreamDataSourceFactory(http)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            http
        }
        // DefaultDataSource routes data: (inline MPD) to DataSchemeDataSource and http(s): to `upstream`.
        return DefaultDataSource.Factory(context, upstream)
    }

    /**
     * Factory for web-sourced dub audio segments: mobile-web UA (matching the URL-minting
     * client) with the GVS poToken already baked into the URL. Cached (dub audio is VOD).
     * Used only for the [androidx.media3.exoplayer.source.MergingMediaSource] audio leg —
     * the VR video leg keeps its own Android-UA factory via [forStreams].
     */
    fun forWebDub(): DataSource.Factory {
        val http = cronetDataSourceFactory.createForWebUA()
        val upstream: DataSource.Factory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultDataSource.Factory(context, upstream)
    }
}
