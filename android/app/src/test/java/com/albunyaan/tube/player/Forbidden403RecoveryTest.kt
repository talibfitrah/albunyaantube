package com.albunyaan.tube.player

import com.albunyaan.tube.player.Forbidden403Step.EXHAUSTED
import com.albunyaan.tube.player.Forbidden403Step.PLAY_MUXED
import com.albunyaan.tube.player.Forbidden403Step.REFRESH
import org.junit.Assert.assertEquals
import org.junit.Test

class Forbidden403RecoveryTest {
    @Test fun `first 403 refreshes`() = assertEquals(REFRESH, next403Step(0, 2, playingMuxed = false, hasMuxed = true))
    @Test fun `repeat 403 on adaptive plays muxed`() = assertEquals(PLAY_MUXED, next403Step(1, 2, false, true))
    @Test fun `repeat 403 at the budget edge still plays muxed before exhausting`() = assertEquals(PLAY_MUXED, next403Step(2, 2, false, true))
    @Test fun `403 while muxed refreshes then exhausts`() { assertEquals(REFRESH, next403Step(1, 2, true, true)); assertEquals(EXHAUSTED, next403Step(2, 2, true, true)) }
    @Test fun `no muxed track keeps the refresh ladder`() { assertEquals(REFRESH, next403Step(1, 2, false, false)); assertEquals(EXHAUSTED, next403Step(2, 2, false, false)) }
}
