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
                    Log.w(TAG, "STATUS_PENDING_USER_ACTION with no EXTRA_INTENT")
                }
                finish()
            }
            PackageInstaller.STATUS_SUCCESS -> {
                if (targetVersion != null) {
                    lifecycleScope.launch {
                        try {
                            withContext(NonCancellable) {
                                lastInstallAttempt.recordSuccess(targetVersion)
                            }
                        } finally {
                            finish()
                        }
                    }
                } else {
                    finish()
                }
            }
            else -> {
                recordAndFinish(targetVersion, describeFailure(status, message))
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
