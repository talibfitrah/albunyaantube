package com.albunyaan.tube.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [audioPresentButUnplayable] — the Android-TV "starts silent" predicate
 * that drives PlayerFragment.maybeRecoverSilentAudio. Verifies it fires only when audio
 * exists but no track is both selected AND supported, and never on audioless media.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class AudioPresentButUnplayableTest {

    private fun audioGroup(supported: Boolean, selected: Boolean): Tracks.Group {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        return Tracks.Group(
            TrackGroup(format),
            /* adaptiveSupported= */ false,
            /* trackSupport= */ intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE),
            /* trackSelected= */ booleanArrayOf(selected),
        )
    }

    private fun videoGroup(): Tracks.Group {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        return Tracks.Group(
            TrackGroup(format),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true),
        )
    }

    @Test
    fun `no audio groups is not the silent-audio bug`() {
        assertFalse(audioPresentButUnplayable(Tracks(listOf(videoGroup()))))
        assertFalse(audioPresentButUnplayable(Tracks.EMPTY))
    }

    @Test
    fun `audio selected and supported plays - not the bug`() {
        val tracks = Tracks(listOf(videoGroup(), audioGroup(supported = true, selected = true)))
        assertFalse(audioPresentButUnplayable(tracks))
    }

    @Test
    fun `audio present but not selected is the bug`() {
        val tracks = Tracks(listOf(videoGroup(), audioGroup(supported = true, selected = false)))
        assertTrue(audioPresentButUnplayable(tracks))
    }

    @Test
    fun `audio selected but unsupported is the bug`() {
        val tracks = Tracks(listOf(videoGroup(), audioGroup(supported = false, selected = true)))
        assertTrue(audioPresentButUnplayable(tracks))
    }
}
