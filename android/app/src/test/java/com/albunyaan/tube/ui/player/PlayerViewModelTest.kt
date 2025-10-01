package com.albunyaan.tube.ui.player

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerViewModelTest {

    @Test
    fun `hydrateQueue picks first playable item and filters exclusions`() = runTest {
        val viewModel = PlayerViewModel()

        val state = viewModel.state.value

        assertEquals("intro_foundations", state.currentItem?.id)
        assertTrue(state.upNext.all { !it.isExcluded })
        assertTrue(state.excludedItems.all { it.isExcluded })
    }

    @Test
    fun `markCurrentComplete advances to next item and emits event`() = runTest {
        val viewModel = PlayerViewModel()

        val initialState = viewModel.state.value
        val initialCurrent = requireNotNull(initialState.currentItem)

        viewModel.markCurrentComplete()

        val updatedState = viewModel.state.value
        assertEquals("tafsir_baqara", updatedState.currentItem?.id)
        val lastEvent = updatedState.lastAnalyticsEvent
        assertNotNull(lastEvent)
        require(lastEvent is PlaybackAnalyticsEvent.PlaybackStarted)
        assertEquals(updatedState.currentItem?.id, lastEvent.item.id)
        assertTrue(updatedState.upNext.none { it.id == initialCurrent.id })
    }

    @Test
    fun `playItem moves selection and re-queues previous current`() = runTest {
        val viewModel = PlayerViewModel()
        val initialCurrent = requireNotNull(viewModel.state.value.currentItem)
        val target = requireNotNull(viewModel.state.value.upNext.lastOrNull())

        viewModel.playItem(target)

        val state = viewModel.state.value
        assertEquals(target.id, state.currentItem?.id)
        assertTrue(state.upNext.any { it.id == initialCurrent.id })
    }

    @Test
    fun `setAudioOnly ignores duplicate state`() = runTest {
        val viewModel = PlayerViewModel()
        viewModel.setAudioOnly(true)

        val firstEvent = viewModel.state.value.lastAnalyticsEvent
        viewModel.setAudioOnly(true)

        val secondEvent = viewModel.state.value.lastAnalyticsEvent
        assertEquals(firstEvent, secondEvent)
    }
}
