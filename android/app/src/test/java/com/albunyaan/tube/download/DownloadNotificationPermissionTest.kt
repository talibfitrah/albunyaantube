package com.albunyaan.tube.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANDROID-PLAY-02: POST_NOTIFICATIONS was declared in the manifest but never
 * requested, so on Android 13+ download-progress notifications silently never
 * appeared. These cover the "should we ask?" predicate, which is the only part
 * of the flow that can be exercised without a device.
 */
class DownloadNotificationPermissionTest {

    @Test
    fun `does not request below api 33 where the permission does not exist`() {
        assertFalse(DownloadNotificationPermission.shouldRequest(sdkInt = 32, granted = false))
    }

    @Test
    fun `requests on api 33 when not yet granted`() {
        assertTrue(DownloadNotificationPermission.shouldRequest(sdkInt = 33, granted = false))
    }

    @Test
    fun `does not request on api 33 when already granted`() {
        assertFalse(DownloadNotificationPermission.shouldRequest(sdkInt = 33, granted = true))
    }

    @Test
    fun `requests above api 33 when not yet granted`() {
        assertTrue(DownloadNotificationPermission.shouldRequest(sdkInt = 36, granted = false))
    }
}
