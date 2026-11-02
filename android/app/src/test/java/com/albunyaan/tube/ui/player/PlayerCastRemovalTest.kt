package com.albunyaan.tube.ui.player

import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Owner ruling: an admin removal stops playback on the cast receiver too, not only the phone's player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerCastRemovalTest {

    private val ourUrl = "https://cast.example/ours"

    private fun receiver(playing: Boolean = true, url: String? = ourUrl) = mock<RemoteMediaClient>().also { client ->
        whenever(client.hasMediaSession()).thenReturn(playing)
        val media = url?.let { u -> mock<MediaInfo>().also { whenever(it.contentId).thenReturn(u) } }
        whenever(client.mediaInfo).thenReturn(media)
    }

    private fun watch(client: RemoteMediaClient?, castId: String?) =
        CastRemovalWatch(CoroutineScope(Dispatchers.Unconfined)) { client }.apply { loaded = castId?.let { it to ourUrl } }

    @Test fun `a removal of the video this app cast stops the receiver`() {
        val client = receiver()

        watch(client, "videoAAAAAA").stop("videoAAAAAA")

        verify(client).stop()
    }

    /** Cubic R4-2: casting A, then opening a blocked B on the phone, leaves A playing on the TV. */
    @Test fun `a removal of another video, or with nothing cast from here, leaves the receiver alone`() {
        val client = receiver()

        watch(client, "videoAAAAAA").stop("videoBBBBBB")
        watch(client, null).stop("videoBBBBBB")

        verify(client, never()).stop()
    }

    /** Codex r3: another phone's load on the same TV, or a load of ours the receiver never took, is not ours to stop. */
    @Test fun `a receiver playing another URL is left alone`() {
        val other = receiver(url = "https://cast.example/another-sender")
        val nothing = receiver(url = null)

        watch(other, "videoAAAAAA").stop("videoAAAAAA")
        watch(nothing, "videoAAAAAA").stop("videoAAAAAA")

        verify(other, never()).stop()
        verify(nothing, never()).stop()
    }

    @Test fun `an idle receiver, or no cast session, is left alone`() {
        val idle = receiver(playing = false)

        watch(idle, "videoAAAAAA").stop("videoAAAAAA")
        watch(null, "videoAAAAAA").stop("videoAAAAAA")

        verify(idle, never()).stop()
    }
}
