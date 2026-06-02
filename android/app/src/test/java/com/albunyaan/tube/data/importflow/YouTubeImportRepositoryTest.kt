package com.albunyaan.tube.data.importflow

import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.data.importflow.dto.ImportResolveRequestDto
import com.albunyaan.tube.data.importflow.dto.ImportResolveResponseDto
import com.albunyaan.tube.data.importflow.dto.ImportResultDto
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.model.api.models.ContentItemDto
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import com.albunyaan.tube.data.youtube.CandidateType
import com.albunyaan.tube.data.youtube.ImportCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * B9: Unit tests for [YouTubeImportRepository.import].
 *
 * [SubscriptionRepository] is mocked via Mockito (it is a final concrete class
 * with many Room constructor dependencies). [FavoritesRepository] uses an
 * in-memory fake so we can inspect written [FavoriteVideo] entities directly.
 */
class YouTubeImportRepositoryTest {

    private lateinit var fakeApi: FakeImportApi
    private lateinit var mockSubRepo: SubscriptionRepository
    private lateinit var fakeFavRepo: InMemoryFavoritesRepository
    private lateinit var repository: YouTubeImportRepository

    @Before
    fun setUp() {
        fakeApi = FakeImportApi()
        mockSubRepo = mock()
        fakeFavRepo = InMemoryFavoritesRepository()
        repository = YouTubeImportRepository(
            importApi = fakeApi,
            subscriptionRepository = mockSubRepo,
            favoritesRepository = fakeFavRepo,
            accountRepository = FakeAccountRepository(),
        )
    }

    // ── APPROVED ─────────────────────────────────────────────────────────────

    @Test
    fun `APPROVED channel written with approvalStatus APPROVED and canonical metadata`() = runTest {
        val candidate = ImportCandidate(
            type = CandidateType.CHANNEL,
            youtubeId = "UC_channel_1",
            title = "Candidate Title",
            thumbnailUrl = "https://candidate.thumb/",
            channelId = null,
        )
        val content = ContentItemDto(
            id = "firestore-ch-1",
            type = ContentItemDto.Type.CHANNEL,
            name = "Canonical Channel Name",
            thumbnailUrl = "https://canonical.thumb/",
        )
        fakeApi.enqueue(
            ImportResultDto("UC_channel_1", "CHANNEL", "APPROVED", content)
        )
        // No pre-existing entry
        whenever(mockSubRepo.channelExistsAny(any(), any())).thenReturn(false)

        val summary = repository.import(listOf(candidate))

        assertEquals(1, summary.added)
        assertEquals(0, summary.sentForReview)
        assertEquals(0, summary.skipped)

        val captor = argumentCaptor<SubscribedChannel>()
        verify(mockSubRepo).subscribe(captor.capture())
        val written = captor.firstValue
        assertEquals("UC_channel_1", written.channelId)
        assertEquals("APPROVED", written.approvalStatus)
        assertEquals(YouTubeImportRepository.SOURCE_USER_IMPORT, written.source)
        // Canonical name from content
        assertEquals("Canonical Channel Name", written.name)
        assertEquals("https://canonical.thumb/", written.avatarUrl)
    }

    @Test
    fun `APPROVED playlist written with canonical metadata from content`() = runTest {
        val candidate = ImportCandidate(
            type = CandidateType.PLAYLIST,
            youtubeId = "PL_ap_1",
            title = "Candidate PL",
            thumbnailUrl = null,
            channelId = null,
        )
        val content = ContentItemDto(
            id = "firestore-pl-1",
            type = ContentItemDto.Type.PLAYLIST,
            title = "Canonical Playlist Title",
            channelTitle = "Creator Channel",
            thumbnailUrl = "https://canonical.thumb/pl",
        )
        fakeApi.enqueue(ImportResultDto("PL_ap_1", "PLAYLIST", "APPROVED", content))
        whenever(mockSubRepo.playlistExistsAny(any(), any())).thenReturn(false)

        val summary = repository.import(listOf(candidate))

        assertEquals(1, summary.added)

        val captor = argumentCaptor<SavedPlaylist>()
        verify(mockSubRepo).savePlaylist(captor.capture())
        val written = captor.firstValue
        assertEquals("PL_ap_1", written.playlistId)
        assertEquals("APPROVED", written.approvalStatus)
        assertEquals(YouTubeImportRepository.SOURCE_USER_IMPORT, written.source)
        assertEquals("Canonical Playlist Title", written.name)
        assertEquals("Creator Channel", written.uploaderName)
    }

