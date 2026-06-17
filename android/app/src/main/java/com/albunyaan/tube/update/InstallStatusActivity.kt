package com.albunyaan.tube.update

import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
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
                        // DEX-restore mitigation: Samsung/Xiaomi keep the pre-update
                        // process alive, so a later cold launch can restore the OLD
                        // code ("install did nothing"). Now that the install is
                        // CONFIRMED successful, end the old process so the next launch
                        // loads the freshly-installed APK. This replaces the previous
                        // blind 2s-after-commit self-kill in UpdatePromptFlow, which
                        // fired while the user was still on the system install
                        // confirmation and tore the install down on slow / OEM devices
                        // ("downloading then nothing").
                        android.os.Process.killProcess(android.os.Process.myPid())
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
    }
}
