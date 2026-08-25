package com.albunyaan.tube.data.account

import android.content.Context
import com.albunyaan.tube.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ANDROID-ACCT-DEL-01 — erases everything this install holds for the signed-in
 * user after the server has confirmed the account is gone.
 *
 * Sign-out deliberately keeps local data (the same person signs back in), so
 * nothing in the app wipes these stores today. Deletion must, or the next
 * person to sign in on this device inherits the previous owner's library,
 * downloaded media and device identity.
 */
@Singleton
class LocalAccountDataWiper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {

    suspend fun wipe() = withContext(Dispatchers.IO) {
        // Every table: favorites, subscriptions, saved playlists, the channel
        // video cache, feed refresh state, followed channels, sync cursors and
        // the account binding row.
        database.clearAllTables()

        // Downloaded media lives in app-private storage and survives sign-out.
        // Recreating downloads/metadata keeps DownloadStorage's on-disk layout
        // valid for the next account without re-constructing the singleton.
        val downloads = File(context.filesDir, DOWNLOADS_DIR)
        downloads.deleteRecursively()
        File(downloads, DOWNLOADS_METADATA_DIR).mkdirs()

        // The per-install id the backend uses to key anonymous content reports.
        // ponytail: NetworkModule captures this once when the OkHttpClient is
        // built, so the rotated id only reaches the wire on the next process
        // start. Harmless here — deletion signs the user out immediately — but
        // if a caller ever needs the new id within the same process, move the
        // lookup inside the header interceptor lambda.
        context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(DEVICE_ID_KEY)
            .commit()

        Unit
    }

    private companion object {
        const val DOWNLOADS_DIR = "downloads"
        const val DOWNLOADS_METADATA_DIR = "metadata"
        const val DEVICE_PREFS = "device_prefs"
        const val DEVICE_ID_KEY = "device_id"
    }
}
