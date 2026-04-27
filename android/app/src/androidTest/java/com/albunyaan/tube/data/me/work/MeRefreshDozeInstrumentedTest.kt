package com.albunyaan.tube.data.me.work

import android.content.Context
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
 *  3. Triggers the JobScheduler tick via `cmd jobscheduler run -f` so the
 *     worker runs immediately under Doze instead of waiting for the OS
 *     window.
 *  4. Polls [WorkManager.getWorkInfoById] in a 500 ms loop with a 30 s
 *     deadline (Awaitility is not on the androidTest classpath — we use
 *     the same hand-rolled timeout shape as the rest of the suite).
 *  5. Always restores the device idle state with `dumpsys deviceidle
 *     unforce` in the finally block, regardless of test outcome.
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

            // Force the JobScheduler to run our job *now* under Doze.
            executeShellCommand("cmd jobscheduler run -f ${context.packageName} ${request.id}")

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
     * Run a shell command via UiAutomation and close the returned file
     * descriptor immediately — we don't read the output, but leaving the
     * fd open leaks per [androidx.test.platform.app.InstrumentationRegistry]
     * documentation.
     */
    private fun executeShellCommand(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd)
            .use { /* close pfd */ }
    }

    companion object {
        private const val DEADLINE_MS: Long = 30_000L
        private const val POLL_MS: Long = 500L
    }
}
