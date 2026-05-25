package com.albunyaan.tube.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives [PackageInstaller] status broadcasts for in-app APK installs. The
 * `ApkInstaller.launchInstaller` flow commits a session with a [android.app.PendingIntent]
 * pointing at this receiver; one of the [PackageInstaller.STATUS_*] codes lands
 * here when the OS finishes (or refuses) the install.
 *
 * Three branches matter:
 *
 *  - [PackageInstaller.STATUS_PENDING_USER_ACTION] — the OS needs an explicit
 *    "do you want to install" confirmation from the user. We forward the
 *    embedded `Intent.EXTRA_INTENT` to start the system installer activity.
 *    Without this branch the install silently hangs in PENDING forever on
 *    Android < 12 sideload paths.
 *
 *  - [PackageInstaller.STATUS_SUCCESS] — the new APK is on disk. We let
 *    [LastInstallAttempt] record SUCCESS so the splash banner doesn't fire on
 *    the next launch. Note: on most devices the install also kills our process,
 *    so this branch may never actually execute in practice — the snapshot's
 *    own "current version matches target → clear" path handles that case.
 *
 *  - Any [PackageInstaller.STATUS_FAILURE_*] — we record the failure with the
 *    OS-provided [PackageInstaller.EXTRA_STATUS_MESSAGE] so the splash gate can
 *    show "last update didn't complete: <reason> — try again?" instead of just
 *    re-prompting silently (the symptom that confused the Huawei Android 9 user
 *    on beta.15).
 *
 * Declared in the manifest with `exported="false"` — PackageInstaller delivers
 * the broadcast via the PendingIntent regardless of export status.
 */
@AndroidEntryPoint
class InstallStatusReceiver : BroadcastReceiver() {

    @Inject lateinit var lastInstallAttempt: LastInstallAttempt

