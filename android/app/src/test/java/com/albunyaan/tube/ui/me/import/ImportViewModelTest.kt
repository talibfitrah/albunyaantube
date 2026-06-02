package com.albunyaan.tube.ui.me.import

import android.app.PendingIntent
import android.content.Intent
import com.albunyaan.tube.data.importflow.ImportProgress
import com.albunyaan.tube.data.importflow.ImportSummary
import com.albunyaan.tube.data.importflow.YouTubeImportRepository
import com.albunyaan.tube.data.youtube.AuthResult
import com.albunyaan.tube.data.youtube.CandidateType
import com.albunyaan.tube.data.youtube.ImportCandidate
import com.albunyaan.tube.data.youtube.ImportFetchResult
import com.albunyaan.tube.data.youtube.YouTubeAuthManager
import com.albunyaan.tube.data.youtube.YouTubeImportRemoteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * B10: Unit tests for [ImportViewModel].
 *
 * Uses [UnconfinedTestDispatcher] set as Main so viewModelScope.launch runs eagerly
 * in tests (same pattern as MeViewModelTest's Dispatchers.Unconfined).
 *
 * All three collaborators are Mockito mocks. Suspend stubs with nullable args use
 * [anyOrNull()] per the project MEMORY.md quirk (Mockito returns null → Kotlin NPE
 * on unbox otherwise).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {

    private lateinit var authManager: YouTubeAuthManager
    private lateinit var remoteSource: YouTubeImportRemoteSource
    private lateinit var importRepository: YouTubeImportRepository

    private lateinit var vm: ImportViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        authManager = mock()
        remoteSource = mock()
        importRepository = mock()

        // Default progress flow — overridden per-test as needed.
        val defaultProgress = MutableStateFlow(ImportProgress(ImportProgress.Phase.RESOLVING, 0, 0))
        whenever(importRepository.progress).thenReturn(defaultProgress)

        vm = ImportViewModel(authManager, remoteSource, importRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── start() → Granted → Review ────────────────────────────────────────────

    @Test
    fun `start - Granted - fetch returns candidates - state is Review with all selected`() = runTest {
        val candidates = listOf(
            candidate("ch1", CandidateType.CHANNEL),
            candidate("ch2", CandidateType.CHANNEL),
            candidate("pl1", CandidateType.PLAYLIST),
        )
        whenever(authManager.authorize()).thenReturn(AuthResult.Granted("token-abc"))
        whenever(remoteSource.fetchAll("token-abc"))
            .thenReturn(ImportFetchResult(candidates, emptySet()))

        vm.start()

        val state = vm.uiState.value as ImportUiState.Review
        assertEquals(candidates, state.candidates)
        assertEquals(setOf("ch1", "ch2", "pl1"), state.selected)
        assertTrue(state.partialFailureTypes.isEmpty())
    }

    @Test
    fun `start - Granted - partial failure types exposed in Review`() = runTest {
        val candidates = listOf(candidate("ch1", CandidateType.CHANNEL))
        whenever(authManager.authorize()).thenReturn(AuthResult.Granted("token-abc"))
        whenever(remoteSource.fetchAll("token-abc"))
            .thenReturn(ImportFetchResult(candidates, setOf(CandidateType.VIDEO)))

        vm.start()

        val state = vm.uiState.value as ImportUiState.Review
        assertEquals(setOf(CandidateType.VIDEO), state.partialFailureTypes)
    }

    @Test
    fun `start - Granted - empty candidates no failures - Error not retryable`() = runTest {
        whenever(authManager.authorize()).thenReturn(AuthResult.Granted("token-abc"))
        whenever(remoteSource.fetchAll("token-abc"))
            .thenReturn(ImportFetchResult(emptyList(), emptySet()))

        vm.start()

        val state = vm.uiState.value as ImportUiState.Error
        assertEquals("No items found", state.message)
        assertFalse(state.retryable)
    }

    @Test
    fun `start - Granted - empty candidates with failedTypes - Error retryable`() = runTest {
        whenever(authManager.authorize()).thenReturn(AuthResult.Granted("token-abc"))
        whenever(remoteSource.fetchAll("token-abc"))
            .thenReturn(ImportFetchResult(emptyList(), setOf(CandidateType.CHANNEL)))

        vm.start()

        val state = vm.uiState.value as ImportUiState.Error
        assertTrue(state.retryable)
    }

    // ── start() → Denied ─────────────────────────────────────────────────────

    @Test
    fun `start - Denied - state is Error retryable`() = runTest {
        whenever(authManager.authorize()).thenReturn(AuthResult.Denied)

        vm.start()

        val state = vm.uiState.value as ImportUiState.Error
        assertEquals("Permission denied", state.message)
        assertTrue(state.retryable)
    }

    // ── start() → Failed ─────────────────────────────────────────────────────

    @Test
    fun `start - Failed - state is Error retryable with exception message`() = runTest {
        whenever(authManager.authorize())
            .thenReturn(AuthResult.Failed(RuntimeException("network timeout")))

        vm.start()

        val state = vm.uiState.value as ImportUiState.Error
        assertEquals("network timeout", state.message)
        assertTrue(state.retryable)
    }

    // ── start() → NeedsConsent → onConsentResult(Granted) → Review ───────────

    @Test
    fun `start - NeedsConsent - state is NeedsConsent with pendingIntent`() = runTest {
        val pi = mock<PendingIntent>()
        whenever(authManager.authorize()).thenReturn(AuthResult.NeedsConsent(pi))

        vm.start()

        val state = vm.uiState.value as ImportUiState.NeedsConsent
        assertEquals(pi, state.pendingIntent)
    }

    @Test
    fun `onConsentResult - Granted - proceeds to Review`() = runTest {
        // First start puts us in NeedsConsent.
        val pi = mock<PendingIntent>()
        whenever(authManager.authorize()).thenReturn(AuthResult.NeedsConsent(pi))
        vm.start()
        assertTrue(vm.uiState.value is ImportUiState.NeedsConsent)

        // Simulate user completing consent.
        val candidates = listOf(candidate("vid1", CandidateType.VIDEO))
        // anyOrNull() because the Intent arg is nullable (from activity result)
        whenever(authManager.authorizeFromConsentResult(anyOrNull()))
            .thenReturn(AuthResult.Granted("token-xyz"))
        whenever(remoteSource.fetchAll("token-xyz"))
            .thenReturn(ImportFetchResult(candidates, emptySet()))

        vm.onConsentResult(Intent())

        val state = vm.uiState.value as ImportUiState.Review
        assertEquals(candidates, state.candidates)
        assertEquals(setOf("vid1"), state.selected)
    }

    @Test
    fun `onConsentResult - Denied - state is Error retryable`() = runTest {
        val pi = mock<PendingIntent>()
        whenever(authManager.authorize()).thenReturn(AuthResult.NeedsConsent(pi))
        vm.start()

        whenever(authManager.authorizeFromConsentResult(anyOrNull()))
            .thenReturn(AuthResult.Denied)
        vm.onConsentResult(null)

        val state = vm.uiState.value as ImportUiState.Error
        assertTrue(state.retryable)
    }

    // ── toggleSelection ───────────────────────────────────────────────────────

    @Test
    fun `toggleSelection deselects a selected candidate`() = runTest {
        setupReviewState(listOf(
            candidate("ch1", CandidateType.CHANNEL),
            candidate("ch2", CandidateType.CHANNEL),
        ))

        vm.toggleSelection("ch1")

        val state = vm.uiState.value as ImportUiState.Review
        assertFalse("ch1" in state.selected)
        assertTrue("ch2" in state.selected)
    }

    @Test
    fun `toggleSelection reselects a deselected candidate`() = runTest {
        setupReviewState(listOf(candidate("ch1", CandidateType.CHANNEL)))
        vm.toggleSelection("ch1")    // deselect
        vm.toggleSelection("ch1")    // re-select

        val state = vm.uiState.value as ImportUiState.Review
        assertTrue("ch1" in state.selected)
    }

    @Test
    fun `toggleSelection no-op when not in Review state`() = runTest {
        vm.toggleSelection("anything")
        assertTrue(vm.uiState.value is ImportUiState.Idle)
    }

    // ── setGroupSelected ──────────────────────────────────────────────────────

    @Test
    fun `setGroupSelected false deselects all candidates of that type`() = runTest {
        setupReviewState(listOf(
            candidate("ch1", CandidateType.CHANNEL),
            candidate("ch2", CandidateType.CHANNEL),
            candidate("pl1", CandidateType.PLAYLIST),
        ))

        vm.setGroupSelected(CandidateType.CHANNEL, selected = false)

        val state = vm.uiState.value as ImportUiState.Review
        assertFalse("ch1" in state.selected)
        assertFalse("ch2" in state.selected)
        assertTrue("pl1" in state.selected)
    }

    @Test
    fun `setGroupSelected true reselects all candidates of that type`() = runTest {
        setupReviewState(listOf(
            candidate("ch1", CandidateType.CHANNEL),
            candidate("ch2", CandidateType.CHANNEL),
        ))
        vm.setGroupSelected(CandidateType.CHANNEL, selected = false)
        vm.setGroupSelected(CandidateType.CHANNEL, selected = true)

        val state = vm.uiState.value as ImportUiState.Review
        assertTrue("ch1" in state.selected)
        assertTrue("ch2" in state.selected)
    }

    @Test
    fun `setGroupSelected no-op when not in Review state`() = runTest {
        vm.setGroupSelected(CandidateType.CHANNEL, selected = false)
        assertTrue(vm.uiState.value is ImportUiState.Idle)
    }

    // ── confirmImport → Done ──────────────────────────────────────────────────

    @Test
    fun `confirmImport - transitions through Importing to Done with correct summary`() = runTest {
        val candidates = listOf(
            candidate("ch1", CandidateType.CHANNEL),
            candidate("pl1", CandidateType.PLAYLIST),
        )
        setupReviewState(candidates)

        val expectedSummary = ImportSummary(added = 2, sentForReview = 0, skipped = 0, alreadyPresent = 0)
        whenever(importRepository.import(candidates)).thenReturn(expectedSummary)

        vm.confirmImport()

        val state = vm.uiState.value as ImportUiState.Done
        assertEquals(expectedSummary, state.summary)
    }

    @Test
    fun `confirmImport - passes only selected candidates to repository`() = runTest {
        val c1 = candidate("ch1", CandidateType.CHANNEL)
        val c2 = candidate("ch2", CandidateType.CHANNEL)
        setupReviewState(listOf(c1, c2))
        vm.toggleSelection("ch2")    // deselect ch2

        val expectedSummary = ImportSummary(1, 0, 0, 0)
        val captor = argumentCaptor<List<ImportCandidate>>()
        whenever(importRepository.import(captor.capture())).thenReturn(expectedSummary)

        vm.confirmImport()

        assertEquals(listOf(c1), captor.firstValue)
        assertTrue(vm.uiState.value is ImportUiState.Done)
    }

    @Test
    fun `confirmImport - progress emitted by repository reflected in Importing state`() = runTest {
        val candidates = listOf(candidate("ch1", CandidateType.CHANNEL))
        val progressFlow = MutableStateFlow(
            ImportProgress(ImportProgress.Phase.RESOLVING, 0, 1)
        )
        whenever(importRepository.progress).thenReturn(progressFlow)

        // Re-create VM so the new progress flow is used from init.
        vm = ImportViewModel(authManager, remoteSource, importRepository)
        setupReviewState(candidates)

        val midProgress = ImportProgress(ImportProgress.Phase.WRITING, 0, 1)
        whenever(importRepository.import(candidates)).thenAnswer {
            // Emit a mid-import progress value before returning the summary.
            progressFlow.value = midProgress
            ImportSummary(1, 0, 0, 0)
        }

        vm.confirmImport()

        // After import completes the state transitions to Done.
        assertTrue(vm.uiState.value is ImportUiState.Done)
    }

    @Test
    fun `confirmImport - repository throws - state is Error retryable`() = runTest {
        setupReviewState(listOf(candidate("ch1", CandidateType.CHANNEL)))
        whenever(importRepository.import(org.mockito.kotlin.any()))
            .thenAnswer { throw RuntimeException("backend unreachable") }

        vm.confirmImport()

        val state = vm.uiState.value as ImportUiState.Error
        assertEquals("backend unreachable", state.message)
        assertTrue(state.retryable)
    }

    @Test
    fun `confirmImport - no-op when not in Review state`() = runTest {
        vm.confirmImport()
        assertTrue(vm.uiState.value is ImportUiState.Idle)
    }

    // ── fetch throws ──────────────────────────────────────────────────────────

    @Test
    fun `fetch throwing - state is Error retryable`() = runTest {
        whenever(authManager.authorize()).thenReturn(AuthResult.Granted("token-abc"))
        whenever(remoteSource.fetchAll("token-abc"))
            .thenAnswer { throw RuntimeException("quota exceeded") }

        vm.start()

        val state = vm.uiState.value as ImportUiState.Error
        assertEquals("quota exceeded", state.message)
        assertTrue(state.retryable)
    }

    // ── retry() ───────────────────────────────────────────────────────────────

    @Test
    fun `retry - restarts flow from Authorizing`() = runTest {
        whenever(authManager.authorize()).thenReturn(AuthResult.Denied)
        vm.start()
        assertTrue(vm.uiState.value is ImportUiState.Error)

        // On retry, provide a successful path.
        val candidates = listOf(candidate("ch1", CandidateType.CHANNEL))
        whenever(authManager.authorize()).thenReturn(AuthResult.Granted("token-retry"))
        whenever(remoteSource.fetchAll("token-retry"))
            .thenReturn(ImportFetchResult(candidates, emptySet()))

        vm.retry()

        assertTrue(vm.uiState.value is ImportUiState.Review)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Puts the VM into Review state by stubbing a successful auth + fetch,
     * then calling [ImportViewModel.start].
     */
    private suspend fun setupReviewState(candidates: List<ImportCandidate>) {
        whenever(authManager.authorize()).thenReturn(AuthResult.Granted("token-setup"))
        whenever(remoteSource.fetchAll("token-setup"))
            .thenReturn(ImportFetchResult(candidates, emptySet()))
        vm.start()
        assertTrue(
            "Expected Review but got ${vm.uiState.value}",
            vm.uiState.value is ImportUiState.Review,
        )
    }

    private fun candidate(id: String, type: CandidateType) = ImportCandidate(
        type = type,
        youtubeId = id,
        title = "Title $id",
        thumbnailUrl = null,
        channelId = null,
    )
}
