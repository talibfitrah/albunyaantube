package com.albunyaan.tube.update

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
    private val catalog: ReleaseCatalogCache,
    private val lastInstallAttempt: LastInstallAttempt,
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
     * Coalesces rapid double-taps on a picker row. The dialog flow does an
     * async DataStore read before `dialog.show()` (10-200 ms on cold cache),
     * and a second tap inside that window would otherwise queue a second
     * `lifecycleScope.launch` and stack two AlertDialogs on top of each other.
     *
     * Why a [Mutex.tryLock] and not an [AtomicBoolean]: the lock acquire MUST
     * happen inside the launched coroutine, so a pre-dispatch lifecycle
     * destroy (rotation, theme change, fast nav-away) cancels the coroutine
     * before `tryLock` runs and the lock is never acquired — no leak. A
     * synchronous `compareAndSet` at the call site would set the flag, then
     * the dispatched lambda would never run, and the flag would sit `true`
     * for the life of the process killing every subsequent picker tap (Stage 3
     * review pipeline round 3 P1).
     *
     * Process-singleton scope is intentional: a tap on row A locks out a tap
     * on row B during A's snapshot read. Two stacked dialogs for two
     * different versions would be more confusing than a 200 ms latency on B.
     */
    private val pickerInstallMutex = Mutex()

    /**
     * Process-scoped guard so an update prompt fires at most once per cold start
     * AFTER the user has actually acted on the dialog. Cleared at process death.
     *
     * Set by a user-driven dismissal from EITHER entry point (splash gate or picker
     * → install path). The name reflects the actual job: "an update prompt has fired
     * in this process; don't re-prompt." Both splash and picker set this on real user
     * action so a picker Install that precedes the splash gate check does not re-show
     * the dialog on the same cold start.
     *
     * Lifecycle-driven teardown (rotation, theme change) clears the dialog's dismiss
     * listener *before* calling `dismiss()` so the cleanup path leaves this flag false
     * — that way the recreated SplashFragment can re-show the dialog instead of
     * silently swallowing it (round-2 code-reviewer C1).
     *
     * Manual checks from Settings bypass this — they call [runCheck] directly, which
     * is intentional user-initiated re-check.
     */
    @Volatile
    private var promptDismissedThisProcess = false

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
        if (promptDismissedThisProcess) return null
        return withTimeoutOrNull(CHECK_TIMEOUT_MS) { catalog.latest() }
    }

    /**
     * Returns a snapshot of the last install attempt the user made, or null when
     * none is recorded / it was for a version that's now installed. The splash
     * gate consults this BEFORE [checkForUpdate] so the user sees "last update
     * didn't complete" feedback for a previously-failed attempt instead of just
     * the standard "new version available" dialog — the symptom the Huawei
     * Android 9 user reported on beta.15, where the install silently failed
     * and the prompt kept re-appearing with no explanation.
     *
     * Returns ABANDONED for a PENDING record older than 24h (the OS never
     * delivered the success/failure callback — most likely because the install
     * was rejected by Knox/Auto-Blocker or the user backed out of the system
     * installer without confirming).
     */
    suspend fun lastInstallStatus(): LastInstallAttempt.Snapshot? = lastInstallAttempt.snapshot()

    /**
     * Manual install entry point from the Available Updates picker. Unlike
     * [showUpdateDialogAndAwait] (splash-gate), this bypasses the
     * `promptDismissedThisProcess` short-circuit because picker taps are
     * explicit user-initiated actions, equivalent to the Settings
     * "Check for updates" path (see [runCheck]).
     *
     * Without this entry point the picker silently no-ops after the user has
     * dismissed the cold-start dialog (codex stage-6 HIGH).
     */
    fun showPickerInstallDialog(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: UpdateInfo
    ) {
        // Intentional: do NOT check `promptDismissedThisProcess`. Picker taps
        // are explicit user actions and must always show the dialog.
        // showUpdateDialog (the private helper) does not consult the flag.
        //
        // Re-entrancy guard via [pickerInstallMutex]: a rapid double-tap on
        // the picker row would otherwise queue two parallel
        // `lifecycleScope.launch` jobs, both suspending on the DataStore
        // read, both calling `showUpdateDialog`, both calling `dialog.show()`
        // against the same activity → two stacked AlertDialogs. The
        // [Mutex.tryLock] acquire happens INSIDE the coroutine so a lifecycle
        // destroy that cancels the launch before dispatch never leaks a
        // locked state — see the field docstring for the AtomicBoolean
        // variant that did leak (Stage 3 review pipeline round 3 P1).
        //
        // Fetch the previous-attempt snapshot off Main, then render the
        // dialog on Main with the inline warning if one applies. Same
        // dialog plumbing as the splash gate so a retry from the picker
        // surfaces the prior failure reason without diverging UX.
        lifecycleOwner.lifecycleScope.launch {
            if (!pickerInstallMutex.tryLock()) return@launch
            try {
                // Swallow a DataStore IOException from the snapshot read so
                // the picker tap isn't silently no-op'd (Stage 3 P3). Manually
                // re-throw CancellationException — runCatching catches it
                // along with everything else (KT-40996), and lifecycleScope
                // cancellation should propagate, not be swallowed into a
                // null previousAttempt that then races into showUpdateDialog
                // (Stage 1 R4 + Stage 3 R4 P3).
                val previousAttempt = runCatching { lastInstallAttempt.snapshot() }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "Failed to read last install attempt", it)
                    }
                    .getOrNull()
                showUpdateDialog(activity, lifecycleOwner, info, previousAttempt)
            } finally {
                pickerInstallMutex.unlock()
            }
        }
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
     * can re-show it — the `promptDismissedThisProcess` doc explains the contract.
     */
    suspend fun showUpdateDialogAndAwait(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: UpdateInfo
    ): Unit {
        // Read the LastInstallAttempt snapshot off the suspend boundary BEFORE
        // entering suspendCancellableCoroutine — the DataStore read is itself a
        // suspend call and cannot be invoked inside the synchronous body of
        // suspendCancellableCoroutine. The result rides into showUpdateDialog so
        // a prior failure renders inline as an error-colored warning above the
        // dialog body (Stage 1 review P1: a toast 50-100ms before the dialog
        // gets swallowed by dialog focus).
        val previousAttempt = lastInstallAttempt.snapshot()
        suspendCancellableCoroutine<Unit> { cont ->
            if (promptDismissedThisProcess) {
                if (cont.isActive) cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            val dialog = showUpdateDialog(
                activity,
                lifecycleOwner,
                info,
                previousAttempt,
                onUserAction = {
                // Fires synchronously from Later/Install click handlers BEFORE the
                // dismiss is dispatched. Wins the race against a simultaneous
                // activity destroy that would otherwise clear the dismiss listener
                // before the queued dismiss message could fire (cubic round-2 P2).
                promptDismissedThisProcess = true
            },
            onDismissed = {
                // Fallback for back-button / outside-tap dismissals (no button handler
                // to fire onUserAction). Also idempotently re-sets the flag after a
                // button-driven dismiss — same value, no-op.
                promptDismissedThisProcess = true
                if (cont.isActive) cont.resume(Unit)
            },
        )
        if (dialog == null) {
            // Activity finishing — couldn't show the dialog. Resume cont so the splash
            // can route on, but leave promptDismissedThisProcess false so the next cold
            // start with a live activity gets another chance to prompt.
            if (cont.isActive) cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            // invokeOnCancellation can fire on any thread (the cancelling thread).
            // AlertDialog's setOnDismissListener mutates state without synchronization
            // and must run on main. When we are already on main (the lifecycle case),
            // run synchronously — posting opens a narrow window where the current main
            // message could deliver a queued user-tap dismiss before our listener-clear
            // runs, flipping promptDismissedThisProcess prematurely (cubic P3). When we are
            // off-main, posting is the only safe option.
            //
            // The dismiss listener is dropped BEFORE dismissing so lifecycle-driven teardown
            // does not flip promptDismissedThisProcess — the user never saw the dialog dismiss
            // on their own terms, the system tore it down (round-2 code-reviewer C1).
            val cleanup = {
                dialog.setOnDismissListener(null)
                runCatching { dialog.dismiss() }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cleanup()
            } else {
                Handler(Looper.getMainLooper(), null).post(cleanup)
            }
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
        previousAttempt: LastInstallAttempt.Snapshot? = null,
        onUserAction: () -> Unit = {},
        onDismissed: () -> Unit = {}
    ): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val dialogView = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_update_available, null)
        dialogView.findViewById<TextView>(R.id.update_version_label).text =
            activity.getString(R.string.update_version_ready, info.releaseName)
        // Inline "previous attempt failed" line above the body, in error red,
        // when a non-success record exists for the same target. Renders BEFORE
        // the dialog steals focus so the user can't miss it (Stage 1 review P1
        // — a transient toast just before the dialog read as background noise).
        val warningView = dialogView.findViewById<TextView>(R.id.update_previous_attempt_warning)
        if (previousAttempt != null &&
            previousAttempt.targetVersion == info.versionName &&
            (previousAttempt.status == LastInstallAttempt.Status.FAILURE ||
                previousAttempt.status == LastInstallAttempt.Status.ABANDONED)
        ) {
            val detail = previousAttempt.failureMessage?.takeIf { it.isNotBlank() }
            warningView.text = if (detail != null) {
                activity.getString(R.string.update_previous_attempt_failed_with_reason, detail)
            } else {
                activity.getString(R.string.update_previous_attempt_failed)
            }
            warningView.visibility = android.view.View.VISIBLE
        } else {
            warningView.visibility = android.view.View.GONE
        }
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
            val safe = info.versionName.sanitizeSemverDisplay()
            if (safe.isEmpty()) {
                // Sanitizer stripped the whole tag (homoglyph-only). The /tag/v
                // URL would 404 anyway; skip the intent so we do not navigate the
                // user to a broken page (cubic R3 P3).
                Log.w(TAG, "Skipping changelog link — versionName sanitized to empty")
                return@setOnClickListener
            }
            val url = "https://github.com/${UpdateChecker.GITHUB_REPO}/releases/tag/v$safe"
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
                // Cert verification + APK parse on the IO dispatcher (cubic R2 P1):
                // PackageManager.getPackageArchiveInfo() parses the full APK manifest
                // and can hold the calling thread for hundreds of ms on a large APK —
                // ANRs the splash gate if it ever ran on Main.
                withContext(Dispatchers.IO) {
                    installer.verifySigningCertMatch(activity, file)
                }
                if (!activity.isFinishing && !activity.isDestroyed) {
                    // Record PENDING INSIDE the alive-activity guard so a
                    // destroy between cert-verify and handoff doesn't leave a
                    // PENDING marker for an install that never started — the
                    // record would later promote to ABANDONED and render a
                    // misleading "last update didn't complete" banner on the
                    // next cold start (Stage 3 review pipeline round 2 P2).
                    // launchInstaller is suspend and handles its own
                    // dispatcher — it does multi-MB streaming + fsync
                    // internally on IO, so wrapping with Dispatchers.Main here
                    // would defeat the point. The finishing/destroyed guards
                    // need Main-thread reads of Activity state, which is fine
                    // inside a viewLifecycleOwner-scoped coroutine that
                    // defaults to Main (Stage 3 review pipeline round 1 P1).
                    lastInstallAttempt.recordPending(info.versionName)
                    installer.launchInstaller(activity, file, info.versionName)
                    // The DEX-restore self-kill (Samsung/Xiaomi keep the pre-update
                    // process alive) now fires from InstallStatusActivity on
                    // STATUS_SUCCESS — only AFTER the user confirms the system install.
                    // Killing here, a blind 2s after commit, raced the confirmation
                    // dialog: on slow / OEM devices the kill landed before the user had
                    // even confirmed, tearing the install down ("downloading then
                    // nothing"). See InstallStatusActivity.STATUS_SUCCESS.
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SecurityException) {
                // Cert / package-name mismatch surfaces here (cubic R2 P2). Distinct
                // toast so the user understands the problem is signature-integrity,
                // not network — actionable hint to re-download from a trusted source.
                Log.e(TAG, "Signing verification failed", se)
                // recordFailure here so a subsequent cold start surfaces a useful
                // banner. SecurityException is thrown by verifySigningCertMatch,
                // which runs BEFORE recordPending in normal flow — but if the
                // throw happens after recordPending (e.g. an OEM PackageManager
                // race during the session commit) the PENDING record would
                // otherwise sit forever; recording FAILURE here is idempotent
                // and corrects either case.
                lastInstallAttempt.recordFailure(info.versionName, "signature mismatch")
                toast(activity, R.string.update_signature_mismatch)
            } catch (t: Throwable) {
                Log.e(TAG, "Update download/install failed", t)
                lastInstallAttempt.recordFailure(
                    info.versionName,
                    t.message?.take(120) ?: t.javaClass.simpleName,
                )
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

        /** Splash budget — long enough for a healthy GitHub response, short enough to not
         * stall cold start on a flaky network. The check runs in parallel with splash
         * animations (≈2.7 s), so this typically resolves before we need the result. */
        private const val CHECK_TIMEOUT_MS = 2_000L
    }
}
