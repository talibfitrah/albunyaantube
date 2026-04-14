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
     */
    suspend fun download(
        context: Context,
        apkUrl: String,
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
                    var read = input.read(buffer)
                    var sum = 0L
                    while (read > 0) {
                        output.write(buffer, 0, read)
                        sum += read
                        if (total > 0 && onProgress != null) {
                            onProgress(sum.toFloat() / total)
                        }
                        read = input.read(buffer)
                    }
                }
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
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    companion object {
        private const val TAG = "ApkInstaller"
    }
}
