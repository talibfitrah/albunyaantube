package com.albunyaan.tube.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.albunyaan.tube.BuildConfig
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Persists "what happened the last time we tried to install an update". Used by
 * the splash gate to distinguish "user has never seen a prompt for this version"
 * from "user tapped Install for this version but the install never completed"
 * (silent OEM failure, user cancelled the system installer, install rejected by
 * Play Protect, etc.). Without this signal the splash just re-shows the same
 * "new version available" dialog after every silent failure, which is the
 * confusing UX the Huawei Android 9 user reported on beta.15.
 *
 * Lifecycle:
 *  - [recordPending] called immediately before [ApkInstaller.launchInstaller]
 *    commits the PackageInstaller session. PENDING means "we handed off to the
 *    OS but haven't heard back yet".
 *  - [InstallStatusReceiver] writes SUCCESS or FAILURE when the PackageInstaller
 *    callback fires. SUCCESS is best-effort cleanup — if the app process dies
 *    during the install, we may never get the success callback, so [snapshot]
 *    treats "PENDING for >24h" as effectively dropped.
 *  - On every cold start, [snapshot] is consulted by [UpdatePromptFlow]. If the
 *    recorded target equals the current running version, the record is cleared
 *    on the spot — the install actually succeeded (or the user manually
 *    upgraded), no banner needed.
 *
 * Backed by its own DataStore (rather than reusing the settings store) so a
 * future settings rewrite cannot accidentally invalidate update telemetry.
 */
@Singleton
class LastInstallAttempt @Inject constructor(
    @Named("updateDataStore") private val dataStore: DataStore<Preferences>,
) {

    /**
     * Status of a recorded install attempt. ABANDONED is a synthetic state derived
     * at read time when a PENDING record is older than [STALE_PENDING_THRESHOLD_MS] —
     * we treat that as "the OS callback never came back". Not persisted explicitly.
     */
    enum class Status { PENDING, SUCCESS, FAILURE, ABANDONED }

    data class Snapshot(
        val targetVersion: String,
        val timestampMs: Long,
        val status: Status,
        val failureMessage: String?,
    )

    suspend fun recordPending(targetVersion: String, nowMs: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            prefs[KEY_TARGET_VERSION] = targetVersion
            prefs[KEY_TIMESTAMP_MS] = nowMs
            prefs[KEY_STATUS] = Status.PENDING.name
            prefs.remove(KEY_FAILURE_MESSAGE)
        }
    }

    suspend fun recordSuccess(targetVersion: String, nowMs: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            prefs[KEY_TARGET_VERSION] = targetVersion
            prefs[KEY_TIMESTAMP_MS] = nowMs
            prefs[KEY_STATUS] = Status.SUCCESS.name
            prefs.remove(KEY_FAILURE_MESSAGE)
        }
    }

    suspend fun recordFailure(
        targetVersion: String,
        message: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_TARGET_VERSION] = targetVersion
            prefs[KEY_TIMESTAMP_MS] = nowMs
            prefs[KEY_STATUS] = Status.FAILURE.name
            if (message != null) prefs[KEY_FAILURE_MESSAGE] = message else prefs.remove(KEY_FAILURE_MESSAGE)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    /**
     * Returns the current attempt record, or null if none. Auto-clears the record
     * if the recorded target matches the running app's [BuildConfig.VERSION_NAME] —
     * the install completed (whether via our flow or out-of-band) and the banner
     * should not show. Auto-promotes a stale PENDING record to ABANDONED so callers
     * can surface an actionable "didn't complete" message instead of leaving the
     * user confused about why the same prompt keeps firing.
     */
    suspend fun snapshot(
        currentVersion: String = BuildConfig.VERSION_NAME,
        nowMs: Long = System.currentTimeMillis(),
    ): Snapshot? {
        val prefs = dataStore.data.first()
        val target = prefs[KEY_TARGET_VERSION] ?: return null
        val ts = prefs[KEY_TIMESTAMP_MS] ?: return null
        val raw = prefs[KEY_STATUS]?.let { name ->
            runCatching { Status.valueOf(name) }.getOrNull()
        } ?: return null
        if (target == currentVersion) {
            // Install actually succeeded; drop the record so the next cold start
            // doesn't render a stale banner.
            clear()
            return null
        }
        val status = if (raw == Status.PENDING && nowMs - ts > STALE_PENDING_THRESHOLD_MS) {
            Status.ABANDONED
        } else {
            raw
        }
        return Snapshot(
            targetVersion = target,
            timestampMs = ts,
            status = status,
            failureMessage = prefs[KEY_FAILURE_MESSAGE],
        )
    }

    companion object {
        private val KEY_TARGET_VERSION = stringPreferencesKey("target_version")
        private val KEY_TIMESTAMP_MS = longPreferencesKey("timestamp_ms")
        private val KEY_STATUS = stringPreferencesKey("status")
        private val KEY_FAILURE_MESSAGE = stringPreferencesKey("failure_message")

        /** A PENDING record older than this is treated as ABANDONED on read. The 24h
         *  window comfortably covers the longest plausible "user backgrounded the
         *  installer, came back tomorrow" scenario; anything older is dead state. */
        const val STALE_PENDING_THRESHOLD_MS: Long = 24L * 60L * 60L * 1_000L
    }
}
