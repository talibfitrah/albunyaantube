package com.albunyaan.tube.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an APK from a URL into the app's cacheDir, then hands off to the
 * system package installer via the [PackageInstaller] session API. Streams the
 * APK bytes directly into the session — FileProvider is no longer needed
 * because the OS reads from the session, not from our content URI.
 *
 * Caller must hold [android.Manifest.permission.REQUEST_INSTALL_PACKAGES] in the manifest
 * (declared by [UpdateChecker]'s consumer module) and — on Android 8.0+ — the user must
 * have granted "install unknown apps" for this app; use [isInstallPermissionGranted] +
 * [openInstallPermissionSettings] to guide the user through granting it.
 *
 * The PackageInstaller commit→PendingIntent loop delivers the result (success
 * or failure code) to [InstallStatusActivity], which persists the outcome via
 * [LastInstallAttempt] so the splash gate can surface "didn't complete: <reason>"
 * instead of re-prompting silently after an OEM-rejected install (the beta.15
 * failure mode reported on Huawei EMUI 9).
 */
@Singleton
class ApkInstaller @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    /**
     * Streams the APK to `cacheDir/updates/<file>`. Returns the File on success or throws.
     * Progress reporting can be wired via the optional [onProgress] callback (0..1).
     *
     * When [expectedSizeBytes] is provided (from the GitHub release asset metadata), the
     * downloaded file's size is verified against it after the stream closes. A CDN that
     * returns `200 OK` with a truncated body (reverse proxy dropped the connection, CF
     * bug, etc.) would otherwise hand a corrupt APK to the system installer. Defense in
     * depth — the OS will eventually reject mismatched signatures, but catching the
     * truncation earlier gives the user a clear error instead of an opaque installer
     * failure. Flagged by code-reviewer (I1) and codex (Medium).
     */
    suspend fun download(
        context: Context,
        apkUrl: String,
        expectedSizeBytes: Long? = null,
        onProgress: ((Float) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Overwrite any prior download to avoid stale APKs occupying cache.
        updatesDir.listFiles()?.forEach { it.delete() }
        val target = File(updatesDir, "fitrahtube-update.apk")

        val request = Request.Builder().url(apkUrl).build()
        // The shared OkHttpClient has a 20s readTimeout sized for snappy API calls —
        // far too tight for a multi-MB APK on a slow link, where any chunk read that
        // blocks longer than 20s would throw SocketTimeoutException and surface as
        // the misleading "Download failed" toast. Clone with a generous per-chunk
        // timeout. The cloned client shares the parent's Dispatcher, ConnectionPool,
        // and interceptors (per OkHttp docs, `newBuilder()` carries those refs
        // through) so no resource leak on each Update tap.
        //
        // callTimeout bounds the whole download: 20 min @ 75 MB = 64 KB/s minimum
        // throughput. Anything slower is effectively broken anyway and prevents a
        // slow-loris payload from holding downloadMutex indefinitely.
        val downloadClient = okHttpClient.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.MINUTES)
            .build()
        val call = downloadClient.newCall(request).cancelWhenCoroutineCancels()
        call.execute().use { response ->
            require(response.isSuccessful) {
                "APK download failed: HTTP ${response.code}"
            }
            val body = response.body ?: error("APK response had no body")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var sum = 0L
                    // Use !=-1 for EOF per InputStream contract (CodeRabbit #2): read()
                    // may legally return 0 on a slow/chunked stream when no bytes are
                    // available yet without being end-of-stream; the `> 0` form would
                    // terminate early and truncate the APK silently.
                    var read = input.read(buffer)
                    while (read != -1) {
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            sum += read
                            if (total > 0 && onProgress != null) {
                                onProgress(sum.toFloat() / total)
                            }
                        }
                        read = input.read(buffer)
                    }
                }
            }
        }
        if (expectedSizeBytes != null && expectedSizeBytes > 0) {
            val actual = target.length()
            if (actual != expectedSizeBytes) {
                // Leave the partial file in place for forensic inspection; caller decides
                // what to do on failure (retry, surface error to user).
                error("APK size mismatch: expected=$expectedSizeBytes actual=$actual")
            }
        }
        target
    }

    /**
     * Streams [apkFile] into a [PackageInstaller] session and commits it. The OS
     * delivers the result asynchronously to [InstallStatusActivity] (registered
     * in the manifest); on Android 8+ the receiver also handles the
     * STATUS_PENDING_USER_ACTION leg that shows the user-confirmation activity.
     *
     * Caller MUST have already invoked [verifySigningCertMatch] (typically on
     * the IO dispatcher — the APK parse is heavy enough to ANR if it lands on
     * Main — cubic R2 P1). `launchInstaller` itself spends most of its time
     * copying the APK file into the session, which is IO-bound; ApkInstaller's
     * caller in [UpdatePromptFlow] already runs this off Main.
     *
     * Why PackageInstaller and not Intent.ACTION_INSTALL_PACKAGE: the legacy
     * intent is deprecated since API 14 and has no success/failure callback —
     * the caller gets a fire-and-forget that may silently no-op on OEM-modified
     * Android (observed on Huawei EMUI 9 in beta.15, where multiple in-app
     * install attempts never moved `lastUpdateTime` in `dumpsys package`).
     * PackageInstaller's commit→PendingIntent loop delivers an explicit
     * STATUS_SUCCESS / STATUS_FAILURE_* so we can persist the outcome to
     * [LastInstallAttempt] and surface a useful "didn't complete" banner on the
     * next splash instead of just re-prompting silently.
     *
     * @param targetVersion the versionName we're trying to install; rides along
     *   inside the status PendingIntent so the receiver can persist the right
     *   target even after our process is killed by the OS during install.
     */
    suspend fun launchInstaller(activity: Activity, apkFile: File, targetVersion: String) {
        val packageInstaller = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(activity.packageName)
        // Set size up front so PackageInstaller can validate streaming bytes
        // against the declared total and fail fast on truncation.
        params.setSize(apkFile.length())
        val sessionId = try {
            packageInstaller.createSession(params)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create PackageInstaller session", t)
            throw t
        }
        var session: PackageInstaller.Session? = null
        try {
            // openWrite + copyTo + fsync is multi-MB synchronous IO. On slow OEM
            // flash (Huawei EMUI 9 / Samsung A-series — exactly the devices
            // PackageInstaller migration was meant to help) this takes
            // 200-1500 ms. The original signature accepted the caller's
            // dispatcher; UpdatePromptFlow wrapped launchInstaller in
            // withContext(Dispatchers.Main) so the streaming pinned the UI
            // thread → ANR right before the system installer appeared.
            // Pushing the streaming into Dispatchers.IO here moves the heavy
            // work off Main regardless of how the caller invokes us (Stage 3
            // codex review P1). The commit + close still run on IO too; both
            // are binder-cheap and there's no benefit to ping-ponging back to
            // Main mid-flow.
            withContext(Dispatchers.IO) {
                session = packageInstaller.openSession(sessionId)
                // Stream APK bytes into the session. `base.apk` is the canonical
                // single-APK filename PackageInstaller expects for a full install.
                session!!.openWrite("base.apk", 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                    session!!.fsync(out)
                }
                // PendingIntent → InstallStatusActivity. Uses getActivity (not
                // getBroadcast) so the OS launches the trampoline Activity directly —
                // Huawei/Honor BAL restrictions block startActivity from BroadcastReceiver
                // context. FLAG_MUTABLE required so the OS can attach EXTRA_STATUS /
                // EXTRA_STATUS_MESSAGE / EXTRA_INTENT before delivery.
                val statusIntent = Intent(activity, InstallStatusActivity::class.java).apply {
                    action = InstallStatusActivity.ACTION_INSTALL_STATUS
                    putExtra(InstallStatusActivity.EXTRA_TARGET_VERSION, targetVersion)
                    setPackage(activity.packageName)
                }
                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getActivity(
                    activity,
                    sessionId,
                    statusIntent,
                    pendingIntentFlags,
                )
                session!!.commit(pendingIntent.intentSender)
                // commit() hands ownership of the session to the OS; we MUST close
                // (not abandon) on the success path. abandon() in the catch below.
                session!!.close()
                session = null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to commit PackageInstaller session $sessionId", t)
            // Best-effort: ditch the half-built session so we don't leak a slot.
            // Closing instead of abandoning here would leave it lingering.
            // abandon() on a committed session is a documented no-op (AOSP
            // PackageInstallerSession), so this is safe regardless of where
            // in the flow the throw happened.
            runCatching { session?.abandon() }
            throw t
        }
    }

    /**
     * Reads the SHA-256 of the signing certificate from the currently-installed
     * app and from the downloaded APK file; throws [SecurityException] when they
     * do not match or when either certificate cannot be read.
     */
    @VisibleForTesting
    internal fun verifySigningCertMatch(activity: Activity, apkFile: File) {
        val pm = activity.packageManager
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val installed = try {
            pm.getPackageInfo(activity.packageName, flag)
        } catch (e: PackageManager.NameNotFoundException) {
            throw SecurityException("Installed package info unavailable; refusing install", e)
        }
        val downloaded = pm.getPackageArchiveInfo(apkFile.absolutePath, flag)
            ?: throw SecurityException("Downloaded APK has no readable package info")
        // Verify packageName equality BEFORE the cert compare — a same-signed
        // APK with a different applicationId (e.g. a debug flavor of the same
        // signing key) would otherwise pass the cert check and side-by-side
        // install instead of upgrading (codex stage-6 MEDIUM).
        if (downloaded.packageName != activity.packageName) {
            Log.e(
                TAG,
                "Package name mismatch: installed=${activity.packageName} downloaded=${downloaded.packageName}"
            )
            throw SecurityException(
                "Downloaded APK packageName does not match installed app"
            )
        }
        val installedSha = certSha256(installed)
            ?: throw SecurityException("No signing certificate on installed app")
        val downloadedSha = certSha256(downloaded)
        if (downloadedSha == null) {
            // On Android <= 9 (API <= 28), getPackageArchiveInfo(GET_SIGNATURES) can
            // only read v1 (JAR) signatures from the APK *file*; a v2-only archive
            // yields no readable cert there. Rather than hard-block the update (the
            // historical Android-9 install failure), fail open on pre-P: the OS
            // PackageInstaller still enforces signing-certificate equality against the
            // installed app on every update, so a mismatched/tampered APK is rejected
            // by the platform regardless. On P+ the archive cert is always readable via
            // apkContentsSigners, so a null there is a genuine problem and still aborts.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                Log.w(
                    TAG,
                    "Downloaded APK cert unreadable on API ${Build.VERSION.SDK_INT} " +
                        "(v2-only archive on pre-P); skipping app-level cert check — " +
                        "PackageInstaller enforces signature match on update"
                )
                return
            }
            throw SecurityException("No signing certificate in downloaded APK")
        }
        if (installedSha != downloadedSha) {
            Log.e(TAG, "Signing certificate mismatch: installed=$installedSha downloaded=$downloadedSha")
            throw SecurityException(
                "Downloaded APK signing certificate does not match installed app"
            )
        }
    }

    /**
     * Returns SHA-256 of the package's first signing certificate, or null when
     * none is readable.
     *
     * Must use [SigningInfo.getApkContentsSigners] — that field is populated for
     * v1, v2, and v3 signatures alike. [SigningInfo.getSigningCertificateHistory]
     * is populated for the installed app (Android tracks the lineage in system
     * records) and for v3-signed APK *files*, but is null/empty for v2-only APK
     * files parsed by [PackageManager.getPackageArchiveInfo] — which is what we
     * pass in for the downloaded update. Using the history field caused every
     * v2-only update download to fail [verifySigningCertMatch] with
     * "No signing certificate in downloaded APK". FitrahTube release builds
     * currently ship v2-only, so the bug was 100% reproducible.
     *
     * If a v3 key rotation is ever introduced, this stays correct for the
     * common case (installed.apkContentsSigners[0] == downloaded.apkContentsSigners[0])
     * and will only require a richer compare against [SigningInfo.signingCertificateHistory]
     * lineage when an installed app has been ROTATED away from its original key.
     */
    private fun certSha256(info: PackageInfo): String? {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        // Comparing only [0] is safe for FitrahTube today: single-signer, no v3
        // key rotation. Two future changes would each invalidate this shortcut
        // and need the richer compare noted in the doc-comment above:
        //   1) Multi-signer signing: switch to set-equality on the full
        //      apkContentsSigners array.
        //   2) v3 key rotation: installed app's apkContentsSigners[0] is the
        //      *current* (post-rotation) cert, but an installed-but-not-yet-
        //      upgraded device still reports the *original* cert — a single-
        //      signer rotation alone would break upgrades from pre-rotation
        //      installs. Compare against signingCertificateHistory lineage
        //      from the installed side instead of just [0]==[0].
        val signerCount = signatures?.size ?: 0
        if (signerCount > 1) {
            // Surface the dormant multi-signer branch loudly the day FitrahTube
            // (or a fork) ships multi-signer — silent [0]==[0] would otherwise
            // depend on PackageManager's signer ordering, which is not a stable
            // contract across Android versions.
            Log.w(TAG, "Multi-signer APK detected (count=$signerCount); [0]==[0] compare may misjudge")
        }
        val first = signatures?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(first.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** On O+ the user must grant "install unknown apps" for this app specifically. */
    fun isInstallPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /** Sends the user to the system Settings screen where they can toggle the permission. */
    fun openInstallPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${activity.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not open ACTION_MANAGE_UNKNOWN_APP_SOURCES; falling back", t)
            // CodeRabbit #1: wrap the fallback too — on heavily-customized OEM skins
            // (some Huawei/Xiaomi variants) the app-details screen can also be missing
            // or blocked. Log the secondary failure distinctly so support can tell
            // apart "first intent failed" from "every settings intent failed".
            try {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (fallbackError: Throwable) {
                Log.e(TAG, "Could not open app details settings either", fallbackError)
            }
        }
    }

    companion object {
        private const val TAG = "ApkInstaller"
    }
}
