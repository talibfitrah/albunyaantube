package com.albunyaan.tube.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2B: Custom DataSource for synthetic DASH MPD transport.
 *
 * This DataSource handles the "syntheticdash://" URI scheme, serving pre-generated
 * MPD content directly from memory without network calls.
 *
 * **Why a custom scheme?**
 * - data: URIs can be problematic with large manifests (URL length limits)
 * - Custom scheme gives us control over caching and refresh
 * - Cleaner separation between MPD generation and playback
 *
 * **URI format:**
 * `syntheticdash://{videoId}`
 *
 * **Usage:**
 * 1. Register MPD content: `SyntheticDashMpdRegistry.register(videoId, mpdXml)`
 * 2. Create MediaItem with URI: `syntheticdash://{videoId}`
 * 3. Use SyntheticDashDataSource.Factory with DashMediaSource
 * 4. Clean up: `SyntheticDashMpdRegistry.unregister(videoId)` after playback
 */
@OptIn(UnstableApi::class)
class SyntheticDashDataSource(
    private val mpdRegistry: SyntheticDashMpdRegistry
) : BaseDataSource(/* isNetwork= */ false) {

    companion object {
        private const val TAG = "SyntheticDashDS"

        /** URI scheme for synthetic DASH manifests */
        const val SCHEME = "syntheticdash"
    }

    private var mpdBytes: ByteArray? = null
    private var readPosition: Int = 0
    private var currentVideoId: String? = null
    private var currentDataSpec: DataSpec? = null

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri

        // Notify listeners that transfer is being initialized
        transferInitializing(dataSpec)

        if (uri.scheme != SCHEME) {
            throw IOException("Unsupported scheme: ${uri.scheme}")
        }

        val videoId = uri.host ?: uri.path?.removePrefix("/")
            ?: throw IOException("Invalid syntheticdash URI: missing videoId")

        val mpdContent = mpdRegistry.getMpd(videoId)
            ?: throw IOException("No MPD registered for videoId: $videoId")

        mpdBytes = mpdContent.toByteArray(Charsets.UTF_8)
        val bytes = mpdBytes!!

        // Validate position is within Int range and bounds
        val position = dataSpec.position
        if (position < 0) {
            throw IOException("Invalid negative position: $position")
        }
        if (position > Int.MAX_VALUE) {
            throw IOException("Position out of range: $position exceeds Int.MAX_VALUE")
        }
        if (position > bytes.size) {
            throw IOException("Position $position exceeds content size ${bytes.size}")
        }

        readPosition = position.toInt()
        currentVideoId = videoId
        currentDataSpec = dataSpec

        // Notify listeners that transfer has started
        transferStarted(dataSpec)

        Log.d(TAG, "Opened syntheticdash://$videoId (${bytes.size} bytes, pos=$readPosition)")

        // Return remaining bytes from position, respecting dataSpec.length if set
        val remainingBytes = bytes.size - readPosition
        return if (dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length >= 0) {
            minOf(dataSpec.length, remainingBytes.toLong())
        } else {
            remainingBytes.toLong()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytes = mpdBytes ?: return C.RESULT_END_OF_INPUT

        if (readPosition >= bytes.size) {
            return C.RESULT_END_OF_INPUT
        }

        val bytesToRead = minOf(length, bytes.size - readPosition)
        System.arraycopy(bytes, readPosition, buffer, offset, bytesToRead)
        readPosition += bytesToRead

        // Notify listeners of bytes transferred (for bandwidth estimation)
        bytesTransferred(bytesToRead)

        return bytesToRead
    }

    override fun getUri(): Uri? {
        return currentVideoId?.let { Uri.parse("$SCHEME://$it") }
    }

    override fun close() {
        // Notify listeners that transfer has ended (only if we had an active transfer)
        if (currentDataSpec != null) {
            transferEnded()
        }
        mpdBytes = null
        readPosition = 0
        currentVideoId = null
        currentDataSpec = null
    }

    /**
     * Factory for creating SyntheticDashDataSource instances.
     */
    class Factory(
        private val mpdRegistry: SyntheticDashMpdRegistry
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return SyntheticDashDataSource(mpdRegistry)
        }
    }
}

