package com.albunyaan.tube.ui.settings.availableversions

import com.albunyaan.tube.update.ReleaseCatalogCache
import com.albunyaan.tube.update.ReleaseSummaries
import com.albunyaan.tube.update.RowState
import com.albunyaan.tube.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class AvailableVersionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After  fun tearDown() = Dispatchers.resetMain()

    private fun info(v: String) = UpdateInfo(v, v, "https://x/$v.apk", 1)

    @Test
    fun `merges releases with summaries and assigns row state by installed version`() = runTest {
        val newer = info("1.0.0-beta.15")
        val current = info("1.0.0-beta.14")
        val older = info("1.0.0-beta.13")
        val cache = mock<ReleaseCatalogCache> {
            onBlocking { list(any()) } doReturn listOf(newer, current, older)
            onBlocking { summaries() } doReturn ReleaseSummaries(
                mapOf("1.0.0-beta.15" to mapOf("en" to "Future release."))
            )
        }
        val vm = AvailableVersionsViewModel(cache, installedVersionName = "1.0.0-beta.14", locale = "en")
        vm.load()
        advanceUntilIdle()

        val rows = vm.rows.value
        assertEquals(3, rows.size)
        assertEquals(RowState.Newer, rows[0].state)
        assertEquals("Future release.", rows[0].localizedSummary)
        assertEquals(RowState.Current, rows[1].state)
        assertEquals(RowState.Older, rows[2].state)
        assertNull(rows[2].localizedSummary)
    }

    @Test
    fun `missing summary entry leaves localizedSummary null`() = runTest {
        val release = info("1.0.0-beta.14")
        val cache = mock<ReleaseCatalogCache> {
            onBlocking { list(any()) } doReturn listOf(release)
            onBlocking { summaries() } doReturn ReleaseSummaries(emptyMap())
        }
        val vm = AvailableVersionsViewModel(cache, installedVersionName = "1.0.0-beta.14", locale = "en")
        vm.load()
        advanceUntilIdle()

        assertNull(vm.rows.value.single().localizedSummary)
    }

    @Test
    fun `Arabic locale with English-only summary falls back to English`() = runTest {
        val release = info("1.0.0-beta.14")
        val cache = mock<ReleaseCatalogCache> {
            onBlocking { list(any()) } doReturn listOf(release)
            onBlocking { summaries() } doReturn ReleaseSummaries(
                mapOf("1.0.0-beta.14" to mapOf("en" to "Only English."))
            )
        }
        val vm = AvailableVersionsViewModel(cache, installedVersionName = "1.0.0-beta.14", locale = "ar")
        vm.load()
        advanceUntilIdle()

        assertEquals("Only English.", vm.rows.value.single().localizedSummary)
    }

    @Test
    fun `empty release list produces empty rows - not error state`() = runTest {
        val cache = mock<ReleaseCatalogCache> {
            onBlocking { list(any()) } doReturn emptyList()
            onBlocking { summaries() } doReturn ReleaseSummaries(emptyMap())
        }
        val vm = AvailableVersionsViewModel(cache, installedVersionName = "1.0.0-beta.14", locale = "en")
        vm.load()
        advanceUntilIdle()

        assertEquals(0, vm.rows.value.size)
    }

    // codex C-5: a tag with leading/trailing whitespace (`v1.0.0-beta.14 `) reaches
    // versionName unnormalized. Without trim, the trailing space lands in the
    // prerelease arm of the comparator and can sort above the installed version.
    // With trim, the row resolves to Current as expected.
    @Test
    fun `trailing whitespace in remote tag resolves to Current state when matching installed`() = runTest {
        val withSpace = info("1.0.0-beta.14 ")
        val cache = mock<ReleaseCatalogCache> {
            onBlocking { list(any()) } doReturn listOf(withSpace)
            onBlocking { summaries() } doReturn ReleaseSummaries(emptyMap())
        }
        val vm = AvailableVersionsViewModel(cache, installedVersionName = "1.0.0-beta.14", locale = "en")
        vm.load()
        advanceUntilIdle()

        assertEquals(RowState.Current, vm.rows.value.single().state)
    }
}
