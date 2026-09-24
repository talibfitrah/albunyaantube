package com.albunyaan.tube.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The sign-in wall: nothing keeps playing once the user is signed out. */
@RunWith(RobolectricTestRunner::class)
class PlaybackServiceSignOutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `sign-out stops the running playback service`() {
        val service = Robolectric.buildService(PlaybackService::class.java).create().get()

        PlaybackService.stopForSignOut(context)

        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `sign-out with nothing playing and no Cast is a no-op`() {
        PlaybackService.stopForSignOut(context)  // must not throw
    }
}
