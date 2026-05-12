package com.albunyaan.tube.ui.me.submissions

import com.albunyaan.tube.data.approvals.MySubmissionsRepository
import com.albunyaan.tube.data.approvals.dto.PendingApprovalDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MySubmissionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadingThenLoaded() = runTest(dispatcher) {
        val repo: MySubmissionsRepository = mock()
        whenever(repo.fetchMySubmissions(null)).thenReturn(Result.success(
            listOf(PendingApprovalDto(
                id = "a", type = "channel", entityId = "UC1", title = "X", category = "Quran",
                submittedAt = 1000L, submittedBy = "uid",
                submittedByDisplayName = "Test", submittedByEmail = "t@x",
                status = "PENDING",
            ))
        ))
        val vm = MySubmissionsViewModel(repo)
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state is MySubmissionsUiState.Loaded)
        assertEquals(1, (state as MySubmissionsUiState.Loaded).items.size)
    }

    @Test fun emptyResultsState() = runTest(dispatcher) {
        val repo: MySubmissionsRepository = mock()
        whenever(repo.fetchMySubmissions(null)).thenReturn(Result.success(emptyList()))
        val vm = MySubmissionsViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value is MySubmissionsUiState.Empty)
    }

    @Test fun errorState() = runTest(dispatcher) {
        val repo: MySubmissionsRepository = mock()
        whenever(repo.fetchMySubmissions(null)).thenReturn(Result.failure(RuntimeException("boom")))
        val vm = MySubmissionsViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value is MySubmissionsUiState.Error)
    }
}
