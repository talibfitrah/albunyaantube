package com.albunyaan.tube.data.me.work

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ANDROID-PERSONAL-02 / T12: instrumented verification that
 * [RefreshSubscriptionsWorker] still completes when the OS is in Doze.
 *
 * OEM-specific behaviour (Samsung/Xiaomi/Huawei) is not validated by
 * this test — they require physical device QA per spec §10.
 *
 * The test:
 *  1. Forces the device into Doze idle via `dumpsys deviceidle force-idle`.
 *  2. Enqueues a one-shot [RefreshSubscriptionsWorker] through the real
 *     WorkManager — Hilt's [androidx.hilt.work.HiltWorkerFactory] builds
 *     it with the [com.albunyaan.tube.data.me.MeFeedRepository] +
 *     [com.albunyaan.tube.data.me.MeRefreshTelemetry] graph from
 *     [com.albunyaan.tube.AlBunyaanApplication] (HiltTestApplication uses
 *     the same factory binding).
 *  3. Polls [WorkManager.getWorkInfoById] in a 500 ms loop with a 60 s
 *     deadline (Awaitility is not on the androidTest classpath — we use
 *     the same hand-rolled timeout shape as the rest of the suite).
 *     `dumpsys deviceidle force-idle` permits Doze maintenance windows
 *     to fire JobScheduler jobs without an explicit kick — the longer
 *     deadline gives one of those windows time to land.
 *  4. Always restores the device idle state with `dumpsys deviceidle
 *     unforce` in the finally block, regardless of test outcome.
 *
 * ANDROID-PERSONAL-02 [Bug 5]: the prior implementation issued
 * `cmd jobscheduler run -f <pkg> <work-request-uuid>`. JobScheduler's
 * shell command expects the integer JobScheduler job ID, not the
 * WorkRequest UUID — so the kick never landed and the test only
 * "passed" if the worker happened to run normally before the deadline.
 * The shell-command stdout/stderr was also discarded (the
 * [java.io.FileDescriptor] from `executeShellCommand` was closed
 * without being read), so the silent failure was invisible. Both
 * issues are fixed: the bogus kick is dropped (Doze maintenance is
 * sufficient for this verification), the deadline is doubled to 60 s,
 * and shell output is logged.
 *
 * Note on configuration: this test relies on the `Configuration.Provider`
 * the production [com.albunyaan.tube.AlBunyaanApplication] installs (see
 * [androidx.hilt.work.HiltWorkerFactory]). HiltTestApplication does not
 * implement `Configuration.Provider` itself, so `WorkManager.initialize`
 * is called by the platform's auto-init at app startup with the default
 * factory. Under HiltTestApplication this test still works because the
 * worker's only injectable dependencies are `@Singleton` and resolvable
 * from the EntryPoint without test-specific bindings.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MeRefreshDozeInstrumentedTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun worker_runs_under_doze() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        executeShellCommand("dumpsys deviceidle force-idle")
        try {
            val request = OneTimeWorkRequestBuilder<RefreshSubscriptionsWorker>().build()
            val workManager = WorkManager.getInstance(context)
            workManager.enqueue(request).result.get()

            // ANDROID-PERSONAL-02 [Bug 5]: do NOT call
            // `cmd jobscheduler run -f <pkg> <id>` here — `request.id`
            // is the WorkRequest UUID, but `cmd jobscheduler` expects
            // the integer JobScheduler job id (which is not exposed by
            // WorkManager's public API). The prior version of this test
            // passed a UUID where the shell expected an integer; the
            // shell silently rejected it and the test was just polling
            // for the worker to run on its own. Doze maintenance
            // windows under `force-idle` still permit JobScheduler to
            // fire jobs, so we rely on the natural maintenance schedule
            // and a longer poll deadline below.

            val deadline = System.currentTimeMillis() + DEADLINE_MS
            var lastState: WorkInfo.State = WorkInfo.State.ENQUEUED
            do {
                val info = workManager.getWorkInfoById(request.id).get()
                if (info != null) {
                    lastState = info.state
                    if (lastState == WorkInfo.State.SUCCEEDED) return
                    if (lastState == WorkInfo.State.FAILED ||
                        lastState == WorkInfo.State.CANCELLED
                    ) {
                        fail("worker did not succeed under Doze (state=$lastState)")
                    }
                }
                Thread.sleep(POLL_MS)
            } while (System.currentTimeMillis() < deadline)

            fail("worker did not finish within ${DEADLINE_MS}ms under Doze (last state=$lastState)")
        } finally {
            // ALWAYS restore the device idle state, even on test failure.
            executeShellCommand("dumpsys deviceidle unforce")
        }
    }

    /**
     * Run a shell command via UiAutomation. Reads the resulting file
     * descriptor's contents and logs them — the prior version closed
     * the descriptor without reading, which silently swallowed shell
     * errors (e.g. malformed command, missing argument) and made the
     * Bug 5 jobscheduler-UUID misuse invisible. Closing without reading
     * is also a small leak per
     * [androidx.test.platform.app.InstrumentationRegistry] documentation.
     */
    private fun executeShellCommand(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd)
        try {
            // Drain the descriptor so the command's stdout/stderr is
            // visible in test logs. We don't fail the test on shell
            // errors (some commands legitimately produce stderr); we
            // just make the output observable.
            val out = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                .use { it.readBytes().toString(Charsets.UTF_8) }
            if (out.isNotEmpty()) {
                android.util.Log.d(TAG, "[$cmd] -> $out")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "[$cmd] read failed", t)
        }
    }

    companion object {
        private const val TAG = "MeRefreshDozeTest"

        // ANDROID-PERSONAL-02 [Bug 5]: extended from 30 s to 60 s. The
        // prior 30 s value relied on a (broken) `cmd jobscheduler run
        // -f` kick to bypass Doze maintenance windows. Without that
        // kick, the worker waits for a natural maintenance window —
        // those land within ~1 minute of `force-idle` on AOSP-flavoured
        // emulators, so 60 s gives one window of safety margin.
        private const val DEADLINE_MS: Long = 60_000L
        private const val POLL_MS: Long = 500L
    }
}
