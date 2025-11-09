package com.albunyaan.tube.util

import com.albunyaan.tube.data.model.*

/**
 * Test data builders for creating mock domain objects in instrumentation tests.
 *
 * Usage:
 * ```
 * val video = TestDataBuilder.video(id = "video123", title = "Test Video")
 * val channel = TestDataBuilder.channel(id = "channel456")
 * ```
 */
object TestDataBuilder {

    /**
     * Create a test ContentItem.Video
     */
    fun video(
        id: String = "test_video_${System.currentTimeMillis()}",
        title: String = "Test Video Title",
        channelId: String = "test_channel",
        channelName: String = "Test Channel",
        thumbnailUrl: String = "https://example.com/thumb.jpg",
        durationSeconds: Int = 600,
        viewCount: Long = 1000,
        uploadedAt: String = "2025-01-01T00:00:00Z",
        description: String = "Test video description",
        categoryId: String = "test_category",
        status: String = "APPROVED"
    ): ContentItem.Video {
        return ContentItem.Video(
            id = id,
            title = title,
            channelId = channelId,
            channelName = channelName,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSeconds,
            viewCount = viewCount,
            uploadedAt = uploadedAt,
            description = description,
            categoryId = categoryId,
            status = status
        )
    }

    /**
     * Create a test ContentItem.Channel
     */
    fun channel(
        id: String = "test_channel_${System.currentTimeMillis()}",
        name: String = "Test Channel",
        thumbnailUrl: String = "https://example.com/channel.jpg",
        subscriberCount: Long = 10000,
        videoCount: Int = 50,
        description: String = "Test channel description",
        categoryId: String = "test_category",
        status: String = "APPROVED"
    ): ContentItem.Channel {
        return ContentItem.Channel(
            id = id,
            name = name,
            thumbnailUrl = thumbnailUrl,
            subscriberCount = subscriberCount,
            videoCount = videoCount,
            description = description,
            categoryId = categoryId,
            status = status
        )
    }

    /**
     * Create a test ContentItem.Playlist
     */
    fun playlist(
        id: String = "test_playlist_${System.currentTimeMillis()}",
        title: String = "Test Playlist",
        channelId: String = "test_channel",
        channelName: String = "Test Channel",
        thumbnailUrl: String = "https://example.com/playlist.jpg",
        itemCount: Int = 20,
        description: String = "Test playlist description",
        categoryId: String = "test_category",
        status: String = "APPROVED"
    ): ContentItem.Playlist {
        return ContentItem.Playlist(
            id = id,
            title = title,
            channelId = channelId,
            channelName = channelName,
            thumbnailUrl = thumbnailUrl,
            itemCount = itemCount,
            description = description,
            categoryId = categoryId,
            status = status
        )
    }

    /**
     * Create a test Category
     */
    fun category(
        id: String = "test_category_${System.currentTimeMillis()}",
        name: String = "Test Category",
        description: String = "Test category description",
        parentCategoryId: String? = null,
        itemCount: Int = 100
    ): Category {
        return Category(
            id = id,
            name = name,
            description = description,
            parentCategoryId = parentCategoryId,
            itemCount = itemCount
        )
    }

    /**
     * Create a list of test videos
     */
    fun videoList(count: Int = 10): List<ContentItem.Video> {
        return (1..count).map { i ->
            video(
                id = "video_$i",
                title = "Video $i",
                viewCount = (i * 1000).toLong()
            )
        }
    }

    /**
     * Create a list of test channels
     */
    fun channelList(count: Int = 5): List<ContentItem.Channel> {
        return (1..count).map { i ->
            channel(
                id = "channel_$i",
                name = "Channel $i",
                subscriberCount = (i * 10000).toLong()
            )
        }
    }

    /**
     * Create a list of test playlists
     */
    fun playlistList(count: Int = 5): List<ContentItem.Playlist> {
        return (1..count).map { i ->
            playlist(
                id = "playlist_$i",
                title = "Playlist $i",
                itemCount = i * 10
            )
        }
    }

    /**
     * Create mock JSON response for HOME endpoint
     */
    fun homeResponseJson(): String {
        return """
        {
          "channels": [
            {
              "id": "channel1",
              "name": "Test Channel 1",
              "thumbnailUrl": "https://example.com/channel1.jpg",
              "subscriberCount": 100000,
              "videoCount": 50,
              "description": "First test channel",
              "categoryId": "quran",
              "status": "APPROVED"
            }
          ],
          "playlists": [
            {
              "id": "playlist1",
              "title": "Test Playlist 1",
              "channelId": "channel1",
              "channelName": "Test Channel 1",
              "thumbnailUrl": "https://example.com/playlist1.jpg",
              "itemCount": 20,
              "description": "First test playlist",
              "categoryId": "quran",
              "status": "APPROVED"
            }
          ],
          "videos": [
            {
              "id": "video1",
              "title": "Test Video 1",
              "channelId": "channel1",
              "channelName": "Test Channel 1",
              "thumbnailUrl": "https://example.com/video1.jpg",
              "durationSeconds": 600,
              "viewCount": 5000,
              "uploadedAt": "2025-01-01T00:00:00Z",
              "description": "First test video",
              "categoryId": "quran",
              "status": "APPROVED"
            }
          ]
        }
        """.trimIndent()
    }

    /**
     * Create mock JSON response for categories endpoint
     */
    fun categoriesResponseJson(): String {
        return """
        [
          {
            "id": "quran",
            "name": "Quran",
            "description": "Quran recitation and tafsir",
            "parentCategoryId": null,
            "itemCount": 100
          },
          {
            "id": "hadith",
            "name": "Hadith",
            "description": "Hadith studies",
            "parentCategoryId": null,
            "itemCount": 50
          }
        ]
        """.trimIndent()
    }
}
