package com.albunyaan.tube.ui.bootstrap

import com.albunyaan.tube.auth.AuthRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class AgeIneligibleViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var authRepository: AuthRepository
    private lateinit var viewModel: AgeIneligibleViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        firebaseAuth = mock()
        authRepository = mock()
        viewModel = AgeIneligibleViewModel(firebaseAuth, authRepository)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `acknowledge deletes user and triggers NavigateToSignIn`() = runTest(dispatcher) {
        val user: FirebaseUser = mock()
        whenever(firebaseAuth.currentUser).thenReturn(user)
        whenever(user.delete()).thenReturn(Tasks.forResult(null))

        viewModel.acknowledge()
        advanceUntilIdle()

        verify(user).delete()
        verify(authRepository).signOut()
        assertEquals(AgeIneligibleNav.NavigateToSignIn, viewModel.nav.value)
    }

    @Test fun `acknowledge proceeds even if delete fails`() = runTest(dispatcher) {
        val user: FirebaseUser = mock()
        whenever(firebaseAuth.currentUser).thenReturn(user)
        whenever(user.delete()).thenReturn(Tasks.forException(RuntimeException("offline")))

        viewModel.acknowledge()
        advanceUntilIdle()

        verify(authRepository).signOut()
        assertEquals(AgeIneligibleNav.NavigateToSignIn, viewModel.nav.value)
    }

    @Test fun `acknowledge with null currentUser still signs out and navigates`() = runTest(dispatcher) {
        whenever(firebaseAuth.currentUser).thenReturn(null)

        viewModel.acknowledge()
        advanceUntilIdle()

        verify(authRepository).signOut()
        assertEquals(AgeIneligibleNav.NavigateToSignIn, viewModel.nav.value)
    }
}
