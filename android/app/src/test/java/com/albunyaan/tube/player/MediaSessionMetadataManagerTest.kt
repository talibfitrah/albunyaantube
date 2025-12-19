package com.albunyaan.tube.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for MediaSession metadata data structures.
 *
 * These tests verify the MediaMetadata and MediaItem construction patterns
 * used by MediaSessionMetadataManager. They validate the contract that
 * Media3 APIs behave as expected for our use cases.
 *
 * NOTE: Testing MediaSessionMetadataManager's internal logic (caching, job
 * cancellation, artwork loading) requires Robolectric or instrumented tests
 * due to Android Context dependency. This test file focuses on the data
 * structures and transformation patterns that the manager relies on.
 *
 * MediaSessionMetadataManager behaviors that need Robolectric/instrumented tests:
 * - cancelArtworkLoading() cancels pending job (verify job.cancel() is called)
 * - Artwork caching (same URL reuses cached bytes)
 * - Media item index change detection (skips update if item changed during load)
 * - release() clears cache and cancels scope
 */
class MediaSessionMetadataManagerTest {

    // region MediaMetadata Structure Tests

    @Test
    fun `MediaMetadata includes title and artist correctly`() {
        val title = "Test Video Title"
        val artist = "Test Channel"

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setDisplayTitle(title)
            .build()

        assertEquals(title, metadata.title)
        assertEquals(artist, metadata.artist)
        assertEquals(title, metadata.displayTitle)
    }

    @Test
    fun `MediaMetadata accepts artwork bytes for notification display`() {
        val artworkBytes = byteArrayOf(0, 1, 2, 3, 4, 5)

        val metadata = MediaMetadata.Builder()
            .setTitle("Test")
            .setArtist("Artist")
            .setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()

        assertNotNull(metadata.artworkData)
        assertEquals(artworkBytes.size, metadata.artworkData?.size)
    }

    @Test
    fun `MediaMetadata builder handles all notification-relevant fields`() {
        val artworkBytes = byteArrayOf(1, 2, 3)

        val metadata = MediaMetadata.Builder()
            .setTitle("Video Title")
            .setArtist("Channel Name")
            .setDisplayTitle("Video Title")
            .setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()

        assertEquals("Video Title", metadata.title)
        assertEquals("Channel Name", metadata.artist)
        assertEquals("Video Title", metadata.displayTitle)
        assertNotNull(metadata.artworkData)
    }

    @Test
    fun `empty metadata fields are null by default`() {
        val metadata = MediaMetadata.Builder()
            .setTitle("Only Title")
            .build()

        assertEquals("Only Title", metadata.title)
        assertEquals(null, metadata.artist)
        assertEquals(null, metadata.artworkData)
    }

    // endregion

    // region MediaItem Transformation Tests

    @Test
    fun `MediaItem can be rebuilt with updated metadata`() {
        val originalItem = MediaItem.Builder()
            .setMediaId("video-123")
            .build()

        val metadata = MediaMetadata.Builder()
            .setTitle("Updated Title")
            .setArtist("Channel Name")
            .build()

        val updatedItem = originalItem.buildUpon()
            .setMediaMetadata(metadata)
            .build()

        assertEquals("video-123", updatedItem.mediaId)
        assertEquals("Updated Title", updatedItem.mediaMetadata.title)
        assertEquals("Channel Name", updatedItem.mediaMetadata.artist)
    }

    @Test
    fun `MediaItem buildUpon preserves mediaId when updating metadata`() {
        // This tests the pattern used in updatePlayerMediaItem()
        val mediaId = "unique-stream-id"
        val originalItem = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri("https://example.com/video.mp4")
            .build()

        // First update: title only
        val metadata1 = MediaMetadata.Builder()
            .setTitle("Initial Title")
            .build()
        val update1 = originalItem.buildUpon()
            .setMediaMetadata(metadata1)
            .build()

        // Second update: full metadata with artwork
        val artworkBytes = byteArrayOf(1, 2, 3, 4)
        val metadata2 = MediaMetadata.Builder()
            .setTitle("Initial Title")
            .setArtist("Channel Name")
            .setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        val update2 = update1.buildUpon()
            .setMediaMetadata(metadata2)
            .build()

        // Verify mediaId is preserved through multiple updates
        assertEquals(mediaId, update1.mediaId)
        assertEquals(mediaId, update2.mediaId)
        // Verify metadata was updated
        assertEquals("Initial Title", update2.mediaMetadata.title)
        assertEquals("Channel Name", update2.mediaMetadata.artist)
        assertTrue(update2.mediaMetadata.artworkData?.size == 4)
    }

    // endregion
}
