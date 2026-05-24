package com.albunyaan.tube.update

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class InstallSourceTest {

    @Test
    fun `isPlayStore returns true when installer is com_android_vending`() {
        val pm = mock<PackageManager>()
        val info = mock<InstallSourceInfo> {
            whenever(it.installingPackageName).thenReturn("com.android.vending")
        }
        whenever(pm.getInstallSourceInfo("pkg")).thenReturn(info)
        val ctx = mock<Context> {
            whenever(it.packageName).thenReturn("pkg")
            whenever(it.packageManager).thenReturn(pm)
        }

        assertTrue(InstallSource(ctx).isPlayStore())
    }

    @Test
    fun `isPlayStore returns false for sideloaded install`() {
        val pm = mock<PackageManager>()
        val info = mock<InstallSourceInfo> {
            whenever(it.installingPackageName).thenReturn(null)
        }
        whenever(pm.getInstallSourceInfo("pkg")).thenReturn(info)
        val ctx = mock<Context> {
            whenever(it.packageName).thenReturn("pkg")
            whenever(it.packageManager).thenReturn(pm)
        }

        assertFalse(InstallSource(ctx).isPlayStore())
    }

    // --- Legacy path (API 26-29): getInstallerPackageName ---

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    @Suppress("DEPRECATION")
    fun `isPlayStore returns true when legacy installer is com_android_vending`() {
        val pm = mock<PackageManager>()
        whenever(pm.getInstallerPackageName("pkg")).thenReturn("com.android.vending")
        val ctx = mock<Context> {
            whenever(it.packageName).thenReturn("pkg")
            whenever(it.packageManager).thenReturn(pm)
        }

        assertTrue(InstallSource(ctx).isPlayStore())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    @Suppress("DEPRECATION")
    fun `isPlayStore returns false when legacy installer is null`() {
        val pm = mock<PackageManager>()
        whenever(pm.getInstallerPackageName("pkg")).thenReturn(null)
        val ctx = mock<Context> {
            whenever(it.packageName).thenReturn("pkg")
            whenever(it.packageManager).thenReturn(pm)
        }

        assertFalse(InstallSource(ctx).isPlayStore())
    }
}
