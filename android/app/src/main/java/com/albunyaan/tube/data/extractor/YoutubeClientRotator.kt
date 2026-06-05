package com.albunyaan.tube.data.extractor

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages per-video YouTube client fallback order (IOS → ANDROID).
 * Call [initialClient] to get the first client to try. On failure, call [nextClient]
 * to advance to the next fallback. State is evicted after 30 minutes of inactivity.
 */
@Singleton
class YoutubeClientRotator @Inject constructor() {

    enum class Client { IOS, ANDROID }

    companion object {
        private const val EVICTION_TTL_MS = 30L * 60 * 1000
        private val ROTATION_ORDER = listOf(Client.IOS, Client.ANDROID)
    }

    private data class RotationState(val index: Int, val updatedAtMs: Long)

    private val states = ConcurrentHashMap<String, RotationState>()

    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    fun initialClient(isIosEnabled: Boolean): Client =
        if (isIosEnabled) Client.IOS else Client.ANDROID

    /**
     * Advance to the next client for this video after the initial attempt ([initialClient]) failed.
     * Returns null when all fallback clients are exhausted.
     *
     * Must only be called after [initialClient] has already been attempted for this video —
     * calling it first silently skips the IOS client.
     */
    fun nextClient(videoId: String): Client? {
        evictExpired()
        val now = clock()
        val current = states[videoId]
        val nextIndex = if (current == null) 1 else current.index + 1
        if (nextIndex >= ROTATION_ORDER.size) {
            states.remove(videoId)
            return null
        }
        states[videoId] = RotationState(nextIndex, now)
        return ROTATION_ORDER[nextIndex]
    }

    /**
     * The client to use RIGHT NOW for [videoId]: the armed fallback client if a prior
     * extraction failed and advanced the rotation state (via [nextClient]), otherwise the
     * [initialClient]. Does NOT advance state — a post-seek URL refresh calls this and stays
     * on the same client, so it never downgrades a working IOS adaptive stream to the
     * muxed-only ANDROID client (ANDROID-PLAYBACK-02).
     */
    fun currentClient(videoId: String, isIosEnabled: Boolean): Client {
        evictExpired()
        // nextClient() never stores an index >= ROTATION_ORDER.size (it removes the state
        // at the boundary and returns null), so a stored index is always in range.
        val state = states[videoId] ?: return initialClient(isIosEnabled)
        return ROTATION_ORDER[state.index]
    }

    fun reset(videoId: String) {
        states.remove(videoId)
    }

    private fun evictExpired() {
        val now = clock()
        val expiredKeys = states.entries
            .filter { now - it.value.updatedAtMs > EVICTION_TTL_MS }
            .map { it.key }
        expiredKeys.forEach { states.remove(it) }
    }
}
