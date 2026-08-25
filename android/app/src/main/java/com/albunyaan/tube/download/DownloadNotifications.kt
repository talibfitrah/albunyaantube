package com.albunyaan.tube.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.albunyaan.tube.R
import com.albunyaan.tube.ui.MainActivity

/**
 * ANDROID-PLAY-02: POST_NOTIFICATIONS is declared in the manifest but nothing
 * ever requested it, so on Android 13+ (API 33) every download-progress
 * notification built above was posted into a permission the user was never
 * asked for — silently dropped by the OS.
 *
 * Asked contextually at the first download tap rather than at app launch: a
 * cold-start permission prompt has no visible justification and is a known
 * cause of denial. Denial is non-fatal — the download still runs, only its
 * notification is lost.
 *
 * Media-session (playback) notifications are exempt from the API 33 runtime
 * grant, so playback must never be gated on this.
 */
object DownloadNotificationPermission {

    /**
     * Pure form, so the decision is testable off-device. Below API 33 the
     * permission does not exist and is granted at install time.
     */
    fun shouldRequest(sdkInt: Int, granted: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted

    fun shouldRequest(context: Context): Boolean =
        shouldRequest(Build.VERSION.SDK_INT, isGranted(context))

    private fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

class DownloadNotifications(private val context: Context) {

    fun createForegroundInfo(downloadId: String, title: String, progress: Int): ForegroundInfo {
        ensureChannel()
        val notification = buildNotification(title, progress)
        val notificationId = NOTIFICATION_ID_BASE + downloadId.hashCode()
        // Android 14 (API 34+) requires foregroundServiceType when starting foreground service
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildNotification(title: String, progress: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.download_notification_title, title))
            .setContentText(context.getString(R.string.download_notification_progress, progress))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(contentIntent)
            .build()
    }

    fun notifyCompletion(downloadId: String, title: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.download_notification_title, title))
            .setContentText(context.getString(R.string.download_notification_complete))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context)
            .notify(NOTIFICATION_ID_BASE + downloadId.hashCode(), notification)
    }

    /**
     * Update progress notification (for use outside of foreground info).
     */
    fun updateProgress(downloadId: String, title: String, progress: Int) {
        ensureChannel()
        val notification = buildNotification(title, progress)
        NotificationManagerCompat.from(context)
            .notify(NOTIFICATION_ID_BASE + downloadId.hashCode(), notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.download_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.download_notification_channel_desc)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID_BASE = 5000
    }
}
