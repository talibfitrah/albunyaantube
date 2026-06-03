package com.albunyaan.tube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.albunyaan.tube.data.extractor.QualityConstraintMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies the cellular network ceiling on the track selector (ANDROID-PLAYBACK-01).
 *
 * The ceiling is the engine-level guarantee that adaptive ABR cannot climb into a stall on
 * cellular. These tests pin the composition rules: ceiling bounds AUTO/CAP, a MANUAL pick
 * overrides upward, and WiFi (no ceiling) leaves ABR unbounded exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(UnstableApi::class)
class QualityTrackSelectorCeilingTest {

    private fun newSelector() =
        QualityTrackSelector.createForDiscreteQualities(RuntimeEnvironment.getApplication())

    @Test
    fun `default selector is unbounded`() {
        val s = newSelector()
        assertEquals(Int.MAX_VALUE, s.parameters.maxVideoHeight)
        assertEquals(Int.MAX_VALUE, s.parameters.maxVideoBitrate)
    }

    @Test
    fun `applying a cellular ceiling bounds height and bitrate`() {
        val s = newSelector()
        s.applyNetworkCeiling(720, 2_500_000)
        assertEquals(720, s.parameters.maxVideoHeight)
        assertEquals(2_500_000, s.parameters.maxVideoBitrate)
    }

    @Test
    fun `auto CAP above the ceiling is clamped down to the ceiling`() {
        val s = newSelector()
        s.applyNetworkCeiling(720, 2_500_000)
        s.applyQualityConstraint(1080, QualityConstraintMode.CAP)
        assertEquals("CAP must not exceed the network ceiling", 720, s.parameters.maxVideoHeight)
        assertEquals(2_500_000, s.parameters.maxVideoBitrate)
    }

    @Test
    fun `manual LOCK overrides the ceiling upward`() {
        val s = newSelector()
        s.applyNetworkCeiling(720, 2_500_000)
        s.applyQualityConstraint(1080, QualityConstraintMode.LOCK)
        // Manual pick is an explicit user override — it wins over the cellular ceiling.
        assertEquals(1080, s.parameters.maxVideoHeight)
        assertEquals(Int.MAX_VALUE, s.parameters.maxVideoBitrate)
    }

    @Test
    fun `ceiling does not stomp an active manual lock on network change`() {
        val s = newSelector()
        s.applyQualityConstraint(1080, QualityConstraintMode.LOCK)
        // A network handover re-applies the ceiling, but a manual lock must stay put.
        s.applyNetworkCeiling(480, 1_200_000)
        assertEquals(1080, s.parameters.maxVideoHeight)
    }

    @Test
    fun `selecting auto re-enables the ceiling and clears the manual lock`() {
        val s = newSelector()
        s.applyNetworkCeiling(720, 2_500_000)
        s.applyQualityConstraint(1080, QualityConstraintMode.LOCK) // manual override
        assertEquals(1080, s.parameters.maxVideoHeight)

        s.selectAutoQuality() // back to AUTO -> ceiling governs again
        assertEquals(720, s.parameters.maxVideoHeight)
        assertEquals(2_500_000, s.parameters.maxVideoBitrate)

        // And a later ceiling change now applies (lock was cleared).
        s.applyNetworkCeiling(480, 1_200_000)
        assertEquals(480, s.parameters.maxVideoHeight)
    }

    @Test
    fun `clearing the ceiling restores unbounded ABR (WiFi)`() {
        val s = newSelector()
        s.applyNetworkCeiling(720, 2_500_000)
        s.applyNetworkCeiling(null, null) // moved onto WiFi
        assertEquals(Int.MAX_VALUE, s.parameters.maxVideoHeight)
        assertEquals(Int.MAX_VALUE, s.parameters.maxVideoBitrate)
    }
}
