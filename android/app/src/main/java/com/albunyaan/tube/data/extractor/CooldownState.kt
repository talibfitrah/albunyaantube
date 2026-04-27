package com.albunyaan.tube.data.extractor

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.albunyaan.tube.data.me.MeRefreshTelemetry
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
 *
 * T12 (spec §10 P10): emits [MeRefreshTelemetry.Event.CooldownTripped]
 * after each [trip] commit, and [MeRefreshTelemetry.Event.CooldownCleared]
 * after [markCleanFetch] when (and only when) the 7-day reset branch ran.
 * Operator visibility into rate-limit storms.
 */
@Singleton
class CooldownState @VisibleForTesting internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val now: () -> Long,
    private val telemetry: MeRefreshTelemetry,
) {
    @Inject
    constructor(
        @Named("cooldownDataStore") dataStore: DataStore<Preferences>,
        telemetry: MeRefreshTelemetry,
    ) : this(dataStore, { System.currentTimeMillis() }, telemetry)

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
    suspend fun trip(reason: Throwable, currentMs: Long = now()) {
        var newTripCount: Int = 0
        var newUntilMs: Long = 0L
        dataStore.edit { prefs ->
            val lastTrip = prefs[Keys.LAST_TRIP_MS] ?: 0L
            val withinWindow = currentMs - lastTrip < tripWindowMs
            val tripCount = if (withinWindow) (prefs[Keys.TRIP_COUNT] ?: 0) + 1 else 1
            val durationIdx = (tripCount - 1).coerceAtMost(durations.size - 1)
            val untilMs = currentMs + durations[durationIdx]
            prefs[Keys.UNTIL_MS] = untilMs
            prefs[Keys.TRIP_COUNT] = tripCount
            prefs[Keys.LAST_TRIP_MS] = currentMs
            // Anchor the clean-streak window at the trip; markCleanFetch
            // measures elapsed time from here.
            prefs[Keys.CLEAN_STREAK_START_MS] = currentMs
            newTripCount = tripCount
            newUntilMs = untilMs
        }
        // T12: emit AFTER the DataStore commit so a subsequent snapshot
        // observer who reads `untilMs()` sees the same value the event
        // reports.
        telemetry.emit(
            MeRefreshTelemetry.Event.CooldownTripped(
                timestampMs = currentMs,
                reason = reason.message ?: reason::class.java.simpleName,
                tripCount24h = newTripCount,
                untilMs = newUntilMs,
            )
        )
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
        var didReset = false
        dataStore.edit { prefs ->
            val streakStart = prefs[Keys.CLEAN_STREAK_START_MS] ?: return@edit
            if (currentMs - streakStart >= cleanResetWindowMs) {
                prefs[Keys.TRIP_COUNT] = 0
                prefs[Keys.CLEAN_STREAK_START_MS] = currentMs
                didReset = true
            }
        }
        // T12: only emit on the actual reset branch. The no-op path is
        // expected on every healthy fetch and would flood the ring buffer
        // with non-events.
        if (didReset) {
            telemetry.emit(
                MeRefreshTelemetry.Event.CooldownCleared(timestampMs = currentMs)
            )
        }
    }

    /**
     * T12 / dev-settings: wipe every cooldown key so the next [trip] starts
     * at the 1-hour first-trip rung. Used by the dev-settings "Reset
     * cooldown" affordance and never called from production code paths.
     *
     * No telemetry event — the dialog itself toasts confirmation, and
     * conflating "operator wiped state" with the natural 7-day clean-streak
     * reset would be misleading.
     */
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.UNTIL_MS)
            prefs.remove(Keys.TRIP_COUNT)
            prefs.remove(Keys.LAST_TRIP_MS)
            prefs.remove(Keys.CLEAN_STREAK_START_MS)
        }
    }
}
