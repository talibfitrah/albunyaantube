package com.albunyaan.tube.update

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Transparent trampoline Activity for [PackageInstaller] status callbacks.
 * Uses an Activity-based PendingIntent so the OS launches it directly,
 * bypassing Huawei/Honor BAL restrictions that block startActivity from
 * a BroadcastReceiver context.
 */
@AndroidEntryPoint
class InstallStatusActivity : AppCompatActivity() {

    @Inject lateinit var lastInstallAttempt: LastInstallAttempt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        if (i == null || i.action != ACTION_INSTALL_STATUS) {
            finish()
            return
        }
        handleInstallStatus(i)
    }

    private fun handleInstallStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val targetVersion = intent.getStringExtra(EXTRA_TARGET_VERSION)
            ?.take(MAX_VERSION_NAME_LENGTH)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.d(TAG, "status=$status target=$targetVersion message=${message?.take(80)}")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = extraIntent(intent)
                if (confirmIntent != null) {
                    try {
                        startActivity(confirmIntent)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to start install confirmation", t)
                        toastInstallFailure("could not show install confirmation")
                        recordAndFinish(targetVersion, "could not show install confirmation")
                        return
                    }
                } else {
                    // No confirmation intent means the OS can't show the install prompt —
                    // another silent "downloaded then nothing" path. Surface it and record a
                    // failure instead of finishing as if nothing was wrong (codex Stage-3 P3).
                    Log.w(TAG, "STATUS_PENDING_USER_ACTION with no EXTRA_INTENT")
                    toastInstallFailure("missing install confirmation")
                    recordAndFinish(targetVersion, "missing install confirmation")
                    return
                }
                finish()
            }
            PackageInstaller.STATUS_SUCCESS -> {
                lifecycleScope.launch {
                    try {
                        if (targetVersion != null) {
                            withContext(NonCancellable) {
                                lastInstallAttempt.recordSuccess(targetVersion)
                            }
                        }
                    } finally {
                        finish()
                        scheduleDexRestoreSelfKill()
                    }
                }
            }
            else -> {
                val reason = describeFailure(status, message)
                // Surface non-cancellation failures immediately. Without this the user
                // sees the progress dialog vanish with no explanation ("downloading
                // then nothing"); the only prior feedback was a banner on the NEXT cold
                // start. STATUS_FAILURE_ABORTED is the user backing out — no nag there.
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    toastInstallFailure(reason)
                }
                recordAndFinish(targetVersion, reason)
            }
        }
    }

    private fun recordAndFinish(targetVersion: String?, reason: String) {
        if (targetVersion != null) {
            lifecycleScope.launch {
                try {
                    withContext(NonCancellable) {
                        lastInstallAttempt.recordFailure(targetVersion, reason)
                    }
                } finally {
                    finish()
                }
            }
        } else {
            finish()
        }
    }

    /** Loud, immediate feedback so a failed install isn't a silent "nothing happened". */
    private fun toastInstallFailure(reason: String) {
        android.widget.Toast.makeText(
            applicationContext,
            getString(com.albunyaan.tube.R.string.update_install_failed, reason),
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /**
     * DEX-restore mitigation. Samsung/Xiaomi keep the pre-update process alive, so a later
     * cold launch can restore the OLD code ("install did nothing"). After a CONFIRMED-
     * successful install, end the old process so the next launch loads the new APK — but
     * ONLY if the user is not back in an active session. The visibility re-check runs after
     * a short delay (post-finish, so this transparent trampoline isn't itself counted as
     * foreground): if the user tapped "Open" and is now using the app, importance is
     * FOREGROUND/VISIBLE and we skip the kill instead of killing a fresh launch or live
     * playback. Replaces the old blind 2s-after-commit kill in UpdatePromptFlow, which
     * fired before the user had even confirmed the install on slow / OEM devices.
     */
    private fun scheduleDexRestoreSelfKill() {
        Handler(Looper.getMainLooper()).postDelayed({
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                Log.d(TAG, "App foreground/visible post-install — skipping DEX-restore self-kill")
                return@postDelayed
            }
            Process.killProcess(Process.myPid())
        }, SELF_KILL_DELAY_MS)
    }

    @Suppress("DEPRECATION")
    private fun extraIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        }
    }

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
        private const val TAG = "InstallStatusActivity"

        const val ACTION_INSTALL_STATUS = "com.albunyaan.tube.update.action.INSTALL_STATUS"
        const val EXTRA_TARGET_VERSION = "com.albunyaan.tube.update.extra.TARGET_VERSION"
        private const val MAX_VERSION_NAME_LENGTH = 64

        /** Delay before the post-install self-kill so the trampoline finishes and the
         *  process settles to background — a fast "Open" tap is then seen as foreground. */
        private const val SELF_KILL_DELAY_MS = 2_000L
    }
}
