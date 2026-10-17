package com.albunyaan.tube.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.data.extractor.ExtractionClient
import com.albunyaan.tube.data.extractor.ResolvedStreams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spec §9.2(a): the seamless live refresh. A live HLS player keeps reloading the media-playlist URLs its master
 * named, and those carry the master's `/expire/` (~6 h, F11). After a refresh this rewrites each media-playlist
 * request to the fresh extraction's URL for the same itag; a re-extraction continues the same media sequence (F12),
 * so the player never rebuilds. Segments are absolute URLs inside the playlists and pass through untouched.
 *
 * A rewrite needs, all at once (RV I10a): an `hls_playlist` URL with an `/itag/`, a mapping for that itag, a later
 * `/expire/`, and the same `/ip/`. The map is cleared whenever a new live source is built (DashSourceBuilder).
 */
@OptIn(UnstableApi::class)
@Singleton
class LivePlaylistRedirector internal constructor(
    private val fetchText: suspend (url: String, client: ExtractionClient) -> String?,
) {
    @Inject constructor(provider: SegmentDataSourceFactoryProvider) : this({ url, client ->
        withContext(Dispatchers.IO) {
            val source = provider.forClient(client, cache = false).createDataSource()
            try {
                source.open(DataSpec(Uri.parse(url)))
                String(DataSourceUtil.readToEnd(source), Charsets.UTF_8)
            } catch (e: IOException) {
                null
            } finally {
                DataSourceUtil.closeQuietly(source)                 // close() throws IOException too; never escape refresh
            }
        }
    })

    private val maps = ConcurrentHashMap<String, Map<Int, String>>()
    /** The itags the player has asked for since the source was built: a fresh master must cover all of them. */
    private val requested = ConcurrentHashMap<String, MutableSet<Int>>()

    /** The `ResolvingDataSource` hook. */
    fun resolve(streamId: String, spec: DataSpec): DataSpec =
        rewrite(streamId, spec.uri.toString())?.let { spec.withUri(Uri.parse(it)) } ?: spec

    /** RV I10a's rule; null = pass through. */
    fun rewrite(streamId: String, url: String): String? {
        if (!url.contains(PLAYLIST_PATH)) return null
        val itag = itag(url) ?: return null
        requested.getOrPut(streamId) { ConcurrentHashMap.newKeySet() }.add(itag)
        val mapped = maps[streamId]?.get(itag) ?: return null
        val have = expire(url) ?: return null
        val fresh = expire(mapped) ?: return null
        if (fresh <= have || ip(mapped) != ip(url)) return null
        return mapped
    }

    /** A new source for [streamId] starts from its own master: never redirected to an older one. */
    fun clear(streamId: String) {
        maps.remove(streamId)
        requested.remove(streamId)
    }

    /**
     * Spec §9.2(a), the refresh: fetch the fresh master once through the matching client's data source and map
     * itag → media-playlist URL. false = the caller takes today's rebuild (another client, an itag the player uses
     * is missing, or the fetch failed).
     */
    suspend fun refresh(current: ResolvedStreams, fresh: ResolvedStreams): Boolean {
        if (fresh.extractionClient != current.extractionClient) return false       // RV I10b
        val master = fresh.hlsUrl ?: return false
        val map = variantMap(fetchText(master, fresh.extractionClient) ?: return false)
        if (map.isEmpty() || !map.keys.containsAll(requested[current.streamId].orEmpty())) return false
        maps[current.streamId] = map
        if (BuildConfig.DEBUG) Log.d(TAG, "swap ${current.streamId}: ${map.size} renditions -> expire ${expire(master)}")
        return true
    }

    companion object {
        private const val TAG = "LiveSwap"
        private const val PLAYLIST_PATH = "/api/manifest/hls_playlist/"
        private val ITAG = Regex("/itag/(\\d+)/")
        private val EXPIRE = Regex("/expire/(\\d+)/")
        private val IP = Regex("/ip/([^/]+)/")
        private val URL = Regex("https://[^\\s\"]+")

        fun itag(url: String): Int? = ITAG.find(url)?.groupValues?.get(1)?.toIntOrNull()
        fun expire(url: String): Long? = EXPIRE.find(url)?.groupValues?.get(1)?.toLongOrNull()
        fun ip(url: String): String? = IP.find(url)?.groupValues?.get(1)

        /**
         * itag → media-playlist URL from a master's variant lines and `EXT-X-MEDIA URI="…"` attributes.
         * ponytail: a regex over absolute URLs (what YouTube serves) instead of Media3's HlsPlaylistParser, so the
         * tests stay pure JVM; switch to the parser if YouTube ever emits relative URIs.
         */
        fun variantMap(master: String): Map<Int, String> =
            URL.findAll(master).map { it.value }.filter { it.contains(PLAYLIST_PATH) }
                .mapNotNull { u -> itag(u)?.let { it to u } }.toMap()
    }
}
