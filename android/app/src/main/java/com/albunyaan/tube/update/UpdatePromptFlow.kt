package com.albunyaan.tube.update

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

/**
 * End-to-end "new version available" UI flow. Orchestrates:
 * 1. showing the user an "Update available" dialog,
 * 2. verifying REQUEST_INSTALL_PACKAGES is granted (prompts if not),
 * 3. downloading the APK via [ApkInstaller],
 * 4. launching the system installer.
 *
 * Triggered from two entry points:
 * - [SettingsFragment] "Check for updates" row (manual; always shows a result toast).
 * - SplashFragment cold-start gate via [checkForUpdate] + [showUpdateDialogAndAwait],
 *   which blocks splash routing until the user dismisses the dialog so the prompt
 *   fronts the sign-in screen instead of racing it.
 */
@Singleton
class UpdatePromptFlow @Inject constructor(
    private val checker: UpdateChecker,
    private val installer: ApkInstaller,
    private val catalog: ReleaseCatalogCache
) {

    /**
     * Serializes download+install flow so the splash auto-check and the manual
     * "Check for updates" button can never race on the shared APK cache file
     * (cacheDir/updates/fitrahtube-update.apk). Without this, two concurrent
     * coroutines would overwrite the same file while the first download's URI might
     * still be in flight to the package installer (TOCTOU → corrupt APK handoff).
     */
    private val downloadMutex = Mutex()

    /**
     * Process-scoped guard so the splash gate prompts at most once per cold start
     * AFTER the user has actually acted on the dialog. Cleared at process death.
     *
     * Set ONLY by a user-driven dismissal (Later, Install, back-button, outside-tap on
     * the live dialog). Lifecycle-driven teardown (rotation, theme change) clears the
     * dialog's dismiss listener *before* calling `dismiss()` so the cleanup path
     * leaves this flag false — that way the recreated SplashFragment can re-show
     * the dialog instead of silently swallowing it (round-2 code-reviewer C1).
     *
     * Manual checks from Settings bypass this — they call [runCheck] directly, which
     * is intentional user-initiated re-check.
     */
    @Volatile
    private var splashPromptDismissed = false

    /**
     * Manual "Check for updates" entry point from Settings. Always surfaces a result toast
     * ("no update available" or "check failed") because the user explicitly asked. Splash
     * cold-start uses [checkForUpdate] + [showUpdateDialogAndAwait] instead.
     */
    fun runCheck(activity: Activity, lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycleScope.launch {
            val result = checker.checkForUpdate()
            val info = result.getOrNull()
            when {
                info != null -> showUpdateDialog(activity, lifecycleOwner, info)
                result.isFailure -> toast(activity, R.string.update_check_failed)
                else -> toast(activity, R.string.update_check_up_to_date)
            }
        }
    }

    /**
     * Bounded check used by the splash gate. Returns the [UpdateInfo] or null on no-update,
     * network failure, or timeout — splash never wants to stall cold start waiting on GitHub.
     * Short-circuits to null once the user has dismissed the prompt this process so a
     * second splash from a rotation/recreate does not re-hit GitHub. The 5-minute TTL
     * and shared snapshot are owned by [ReleaseCatalogCache] so the Available Updates
     * screen reuses the same network call without an extra roundtrip.
     */
    suspend fun checkForUpdate(): UpdateInfo? {
        if (splashPromptDismissed) return null
        return withTimeoutOrNull(CHECK_TIMEOUT_MS) { catalog.latest() }
    }

    /**
     * Splash-gate entry point: shows the update dialog and suspends until the user dismisses
     * it (Later, Install, back button, or outside tap). Lets SplashFragment hold its routing
     * decision until the user has seen the update prompt — ensures the dialog is in front of
     * the user *before* the sign-in screen renders, instead of racing it.
     *
     * Once the user has acted on the dialog the suspend is a no-op for the rest of the
     * process (idempotent per cold start). Lifecycle teardown (rotation, theme change)
     * tears down the dialog without marking it as dismissed so the recreated SplashFragment
     * can re-show it — the `splashPromptDismissed` doc explains the contract.
     */
    suspend fun showUpdateDialogAndAwait(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: UpdateInfo
    ): Unit = suspendCancellableCoroutine { cont ->
        if (splashPromptDismissed) {
            if (cont.isActive) cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        val dialog = showUpdateDialog(
            activity,
            lifecycleOwner,
            info,
            onUserAction = {
                // Fires synchronously from Later/Install click handlers BEFORE the
                // dismiss is dispatched. Wins the race against a simultaneous
                // activity destroy that would otherwise clear the dismiss listener
                // before the queued dismiss message could fire (cubic round-2 P2).
                splashPromptDismissed = true
            },
            onDismissed = {
                // Fallback for back-button / outside-tap dismissals (no button handler
                // to fire onUserAction). Also idempotently re-sets the flag after a
                // button-driven dismiss — same value, no-op.
                splashPromptDismissed = true
                if (cont.isActive) cont.resume(Unit)
            },
        )
        if (dialog == null) {
            // Activity finishing — couldn't show the dialog. Resume cont so the splash
            // can route on, but leave splashPromptDismissed false so the next cold start
            // with a live activity gets another chance to prompt.
            if (cont.isActive) cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            // invokeOnCancellation can fire on any thread (the cancelling thread).
            // AlertDialog's setOnDismissListener mutates state without synchronization
            // and must run on main. When we are already on main (the lifecycle case),
            // run synchronously — posting opens a narrow window where the current main
            // message could deliver a queued user-tap dismiss before our listener-clear
            // runs, flipping splashPromptDismissed prematurely (cubic P3). When we are
            // off-main, posting is the only safe option.
            //
            // The dismiss listener is dropped BEFORE dismissing so lifecycle-driven teardown
            // does not flip splashPromptDismissed — the user never saw the dialog dismiss
            // on their own terms, the system tore it down (round-2 code-reviewer C1).
            val cleanup = {
                dialog.setOnDismissListener(null)
                runCatching { dialog.dismiss() }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cleanup()
            } else {
                Handler(Looper.getMainLooper()).post(cleanup)
            }
        }
    }

    /**
     * Returns the AlertDialog so [showUpdateDialogAndAwait] can wire cancellation cleanup,
     * or null when we early-returned because the host is finishing/destroyed. The early-return
     * path deliberately does NOT call [onDismissed] — caller is responsible for resuming any
     * awaiting continuation without flipping session-state flags.
     */
    private fun showUpdateDialog(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: UpdateInfo,
        onUserAction: () -> Unit = {},
        onDismissed: () -> Unit = {}
    ): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val dialogView = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_update_available, null)
        dialogView.findViewById<TextView>(R.id.update_version_label).text =
            activity.getString(R.string.update_version_ready, info.releaseName)
        // Body text is a generic, localized "new version available" message
        // (see R.string.update_body_generic). We deliberately do NOT render
        // the GitHub release notes inline — those are written in English and
        // would be unreadable for users on Arabic / Dutch locales. Curious
        // users can tap "View full changelog" to read the release page.
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .create()
        // Dismiss sink: catches back-button and outside-tap dismissals. Button taps fire
        // [onUserAction] synchronously BEFORE dispatching dismiss so the splash-gate flag
        // wins the race against simultaneous activity destruction (cubic round-2 P2 —
        // Dialog.dismiss() posts the listener invocation via ListenersHandler, which
        // can be dequeued after a racing invokeOnCancellation clears the listener).
        dialog.setOnDismissListener { onDismissed() }
        dialogView.findViewById<TextView>(R.id.update_full_changelog).setOnClickListener {
            val url = "https://github.com/$GITHUB_REPO/releases/tag/v${info.versionName}"
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Log.w(TAG, "Failed to open changelog URL: $url", it) }
        }
        dialogView.findViewById<MaterialButton>(R.id.update_btn_later).setOnClickListener {
            onUserAction()
            dialog.dismiss()
            toast(activity, R.string.update_cancelled_warning)
        }
        dialogView.findViewById<MaterialButton>(R.id.update_btn_install).setOnClickListener {
            onUserAction()
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
        return dialog
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
                        scheduleSelfKillAfterInstall()
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

    /**
     * Kills our process ~2 s after the system installer takes over. Mitigates
     * Samsung/Xiaomi/Huawei aggressive process retention where the old DEX is restored
     * when the user re-opens the app post-install, leaving them on the prior version
     * ("install did nothing"). Standard "self-restart" pattern for sideload updaters.
     *
     * The 2 s delay gives the system PackageInstaller activity time to take focus
     * before we go away. At the kill instant we re-check process importance — if the
     * user backed out of the installer in <2 s and our app is back in any visible
     * state, we skip the kill so we do not yank them out of their session (codex C3 /
     * code-reviewer C1). The Samsung-restore case only applies when the install
     * actually proceeded, which always leaves us at IMPORTANCE_CACHED or worse.
     */
    private fun scheduleSelfKillAfterInstall() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAppForegroundOrVisible()) {
                Log.d(TAG, "User returned before self-kill window elapsed — skipping")
                return@postDelayed
            }
            Process.killProcess(Process.myPid())
        }, SELF_KILL_DELAY_MS)
    }

    /**
     * True when our process is foreground or directly visible to the user. AOSP importance
     * values: FOREGROUND=100, FOREGROUND_SERVICE=125, VISIBLE=200 — covered by `<= 200`.
     * Higher values (PERCEPTIBLE=230, TOP_SLEEPING=325, CACHED=400, GONE=1000) indicate
     * the user is not actively interacting with us — safe to kill.
     *
     * Threshold tuned for the "user backed out of installer" case (codex round-2 minor):
     * strict `== IMPORTANCE_FOREGROUND` missed the ~100-300 ms transitional window on
     * slower devices where the OS has not yet restored full foreground importance.
     */
    private fun isAppForegroundOrVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    companion object {
        private const val TAG = "UpdatePromptFlow"
        private const val GITHUB_REPO = "talibfitrah/albunyaantube"

        /** Splash budget — long enough for a healthy GitHub response, short enough to not
         * stall cold start on a flaky network. The check runs in parallel with splash
         * animations (≈2.7 s), so this typically resolves before we need the result. */
        private const val CHECK_TIMEOUT_MS = 2_000L

        /** Hand-off window before we kill the process so the system installer Activity
         * has time to take focus. Empirically 2 s covers slow OEM launchers. */
        private const val SELF_KILL_DELAY_MS = 2_000L
    }
}