    @Test
    fun `APPROVED video written with canonical metadata and APPROVED status`() = runTest {
        val candidate = ImportCandidate(
            type = CandidateType.VIDEO,
            youtubeId = "vid_001",
            title = "Candidate Video Title",
            thumbnailUrl = "https://candidate.thumb/vid",
            channelId = "UC_owner",
        )
        val content = ContentItemDto(
            id = "firestore-vid-1",
            type = ContentItemDto.Type.VIDEO,
            title = "Canonical Video Title",
            channelTitle = "Canonical Channel",
            thumbnailUrl = "https://canonical.thumb/vid",
            durationSeconds = 300,
        )
        fakeApi.enqueue(ImportResultDto("vid_001", "VIDEO", "APPROVED", content))
        whenever(fakeFavRepo.favoriteExistsAny("test-uid", "vid_001")).thenReturn(false)

        val summary = repository.import(listOf(candidate))

        assertEquals(1, summary.added)

        val written = fakeFavRepo.importedFavorites.first()
        assertEquals("vid_001", written.videoId)
        assertEquals("APPROVED", written.approvalStatus)
        assertEquals(YouTubeImportRepository.SOURCE_USER_IMPORT, written.source)
        assertEquals("Canonical Video Title", written.title)
        assertEquals("Canonical Channel", written.channelName)
        assertEquals(300, written.durationSeconds)
    }

    // ── PENDING ───────────────────────────────────────────────────────────────

    @Test
    fun `PENDING channel written with AWAITING and candidate metadata`() = runTest {
        val candidate = ImportCandidate(
            type = CandidateType.CHANNEL,
            youtubeId = "UC_pending",
            title = "Pending Channel",
            thumbnailUrl = "https://pending.thumb/",
            channelId = null,
        )
        fakeApi.enqueue(ImportResultDto("UC_pending", "CHANNEL", "PENDING", null))
        whenever(mockSubRepo.channelExistsAny(any(), any())).thenReturn(false)

        val summary = repository.import(listOf(candidate))

        assertEquals(0, summary.added)
        assertEquals(1, summary.sentForReview)
        assertEquals(0, summary.skipped)

        val captor = argumentCaptor<SubscribedChannel>()
        verify(mockSubRepo).subscribe(captor.capture())
        val written = captor.firstValue
        assertEquals("UC_pending", written.channelId)
        assertEquals("AWAITING", written.approvalStatus)
        assertEquals(YouTubeImportRepository.SOURCE_USER_IMPORT, written.source)
        // Candidate metadata (no content for PENDING)
        assertEquals("Pending Channel", written.name)
        assertEquals("https://pending.thumb/", written.avatarUrl)
    }

    @Test
    fun `PENDING video written with AWAITING status`() = runTest {
        val candidate = ImportCandidate(
            type = CandidateType.VIDEO,
            youtubeId = "vid_pend",
            title = "Pending Video",
            thumbnailUrl = null,
            channelId = "UC_ch",
        )
        fakeApi.enqueue(ImportResultDto("vid_pend", "VIDEO", "PENDING", null))

        val summary = repository.import(listOf(candidate))

        assertEquals(1, summary.sentForReview)

        val written = fakeFavRepo.importedFavorites.first()
        assertEquals("vid_pend", written.videoId)
        assertEquals("AWAITING", written.approvalStatus)
    }

    // ── REJECTED ──────────────────────────────────────────────────────────────

    @Test
    fun `REJECTED item not written and counted as skipped`() = runTest {
        val candidate = ImportCandidate(CandidateType.VIDEO, "vid_rejected", "Rejected", null, null)
        fakeApi.enqueue(ImportResultDto("vid_rejected", "VIDEO", "REJECTED", null))

        val summary = repository.import(listOf(candidate))

        assertEquals(0, summary.added)
        assertEquals(0, summary.sentForReview)
        assertEquals(1, summary.skipped)
        assertTrue("No favorites should be written for REJECTED", fakeFavRepo.importedFavorites.isEmpty())
        verify(mockSubRepo, never()).subscribe(any())
        verify(mockSubRepo, never()).savePlaylist(any())
    }

    @Test
    fun `ERROR disposition not written and counted as skipped`() = runTest {
        val candidate = ImportCandidate(CandidateType.CHANNEL, "UC_err", "Error", null, null)
        fakeApi.enqueue(ImportResultDto("UC_err", "CHANNEL", "ERROR", null))
        whenever(mockSubRepo.channelExistsAny(any(), any())).thenReturn(false)

        val summary = repository.import(listOf(candidate))

        assertEquals(1, summary.skipped)
        verify(mockSubRepo, never()).subscribe(any())
    }

