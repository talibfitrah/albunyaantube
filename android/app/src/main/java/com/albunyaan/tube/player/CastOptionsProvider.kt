package com.albunyaan.tube.player

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Provides Cast SDK configuration options.
 *
 * This configures the Cast SDK to work with the Default Media Receiver app.
 * For production, you may want to use a custom receiver app with your branding.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        // Configure notification options for media playback
        val notificationOptions = NotificationOptions.Builder()
            .setTargetActivityClassName(
                "com.albunyaan.tube.ui.MainActivity"
            )
            .build()

        // Configure media options for cast
        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            .setExpandedControllerActivityClassName(
                "com.albunyaan.tube.ui.MainActivity"
            )
            .build()

        // Build cast options with Default Media Receiver
        // For production, replace with your custom receiver app ID
        return CastOptions.Builder()
            .setReceiverApplicationId(
                // Default Media Receiver - works with any media content
                "CC1AD845" // Default Media Receiver Application ID
            )
            .setCastMediaOptions(mediaOptions)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null
    }
}
