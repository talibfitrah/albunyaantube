package com.albunyaan.tube.data.me

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * ANDROID-PERSONAL-02 / T12: in-process telemetry for the Me-feed refresh
 * pipeline (spec §10 P10).
 *
 * Two surfaces:
 *  - A capped ring buffer ([snapshot]) so the dev-settings dialog can show
 *    the most recent [MAX_EVENTS] events without subscribing to the flow.
 *  - A hot [events] flow ([SharedFlow]) so future tooling (overlays, log
 *    sinks, automated tests) can react to events as they happen.
 *
 * Threading: the ring is guarded by a `synchronized(ring)` block — emit
 * sites can come from any thread (worker, repo IO, downloader callbacks)
 * and the buffer is small. A coroutine [Mutex] would be heavier without
 * benefit. The [SharedFlow] uses [BufferOverflow.DROP_OLDEST] so a slow
 * subscriber can never stall an emit caller — telemetry is fire-and-forget.
 *
 * The buffer lives in-process only — restarts wipe it. That's intentional;
 * production telemetry will be added separately if needed.
 */
@Singleton
class MeRefreshTelemetry @Inject constructor() {

    sealed class Event {
        abstract val timestampMs: Long

        /** Worker started a tick — emit at the very top of [doWork]. */
        data class MeRefreshStarted(
            override val timestampMs: Long,
            val mode: Mode,
            val candidatesCount: Int,
        ) : Event()

        /** Worker finished a tick (success or failure) — emit in finally. */
        data class MeRefreshFinished(
            override val timestampMs: Long,
            val mode: Mode,
            val success: Boolean,
            val durationMs: Long,
            val error: String?,
        ) : Event()

        /** Repository finished a single channel's refreshOne call. */
        data class MeChannelFetched(
            override val timestampMs: Long,
            val channelId: String,
            val itemsCount: Int,
            val latencyMs: Long,
            val outcome: ChannelOutcome,
        ) : Event()

        /** CooldownState.trip() has armed a new cooldown window. */
        data class CooldownTripped(
            override val timestampMs: Long,
            val reason: String,
            val tripCount24h: Int,
            val untilMs: Long,
        ) : Event()

        /**
         * CooldownState.markCleanFetch() actually reset the trip count
         * (i.e. the 7-day clean-streak branch ran — NOT emitted on the
         * no-op path).
         */
        data class CooldownCleared(
            override val timestampMs: Long,
        ) : Event()
    }

    /** Whether the worker tick is the periodic budget or pull-to-refresh. */
    enum class Mode { PERIODIC, PULL }

    /**
     * Outcome buckets for [Event.MeChannelFetched]. Correspond 1:1 to the
     * branches in [MeFeedRepository.refreshOne]:
     *  - [NEW_ITEMS] — non-empty Items result, cache replaced.
     *  - [NOT_MODIFIED] — server returned 304.
     *  - [EMPTY_PROTECTED] — empty result inside FEED_WINDOW protection (cache preserved).
     *  - [EMPTY_REAL] — empty result outside protection (cache wiped).
     *  - [ERROR_BACKOFF] — error message matched 429/5xx ladder.
     *  - [ERROR_NEUTRAL] — other throwable; backoff preserved as-is.
     *  - [TIMEOUT] — withTimeout fired; counters preserved.
     *  - [FRESHNESS_SKIPPED] — TTL gate short-circuited (no fetcher call).
     *  - [BACKOFF_SKIPPED] — backoff gate short-circuited (no fetcher call).
     *  - [FORCE_BYPASSED] — should not be observed at completion (reserved
     *    for future use; included for completeness of the enum).
     */
    enum class ChannelOutcome {
        NEW_ITEMS,
        NOT_MODIFIED,
        EMPTY_PROTECTED,
        EMPTY_REAL,
        ERROR_BACKOFF,
        ERROR_NEUTRAL,
        TIMEOUT,
        FRESHNESS_SKIPPED,
        BACKOFF_SKIPPED,
        FORCE_BYPASSED,
    }

    private val ring: ArrayDeque<Event> = ArrayDeque(MAX_EVENTS)

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Hot flow; collectors only see events emitted after they subscribe. */
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * Append [event] to the ring buffer and (best-effort) emit it on the
     * shared flow. Safe to call from any thread.
     */
    fun emit(event: Event) {
        synchronized(ring) {
            ring.addLast(event)
            while (ring.size > MAX_EVENTS) {
                ring.removeFirst()
            }
        }
        _events.tryEmit(event)
    }

    /**
     * Defensive copy of the ring buffer in arrival order (oldest first).
     * The dev-settings dialog calls this on demand — collectors that want
     * a live stream should use [events] instead.
     */
    fun snapshot(): List<Event> = synchronized(ring) { ring.toList() }

    /**
     * Drop every event from the ring. Wired up to the dev-settings
     * "clear log" affordance; production code should not call this.
     */
    fun clear() {
        synchronized(ring) { ring.clear() }
    }

    companion object {
        const val MAX_EVENTS: Int = 100
    }
}
