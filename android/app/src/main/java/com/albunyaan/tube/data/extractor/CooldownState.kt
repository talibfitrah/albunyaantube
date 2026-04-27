package com.albunyaan.tube.data.extractor

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Tracks the NewPipe-paths cooldown state with exponential escalation
 * (spec §4.6).
 *
 * Trips on ReCaptcha / HTTP 429 detection (in T7's `RateLimitedDownloader`).
 * Player paths bypass entirely per spec §4.6 + D1 — Player NEVER calls
 * [trip] / [isTripped] on this object.
 *
 * Escalation by trip count in the last 24 h:
 *   1st trip → 1 h
 *   2nd trip → 4 h
 *   3rd trip → 12 h
 *   4th+    → 24 h
 *
 * 7 consecutive days without a trip resets the trip count to 0, so the
 * next trip starts back at the 1 h cooldown. The "clean streak" window
 * begins at the most recent trip timestamp (recorded in [trip]); a single
 * call to [markCleanFetch] after 7 days has elapsed is sufficient to
 * trigger the reset.
 *
 * Persistence: DataStore Preferences. Survives app restarts — a fresh
 * [CooldownState] instance pointing at the same [DataStore] sees the
 * previously persisted trip state.
 */
@Singleton
class CooldownState @VisibleForTesting internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val now: () -> Long,
) {
    @Inject
    constructor(
        @Named("cooldownDataStore") dataStore: DataStore<Preferences>,
    ) : this(dataStore, { System.currentTimeMillis() })

    private object Keys {
        val UNTIL_MS = longPreferencesKey("cooldown_until_ms")
        val TRIP_COUNT = intPreferencesKey("cooldown_trip_count_24h")
        val LAST_TRIP_MS = longPreferencesKey("cooldown_last_trip_ms")
        val CLEAN_STREAK_START_MS = longPreferencesKey("cooldown_clean_streak_start_ms")
    }

    private val durations = listOf(1L, 4L, 12L, 24L).map { it * 60L * 60_000L }
    private val cleanResetWindowMs = 7L * 24L * 60L * 60_000L
    private val tripWindowMs = 24L * 60L * 60_000L

    /**
     * Returns `true` if the cooldown is still in effect at [currentMs].
     */
    suspend fun isTripped(currentMs: Long = now()): Boolean {
        val prefs = dataStore.data.first()
        val until = prefs[Keys.UNTIL_MS] ?: return false
        return currentMs < until
    }

    /**
     * Synchronous variant for callers in non-suspending contexts (e.g.
     * NewPipe's blocking `Downloader.execute(...)` — see T7).
     */
    fun isTrippedSync(currentMs: Long = now()): Boolean = runBlocking { isTripped(currentMs) }

    /**
     * Returns the absolute timestamp the cooldown ends at, or `null` if
     * no cooldown has ever been recorded.
     */
    suspend fun untilMs(): Long? = dataStore.data.first()[Keys.UNTIL_MS]

    /**
     * Records a cooldown trip (e.g. ReCaptcha / 429). Increments the
     * 24-hour trip count and writes the new `until` timestamp using the
     * escalation table.
     *
     * Trips outside the 24-hour window reset the trip count to 1 (back
     * to first-trip cooldown).
     *
     * Also stamps `CLEAN_STREAK_START_MS` so a subsequent
     * [markCleanFetch] can determine whether 7 clean days have elapsed.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun trip(reason: Throwable, currentMs: Long = now()) {
        dataStore.edit { prefs ->
            val lastTrip = prefs[Keys.LAST_TRIP_MS] ?: 0L
            val withinWindow = currentMs - lastTrip < tripWindowMs
            val tripCount = if (withinWindow) (prefs[Keys.TRIP_COUNT] ?: 0) + 1 else 1
            val durationIdx = (tripCount - 1).coerceAtMost(durations.size - 1)
            prefs[Keys.UNTIL_MS] = currentMs + durations[durationIdx]
            prefs[Keys.TRIP_COUNT] = tripCount
            prefs[Keys.LAST_TRIP_MS] = currentMs
            // Anchor the clean-streak window at the trip; markCleanFetch
            // measures elapsed time from here.
            prefs[Keys.CLEAN_STREAK_START_MS] = currentMs
        }
    }

    /**
     * Records a successful (non-tripped) fetch. If 7 days have elapsed
     * since the most recent trip (or the most recent reset), clears the
     * 24-hour trip count so the next [trip] starts back at the 1 h
     * cooldown.
     *
     * No-op if no trip has been recorded yet.
     */
    suspend fun markCleanFetch(currentMs: Long = now()) {
        dataStore.edit { prefs ->
            val streakStart = prefs[Keys.CLEAN_STREAK_START_MS] ?: return@edit
            if (currentMs - streakStart >= cleanResetWindowMs) {
                prefs[Keys.TRIP_COUNT] = 0
                prefs[Keys.CLEAN_STREAK_START_MS] = currentMs
            }
        }
    }
}
