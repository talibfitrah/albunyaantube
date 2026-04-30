package com.albunyaan.tube.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an APK from a URL into the app's cacheDir, then hands off to the system package
 * installer via [Intent.ACTION_INSTALL_PACKAGE]. Uses FileProvider to expose the file to
 * the installer without needing `WRITE_EXTERNAL_STORAGE` permission on modern Android.
 *
 * Caller must hold [android.Manifest.permission.REQUEST_INSTALL_PACKAGES] in the manifest
 * (declared by [UpdateChecker]'s consumer module) and — on Android 8.0+ — the user must
 * have granted "install unknown apps" for this app; use [isInstallPermissionGranted] +
 * [openInstallPermissionSettings] to guide the user through granting it.
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
        okHttpClient.newCall(request).execute().use { response ->
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
     * Launches the system package installer for [apkFile]. The installer runs in its own
     * activity; [activity] is only used to grant the URI permission and start the intent.
     */
    fun launchInstaller(activity: Activity, apkFile: File) {
        val authority = "${activity.packageName}.downloads.provider"
        val uri: Uri = FileProvider.getUriForFile(activity, authority, apkFile)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, activity.packageName)
        }
        try {
            activity.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch package installer", t)
            throw t
        }
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
