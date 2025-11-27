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
        category: String = "test_category",
        durationSeconds: Int = 10,
        uploadedDaysAgo: Int = 7,
        description: String = "Test video description",
        thumbnailUrl: String? = "https://example.com/thumb.jpg",
        viewCount: Long? = 1000
    ): ContentItem.Video {
        return ContentItem.Video(
            id = id,
            title = title,
            category = category,
            durationSeconds = durationSeconds,
            uploadedDaysAgo = uploadedDaysAgo,
            description = description,
            thumbnailUrl = thumbnailUrl,
            viewCount = viewCount
        )
    }

    /**
     * Create a test ContentItem.Channel
     */
    fun channel(
        id: String = "test_channel_${System.currentTimeMillis()}",
        name: String = "Test Channel",
        category: String = "test_category",
        subscribers: Int = 10000,
        description: String? = "Test channel description",
        thumbnailUrl: String? = "https://example.com/channel.jpg",
        videoCount: Int? = 50,
        categories: List<String>? = null
    ): ContentItem.Channel {
        return ContentItem.Channel(
            id = id,
            name = name,
            category = category,
            subscribers = subscribers,
            description = description,
            thumbnailUrl = thumbnailUrl,
            videoCount = videoCount,
            categories = categories
        )
    }

    /**
     * Create a test ContentItem.Playlist
     */
    fun playlist(
        id: String = "test_playlist_${System.currentTimeMillis()}",
        title: String = "Test Playlist",
        category: String = "test_category",
        itemCount: Int = 20,
        description: String? = "Test playlist description",
        thumbnailUrl: String? = "https://example.com/playlist.jpg"
    ): ContentItem.Playlist {
        return ContentItem.Playlist(
            id = id,
            title = title,
            category = category,
            itemCount = itemCount,
            description = description,
            thumbnailUrl = thumbnailUrl
        )
    }

    /**
     * Create a test Category
     */
    fun category(
        id: String = "test_category_${System.currentTimeMillis()}",
        name: String = "Test Category",
        slug: String? = null,
        parentId: String? = null,
        hasSubcategories: Boolean = false,
        icon: String? = null
    ): Category {
        return Category(
            id = id,
            name = name,
            slug = slug,
            parentId = parentId,
            hasSubcategories = hasSubcategories,
            icon = icon
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
                subscribers = i * 10000
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
              "subscribers": 100000,
              "videoCount": 50,
              "description": "First test channel",
              "category": "quran"
            }
          ],
          "playlists": [
            {
              "id": "playlist1",
              "title": "Test Playlist 1",
              "thumbnailUrl": "https://example.com/playlist1.jpg",
              "itemCount": 20,
              "description": "First test playlist",
              "category": "quran"
            }
          ],
          "videos": [
            {
              "id": "video1",
              "title": "Test Video 1",
              "thumbnailUrl": "https://example.com/video1.jpg",
              "durationSeconds": 10,
              "uploadedDaysAgo": 7,
              "viewCount": 5000,
              "description": "First test video",
              "category": "quran"
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
            "slug": "quran",
            "parentId": null,
            "hasSubcategories": true,
            "icon": null
          },
          {
            "id": "hadith",
            "name": "Hadith",
            "slug": "hadith",
            "parentId": null,
            "hasSubcategories": false,
            "icon": null
          }
        ]
        """.trimIndent()
    }
}
