package com.albunyaan.tube.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages MediaSession metadata updates including artwork loading.
 *
 * This class is responsible for:
 * - Creating MediaMetadata objects from playback item info
 * - Loading artwork bitmaps asynchronously via Coil
 * - Updating the player's MediaItem with metadata so MediaSession reflects it
 *
 * **Why this exists**: Media3's MediaSessionService automatically publishes
 * notification content from the player's current MediaItem's MediaMetadata.
 * This manager ensures metadata (title, artist, artwork) is set correctly.
 *
 * **Artwork pipeline**: Thumbnail URLs are loaded via Coil into bitmaps,
 * then set on MediaMetadata.artworkData as PNG bytes. This allows the
 * notification to display artwork without requiring a URI content provider.
 */
@Singleton
class MediaSessionMetadataManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val imageLoader = ImageLoader.Builder(context)
        .crossfade(false)  // No animation needed for notification artwork
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkLoadJob: Job? = null

    // Cache the last loaded artwork URL to avoid redundant loads
    private var lastArtworkUrl: String? = null
    private var lastArtworkBytes: ByteArray? = null

    // Stable identity token for the current metadata update request.
    // Used to detect context changes during async artwork loading (safer than index-based checks).
    private var currentMetadataToken: Long = 0

    /**
     * Updates the player's current media item with metadata for notification display.
     *
     * This sets the title, artist (channel name), and queues artwork loading.
     * Once artwork is loaded, the media item is updated again with the bitmap.
     *
     * @param player The ExoPlayer instance
     * @param title Video title
     * @param artist Channel/uploader name
     * @param thumbnailUrl URL of the thumbnail image (optional)
     */
    fun updateMetadata(
        player: Player,
        title: String,
        artist: String,
        thumbnailUrl: String?
    ) {
        Log.d(TAG, "Updating metadata: title='$title', artist='$artist', thumbnail=${thumbnailUrl != null}")

        // ALWAYS cancel any pending artwork load first to prevent stale metadata.
        // This is critical when thumbnailUrl is null - we must not let old artwork apply.
        artworkLoadJob?.cancel()
        artworkLoadJob = null

        // Increment token for this update request (used by async artwork loader)
        val thisToken = ++currentMetadataToken

        // Build initial metadata without artwork (fast path for immediate update)
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setDisplayTitle(title)

        // Set artwork URI for systems that can load from URI directly
        thumbnailUrl?.let { url ->
            metadataBuilder.setArtworkUri(Uri.parse(url))
        }

        // Include cached artwork if URL matches
        if (thumbnailUrl == lastArtworkUrl && lastArtworkBytes != null) {
            metadataBuilder.setArtworkData(lastArtworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            Log.d(TAG, "Using cached artwork for: $thumbnailUrl")
        }

        val initialMetadata = metadataBuilder.build()

        // Update player's current media item with metadata
        updatePlayerMediaItem(player, initialMetadata)

        // Load artwork bitmap asynchronously if URL is new
        if (thumbnailUrl != null && thumbnailUrl != lastArtworkUrl) {
            loadArtworkAsync(player, thumbnailUrl, title, artist, thisToken)
        }
    }

    /**
     * Cancels any pending artwork loading operation.
     * Cached artwork is retained for potential reuse.
     */
    fun cancelArtworkLoading() {
        artworkLoadJob?.cancel()
        artworkLoadJob = null
    }

    /**
     * Releases resources when manager is no longer needed.
     */
    fun release() {
        scope.cancel()
        imageLoader.shutdown()
        lastArtworkUrl = null
        lastArtworkBytes = null
    }

    /**
     * Updates the player's current MediaItem with new metadata.
     *
     * Implementation note: Media3 does not provide a direct API to update MediaMetadata
     * on the currently playing item without replacement. Using replaceMediaItem() is the
     * recommended approach for this use case. The manual position/playWhenReady restoration
     * is necessary because replaceMediaItem may reset these values during the swap.
     * This approach maintains playback continuity while updating notification/lock screen metadata.
     */
    private fun updatePlayerMediaItem(player: Player, metadata: MediaMetadata) {
        val currentItem = player.currentMediaItem ?: return

        // Create new MediaItem with updated metadata
        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(metadata)
            .build()

        // Capture current playback state before replacement
        val currentPosition = player.currentPosition
        val playWhenReady = player.playWhenReady

        // Replace current item - this is the only way to update metadata in Media3
        player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)

        // Restore playback state (replaceMediaItem may reset it)
        if (player.currentPosition != currentPosition) {
            player.seekTo(currentPosition)
        }
        player.playWhenReady = playWhenReady

        Log.d(TAG, "Updated player media item with metadata")
    }

    private fun loadArtworkAsync(
        player: Player,
        thumbnailUrl: String,
        title: String,
        artist: String,
        requestToken: Long
    ) {
        // Note: Job cancellation now happens in updateMetadata() before this is called

        artworkLoadJob = scope.launch {
            try {
                val bitmap = loadBitmap(thumbnailUrl)
                if (bitmap != null) {
                    // Convert bitmap to PNG bytes for MediaMetadata
                    val artworkBytes = withContext(Dispatchers.Default) {
                        bitmapToBytes(bitmap)
                    }

                    if (artworkBytes != null) {
                        // Verify this request is still current using stable token.
                        // This is more reliable than index-based checks for single-item timelines.
                        if (currentMetadataToken != requestToken) {
                            Log.d(TAG, "Metadata context changed during artwork load (token mismatch), skipping update")
                            return@launch
                        }

                        // Cache for reuse
                        lastArtworkUrl = thumbnailUrl
                        lastArtworkBytes = artworkBytes

                        // Build metadata with artwork
                        val metadataWithArt = MediaMetadata.Builder()
                            .setTitle(title)
                            .setArtist(artist)
                            .setDisplayTitle(title)
                            .setArtworkUri(Uri.parse(thumbnailUrl))
                            .setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            .build()

                        // Update player on main thread
                        updatePlayerMediaItem(player, metadataWithArt)
                        Log.d(TAG, "Artwork loaded and set for: $thumbnailUrl")
                    }
                } else {
                    Log.w(TAG, "Failed to load artwork bitmap: $thumbnailUrl")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error loading artwork: ${e.message}")
            }
        }
    }

    private suspend fun loadBitmap(url: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)  // Required for bitmap conversion
                    .size(ARTWORK_SIZE, ARTWORK_SIZE)  // Notification artwork size
                    .build()

                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bitmap load error: ${e.message}")
                null
            }
        }
    }

    private fun bitmapToBytes(bitmap: Bitmap): ByteArray? {
        return try {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap to bytes error: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MediaSessionMetadata"
        private const val ARTWORK_SIZE = 512  // px, suitable for notification and lockscreen
    }
}
