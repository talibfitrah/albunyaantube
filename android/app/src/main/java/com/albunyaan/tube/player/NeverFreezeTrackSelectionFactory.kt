package com.albunyaan.tube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class NeverFreezeTrackSelectionFactory @Inject constructor() {

    companion object {
        private const val MIN_DURATION_FOR_QUALITY_INCREASE_MS = 4_000
        private const val MAX_DURATION_FOR_QUALITY_DECREASE_MS = 500
        private const val MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS = 15_000
        private const val BANDWIDTH_FRACTION = 0.65f
    }

    fun create(): ExoTrackSelection.Factory = AdaptiveTrackSelection.Factory(
        MIN_DURATION_FOR_QUALITY_INCREASE_MS,
        MAX_DURATION_FOR_QUALITY_DECREASE_MS,
        MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
        BANDWIDTH_FRACTION
    )
}
