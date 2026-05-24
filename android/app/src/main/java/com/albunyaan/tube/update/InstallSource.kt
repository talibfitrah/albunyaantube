package com.albunyaan.tube.update

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports how the app was installed. Used to suppress GitHub-based update affordances
 * for Play Store installs (Play manages its own updates; sideloading from GitHub on top
 * of a Play install would break auto-updates).
 *
 * Extracted from UpdateChecker so non-update callers (e.g. SettingsFragment hiding the
 * "Available updates" row) don't need to pull in the whole update package.
 */
@Singleton
class InstallSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isPlayStore(): Boolean {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        return installer == PLAY_STORE_INSTALLER
    }

    private companion object {
        const val PLAY_STORE_INSTALLER = "com.android.vending"
    }
}
