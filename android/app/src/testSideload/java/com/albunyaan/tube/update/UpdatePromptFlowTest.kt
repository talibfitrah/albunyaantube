package com.albunyaan.tube.update

import com.albunyaan.tube.ui.SplashFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
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

        val flow = UpdatePromptFlow(mock(), mock(), catalog, mock())

        assertEquals(info, flow.checkForUpdate())
        verify(catalog).latest()
    }

    @Test
    fun `checkForUpdate short-circuits to null once promptDismissedThisProcess is set`() = runTest {
        val info = updateInfo()
        val catalog = mock<ReleaseCatalogCache> {
            onBlocking { latest() } doReturn info
        }

        val flow = UpdatePromptFlow(mock(), mock(), catalog, mock())

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
        val flow = UpdatePromptFlow(mock(), mock(), catalog, mock())
        flipPromptDismissedThisProcess(flow, true)

        val nonLatest = UpdateInfo(
            versionName = "1.0.0-beta.13",  // one behind the running build, hypothetically
            releaseName = "beta-13",
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

    /**
     * Regression: the splash probe must outlive a realistic cold-start fetch.
     *
     * SplashFragment animates for [SplashFragment.SPLASH_PRE_AWAIT_MS] (2750 ms) and
     * only THEN awaits this result, so a probe finishing inside that window is on
     * time and costs the user nothing. The budget used to be 2000 ms, which killed
     * the probe before the splash even wanted the answer, and withTimeoutOrNull
     * reports that as null — no dialog, no log, indistinguishable from "up to date".
     * This delay sits above the old budget and below the new one, so it fails on the
     * old constant and passes on the new one.
     */
    @Test
    fun `probe slower than the old budget still yields a prompt`() = runTest {
        val info = updateInfo()
        val catalog = mock<ReleaseCatalogCache> {
            onBlocking { latest() } doSuspendableAnswer {
                delay(2_500)
                info
            }
        }

        val flow = UpdatePromptFlow(mock(), mock(), catalog, mock())

        assertEquals(info, flow.checkForUpdate())
    }

    /**
     * Guards the direction that actually gets edited: nobody should lower
     * CHECK_TIMEOUT_MS back under the splash animation, which is the gap that caused
     * the missed-update bug. It compares two hand-maintained constants, so it does NOT
     * catch someone ADDING a splash phase without updating SPLASH_PRE_AWAIT_MS — that
     * one is covered only by the warning on the constant itself.
     */
    @Test
    fun `probe budget is not shorter than the splash animation it runs behind`() {
        assertTrue(
            "CHECK_TIMEOUT_MS (${UpdatePromptFlow.CHECK_TIMEOUT_MS}) must be >= " +
                "SPLASH_PRE_AWAIT_MS (${SplashFragment.SPLASH_PRE_AWAIT_MS})",
            UpdatePromptFlow.CHECK_TIMEOUT_MS >= SplashFragment.SPLASH_PRE_AWAIT_MS,
        )
    }

    /**
     * The budget is bounded for CANCELLABLE work. Note this shape cannot reproduce the
     * production stall, where a thread is parked in Call.execute() and coroutine
     * cancellation cannot unwind it (see cancelWhenCoroutineCancels) — bounding that
     * needs an OkHttp callTimeout, which is a separate change.
     */
    @Test
    fun `probe that outruns the budget gives up (cancellable work only)`() = runTest {
        val catalog = mock<ReleaseCatalogCache> {
            onBlocking { latest() } doSuspendableAnswer {
                delay(60_000)
                updateInfo()
            }
        }

        val flow = UpdatePromptFlow(mock(), mock(), catalog, mock())

        assertNull(flow.checkForUpdate())
    }

    private fun updateInfo() = UpdateInfo(
        versionName = "1.0.0-beta.14",
        releaseName = "v1.0.0-beta.14",
        apkUrl = "https://github.test/release.apk",
        apkSizeBytes = 1_024L,
    )

    private fun flipPromptDismissedThisProcess(flow: UpdatePromptFlow, value: Boolean) {
        val field = UpdatePromptFlow::class.java.getDeclaredField("promptDismissedThisProcess")
        field.isAccessible = true
        field.setBoolean(flow, value)
    }
}
