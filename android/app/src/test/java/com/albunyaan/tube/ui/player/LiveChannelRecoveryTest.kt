package com.albunyaan.tube.ui.player

import com.albunyaan.tube.data.source.api.LiveItem
import com.albunyaan.tube.ui.player.LiveChannelRecovery.Step
import com.albunyaan.tube.ui.player.LiveChannelRecovery.Trigger
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec §9.1 and D12: the decision after a live-channel trigger. */
class LiveChannelRecoveryTest {

    private val t0 = 1_000_000L
    private fun at(seconds: Int) = t0 + seconds * 1000L
    private fun recovery() = LiveChannelRecovery("old0000000a", "Laith & Layla", t0)

    @Test fun `a single new live is taken at once`() {
        assertEquals(Step.SwitchTo("new0000000a", "Other title"),
            recovery().next(Trigger.ENDED, listOf(LiveItem("new0000000a", "Other title")), at(0)))
    }

    /** Review Focus 4 / RV M2: siblings stay live while the ended show restarts; wait 2 min for the same title. */
    @Test fun `sibling rotation waits for the same title then takes the first`() {
        val siblings = listOf(LiveItem("kazwa000001", "Kazwa & Bilal"), LiveItem("zaky0000001", "A Day With Zaky"))
        val r = recovery()

        assertEquals(Step.Wait(15), r.next(Trigger.ENDED, siblings, at(0)))
        assertEquals(Step.SwitchTo("new0000000a", "Laith & Layla"),
            recovery().next(Trigger.ENDED, siblings + LiveItem("new0000000a", "Laith & Layla"), at(20)))
        assertEquals(Step.Wait(30), r.next(Trigger.ENDED, siblings, at(60)))
        assertEquals(Step.SwitchTo("kazwa000001", "Kazwa & Bilal"), r.next(Trigger.ENDED, siblings, at(120)))
    }

    @Test fun `the same id after a failure runs today's same-id recovery`() {
        assertEquals(Step.SameId, recovery().next(Trigger.FAILURE, listOf(LiveItem("old0000000a", "x")), at(0)))
    }

    /** Never re-resolve an ended id in a loop. */
    @Test fun `the same id after an end waits instead`() {
        assertEquals(Step.Wait(15), recovery().next(Trigger.ENDED, listOf(LiveItem("old0000000a", "x")), at(0)))
    }

    @Test fun `no answer or no lives backs off 15 30 60 120 then gives up after ten minutes`() {
        val r = recovery()
        assertEquals(Step.Wait(15), r.next(Trigger.ENDED, null, at(0)))
        assertEquals(Step.Wait(30), r.next(Trigger.ENDED, emptyList(), at(15)))
        assertEquals(Step.Wait(60), r.next(Trigger.ENDED, null, at(45)))
        assertEquals(Step.Wait(120), r.next(Trigger.ENDED, null, at(105)))
        assertEquals(Step.Wait(120), r.next(Trigger.ENDED, null, at(225)))
        assertEquals(Step.GiveUp, r.next(Trigger.ENDED, null, at(600)))
    }
}
