package com.albunyaan.tube.data.extractor

import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataHydratorTest {

    @Test
    fun `hydrate fills missing video metadata from extractor`() = runTest {
        val baseVideo = ContentItem.Video(
            id = "video-1",
            title = "",
            category = "Knowledge",
            durationMinutes = 0,
            uploadedDaysAgo = 3,
            description = ""
        )
        val client = object : ExtractorClient {
            override suspend fun fetchVideoMetadata(ids: List<String>): Map<String, VideoMetadata> =
                ids.associateWith {
                    VideoMetadata(
                        title = "Extractor Title",
                        description = "Extractor Description",
                        thumbnailUrl = "thumb",
                        durationSeconds = 720,
                        viewCount = 1234
                    )
                }

            override suspend fun fetchChannelMetadata(ids: List<String>): Map<String, ChannelMetadata> = emptyMap()

            override suspend fun fetchPlaylistMetadata(ids: List<String>): Map<String, PlaylistMetadata> = emptyMap()
        }

        val hydrator = MetadataHydrator(client)
        val result = hydrator.hydrate(ContentType.VIDEOS, listOf(baseVideo))
        val hydrated = result.single() as ContentItem.Video

        assertEquals("Extractor Title", hydrated.title)
        assertEquals("Extractor Description", hydrated.description)
        assertEquals(12, hydrated.durationMinutes)
        assertEquals(1234L, hydrated.viewCount)
        assertEquals("thumb", hydrated.thumbnailUrl)
    }

    @Test
    fun `hydrate retains backend overrides when provided`() = runTest {
        val baseVideo = ContentItem.Video(
            id = "video-2",
            title = "Backend Title",
            category = "Knowledge",
            durationMinutes = 15,
            uploadedDaysAgo = 5,
            description = "Backend Description",
            thumbnailUrl = "backend-thumb",
            viewCount = 9999
        )
        val client = object : ExtractorClient {
            override suspend fun fetchVideoMetadata(ids: List<String>): Map<String, VideoMetadata> =
                ids.associateWith {
                    VideoMetadata(
                        title = "Extractor Title",
                        description = "Extractor Description",
                        thumbnailUrl = "thumb",
                        durationSeconds = 360,
                        viewCount = 1234
                    )
                }

            override suspend fun fetchChannelMetadata(ids: List<String>): Map<String, ChannelMetadata> = emptyMap()

            override suspend fun fetchPlaylistMetadata(ids: List<String>): Map<String, PlaylistMetadata> = emptyMap()
        }

        val hydrator = MetadataHydrator(client)
        val result = hydrator.hydrate(ContentType.VIDEOS, listOf(baseVideo))
        val hydrated = result.single() as ContentItem.Video

        assertEquals("Backend Title", hydrated.title)
        assertEquals("Backend Description", hydrated.description)
        assertEquals(15, hydrated.durationMinutes)
        assertEquals(9999L, hydrated.viewCount)
        assertEquals("backend-thumb", hydrated.thumbnailUrl)
    }

    @Test
    fun `hydrate returns originals when extractor throws`() = runTest {
        val baseVideo = ContentItem.Video(
            id = "video-3",
            title = "Title",
            category = "Knowledge",
            durationMinutes = 10,
            uploadedDaysAgo = 7,
            description = "Description"
        )
        val client = object : ExtractorClient {
            override suspend fun fetchVideoMetadata(ids: List<String>): Map<String, VideoMetadata> {
                error("Extractor failure")
            }

            override suspend fun fetchChannelMetadata(ids: List<String>): Map<String, ChannelMetadata> = emptyMap()

            override suspend fun fetchPlaylistMetadata(ids: List<String>): Map<String, PlaylistMetadata> = emptyMap()
        }

        val hydrator = MetadataHydrator(client)
        val result = hydrator.hydrate(ContentType.VIDEOS, listOf(baseVideo))

        assertEquals(1, result.size)
        val hydrated = result.single() as ContentItem.Video
        assertTrue(hydrated === baseVideo || hydrated == baseVideo)
    }

    @Test
    fun `hydrate updates channel metadata when missing`() = runTest {
        val baseChannel = ContentItem.Channel(
            id = "channel-1",
            name = "",
            category = "Knowledge",
            subscribers = 0
        )
        val client = object : ExtractorClient {
            override suspend fun fetchVideoMetadata(ids: List<String>): Map<String, VideoMetadata> = emptyMap()

            override suspend fun fetchChannelMetadata(ids: List<String>): Map<String, ChannelMetadata> =
                ids.associateWith {
                    ChannelMetadata(
                        name = "Extractor Channel",
                        description = "Extractor description",
                        thumbnailUrl = "channel-thumb",
                        subscriberCount = 42_000,
                        videoCount = 12
                    )
                }

            override suspend fun fetchPlaylistMetadata(ids: List<String>): Map<String, PlaylistMetadata> = emptyMap()
        }

        val hydrator = MetadataHydrator(client)
        val result = hydrator.hydrate(ContentType.CHANNELS, listOf(baseChannel))
        val hydrated = result.single() as ContentItem.Channel

        assertEquals("Extractor Channel", hydrated.name)
        assertEquals("Extractor description", hydrated.description)
        assertEquals(42_000, hydrated.subscribers)
        assertEquals(12, hydrated.videoCount)
        assertEquals("channel-thumb", hydrated.thumbnailUrl)
    }
}