    // ── Mixed batch ───────────────────────────────────────────────────────────

    @Test
    fun `mixed APPROVED PENDING REJECTED returns correct summary counts`() = runTest {
        val candidates = listOf(
            ImportCandidate(CandidateType.CHANNEL,  "UC_ap", "Approved Ch",  null, null),
            ImportCandidate(CandidateType.PLAYLIST, "PL_pe", "Pending PL",   null, null),
            ImportCandidate(CandidateType.VIDEO,    "vid_r", "Rejected Vid", null, null),
        )
        fakeApi.enqueue(
            ImportResultDto("UC_ap", "CHANNEL",  "APPROVED",
                ContentItemDto("x", ContentItemDto.Type.CHANNEL, name = "Ch")),
            ImportResultDto("PL_pe", "PLAYLIST", "PENDING",  null),
            ImportResultDto("vid_r", "VIDEO",    "REJECTED", null),
        )
        whenever(mockSubRepo.channelExistsAny(any(), any())).thenReturn(false)
        whenever(mockSubRepo.playlistExistsAny(any(), any())).thenReturn(false)

        val summary = repository.import(candidates)

        assertEquals(1, summary.added)
        assertEquals(1, summary.sentForReview)
        assertEquals(1, summary.skipped)
    }

    // ── Dedup ─────────────────────────────────────────────────────────────────

    @Test
    fun `candidate already in Room is skipped and resolve called only with fresh items`() = runTest {
        val candidates = listOf(
            ImportCandidate(CandidateType.CHANNEL, "UC_already", "Existing", null, null),
            ImportCandidate(CandidateType.CHANNEL, "UC_fresh",   "Fresh",    null, null),
        )
        // UC_already is already present; UC_fresh is not
        whenever(mockSubRepo.channelExistsAny(any(), eq("UC_already"))).thenReturn(true)
        whenever(mockSubRepo.channelExistsAny(any(), eq("UC_fresh"))).thenReturn(false)

        fakeApi.enqueue(
            ImportResultDto("UC_fresh", "CHANNEL", "APPROVED",
                ContentItemDto("x", ContentItemDto.Type.CHANNEL, name = "Fresh Ch"))
        )

        val summary = repository.import(candidates)

        assertEquals(1, summary.alreadyPresent)
        assertEquals(1, summary.skipped)   // alreadyPresent is a subset
        assertEquals(1, summary.added)

        // resolve must only receive the fresh item
        assertEquals(1, fakeApi.lastRequest!!.items.size)
        assertEquals("UC_fresh", fakeApi.lastRequest!!.items.first().youtubeId)
    }

    @Test
    fun `all candidates already present returns zero added and resolve not called`() = runTest {
        val candidates = listOf(
            ImportCandidate(CandidateType.CHANNEL, "UC_a1", "A1", null, null),
            ImportCandidate(CandidateType.CHANNEL, "UC_a2", "A2", null, null),
        )
        whenever(mockSubRepo.channelExistsAny(any(), any())).thenReturn(true)

        val summary = repository.import(candidates)

        assertEquals(2, summary.alreadyPresent)
        assertEquals(2, summary.skipped)
        assertEquals(0, summary.added)
        assertEquals(0, fakeApi.resolveCallCount)
    }

    // ── Chunking ──────────────────────────────────────────────────────────────

    @Test
    fun `more than BATCH_SIZE candidates triggers two resolve calls`() = runTest {
        val batchSize = YouTubeImportRepository.BATCH_SIZE
        val total = batchSize + 5

        val candidates = (1..total).map { i ->
            ImportCandidate(CandidateType.VIDEO, "vid_$i", "Video $i", null, null)
        }

        // Chunk 1: full BATCH_SIZE results
        fakeApi.enqueueAll((1..batchSize).map { i ->
            ImportResultDto("vid_$i", "VIDEO", "PENDING", null)
        })
        // Chunk 2: remaining 5 results
        fakeApi.enqueueAll((batchSize + 1..total).map { i ->
            ImportResultDto("vid_$i", "VIDEO", "PENDING", null)
        })

        val summary = repository.import(candidates)

        assertEquals(2, fakeApi.resolveCallCount)
        assertEquals(total, summary.sentForReview)
        assertEquals(0, summary.added)
        assertEquals(0, summary.skipped)
    }

    // ── Progress ──────────────────────────────────────────────────────────────

