package com.albunyaan.tube.ui.player

import com.albunyaan.tube.data.source.api.LiveItem

/**
 * Spec §9.1 and D12: what to do after a live-channel trigger, given the backend's current lives (null = no answer).
 * Pure; PlayerViewModel does the I/O and the waiting. One instance per incident; [next] counts the waits.
 */
class LiveChannelRecovery(val currentId: String, private val currentTitle: String?, private val startedAtMs: Long) {

    enum class Trigger { FAILURE, ENDED }

    sealed interface Step {
        data class SwitchTo(val id: String, val title: String) : Step
        object SameId : Step
        data class Wait(val seconds: Int) : Step
        object GiveUp : Step
    }

    private var waits = 0
    /** Spec Rev 8: an incident that began with, or met, an end or a not-live resolve never re-resolves the same id. */
    private var ended = false
    /**
     * Spec Rev 8: [waits] at the last same-id try (-1: none). One try per step-4 wake, so the 15 → 120 s pacing bounds
     * them and a failed re-resolve never retries at once at the AUTO_RECOVERY pace (Rev 6).
     */
    private var sameIdAtWait = -1

    fun next(trigger: Trigger, lives: List<LiveItem>?, nowMs: Long): Step {
        if (trigger == Trigger.ENDED) ended = true
        val step = decide(lives, nowMs)
        // Stage 4 S2: the id goes into a thumbnail URL and the extractor's watch URL; anything but a video id is no answer.
        return if (step is Step.SwitchTo && !VIDEO_ID.matches(step.id)) decide(null, nowMs) else step
    }

    private fun decide(lives: List<LiveItem>?, nowMs: Long): Step {
        val elapsed = nowMs - startedAtMs
        var deadline = GIVE_UP_MS
        if (!lives.isNullOrEmpty() && lives.none { it.id == currentId }) {
            if (lives.size == 1) return Step.SwitchTo(lives[0].id, lives[0].title)
            // A blank title (the backend sends "" for none) identifies no show (stage 3 M4).
            lives.firstOrNull { it.title == currentTitle?.ifBlank { null } }?.let { return Step.SwitchTo(it.id, it.title) }
            if (elapsed >= TITLE_WAIT_MS) return Step.SwitchTo(lives[0].id, lives[0].title)
            deadline = TITLE_WAIT_MS
        } else if (!ended && sameIdAtWait != waits && elapsed < GIVE_UP_MS &&
            (lives?.any { it.id == currentId } ?: (sameIdAtWait < 0))
        ) {
            // Listed: once per wake. No answer: the incident's first try only, Rev 5 (the gate fails open; the live
            // check keeps recordings out).
            sameIdAtWait = waits
            return Step.SameId
        }
        if (elapsed >= GIVE_UP_MS) return Step.GiveUp
        // A wait ends by the next deadline: the D12 title wait at 2 min, the give-up at 10 min.
        val untilDeadline = ((deadline - elapsed + 999) / 1000).toInt()
        return Step.Wait(minOf(BACKOFF_S[minOf(waits++, BACKOFF_S.lastIndex)], untilDeadline))
    }

    companion object {
        private val BACKOFF_S = listOf(15, 30, 60, 120)
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        /** D12: a multi-live channel gets up to 2 min for the ended show to restart under its title. */
        const val TITLE_WAIT_MS = 2 * 60_000L
        const val GIVE_UP_MS = 10 * 60_000L
    }
}
