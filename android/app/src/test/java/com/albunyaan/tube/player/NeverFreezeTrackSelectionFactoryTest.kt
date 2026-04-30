package com.albunyaan.tube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(UnstableApi::class)
class NeverFreezeTrackSelectionFactoryTest {

    @Test
    fun `create returns non-null factory`() {
        val factory = NeverFreezeTrackSelectionFactory()
        assertNotNull(factory.create())
    }

    @Test
    fun `created factory is AdaptiveTrackSelection factory`() {
        val factory = NeverFreezeTrackSelectionFactory()
        assertTrue(factory.create() is AdaptiveTrackSelection.Factory)
    }
}
