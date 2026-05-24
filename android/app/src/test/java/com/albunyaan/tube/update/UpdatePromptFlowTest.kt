package com.albunyaan.tube.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * Unit tests for [UpdatePromptFlow]'s splash-gate idempotency.
 *
 * Locks in the cso I2 / round-2 code-reviewer C1 fix: once the user has acted on the
 * splash prompt this process, a re-entry must short-circuit instead of hitting GitHub
 * again and stacking a second dialog. Pre-fix the guard did not exist and a
 * rotation/recreate would re-show the prompt every time the splash fragment was
 * reattached; the round-2 patch only flips the guard on real user-driven dismissal
 * (lifecycle teardown clears the dismiss listener so it does NOT flip the guard).
 *
 * After the ReleaseCatalogCache migration, [UpdatePromptFlow.checkForUpdate] delegates
 * to [ReleaseCatalogCache.latest] — tests mock the cache, not [UpdateChecker] directly.
 */
class UpdatePromptFlowTest {

    @Test
    fun `checkForUpdate hits the catalog on first call and returns result`() = runTest {
        val info = updateInfo()
        val catalog = mock<ReleaseCatalogCache> {
            onBlocking { latest() } doReturn info
        }

        val flow = UpdatePromptFlow(mock(), mock(), catalog)

        assertEquals(info, flow.checkForUpdate())
        verify(catalog).latest()
    }

    @Test
    fun `checkForUpdate short-circuits to null once promptDismissedThisProcess is set`() = runTest {
        val info = updateInfo()
        val catalog = mock<ReleaseCatalogCache> {
            onBlocking { latest() } doReturn info
        }

        val flow = UpdatePromptFlow(mock(), mock(), catalog)

        // First call hits the catalog.
        flow.checkForUpdate()

        // Simulate "user dismissed the prompt this process" — reflectively flip the
        // guard. The public API for that path requires a real Activity context, so we
        // exercise the gating logic directly via reflection.
        flipPromptDismissedThisProcess(flow, true)

        assertNull(flow.checkForUpdate())
        // Catalog was only queried once across the two calls.
        verify(catalog, times(1)).latest()
    }

    @Test
    fun `showUpdateDialogAndAwait accepts non-latest UpdateInfo and returns once promptDismissedThisProcess`() = runTest {
        // Contract widening: the picker passes UpdateInfo for releases that are NOT
        // necessarily the absolute latest from GitHub. The method has always taken
        // UpdateInfo as a parameter — this test locks that arbitrary instances are
        // accepted, by routing through the promptDismissedThisProcess short-circuit (the
        // only path that doesn't need a live Activity to drive the AlertDialog).
        val catalog = mock<ReleaseCatalogCache> {
            onBlocking { latest() } doReturn null
        }
        val flow = UpdatePromptFlow(mock(), mock(), catalog)
        flipPromptDismissedThisProcess(flow, true)

        val nonLatest = UpdateInfo(
            versionName = "1.0.0-beta.13",  // one behind the running build, hypothetically
            releaseName = "beta-13",
            releaseNotes = "",
            apkUrl = "https://example/test.apk",
            apkSizeBytes = 1024L,
            publishedAt = null,
        )

        // No assertion needed beyond "this returns without throwing." If the method
        // started rejecting non-latest UpdateInfo (e.g. by adding a `require(info ==
        // catalog.latest())` guard), it would throw here and fail.
        flow.showUpdateDialogAndAwait(
            activity = mock(),
            lifecycleOwner = mock(),
            info = nonLatest,
        )
    }

    private fun updateInfo() = UpdateInfo(
        versionName = "1.0.0-beta.14",
        releaseName = "v1.0.0-beta.14",
        releaseNotes = "",
        apkUrl = "https://github.test/release.apk",
        apkSizeBytes = 1_024L,
    )

    private fun flipPromptDismissedThisProcess(flow: UpdatePromptFlow, value: Boolean) {
        val field = UpdatePromptFlow::class.java.getDeclaredField("promptDismissedThisProcess")
        field.isAccessible = true
        field.setBoolean(flow, value)
    }
}
