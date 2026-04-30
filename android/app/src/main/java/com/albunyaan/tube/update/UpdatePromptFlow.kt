package com.albunyaan.tube.update

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.albunyaan.tube.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
     * Serializes download+install flow so the auto-check on MainActivity.onStart and
     * the manual "Check for updates" button can never race on the shared APK cache
     * file (cacheDir/updates/fitrahtube-update.apk). Without this, two concurrent
     * coroutines would overwrite the same file while the first download's URI might
     * still be in flight to the package installer (TOCTOU → corrupt APK handoff).
     */
    private val downloadMutex = Mutex()

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
        val dialogView = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_update_available, null)
        dialogView.findViewById<TextView>(R.id.update_version_label).text =
            activity.getString(R.string.update_version_ready, info.releaseName)
        dialogView.findViewById<TextView>(R.id.update_release_notes).text =
            info.releaseNotes.ifBlank { activity.getString(R.string.update_no_release_notes) }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .create()
        dialogView.findViewById<MaterialButton>(R.id.update_btn_later).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<MaterialButton>(R.id.update_btn_install).setOnClickListener {
            dialog.dismiss()
            ensurePermissionThenInstall(activity, lifecycleOwner, info)
        }
        dialog.show()
        // Transparent window so only our card is visible.
        // Width = 85% of screen, capped at 480dp — leaves breathing room on phones
        // and prevents an over-wide card on tablets and TV.
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val density = activity.resources.displayMetrics.density
            val screenWidth = activity.resources.displayMetrics.widthPixels
            val maxWidthPx = (480 * density).toInt()
            val dialogWidth = minOf((screenWidth * 0.85f).toInt(), maxWidthPx)
            setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
    }

    private fun ensurePermissionThenInstall(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: UpdateInfo
    ) {
        if (!installer.isInstallPermissionGranted(activity)) {
            // App name is formatted in at runtime (CodeRabbit #6/#7) so the message
            // resource stays a single localizable template per locale and the actual
            // brand string can be updated independently via R.string.app_name.
            val appName = activity.getString(R.string.app_name)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(activity.getString(R.string.update_permission_message, appName))
                .setPositiveButton(R.string.update_grant_permission) { _, _ ->
                    installer.openInstallPermissionSettings(activity)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        downloadAndInstall(activity, lifecycleOwner, info)
    }

    private fun downloadAndInstall(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: UpdateInfo
    ) {
        lifecycleOwner.lifecycleScope.launch {
            // Acquire the mutex BEFORE showing any UI (CodeRabbit #3).
            if (!downloadMutex.tryLock()) {
                toast(activity, R.string.update_downloading)
                return@launch
            }
            val dialogView = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_update_progress, null)
            val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.update_progress_bar)
            val progressLabel = dialogView.findViewById<TextView>(R.id.update_progress_label)
            val progressDialog: AlertDialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_downloading)
                .setView(dialogView)
                .setCancelable(false)
                .create()
                .also { it.show() }
            try {
                val file = installer.download(
                    activity,
                    info.apkUrl,
                    expectedSizeBytes = info.apkSizeBytes
                ) { fraction ->
                    val pct = (fraction * 100f).toInt().coerceIn(0, 100)
                    activity.runOnUiThread {
                        progressBar.progress = pct
                        progressLabel.text = activity.getString(R.string.update_progress_percent, pct)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        installer.launchInstaller(activity, file)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Update download/install failed", t)
                toast(activity, R.string.update_download_failed)
            } finally {
                downloadMutex.unlock()
                runCatching { progressDialog.dismiss() }
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
