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

    fun next(trigger: Trigger, lives: List<LiveItem>?, nowMs: Long): Step {
        val elapsed = nowMs - startedAtMs
        if (!lives.isNullOrEmpty() && lives.none { it.id == currentId }) {
            if (lives.size == 1) return Step.SwitchTo(lives[0].id, lives[0].title)
            lives.firstOrNull { it.title == currentTitle }?.let { return Step.SwitchTo(it.id, it.title) }
            if (elapsed >= TITLE_WAIT_MS) return Step.SwitchTo(lives[0].id, lives[0].title)
        } else if (trigger == Trigger.FAILURE && lives?.any { it.id == currentId } == true) {
            return Step.SameId
        }
        if (elapsed >= GIVE_UP_MS) return Step.GiveUp
        return Step.Wait(BACKOFF_S[minOf(waits++, BACKOFF_S.lastIndex)])
    }

    companion object {
        val BACKOFF_S = listOf(15, 30, 60, 120)
        /** D12: a multi-live channel gets up to 2 min for the ended show to restart under its title. */
        const val TITLE_WAIT_MS = 2 * 60_000L
        const val GIVE_UP_MS = 10 * 60_000L
    }
}
