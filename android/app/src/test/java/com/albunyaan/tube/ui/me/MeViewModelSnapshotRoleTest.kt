package com.albunyaan.tube.ui.me

import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.auth.AccountStatus
import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.me.MeFeedRepository
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)

/**
 * Unit tests for [MeViewModel.snapshotRole].
 *
 * Uses lightweight stubs for all constructor deps — no Room, no Robolectric.
 * [SubscriptionRepository] and [MeFeedRepository] are mocked; only
 * [AccountRepository] is varied between tests.
 */
class MeViewModelSnapshotRoleTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Builds a ViewModel with all heavy deps mocked except [accountRepo]. */
    private fun buildVm(accountRepo: AccountRepository): MeViewModel {
        val subs = mock<SubscriptionRepository>().also { s ->
            whenever(s.observeSubscribedChannels()).thenReturn(flowOf(emptyList()))
            whenever(s.observeSavedPlaylists()).thenReturn(flowOf(emptyList()))
        }
        val feed = mock<MeFeedRepository>().also { f ->
            whenever(f.observeFeed()).thenReturn(flowOf(emptyList()))
            // MeViewModel.init kicks off loadNextWeek, which hits the deep-page
            // loop and calls countCachedRowsForFilter(filter.value). filter is a
            // StateFlow<String?> with null initial value, so the matcher must
            // accept null — any() rejects null, use anyOrNull(). Without the
            // stub, Mockito returns null for the boxed Integer return and
            // Kotlin NPEs on unbox; the orphan exception surfaces in the next
            // test's runTest as UncaughtExceptionsBeforeTest. Stubbing to 0
            // makes the deep-page loop's rowsAfter == rowsBefore check break
            // out immediately.
            runBlocking { whenever(f.countCachedRowsForFilter(anyOrNull())).thenReturn(0) }
        }
        val favs = StubFavoritesRepository()
        return MeViewModel(subs, feed, favs, accountRepo)
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    fun `snapshotRole returns role from Loaded state`() {
        val repo = StubAccountRepository(
            AccountState.Loaded(
                uid = "u1",
                email = "test@example.com",
                displayName = "Farouq",
                dateOfBirth = null,
                phoneNumber = null,
                status = AccountStatus.ACTIVE,
                role = "moderator",
            )
        )
        val vm = buildVm(repo)
        assertEquals("moderator", vm.snapshotRole())
    }

    @Test
    fun `snapshotRole returns empty string when not loaded`() {
        val repo = StubAccountRepository(AccountState.NotSignedIn)
        val vm = buildVm(repo)
        assertEquals("", vm.snapshotRole())
    }

    // ---------------------------------------------------------------------------
    // Stubs
    // ---------------------------------------------------------------------------

    private class StubAccountRepository(initial: AccountState) : AccountRepository {
        override val accountState: StateFlow<AccountState> = MutableStateFlow(initial)
        override suspend fun fetchMe() =
            Result.failure<AccountState.Loaded>(RuntimeException("stub"))
        override suspend fun completeProfile(displayName: String, dateOfBirth: LocalDate, phoneNumber: String) =
            Result.failure<AccountState.Loaded>(RuntimeException("stub"))
        override fun signOut() {}
        override fun applyProfileUpdate(response: AccountMeResponseDto) {}
    }

    private class StubFavoritesRepository : FavoritesRepository {
        private val state = MutableStateFlow<List<FavoriteVideo>>(emptyList())
        override fun getAllFavorites(): Flow<List<FavoriteVideo>> = state
        override fun observeApprovedFavorites(): Flow<List<FavoriteVideo>> = kotlinx.coroutines.flow.emptyFlow()
        override fun observeAwaitingFavorites(): Flow<List<FavoriteVideo>> = kotlinx.coroutines.flow.emptyFlow()
        override fun isFavorite(videoId: String): Flow<Boolean> =
            state.map { list -> list.any { it.videoId == videoId } }
        override suspend fun isFavoriteOnce(videoId: String): Boolean =
            state.value.any { it.videoId == videoId }
        override suspend fun addFavorite(
            videoId: String, title: String, channelName: String,
            thumbnailUrl: String?, durationSeconds: Int,
        ) {}
        override suspend fun removeFavorite(videoId: String) {}
        override suspend fun toggleFavorite(
            videoId: String, title: String, channelName: String,
            thumbnailUrl: String?, durationSeconds: Int,
        ): Boolean = false
        override fun getFavoriteCount(): Flow<Int> = state.map { it.size }
        override suspend fun clearAll() {}
    }
}
