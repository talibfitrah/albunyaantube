package com.albunyaan.tube.data.extractor.cache

import com.albunyaan.tube.data.extractor.ChannelMetadata
import com.albunyaan.tube.data.extractor.PlaylistMetadata
import com.albunyaan.tube.data.extractor.VideoMetadata
import java.util.concurrent.ConcurrentHashMap
class MetadataCache(
    private val ttlMillis: Long,
    private val maxEntriesPerBucket: Int
) {

    private data class CacheEntry<T>(val value: T, val timestamp: Long)

    private val videos = ConcurrentHashMap<String, CacheEntry<VideoMetadata>>()
    private val channels = ConcurrentHashMap<String, CacheEntry<ChannelMetadata>>()
    private val playlists = ConcurrentHashMap<String, CacheEntry<PlaylistMetadata>>()

    fun getVideo(id: String, nowMillis: Long): VideoMetadata? =
        videos[id]?.takeIf { nowMillis - it.timestamp <= ttlMillis }?.value

    fun putVideo(id: String, metadata: VideoMetadata, nowMillis: Long) {
        videos[id] = CacheEntry(metadata, nowMillis)
        prune(videos)
    }

    fun getChannel(id: String, nowMillis: Long): ChannelMetadata? =
        channels[id]?.takeIf { nowMillis - it.timestamp <= ttlMillis }?.value

    fun putChannel(id: String, metadata: ChannelMetadata, nowMillis: Long) {
        channels[id] = CacheEntry(metadata, nowMillis)
        prune(channels)
    }

    fun getPlaylist(id: String, nowMillis: Long): PlaylistMetadata? =
        playlists[id]?.takeIf { nowMillis - it.timestamp <= ttlMillis }?.value

    fun putPlaylist(id: String, metadata: PlaylistMetadata, nowMillis: Long) {
        playlists[id] = CacheEntry(metadata, nowMillis)
        prune(playlists)
    }

    private fun <T> prune(bucket: ConcurrentHashMap<String, CacheEntry<T>>) {
        if (bucket.size <= maxEntriesPerBucket) return
        val entriesByAge = bucket.entries.sortedBy { it.value.timestamp }
        val excess = bucket.size - maxEntriesPerBucket
        entriesByAge.take(excess).forEach { bucket.remove(it.key, it.value) }
    }
}
