package com.albunyaan.tube.update

/**
 * Represents a newer release of the app published on GitHub. Constructed by
 * `UpdateChecker` (sideload flavor only) when the remote version is strictly greater
 * than the locally-built version.
 *
 * ANDROID-FLAVOR-01: lives in src/main, not src/sideload, because it is the return type
 * of [UpdateGateway.checkForUpdate] and SplashFragment — both of which are shared code —
 * name it. It is an inert data holder: no network, no installer, nothing Play objects to.
 * Was declared at the top of UpdateChecker.kt before that file moved to the sideload
 * source set.
 */
data class UpdateInfo(
    val versionName: String,
    val releaseName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val publishedAt: java.time.Instant? = null,   // null when GitHub omits it
)
