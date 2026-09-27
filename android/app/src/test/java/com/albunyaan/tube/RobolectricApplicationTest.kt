package com.albunyaan.tube

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests must not boot the production AlBunyaanApplication. Its onCreate
 * enqueues real periodic work with a battery-not-low constraint on the
 * process-static WorkManager, whose constraint tracker registers a receiver on
 * a WM.task-* thread after Robolectric has torn the sandbox down. The NPE
 * (`activityThread` is null) then surfaces as UncaughtExceptionsBeforeTest in
 * whichever runTest-based class runs next — ChannelVideoCacheDaoTest,
 * ChannelDetailViewModelTest, … at random.
 * robolectric.properties pins `application=android.app.Application`.
 */
@RunWith(RobolectricTestRunner::class)
class RobolectricApplicationTest {

    @Test fun `the default Robolectric application is not the production app`() {
        assertFalse(ApplicationProvider.getApplicationContext<android.app.Application>() is AlBunyaanApplication)
    }

    @Test fun `booting a test schedules no real WorkManager work`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val initialized = runCatching { WorkManager.getInstance(context) }.isSuccess
        // A plain Application is not a Configuration.Provider and WorkManager's
        // startup initializer is not run by Robolectric, so nothing is running.
        assertFalse("WorkManager must not be initialized by the test application", initialized)
    }
}
