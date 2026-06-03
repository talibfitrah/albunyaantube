package com.albunyaan.tube.player

import com.albunyaan.tube.player.ColdStartQualityChooser.NetworkType
import com.albunyaan.tube.player.ColdStartQualityChooser.ScreenClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the cellular playback-stall fix (ANDROID-PLAYBACK-01).
 *
 * Roots out the "plays 2-3s then stalls on 4G" loop: the cold-start picked 720p on any
 * LTE link (because linkDownstreamBandwidthKbps is a technology estimate, not throughput)
 * and nothing capped adaptive ABR. These tests pin the conservative cellular start and the
 * hard cellular ceiling. They fail against the pre-fix values (720p start, no ceiling).
 */
class ColdStartQualityPolicyTest {

    private val chooser = ColdStartQualityChooser()

    // --- S1: conservative cellular initial quality ---

    @Test
    fun `phone on fast cellular starts at 480p, not 720p`() {
        assertEquals(
            ColdStartQualityChooser.QUALITY_LOW,
            chooser.recommendedHeightFor(NetworkType.CELLULAR_FAST, ScreenClass.PHONE)
        )
    }

    @Test
    fun `tablet on fast cellular starts at 720p`() {
        assertEquals(
            ColdStartQualityChooser.QUALITY_MEDIUM,
            chooser.recommendedHeightFor(NetworkType.CELLULAR_FAST, ScreenClass.TABLET)
        )
    }

    @Test
    fun `slow and metered cellular start at 480p on phone`() {
        assertEquals(
            ColdStartQualityChooser.QUALITY_LOW,
            chooser.recommendedHeightFor(NetworkType.CELLULAR_SLOW, ScreenClass.PHONE)
        )
        assertEquals(
            ColdStartQualityChooser.QUALITY_LOW,
            chooser.recommendedHeightFor(NetworkType.METERED, ScreenClass.PHONE)
        )
    }

    @Test
    fun `wifi behavior is preserved - phone still starts at 1080p`() {
        assertEquals(
            ColdStartQualityChooser.QUALITY_HIGH,
            chooser.recommendedHeightFor(NetworkType.WIFI, ScreenClass.PHONE)
        )
    }

    @Test
    fun `wifi large tablet still aims for 4K`() {
        assertEquals(
            ColdStartQualityChooser.QUALITY_ULTRA,
            chooser.recommendedHeightFor(NetworkType.WIFI, ScreenClass.LARGE_TABLET_TV)
        )
    }

    // --- S2: hard cellular ceiling ---

    @Test
    fun `fast cellular ceiling caps at 720p and 2,5 Mbps`() {
        val ceiling = chooser.ceilingFor(NetworkType.CELLULAR_FAST)
        assertEquals(
            ColdStartQualityChooser.Ceiling(
                ColdStartQualityChooser.QUALITY_MEDIUM,
                ColdStartQualityChooser.CELLULAR_FAST_MAX_BITRATE_BPS
            ),
            ceiling
        )
    }

    @Test
    fun `slow and metered cellular ceiling caps at 480p and 1,2 Mbps`() {
        val expected = ColdStartQualityChooser.Ceiling(
            ColdStartQualityChooser.QUALITY_LOW,
            ColdStartQualityChooser.CELLULAR_SLOW_MAX_BITRATE_BPS
        )
        assertEquals(expected, chooser.ceilingFor(NetworkType.CELLULAR_SLOW))
        assertEquals(expected, chooser.ceilingFor(NetworkType.METERED))
    }

    @Test
    fun `wifi has no ceiling - ABR runs free`() {
        assertNull(chooser.ceilingFor(NetworkType.WIFI))
    }

    @Test
    fun `offline has no ceiling`() {
        assertNull(chooser.ceilingFor(NetworkType.OFFLINE))
    }

    @Test
    fun `cellular ceiling never exceeds the cellular cold-start headroom`() {
        // The ceiling must be >= the initial pick so ABR has room to settle, but still bounded.
        val fastCeiling = chooser.ceilingFor(NetworkType.CELLULAR_FAST)!!
        val phoneStart = chooser.recommendedHeightFor(NetworkType.CELLULAR_FAST, ScreenClass.PHONE)
        assertTrue(
            "Ceiling ${fastCeiling.maxHeight}p must be >= phone start ${phoneStart}p",
            fastCeiling.maxHeight >= phoneStart
        )
        assertTrue(
            "Fast-cellular ceiling must stay below 1080p",
            fastCeiling.maxHeight < ColdStartQualityChooser.QUALITY_HIGH
        )
    }
}