/**
 * Registry for storing generated MPD content with metadata.
 *
 * Thread-safe storage for MPD XML content and associated metadata, keyed by videoId.
 * MPDs are stored in memory and should be cleaned up after playback.
 *
 * **Phase 5 Enhancement:**
 * Now stores metadata (videoTracks, audioTrack, codecFamily) alongside MPD XML.
 * This enables true cache hits where pre-generated MPDs are reused without
 * re-running the generator just to get metadata.
 *
 * **Lifecycle:**
 * - Register MPD when creating multi-rep synthetic DASH source
 * - Unregister when player is released or video changes
 * - Clear all on app background/destroy
 */
@Singleton
class SyntheticDashMpdRegistry @Inject constructor() {

    companion object {
        private const val TAG = "SyntheticDashRegistry"

        /**
         * Maximum number of MPDs to keep in memory.
         * Prevents unbounded memory growth from playlist navigation.
         */
        private const val MAX_ENTRIES = 5

        /**
         * TTL for cached MPD entries in milliseconds.
         * Signed URLs typically expire in 6 hours, but we use a conservative 2 minutes
         * to avoid serving stale URLs that may fail during playback.
         */
        const val MPD_TTL_MS = 2 * 60 * 1000L // 2 minutes
    }

    /**
     * Entry storing MPD content with metadata.
     * Phase 5: Now includes video/audio track metadata for true cache hits.
     * Note: registeredAtMs is provided by caller to allow test clock injection.
     */
    data class MpdEntry(
        val videoId: String,
        val mpdXml: String,
        /** Video tracks included in the MPD (ordered by height desc). Null for legacy registrations. */
        val videoTracks: List<com.albunyaan.tube.data.extractor.VideoTrack>? = null,
        /** Audio track included in the MPD. Null for legacy registrations. */
        val audioTrack: com.albunyaan.tube.data.extractor.AudioTrack? = null,
        /** Codec family used (e.g., "H264", "VP9"). Null for legacy registrations. */
        val codecFamily: String? = null,
        val registeredAtMs: Long
    ) {
        /**
         * Check if this entry has full metadata (Phase 5 registration).
         * Legacy registrations only have mpdXml.
         */
        fun hasMetadata(): Boolean = videoTracks != null && audioTrack != null && codecFamily != null
    }

    private val mpdStore = ConcurrentHashMap<String, MpdEntry>()

    // Clock for testing - uses monotonic time (elapsedRealtime) to avoid NTP/user clock issues
    @Volatile
    private var clock: () -> Long = { SystemClock.elapsedRealtime() }

    @androidx.annotation.VisibleForTesting
    fun setTestClock(testClock: () -> Long) {
        clock = testClock
    }

    /** Lock for atomic eviction operations */
    private val registrationLock = Any()

    /**
     * Register MPD content for a videoId (legacy method - no metadata).
     * If max entries reached, oldest entries are evicted.
     *
     * @param videoId The video ID (used as URI host). Must not be blank.
     * @param mpdXml The raw MPD XML content. Must not be empty.
     * @throws IllegalArgumentException if videoId is blank or mpdXml is empty
     */
    fun register(videoId: String, mpdXml: String) {
        require(videoId.isNotBlank()) { "videoId cannot be blank" }
        require(mpdXml.isNotEmpty()) { "mpdXml cannot be empty" }

        synchronized(registrationLock) {
            // Evict oldest entries if at capacity
            while (mpdStore.size >= MAX_ENTRIES && !mpdStore.containsKey(videoId)) {
                evictOldest()
            }
            mpdStore[videoId] = MpdEntry(
                videoId = videoId,
                mpdXml = mpdXml,
                registeredAtMs = clock()
            )
        }
        Log.d(TAG, "Registered MPD for $videoId (${mpdXml.length} chars, total=${mpdStore.size})")
    }

