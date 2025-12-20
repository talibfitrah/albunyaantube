package com.albunyaan.tube.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.albunyaan.tube.R
import com.albunyaan.tube.ui.MainActivity

class DownloadNotifications(private val context: Context) {

    fun createForegroundInfo(downloadId: String, title: String, progress: Int): ForegroundInfo {
        ensureChannel()
        val notification = buildNotification(title, progress)
        return ForegroundInfo(NOTIFICATION_ID_BASE + downloadId.hashCode(), notification)
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