    @Inject
    @Named("applicationScope")
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        // No explicit `super.onReceive(context, intent)`: at source-compile
        // time the declared parent is `BroadcastReceiver`, whose `onReceive`
        // is abstract — `super.onReceive(...)` would fail kotlinc with
        // "Abstract member cannot be accessed directly" (Stage 3 R4 P1).
        // Hilt's @AndroidEntryPoint Gradle plugin runs an ASM bytecode
        // transform that rewrites the parent to a generated
        // `Hilt_InstallStatusReceiver` and injects the `inject(context)` +
        // super delegate calls itself, so dependency injection happens
        // before any code below runs without us having to call super
        // explicitly.
        if (intent.action != ACTION_INSTALL_STATUS) {
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        // Cap the version length on read — defense in depth against an OS or
        // developer-error path that ships an arbitrarily long string. DataStore
        // preference files cap at ~1MB total before refusing writes; a stray
        // 100KB versionName would brick the entire update gate silently.
        // 64 chars covers semver + reasonable suffixes (e.g. "1.0.0-beta.42+sha.abcdef0")
        // with room to spare (Stage 3 codex review P2).
        val targetVersion = intent.getStringExtra(EXTRA_TARGET_VERSION)?.take(MAX_VERSION_NAME_LENGTH)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.d(
            TAG,
            "onReceive status=$status target=$targetVersion message=${message?.take(80)}",
        )
        // Extend the receiver's lifetime via goAsync(). Without it, once onReceive
        // returns the process can be killed at any moment — but our DataStore
        // write is queued on applicationScope and may not have flushed yet. For
        // STATUS_FAILURE specifically this is the rare-but-critical case the
        // banner exists to surface (Knox/Auto-Blocker silently rejecting an
        // install), so dropping the record because we returned too early
        // defeats the whole feature (Stage 1 review P1).
        val pendingResult = goAsync()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Forward the OS-provided confirmation intent. Must run with
                // FLAG_ACTIVITY_NEW_TASK because we're in a BroadcastReceiver,
                // not an Activity context. On Android 10+ this can be rejected
                // with BackgroundActivityStartException when the receiver has
                // no BAL grant; record a FAILURE in that case so the splash
                // banner explains it instead of going silent (Stage 1 P2).
                val confirmIntent = extraIntent(intent)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val launchResult = runCatching { context.startActivity(confirmIntent) }
                    if (launchResult.isFailure) {
                        Log.e(
                            TAG,
                            "Failed to start install confirmation activity",
                            launchResult.exceptionOrNull(),
                        )
                        if (targetVersion != null) {
                            applicationScope.launch {
                                try {
                                    // Mirror the SUCCESS / generic-failure
                                    // branches: bound the DataStore write so
                                    // it can't blow past the BroadcastReceiver
                                    // ANR threshold under contention (Stage 7
                                    // cubic P2 — inconsistent ANR defense).
                                    withTimeoutOrNull(RECEIVE_WORK_TIMEOUT_MS) {
                                        lastInstallAttempt.recordFailure(
                                            targetVersion,
                                            "could not show install confirmation",
                                        )
                                    }
                                } finally {
                                    pendingResult.finish()
                                }
                            }
                            return
                        }
                    }
                } else {
                    Log.w(TAG, "STATUS_PENDING_USER_ACTION with no EXTRA_INTENT — install will hang")
                }
                pendingResult.finish()
            }
            PackageInstaller.STATUS_SUCCESS -> {
                if (targetVersion != null) {
                    applicationScope.launch {
                        try {
                            // Bound the DataStore write so a concurrent edit
                            // from another writer (e.g. splash snapshot read or
                            // a picker-flow tap landing at the same instant)
                            // can't push us past the OS's BroadcastReceiver
                            // timeout — an ANR-in-BroadcastReceiver is worse
                            // than losing one SUCCESS record, and the snapshot
                            // auto-clear on version-match read covers the loss
                            // (Stage 3 codex review P2).
                            withTimeoutOrNull(RECEIVE_WORK_TIMEOUT_MS) {
                                lastInstallAttempt.recordSuccess(targetVersion)
                            }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    pendingResult.finish()
                }
            }
            else -> {
                // All other codes are failures: STATUS_FAILURE / _ABORTED / _BLOCKED /
                // _CONFLICT / _INCOMPATIBLE / _INVALID / _STORAGE / _TIMEOUT.
                if (targetVersion != null) {
                    val reason = describeFailure(status, message)
                    applicationScope.launch {
                        try {
                            withTimeoutOrNull(RECEIVE_WORK_TIMEOUT_MS) {
                                lastInstallAttempt.recordFailure(targetVersion, reason)
                            }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    pendingResult.finish()
                }
            }
        }
    }

    /**
     * Safe [Intent.EXTRA_INTENT] extraction across API levels. The deprecated
     * `getParcelableExtra(String)` signature returns the wrong type on
     * Tiramisu+ unless we use the class-typed overload.
     */
    @Suppress("DEPRECATION")
    private fun extraIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        }
    }

    /**
     * Turn a [PackageInstaller.STATUS_FAILURE_*] code + OS message into a stable
     * short string we can render in the splash banner. Avoids exposing the raw
     * integer code to the user.
     */
    private fun describeFailure(status: Int, message: String?): String {
        val base = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked by system"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "package conflict"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
            PackageInstaller.STATUS_FAILURE_INVALID -> "invalid APK"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "out of storage"
            PackageInstaller.STATUS_FAILURE -> "failed"
            else -> "failed ($status)"
        }
        return if (message.isNullOrBlank()) base else "$base — $message"
    }

    companion object {
        private const val TAG = "InstallStatusReceiver"

        const val ACTION_INSTALL_STATUS = "com.albunyaan.tube.update.action.INSTALL_STATUS"

        /**
         * Caller-supplied: the versionName the install is targeting. Round-trips
         * through the PendingIntent so the receiver can record the right target
         * even after the installer activity is detached from the launch context.
         */
        const val EXTRA_TARGET_VERSION = "com.albunyaan.tube.update.extra.TARGET_VERSION"

        /**
         * Upper bound for a versionName persisted from a status broadcast.
         * Real semver + suffixes top out around 30 chars; 64 is comfortable
         * headroom and a hard cap against DataStore-bricking malformed input.
         */
        private const val MAX_VERSION_NAME_LENGTH = 64

        /**
         * Per-receiver work budget for the DataStore write triggered by a
         * status broadcast. Below the foreground BroadcastReceiver ANR
         * threshold (~10 s) so a queue-stuck DataStore write can be abandoned
         * before the OS reaps the receiver as unresponsive.
         */
        private const val RECEIVE_WORK_TIMEOUT_MS: Long = 8_000L
    }
}
