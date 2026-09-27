package com.albunyaan.tube.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last `/api/account/me` this device saw, so a signed-in user who launches
 * offline routes on it instead of being signed out (owner, 2026-09-24).
 *
 * One slot, stamped with the uid it belongs to: [read] returns null for any
 * other uid, so a different account can never see this one's profile.
 *
 * Holds PII (email, DOB, phone). App-private MODE_PRIVATE prefs, the same
 * store the app uses for its other per-install state (device_prefs); the file
 * never leaves the device because Auto Backup is off (AndroidManifest,
 * ANDROID-BACKUP-01). Deleted on sign-out and by [com.albunyaan.tube.data.account.LocalAccountDataWiper].
 */
@Singleton
class LastKnownAccountStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun write(account: AccountState.Loaded) {
        prefs.edit()
            .clear()
            .putString(UID, account.uid)
            .putString(EMAIL, account.email)
            .putString(DISPLAY_NAME, account.displayName)
            .putString(DATE_OF_BIRTH, account.dateOfBirth)
            .putString(PHONE, account.phoneNumber)
            .putString(STATUS, account.status.wire)
            .putString(ROLE, account.role)
            .apply()
    }

    fun read(uid: String): AccountState.Loaded? {
        if (uid.isEmpty() || prefs.getString(UID, null) != uid) return null
        return AccountState.Loaded(
            uid = uid,
            email = prefs.getString(EMAIL, null),
            displayName = prefs.getString(DISPLAY_NAME, null),
            dateOfBirth = prefs.getString(DATE_OF_BIRTH, null),
            phoneNumber = prefs.getString(PHONE, null),
            status = AccountStatus.fromWire(prefs.getString(STATUS, null)),
            role = prefs.getString(ROLE, null) ?: "user",
        )
    }

    /**
     * apply(), not commit(): sign-out runs on the main thread. Readers see the
     * clear immediately and SharedPreferences keeps disk writes in order, so a
     * later write can't land before it; only a process death in the few ms
     * before the flush leaves the file — inert, since [read] needs that uid
     * signed in again, and signing in needs a live /me that overwrites it.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "last_known_account"
        const val UID = "uid"
        const val EMAIL = "email"
        const val DISPLAY_NAME = "display_name"
        const val DATE_OF_BIRTH = "date_of_birth"
        const val PHONE = "phone_number"
        const val STATUS = "status"
        const val ROLE = "role"
    }
}