    @Test
    fun `progress ends at DONE phase after import completes`() = runTest {
        val candidate = ImportCandidate(CandidateType.VIDEO, "vid_x", "X", null, null)
        fakeApi.enqueue(ImportResultDto("vid_x", "VIDEO", "REJECTED", null))

        repository.import(listOf(candidate))

        assertEquals(ImportProgress.Phase.DONE, repository.progress.value.phase)
    }

    // ── Empty input ────────────────────────────────────────────────────────────

    @Test
    fun `empty selection returns zero summary without calling resolve`() = runTest {
        val summary = repository.import(emptyList())

        assertEquals(0, summary.added)
        assertEquals(0, summary.sentForReview)
        assertEquals(0, summary.skipped)
        assertEquals(0, fakeApi.resolveCallCount)
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    /**
     * Fake ImportApi: collects queued response chunks in-order.
     * Each [enqueue]/[enqueueAll] call provides results for one [resolve] invocation.
     */
    private class FakeImportApi : ImportApi {
        private val queue = ArrayDeque<List<ImportResultDto>>()
        var resolveCallCount = 0
        var lastRequest: ImportResolveRequestDto? = null

        fun enqueue(vararg results: ImportResultDto) = queue.addLast(results.toList())
        fun enqueueAll(results: List<ImportResultDto>) = queue.addLast(results)

        override suspend fun resolve(request: ImportResolveRequestDto): ImportResolveResponseDto {
            resolveCallCount++
            lastRequest = request
            return ImportResolveResponseDto(results = queue.removeFirstOrNull() ?: emptyList())
        }
    }

    /**
     * In-memory [FavoritesRepository] that records [addImportedFavorite] calls so
     * tests can inspect the written [FavoriteVideo] entities.
     */
    private class InMemoryFavoritesRepository : FavoritesRepository {
        val importedFavorites = mutableListOf<FavoriteVideo>()

        override suspend fun favoriteExistsAny(uid: String, videoId: String): Boolean =
            importedFavorites.any { it.videoId == videoId }

        override suspend fun addImportedFavorite(
            uid: String,
            videoId: String, title: String, channelName: String,
            thumbnailUrl: String?, durationSeconds: Int,
            approvalStatus: String, source: String?, importedAt: Long?,
        ) {
            importedFavorites.removeAll { it.videoId == videoId }
            importedFavorites.add(
                FavoriteVideo(
                    videoId = videoId, title = title, channelName = channelName,
                    thumbnailUrl = thumbnailUrl, durationSeconds = durationSeconds,
                    approvalStatus = approvalStatus, source = source, importedAt = importedAt,
                )
            )
        }

        // ── Stubs ──────────────────────────────────────────────────────────────
        override fun getAllFavorites(): Flow<List<FavoriteVideo>> = MutableStateFlow(emptyList())
        override fun observeApprovedFavorites(): Flow<List<FavoriteVideo>> = MutableStateFlow(emptyList())
        override fun observeAwaitingFavorites(): Flow<List<FavoriteVideo>> = MutableStateFlow(emptyList())
        override fun isFavorite(videoId: String): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun isFavoriteOnce(videoId: String): Boolean = false
        override suspend fun addFavorite(videoId: String, title: String, channelName: String, thumbnailUrl: String?, durationSeconds: Int) {}
        override suspend fun removeFavorite(videoId: String) {}
        override suspend fun toggleFavorite(videoId: String, title: String, channelName: String, thumbnailUrl: String?, durationSeconds: Int): Boolean = false
        override fun getFavoriteCount(): Flow<Int> = MutableStateFlow(0)
        override suspend fun clearAll() {}
    }

    private class FakeAccountRepository : AccountRepository {
        override val accountState: StateFlow<AccountState> =
            MutableStateFlow(
                AccountState.Loaded(
                    uid = "test-uid",
                    email = null,
                    displayName = "Test User",
                    dateOfBirth = null,
                    phoneNumber = null,
                    status = com.albunyaan.tube.auth.AccountStatus.ACTIVE,
                    role = "USER",
                )
            )
        override suspend fun fetchMe(): Result<AccountState.Loaded> =
            Result.failure(RuntimeException("stub"))
        override suspend fun completeProfile(displayName: String, dateOfBirth: LocalDate, phoneNumber: String): Result<AccountState.Loaded> =
            Result.failure(RuntimeException("stub"))
        override fun signOut() {}
        override fun applyProfileUpdate(response: com.albunyaan.tube.data.account.AccountMeResponseDto) {}
    }
}

// Mockito-kotlin doesn't export `eq` from the top level in all versions — use this alias
private fun <T> eq(value: T) = org.mockito.kotlin.eq(value)
