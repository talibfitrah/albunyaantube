package com.albunyaan.tube.data.subscriptions

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SubscriptionRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SubscriptionRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = SubscriptionRepository(
            db = db,
            channels = db.subscribedChannelDao(),
            playlists = db.savedPlaylistDao(),
            cache = db.channelVideoCacheDao(),
            refreshState = db.channelFeedRefreshStateDao(),
            accountRepository = FakeAccountRepository(),
            syncManager = mock(),
            playlistLinks = db.playlistVideoLinkDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `subscribe and unsubscribe updates flows`() = runTest {
        val sub = SubscribedChannel("UC1", "url", "Test", null, 100L)
        assertFalse(repo.isChannelSubscribed("UC1").first())

        repo.subscribe(sub)
        assertTrue(repo.isChannelSubscribed("UC1").first())
        // compare by channelId — dirty/user_id are set by the repo on write
        assertEquals(listOf("UC1"), repo.observeSubscribedChannels().first().map { it.channelId })

        repo.unsubscribe("UC1")
        assertFalse(repo.isChannelSubscribed("UC1").first())
        assertTrue(repo.observeSubscribedChannels().first().isEmpty())
    }

    @Test
    fun `save and unsave playlist updates flows`() = runTest {
        val pl = SavedPlaylist("PL1", "url", "List", null, "Uploader", 200L)
        assertFalse(repo.isPlaylistSaved("PL1").first())

        repo.savePlaylist(pl)
        assertTrue(repo.isPlaylistSaved("PL1").first())
        // compare by playlistId — dirty/user_id are set by the repo on write
        assertEquals(listOf("PL1"), repo.observeSavedPlaylists().first().map { it.playlistId })

        repo.unsavePlaylist("PL1")
        assertFalse(repo.isPlaylistSaved("PL1").first())
    }

    @Test
    fun `getSubscribedChannels returns DESC by subscribedAt`() = runTest {
        repo.subscribe(SubscribedChannel("UC1", "u", "A", null, 1L))
        repo.subscribe(SubscribedChannel("UC2", "u", "B", null, 3L))
        repo.subscribe(SubscribedChannel("UC3", "u", "C", null, 2L))
        assertEquals(listOf("UC2", "UC3", "UC1"), repo.getSubscribedChannels().map { it.channelId })
    }

    private class FakeAccountRepository : AccountRepository {
        override val accountState: StateFlow<AccountState> =
            MutableStateFlow(AccountState.NotSignedIn)
        override suspend fun fetchMe() = Result.failure<AccountState.Loaded>(RuntimeException("stub"))
        override suspend fun completeProfile(displayName: String, dateOfBirth: LocalDate) =
            Result.failure<AccountState.Loaded>(RuntimeException("stub"))
        override fun signOut() {}
        override fun applyProfileUpdate(response: com.albunyaan.tube.data.account.AccountMeResponseDto) {}
    }
}
