package com.albunyaan.tube.data.extractor

import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException

open class MetadataHydrator(
    private val extractorClient: ExtractorClient
) {

    open suspend fun hydrate(type: ContentType, items: List<ContentItem>): List<ContentItem> {
        if (items.isEmpty()) return items
        return when (type) {
            ContentType.HOME -> hydrateMixed(items)
            ContentType.VIDEOS -> hydrateVideos(items)
            ContentType.CHANNELS -> hydrateChannels(items)
            ContentType.PLAYLISTS -> hydratePlaylists(items)
        }
    }

    private suspend fun hydrateMixed(items: List<ContentItem>): List<ContentItem> {
        val videosHydrated = hydrateVideos(items)
        val channelsHydrated = hydrateChannels(videosHydrated)
        return hydratePlaylists(channelsHydrated)
    }

    private suspend fun hydrateVideos(items: List<ContentItem>): List<ContentItem> {
        val videos = items.filterIsInstance<ContentItem.Video>()
        if (videos.isEmpty()) return items
        val metadataById = fetchVideoMetadata(videos)
        if (metadataById.isEmpty()) return items
        return items.map { item ->
            if (item is ContentItem.Video) {
                mergeVideo(item, metadataById[item.id])
            } else {
                item
            }
        }
    }

    private suspend fun hydrateChannels(items: List<ContentItem>): List<ContentItem> {
        val channels = items.filterIsInstance<ContentItem.Channel>()
        if (channels.isEmpty()) return items
        val metadataById = fetchChannelMetadata(channels)
        if (metadataById.isEmpty()) return items
        return items.map { item ->
            if (item is ContentItem.Channel) {
                mergeChannel(item, metadataById[item.id])
            } else {
                item
            }
        }
    }

    private suspend fun hydratePlaylists(items: List<ContentItem>): List<ContentItem> {
        val playlists = items.filterIsInstance<ContentItem.Playlist>()
        if (playlists.isEmpty()) return items
        val metadataById = fetchPlaylistMetadata(playlists)
        if (metadataById.isEmpty()) return items
        return items.map { item ->
            if (item is ContentItem.Playlist) {
                mergePlaylist(item, metadataById[item.id])
            } else {
                item
            }
        }
    }

    private suspend fun fetchVideoMetadata(videos: List<ContentItem.Video>): Map<String, VideoMetadata> {
        return runCatching { extractorClient.fetchVideoMetadata(videos.map { it.id }) }
            .onFailure { rethrowIfCancellation(it) }
            .getOrNull()
            .orEmpty()
    }

    private suspend fun fetchChannelMetadata(channels: List<ContentItem.Channel>): Map<String, ChannelMetadata> {
        return runCatching { extractorClient.fetchChannelMetadata(channels.map { it.id }) }
            .onFailure { rethrowIfCancellation(it) }
            .getOrNull()
            .orEmpty()
    }

    private suspend fun fetchPlaylistMetadata(playlists: List<ContentItem.Playlist>): Map<String, PlaylistMetadata> {
        return runCatching { extractorClient.fetchPlaylistMetadata(playlists.map { it.id }) }
            .onFailure { rethrowIfCancellation(it) }
            .getOrNull()
            .orEmpty()
    }

    private fun mergeVideo(video: ContentItem.Video, metadata: VideoMetadata?): ContentItem.Video {
        if (metadata == null) return video
        val mergedTitle = if (video.title.isNotBlank()) video.title else metadata.title?.takeIf { it.isNotBlank() } ?: video.title
        val mergedDescription = if (video.description.isNotBlank()) video.description else metadata.description ?: video.description
        val mergedDurationMinutes = if (video.durationMinutes > 0) {
            video.durationMinutes
        } else {
            metadata.durationSeconds?.let { max(1, it / 60) } ?: video.durationMinutes
        }
        val mergedViewCount = video.viewCount ?: metadata.viewCount
        val mergedThumbnail = video.thumbnailUrl ?: metadata.thumbnailUrl
        return video.copy(
            title = mergedTitle,
            description = mergedDescription,
            durationMinutes = mergedDurationMinutes,
            viewCount = mergedViewCount,
            thumbnailUrl = mergedThumbnail
        )
    }

    private fun mergeChannel(channel: ContentItem.Channel, metadata: ChannelMetadata?): ContentItem.Channel {
        if (metadata == null) return channel
        val mergedName = if (channel.name.isNotBlank()) channel.name else metadata.name?.takeIf { it.isNotBlank() } ?: channel.name
        val mergedDescription = channel.description ?: metadata.description
        val mergedSubscribers = if (channel.subscribers > 0) {
            channel.subscribers
        } else {
            metadata.subscriberCount?.let { count ->
                min(count, Int.MAX_VALUE.toLong()).toInt()
            } ?: channel.subscribers
        }
        val mergedVideoCount = channel.videoCount ?: metadata.videoCount
        val mergedThumbnail = channel.thumbnailUrl ?: metadata.thumbnailUrl
        return channel.copy(
            name = mergedName,
            description = mergedDescription,
            subscribers = mergedSubscribers,
            videoCount = mergedVideoCount,
            thumbnailUrl = mergedThumbnail
        )
    }

    private fun mergePlaylist(playlist: ContentItem.Playlist, metadata: PlaylistMetadata?): ContentItem.Playlist {
        if (metadata == null) return playlist
        val mergedTitle = if (playlist.title.isNotBlank()) playlist.title else metadata.title?.takeIf { it.isNotBlank() } ?: playlist.title
        val mergedDescription = playlist.description ?: metadata.description
        val mergedItemCount = if (playlist.itemCount > 0) playlist.itemCount else metadata.itemCount ?: playlist.itemCount
        val mergedThumbnail = playlist.thumbnailUrl ?: metadata.thumbnailUrl
        return playlist.copy(
            title = mergedTitle,
            description = mergedDescription,
            itemCount = mergedItemCount,
            thumbnailUrl = mergedThumbnail
        )
    }

    private fun rethrowIfCancellation(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
    }
}
