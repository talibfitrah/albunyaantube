package com.albunyaan.tube.update

import android.app.Activity
import android.app.ProgressDialog
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.albunyaan.tube.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * End-to-end "new version available" UI flow. Orchestrates:
 * 1. showing the user an "Update available" dialog,
 * 2. verifying REQUEST_INSTALL_PACKAGES is granted (prompts if not),
 * 3. downloading the APK via [ApkInstaller],
 * 4. launching the system installer.
 *
 * Triggered from two entry points (per ANDROID-MULTI-01 Issue 3):
 * - [SettingsFragment] "Check for updates" row (manual; always shows a result toast).
 * - [com.albunyaan.tube.ui.MainActivity.onStart] (throttled auto-check; silent if none).
 */
@Singleton
class UpdatePromptFlow @Inject constructor(
    private val checker: UpdateChecker,
    private val installer: ApkInstaller
) {

    /**
     * Runs a full update check. When [manual] is true, shows "no update available" feedback
     * to the user; when false (app-start auto-check), is silent on the no-update / error path.
     */
    fun runCheck(activity: Activity, lifecycleOwner: LifecycleOwner, manual: Boolean) {
        lifecycleOwner.lifecycleScope.launch {
            val result = checker.checkForUpdate()
            val info = result.getOrNull()
            when {
                info != null -> showUpdateDialog(activity, lifecycleOwner, info)
                manual && result.isFailure -> toast(activity, R.string.update_check_failed)
                manual -> toast(activity, R.string.update_check_up_to_date)
            }
        }
    }

    private fun showUpdateDialog(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: UpdateInfo
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val message = activity.getString(
            R.string.update_available_message,
            info.releaseName,
            info.releaseNotes.ifBlank { activity.getString(R.string.update_no_release_notes) }
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download_and_install) { _, _ ->
                ensurePermissionThenInstall(activity, lifecycleOwner, info)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun ensurePermissionThenInstall(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: UpdateInfo
    ) {
        if (!installer.isInstallPermissionGranted(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_grant_permission) { _, _ ->
                    installer.openInstallPermissionSettings(activity)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        downloadAndInstall(activity, lifecycleOwner, info)
    }

    @Suppress("DEPRECATION") // ProgressDialog is deprecated but adequate for a one-shot flow.
    private fun downloadAndInstall(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: UpdateInfo
    ) {
        val progress = ProgressDialog(activity).apply {
            setMessage(activity.getString(R.string.update_downloading))
            setCancelable(false)
            setIndeterminate(false)
            max = 100
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            show()
        }
        lifecycleOwner.lifecycleScope.launch {
            try {
                val file = installer.download(activity, info.apkUrl) { fraction ->
                    val pct = (fraction * 100f).toInt().coerceIn(0, 100)
                    activity.runOnUiThread { progress.progress = pct }
                }
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing) installer.launchInstaller(activity, file)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Update download/install failed", t)
                toast(activity, R.string.update_download_failed)
            } finally {
                runCatching { progress.dismiss() }
            }
        }
    }

    private fun toast(activity: Activity, @StringRes res: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        android.widget.Toast.makeText(activity, res, android.widget.Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "UpdatePromptFlow"
    }
}
