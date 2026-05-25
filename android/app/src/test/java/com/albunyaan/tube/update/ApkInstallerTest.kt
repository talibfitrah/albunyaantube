package com.albunyaan.tube.update

import android.app.Activity
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import okhttp3.OkHttpClient
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for the cert-pinning hook added in response to cso S2-2. Default
 * `@Config` targets API 26 so the legacy `GET_SIGNATURES` path runs; the
 * `signingInfo path` test below pins API P+ so the modern
 * `GET_SIGNING_CERTIFICATES` branch runs (stage-6 testing T1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O])
class ApkInstallerTest {

    @Suppress("DEPRECATION")
    private fun packageInfoWith(sig: Signature, pkg: String = "pkg"): PackageInfo =
        PackageInfo().apply {
            packageName = pkg
            signatures = arrayOf(sig)
        }

    private fun packageInfoP(signingInfo: SigningInfo, pkg: String = "pkg"): PackageInfo =
        PackageInfo().apply {
            packageName = pkg
            this.signingInfo = signingInfo
        }

    @Test
    fun `verifySigningCertMatch passes when downloaded cert equals installed cert`() {
        val sig = Signature(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(packageInfoWith(sig))
            whenever(it.getPackageArchiveInfo(any(), any<Int>())).thenReturn(packageInfoWith(sig))
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        // Does not throw — same cert hash on both sides.
        ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/match.apk"))
    }

    @Test
    fun `verifySigningCertMatch throws SecurityException when certs differ`() {
        val installed = Signature(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val attacker = Signature(byteArrayOf(0x05, 0x06, 0x07, 0x08))
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(packageInfoWith(installed))
            whenever(it.getPackageArchiveInfo(any(), any<Int>())).thenReturn(packageInfoWith(attacker))
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        try {
            ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/evil.apk"))
            fail("Expected SecurityException for mismatched signing certificates")
        } catch (e: SecurityException) {
            // expected
        }
    }

    @Test
    fun `verifySigningCertMatch throws SecurityException when archive has no readable cert`() {
        val sig = Signature(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(packageInfoWith(sig))
            whenever(it.getPackageArchiveInfo(any(), any<Int>())).thenReturn(null)
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        try {
            ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/missing.apk"))
            fail("Expected SecurityException for unreadable archive")
        } catch (e: SecurityException) {
            // expected
        }
    }

    // stage-6 testing T1: API P+ takes the GET_SIGNING_CERTIFICATES branch which
    // reads from `signingInfo.signingCertificateHistory` (single signer) or
    // `signingInfo.apkContentsSigners` (multi-signer per `hasMultipleSigners`).
    // The default-config tests above cover the legacy GET_SIGNATURES branch only.
    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `verifySigningCertMatch on API P uses signingInfo signingCertificateHistory branch`() {
        val sig = Signature(byteArrayOf(0x0A, 0x0B, 0x0C))
        val signingInfo = mock<SigningInfo> {
            whenever(it.hasMultipleSigners()).thenReturn(false)
            whenever(it.signingCertificateHistory).thenReturn(arrayOf(sig))
        }
        val info = packageInfoP(signingInfo)
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(info)
            whenever(it.getPackageArchiveInfo(any(), any<Int>())).thenReturn(info)
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        // Same cert on both sides → no throw.
        ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/p-match.apk"))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `verifySigningCertMatch on API P uses apkContentsSigners when hasMultipleSigners`() {
        val sig = Signature(byteArrayOf(0x0A, 0x0B, 0x0C))
        val signingInfo = mock<SigningInfo> {
            whenever(it.hasMultipleSigners()).thenReturn(true)
            whenever(it.apkContentsSigners).thenReturn(arrayOf(sig))
        }
        val info = packageInfoP(signingInfo)
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(info)
            whenever(it.getPackageArchiveInfo(any(), any<Int>())).thenReturn(info)
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/p-multi.apk"))
    }

    // codex stage-6 MEDIUM: package-name match is checked BEFORE cert compare.
    // A same-signed APK with a different applicationId (debug flavor, foreign
    // package) would otherwise pass cert verification and side-by-side install
    // instead of upgrading.
    @Test
    fun `verifySigningCertMatch throws SecurityException when packageName differs`() {
        val sig = Signature(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(packageInfoWith(sig, "pkg"))
            whenever(it.getPackageArchiveInfo(any(), any<Int>()))
                .thenReturn(packageInfoWith(sig, "pkg.evil"))
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        try {
            ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/foreign.apk"))
            fail("Expected SecurityException for mismatched packageName")
        } catch (e: SecurityException) {
            // expected
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `verifySigningCertMatch on API P throws when signingInfo certs differ`() {
        val installedSig = Signature(byteArrayOf(0x0A))
        val attackerSig = Signature(byteArrayOf(0xB.toByte()))
        val installedSi = mock<SigningInfo> {
            whenever(it.hasMultipleSigners()).thenReturn(false)
            whenever(it.signingCertificateHistory).thenReturn(arrayOf(installedSig))
        }
        val attackerSi = mock<SigningInfo> {
            whenever(it.hasMultipleSigners()).thenReturn(false)
            whenever(it.signingCertificateHistory).thenReturn(arrayOf(attackerSig))
        }
        val installedInfo = packageInfoP(installedSi)
        val attackerInfo = packageInfoP(attackerSi)
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(installedInfo)
            whenever(it.getPackageArchiveInfo(any(), any<Int>())).thenReturn(attackerInfo)
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        try {
            ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/p-evil.apk"))
            fail("Expected SecurityException for mismatched signing certificates on API P")
        } catch (e: SecurityException) {
            // expected
        }
    }
}
