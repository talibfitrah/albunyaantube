package com.albunyaan.tube.player

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton


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
         * Signed URLs are expected to outlive this window, but synthetic manifests
         * still refresh proactively before this TTL to avoid stale embedded URLs.
         */
        const val MPD_TTL_MS = 15 * 60 * 1000L // 15 minutes
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
     * Unregister both the video MPD and the companion audio MPD for a videoId.
     * Use this at all cleanup sites instead of [unregister] to avoid leaking the
     * "${videoId}_audio" companion entry created during audio-MPD registration.
     */
    fun unregisterBoth(videoId: String) {
        unregister(videoId)
        unregister("${videoId}_audio")
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
