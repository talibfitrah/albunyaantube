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
    fun `checkForUpdate short-circuits to null once splashPromptDismissed is set`() = runTest {
        val info = updateInfo()
        val catalog = mock<ReleaseCatalogCache> {
            onBlocking { latest() } doReturn info
        }

        val flow = UpdatePromptFlow(mock(), mock(), catalog)

        // First call hits the catalog.
        flow.checkForUpdate()

        // Simulate "user dismissed the splash prompt this process" — reflectively flip the
        // guard. The public API for that path requires a real Activity context, so we
        // exercise the gating logic directly via reflection.
        flipSplashPromptDismissed(flow, true)

        assertNull(flow.checkForUpdate())
        // Catalog was only queried once across the two calls.
        verify(catalog, times(1)).latest()
    }

    private fun updateInfo() = UpdateInfo(
        versionName = "1.0.0-beta.14",
        releaseName = "v1.0.0-beta.14",
        releaseNotes = "",
        apkUrl = "https://github.test/release.apk",
        apkSizeBytes = 1_024L,
    )

    private fun flipSplashPromptDismissed(flow: UpdatePromptFlow, value: Boolean) {
        val field = UpdatePromptFlow::class.java.getDeclaredField("splashPromptDismissed")
        field.isAccessible = true
        field.setBoolean(flow, value)
    }
}
