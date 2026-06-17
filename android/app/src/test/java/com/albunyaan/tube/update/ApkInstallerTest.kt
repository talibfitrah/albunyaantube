package com.albunyaan.tube.update

import android.app.Activity
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `verifySigningCertMatch defers to the OS when certs differ (legacy pre-P)`() {
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
        // Must NOT throw. A differing cert is not something the app can adjudicate from a
        // file parse (it cannot see a v3 rotation lineage on pre-P at all), so it defers to
        // the OS PackageInstaller, which enforces signing-certificate equality / rotation on
        // every update install and rejects a genuinely foreign APK at commit. Hard-throwing
        // here false-blocked legitimate rotated updates for entire device fleets.
        ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/differ.apk"))
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
    // reads from `signingInfo.apkContentsSigners`. The legacy GET_SIGNATURES branch
    // is covered by the default-config tests above.
    //
    // Regression note: a prior implementation read `signingCertificateHistory` for
    // single-signer APKs. That field is null/empty for v2-only APK *files* parsed
    // via `PackageManager.getPackageArchiveInfo`, which made every update download
    // fail with "No signing certificate in downloaded APK" (FitrahTube release APKs
    // ship v2-only). `apkContentsSigners` is populated for v1/v2/v3 archives alike.
    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `verifySigningCertMatch on API P reads apkContentsSigners`() {
        // Production no longer branches on hasMultipleSigners() — apkContentsSigners
        // is the single source of truth for v1/v2/v3 archives. Test name reflects
        // that; no hasMultipleSigners() stub is needed for the path under test.
        val sig = Signature(byteArrayOf(0x0A, 0x0B, 0x0C))
        val signingInfo = mock<SigningInfo> {
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

    /**
     * Regression for the v2-only APK update bug. The downloaded APK's PackageInfo
     * returned `signingCertificateHistory == null` (because v2-only APK files have
     * no v3 lineage block to parse) but `apkContentsSigners` populated. The fixed
     * code reads from `apkContentsSigners` and must accept the match instead of
     * throwing "No signing certificate in downloaded APK".
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `verifySigningCertMatch accepts v2-only APK where signingCertificateHistory is null`() {
        val sig = Signature(byteArrayOf(0x77, 0x77, 0x77))
        val signingInfo = mock<SigningInfo> {
            whenever(it.apkContentsSigners).thenReturn(arrayOf(sig))
            // The bug we're regressing: production used to read
            // signingCertificateHistory, which on a v2-only archive is null/empty.
            // No need to stub history — production no longer touches it.
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
        // Must not throw — the bug was throwing here even though certs match.
        ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/p-v2only.apk"))
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
    fun `verifySigningCertMatch defers on API P when a rotated signer is unprovable from the archive lineage`() {
        // This is the exact field failure: an installed debug-key build is offered a
        // prod-key-rotated update, but getPackageArchiveInfo() returns the new signer with an
        // EMPTY signingCertificateHistory (observed on Huawei EMUI; stock AOSP returns the full
        // lineage and the sibling "accepts a rotated APK" test covers that path). The app cannot
        // prove the rotation here, so it must NOT hard-block — it defers to the OS, which
        // verifies the v3 lineage cryptographically on commit and installs the valid rotation.
        val installedSig = Signature(byteArrayOf(0x0A))
        val rotatedSig = Signature(byteArrayOf(0xB.toByte()))
        val installedSi = mock<SigningInfo> {
            whenever(it.apkContentsSigners).thenReturn(arrayOf(installedSig))
        }
        val rotatedSi = mock<SigningInfo> {
            whenever(it.apkContentsSigners).thenReturn(arrayOf(rotatedSig))
            // history empty / unreadable from the archive on this ROM
            whenever(it.signingCertificateHistory).thenReturn(null)
        }
        val installedInfo = packageInfoP(installedSi)
        val rotatedInfo = packageInfoP(rotatedSi)
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(installedInfo)
            whenever(it.getPackageArchiveInfo(any(), any<Int>())).thenReturn(rotatedInfo)
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        // Must NOT throw — defer to the OS PackageInstaller.
        ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/p-rotated-noLineage.apk"))
    }

    @Suppress("DEPRECATION")
    private fun packageInfoNoSig(pkg: String = "pkg"): PackageInfo =
        PackageInfo().apply {
            packageName = pkg
            signatures = null
        }

    /**
     * Regression for the old-Android update-install failure (Android <= 8.1, pre-P).
     * On API < 28 the legacy GET_SIGNATURES branch reads `info.signatures`, which is
     * null for a v2-only APK *file* (no v1/JAR block). The old code then threw
     * "No signing certificate in downloaded APK" and the update aborted before
     * install. The fix fails open on pre-P: the OS PackageInstaller still enforces
     * signing-cert equality on update, so this must NOT throw. (@Config sdk=O = API 26.)
     */
    @Test
    fun `verifySigningCertMatch fails open on pre-P when archive cert unreadable (v2-only)`() {
        val sig = Signature(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(packageInfoWith(sig))
            whenever(it.getPackageArchiveInfo(any(), any<Int>())).thenReturn(packageInfoNoSig("pkg"))
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        // Must NOT throw on API 26 (pre-P) — this is the historical install failure.
        ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/v2only-preP.apk"))
    }

    /**
     * On API P+ an unreadable archive cert (null SigningInfo from getPackageArchiveInfo)
     * must ALSO fail open. The earlier assumption that "the archive cert is always readable
     * on API 28+" is false on some OEM ROMs: a real Huawei/Honor EMUI 9.1 device (API 28)
     * returns signingInfo == null for the update archive, which made the app hard-throw
     * "No signing certificate in downloaded APK" and surface a bogus "signature mismatch"
     * toast — killing auto-update on every EMUI device. The OS PackageInstaller reads the
     * real signature on install and enforces it, so the app defers instead of false-blocking.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `verifySigningCertMatch defers on API P plus when archive cert unreadable (EMUI null SigningInfo)`() {
        val installedSig = Signature(byteArrayOf(0x0A))
        val installedSi = mock<SigningInfo> {
            whenever(it.apkContentsSigners).thenReturn(arrayOf(installedSig))
        }
        // Faithful to the measured device behaviour: EMUI 9.1 (real Honor COR-L29, API 28)
        // returns a PackageInfo whose signingInfo is NULL for the update archive, so
        // certSha256(downloaded) resolves to null and the downloadedSha==null branch runs.
        val downloadedInfo = PackageInfo().apply { packageName = "pkg" } // signingInfo defaults to null
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(packageInfoP(installedSi))
            whenever(it.getPackageArchiveInfo(any(), any<Int>())).thenReturn(downloadedInfo)
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        // Must NOT throw — defer to the OS PackageInstaller, which enforces the signature.
        ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/p-noreadcert.apk"))
    }

    /**
     * The defer-to-OS fix narrows the app's role, but the INSTALLED app's signer is read from
     * system records (not the OEM-unreliable archive file parse), so an unreadable installed
     * cert is a genuine "can't identify our own signer" failure and must STILL hard-abort.
     * This pins the last app-level cert guard so a future change can't silently weaken it.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `verifySigningCertMatch still throws when the installed app cert is unreadable`() {
        val installedNoSi = PackageInfo().apply { packageName = "pkg" } // signingInfo null
        val downloadedSi = mock<SigningInfo> {
            whenever(it.apkContentsSigners).thenReturn(arrayOf(Signature(byteArrayOf(0x0A))))
        }
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(installedNoSi)
            whenever(it.getPackageArchiveInfo(any(), any<Int>())).thenReturn(packageInfoP(downloadedSi))
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        try {
            ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/installed-nosig.apk"))
            fail("Expected SecurityException — unreadable installed signer must still abort")
        } catch (e: SecurityException) {
            // expected
        }
    }

    /**
     * Security: the updater must never fetch an APK over a plaintext URL. The guard
     * runs before any network/file IO, so a bare mock Context is fine — it throws on
     * the scheme alone.
     */
    @Test
    fun `download refuses a non-HTTPS APK URL`() {
        val context = mock<android.content.Context>()
        try {
            kotlinx.coroutines.runBlocking {
                ApkInstaller(OkHttpClient()).download(context, "http://evil.example/app.apk")
            }
            fail("Expected SecurityException for non-HTTPS APK URL")
        } catch (e: SecurityException) {
            // expected
        }
    }

    /**
     * v3 key rotation (the beta.30 debug-key -> beta.31 production-key migration). The
     * downloaded APK is signed by a NEW key, but its verified signingCertificateHistory
     * lineage contains the installed OLD key, proving the old key authorised the rotation.
     * The updater MUST accept it so beta.29/30 users auto-update onto the production key.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `verifySigningCertMatch accepts a rotated APK whose lineage contains the installed cert`() {
        val oldKey = Signature(byteArrayOf(0x0D, 0x0E, 0x0B)) // installed (debug)
        val newKey = Signature(byteArrayOf(0x0F, 0x10, 0x12)) // downloaded current signer (production)
        val installedSi = mock<SigningInfo> {
            whenever(it.apkContentsSigners).thenReturn(arrayOf(oldKey))
        }
        val rotatedSi = mock<SigningInfo> {
            whenever(it.apkContentsSigners).thenReturn(arrayOf(newKey))
            whenever(it.signingCertificateHistory).thenReturn(arrayOf(oldKey, newKey))
        }
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(packageInfoP(installedSi))
            whenever(it.getPackageArchiveInfo(any(), any<Int>())).thenReturn(packageInfoP(rotatedSi))
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        // Must NOT throw — installed cert is an ancestor in the verified lineage.
        ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/rotated.apk"))
    }

    /**
     * A rotated APK whose lineage does NOT contain the installed cert (some other key's
     * hand-off) is a genuinely foreign update. The app no longer adjudicates this from the
     * archive — it defers to the OS PackageInstaller, which rejects the foreign rotation at
     * commit (cryptographically verified, unlike the app's unreliable file-parse). The app
     * MUST defer rather than hard-throw here, because the same "installed cert absent from
     * the archive lineage" shape is produced by a perfectly valid rotation on OEM ROMs that
     * don't surface signingCertificateHistory for a file — and false-blocking those killed
     * auto-update fleet-wide. OS-level rejection of a truly foreign signer is verified out of
     * band (an `adb install -r` of a foreign-signed APK over the install fails).
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `verifySigningCertMatch defers when a rotated APK's lineage excludes the installed cert`() {
        val installedKey = Signature(byteArrayOf(0x11, 0x22, 0x33))
        val strangerOld = Signature(byteArrayOf(0x44, 0x55, 0x66))
        val newKey = Signature(byteArrayOf(0x70, 0x71, 0x72))
        val installedSi = mock<SigningInfo> {
            whenever(it.apkContentsSigners).thenReturn(arrayOf(installedKey))
        }
        val foreignRotatedSi = mock<SigningInfo> {
            whenever(it.apkContentsSigners).thenReturn(arrayOf(newKey))
            whenever(it.signingCertificateHistory).thenReturn(arrayOf(strangerOld, newKey))
        }
        val pm = mock<PackageManager> {
            whenever(it.getPackageInfo(eq("pkg"), any<Int>())).thenReturn(packageInfoP(installedSi))
            whenever(it.getPackageArchiveInfo(any(), any<Int>())).thenReturn(packageInfoP(foreignRotatedSi))
        }
        val activity = mock<Activity> {
            whenever(it.packageManager).thenReturn(pm)
            whenever(it.packageName).thenReturn("pkg")
        }
        // Must NOT throw — the app defers; the OS rejects a foreign rotation on commit.
        ApkInstaller(OkHttpClient()).verifySigningCertMatch(activity, File("/tmp/foreign-rotated.apk"))
    }

    @Test
    fun `isUpdateCertAcceptable accepts the same cert and a cert present in the lineage`() {
        val installer = ApkInstaller(OkHttpClient())
        assertTrue(installer.isUpdateCertAcceptable("AA", "AA", emptyList()))
        assertTrue(installer.isUpdateCertAcceptable("OLD", "NEW", listOf("OLD", "NEW")))
    }

    @Test
    fun `isUpdateCertAcceptable rejects a mismatch absent from the lineage`() {
        val installer = ApkInstaller(OkHttpClient())
        assertFalse(installer.isUpdateCertAcceptable("OLD", "NEW", emptyList()))
        assertFalse(installer.isUpdateCertAcceptable("OLD", "NEW", listOf("X", "NEW")))
    }
}