    /**
     * Register MPD content with full metadata (Phase 5).
     * Enables true cache hits without re-running the generator.
     *
     * @param videoId The video ID (used as URI host). Must not be blank.
     * @param mpdXml The raw MPD XML content. Must not be empty.
     * @param videoTracks Video tracks included in the MPD (ordered by height desc).
     * @param audioTrack Audio track included in the MPD.
     * @param codecFamily Codec family used (e.g., "H264", "VP9").
     * @throws IllegalArgumentException if videoId is blank or mpdXml is empty
     */
    fun registerWithMetadata(
        videoId: String,
        mpdXml: String,
        videoTracks: List<com.albunyaan.tube.data.extractor.VideoTrack>,
        audioTrack: com.albunyaan.tube.data.extractor.AudioTrack,
        codecFamily: String
    ) {
        require(videoId.isNotBlank()) { "videoId cannot be blank" }
        require(mpdXml.isNotEmpty()) { "mpdXml cannot be empty" }

        synchronized(registrationLock) {
            // Evict oldest entries if at capacity
            while (mpdStore.size >= MAX_ENTRIES && !mpdStore.containsKey(videoId)) {
                evictOldest()
            }
            mpdStore[videoId] = MpdEntry(
                videoId = videoId,
                mpdXml = mpdXml,
                videoTracks = videoTracks,
                audioTrack = audioTrack,
                codecFamily = codecFamily,
                registeredAtMs = clock()
            )
        }
        Log.d(TAG, "Registered MPD+metadata for $videoId (${mpdXml.length} chars, ${videoTracks.size} reps, $codecFamily, total=${mpdStore.size})")
    }

    /**
     * Get MPD content for a videoId.
     * Returns null if not registered.
     */
    fun getMpd(videoId: String): String? {
        return mpdStore[videoId]?.mpdXml
    }

    /**
     * Get full MpdEntry for a videoId (Phase 5).
     * Returns null if not registered.
     */
    fun getEntry(videoId: String): MpdEntry? {
        return mpdStore[videoId]
    }

    /**
     * Check if MPD is registered for a videoId.
     */
    fun isRegistered(videoId: String): Boolean {
        return mpdStore.containsKey(videoId)
    }

    /**
     * Check if MPD with full metadata is registered for a videoId (Phase 5).
     * Returns true only if both MPD and metadata are available.
     *
     * NOTE: This does not check freshness. Use [isFreshWithMetadata] for cache hit decisions.
     */
    fun isRegisteredWithMetadata(videoId: String): Boolean {
        return mpdStore[videoId]?.hasMetadata() == true
    }

    /**
     * Check if a fresh MPD with full metadata is registered for a videoId.
     * Returns true only if MPD exists, has metadata, AND is within TTL.
     *
     * This is the safe method to use for cache hit decisions to avoid
     * serving stale signed URLs.
     *
     * @param videoId The video ID to check
     * @return true if MPD is fresh and has full metadata, false otherwise
     */
    fun isFreshWithMetadata(videoId: String): Boolean {
        val entry = mpdStore[videoId] ?: return false
        if (!entry.hasMetadata()) return false
        val age = clock() - entry.registeredAtMs
        return age <= MPD_TTL_MS
    }

    /**
     * Get fresh entry for a videoId, or null if stale/missing.
     *
     * @param videoId The video ID
     * @return Entry if fresh and has metadata, null otherwise
     */
    fun getFreshEntry(videoId: String): MpdEntry? {
        val entry = mpdStore[videoId] ?: return null
        if (!entry.hasMetadata()) return null
        val age = clock() - entry.registeredAtMs
        return if (age <= MPD_TTL_MS) entry else null
    }

    /**
     * Unregister MPD for a videoId.
     * Call when playback completes or player is released.
     */
    fun unregister(videoId: String) {
        synchronized(registrationLock) {
            mpdStore.remove(videoId)?.let {
                Log.d(TAG, "Unregistered MPD for $videoId (total=${mpdStore.size})")
            }
        }
    }

    /**
     * Clear all registered MPDs.
     * Call on app background or destroy.
     */
    fun clearAll() {
        synchronized(registrationLock) {
            val count = mpdStore.size
            mpdStore.clear()
            if (count > 0) {
                Log.d(TAG, "Cleared $count MPD entries")
            }
        }
    }

    /**
     * Get count of registered MPDs (for debugging/metrics).
     */
    fun getRegisteredCount(): Int = mpdStore.size

    /**
     * Evict the oldest entry.
     */
    private fun evictOldest() {
        val oldest = mpdStore.entries.minByOrNull { it.value.registeredAtMs }
        oldest?.let {
            mpdStore.remove(it.key)
            Log.d(TAG, "Evicted oldest MPD: ${it.key}")
        }
    }
}
