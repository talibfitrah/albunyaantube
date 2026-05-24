package com.albunyaan.tube.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [UpdatePromptFlow]'s splash-gate idempotency.
 *
 * Locks in the cso I2 / round-2 code-reviewer C1 fix: once the user has acted on the
 * splash prompt this process, a re-entry must short-circuit instead of hitting GitHub
 * again and stacking a second dialog. Pre-fix the guard did not exist and a
 * rotation/recreate would re-show the prompt every time the splash fragment was
 * reattached; the round-2 patch only flips the guard on real user-driven dismissal
 * (lifecycle teardown clears the dismiss listener so it does NOT flip the guard).
 */
class UpdatePromptFlowTest {

    @Test
    fun `checkForUpdate hits the network on first call and returns result`() = runTest {
        val checker = mock<UpdateChecker>()
        val info = updateInfo()
        whenever(checker.checkForUpdate()).thenReturn(Result.success(info))

        val flow = UpdatePromptFlow(checker, mock())

        assertEquals(info, flow.checkForUpdate())
        verify(checker).checkForUpdate()
    }

    @Test
    fun `checkForUpdate short-circuits to null once splashPromptDismissed is set`() = runTest {
        val checker = mock<UpdateChecker>()
        val info = updateInfo()
        whenever(checker.checkForUpdate()).thenReturn(Result.success(info))

        val flow = UpdatePromptFlow(checker, mock())

        // First call hits the network.
        flow.checkForUpdate()

        // Simulate "user dismissed the splash prompt this process" — reflectively flip the
        // guard. The public API for that path requires a real Activity context, so we
        // exercise the gating logic directly via reflection.
        flipSplashPromptDismissed(flow, true)

        assertNull(flow.checkForUpdate())
        // Network was only hit once across the two calls.
        verify(checker).checkForUpdate()
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
