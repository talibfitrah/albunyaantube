package com.albunyaan.tube

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * ANDROID-BACKUP-01: Android Auto Backup must stay OFF.
 *
 * With allowBackup="true" the framework copies filesDir to the user's Google
 * Drive. That directory holds downloaded video files (download/DownloadStorage.kt
 * -> context.filesDir), the Room database and the per-install device UUID, so a
 * backup would (a) survive account deletion in a place the app cannot erase,
 * (b) make the privacy policy's "a random identifier for each installation"
 * false once a restore carries the UUID to a new install, and (c) upload
 * YouTube-derived media into Google's infrastructure under this package name.
 *
 * Asserted against the merged manifest so it holds for every flavor.
 */
@RunWith(RobolectricTestRunner::class)
class ManifestBackupTest {

    @Test
    fun `auto backup is disabled in the merged manifest`() {
        val flags = RuntimeEnvironment.getApplication().applicationInfo.flags
        assertEquals(
            "android:allowBackup must stay false - see ANDROID-BACKUP-01 in AndroidManifest.xml",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP
        )
    }
}
