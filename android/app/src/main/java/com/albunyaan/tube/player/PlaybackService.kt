package com.albunyaan.tube.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import com.albunyaan.tube.R
import com.albunyaan.tube.ui.MainActivity

/**
 * Media3 MediaSessionService for background playback and media notifications.
 *
 * This service:
 * - Manages a MediaSession for external control (notification, Bluetooth, etc.)
 * - Handles foreground notification automatically via MediaSessionService
 * - Provides media button handling via Media3's built-in support
 *
 * **Lifecycle**:
 * - PlayerFragment binds to this service and provides the ExoPlayer instance
 * - The service does NOT own the player - PlayerFragment creates and releases it
 * - When the session is initialized with a player, MediaSessionService auto-starts foreground
 * - When the fragment releases the player, it should call releaseSession() first
 *
 * **Android O+ Foreground Requirement**:
 * Using bindService() with BIND_AUTO_CREATE avoids the startForeground() timing issue.
 * MediaSessionService handles startForeground() automatically when a player is attached.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    @Volatile
    private var mediaSession: MediaSession? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlaybackService created")
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Check for explicit local bind action first - this is deterministic
        // and won't break if Media3 changes super.onBind() behavior.
        //
        // Note: We don't perform UID checks here because onBind() runs on the main thread
        // (not in a Binder transaction), making Binder.getCallingUid() unreliable.
        // External apps receive a BinderProxy with no remote-callable interface (no AIDL/IInterface),
        // so they cannot invoke LocalBinder methods cross-process. The remaining "risk" is nuisance
        // binding (keeping service alive), which already exists since the service is exported for MediaSession.
        if (intent?.action == ACTION_LOCAL_BIND) {
            Log.d(TAG, "Local bind requested")
            return binder
        }
        // Otherwise delegate to MediaSessionService for MediaSession connections
        return super.onBind(intent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * Initialize the MediaSession with the given player.
     * Called by PlayerFragment after binding and creating ExoPlayer.
     *
     * Once a player is attached, MediaSessionService automatically handles
     * foreground notification display.
     *
     * @param player The ExoPlayer instance from PlayerFragment (fragment owns lifecycle)
     */
    fun initializeSession(player: Player) {
        if (mediaSession != null) {
            // Session already initialized - check if same player
            if (mediaSession?.player === player) {
                Log.d(TAG, "Session already initialized with same player")
                return
            }
            // Different player - release old session first
            Log.d(TAG, "Releasing old session for new player")
            mediaSession?.release()
            mediaSession = null
        }

        Log.d(TAG, "Initializing MediaSession with player")
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(createPlayerPendingIntent())
            .setCallback(MediaSessionCallback())
            .build()
    }

    /**
     * Release the MediaSession without releasing the player.
     * The player lifecycle is managed by PlayerFragment.
     *
     * Call this BEFORE releasing the player in PlayerFragment.
     */
    fun releaseSession() {
        Log.d(TAG, "Releasing MediaSession")
        mediaSession?.release()
        mediaSession = null
    }

    override fun onDestroy() {
        Log.d(TAG, "PlaybackService destroyed")
        releaseSession()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            Log.d(TAG, "Task removed with no active playback - stopping service")
            stopSelf()
        }
    }

    private fun createPlayerPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_PLAYER
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.player_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.player_notification_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Custom callback for handling media session commands.
     *
     * **Access levels:**
     * - FULL: Own app + legacy controller (system notification/Bluetooth/lockscreen)
     * - RESTRICTED: All other controllers (play/pause/seek/stop only, no queue manipulation)
     *
     * **Security posture (compatibility-first):**
     * - Service is exported to support Android Auto, Bluetooth, lockscreen, etc.
     * - Unknown controllers are accepted with restricted commands
     * - Restricted commands allow: PLAY_PAUSE, SEEK_*, STOP, GET_METADATA
     * - Restricted commands block: queue manipulation, speed changes, custom commands
     */
    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val accessLevel = determineAccessLevel(
                controllerPackage = controller.packageName,
                ownPackage = packageName
            )

            return when (accessLevel) {
                ControllerAccessLevel.FULL -> {
                    Log.d(TAG, "Full access for: ${controller.packageName}")
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        )
                        .setAvailablePlayerCommands(
                            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                        )
                        .build()
                }
                ControllerAccessLevel.RESTRICTED -> {
                    Log.i(TAG, "Restricted access for: ${controller.packageName} (uid=${controller.uid})")
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(RESTRICTED_SESSION_COMMANDS)
                        .setAvailablePlayerCommands(RESTRICTED_PLAYER_COMMANDS)
                        .build()
                }
            }
        }
    }

    /**
     * Access level for media session controllers.
     * Used by [determineAccessLevel] to decide what commands a controller can execute.
     */
    enum class ControllerAccessLevel {
        /** Full access: all session and player commands */
        FULL,
        /** Restricted access: basic playback only (play/pause/seek/stop/metadata) */
        RESTRICTED
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "playback"
        const val ACTION_OPEN_PLAYER = "com.albunyaan.tube.OPEN_PLAYER"
        /** Action for local in-app binding (distinguishes from MediaSession binding) */
        const val ACTION_LOCAL_BIND = "com.albunyaan.tube.LOCAL_BIND"

        /**
         * Determine the access level for a media session controller.
         *
         * **Full access** is granted only to:
         * - Our own app (in-app MediaController)
         * - Legacy controller ([MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME]):
         *   Represents system components using MediaControllerCompat (notification, lockscreen,
         *   Bluetooth/headset). Granted FULL to avoid regressions with system media button behavior.
         *
         * **Restricted access** is granted to all other controllers, including:
         * - Google apps (Android Auto, Assistant, Wear OS)
         * - Android system apps
         * - Third-party media controller apps
         *
         * This is intentionally restrictive: external controllers typically only need basic
         * playback (play/pause/seek/stop), not queue manipulation or speed changes. Android Auto
         * will work for transport controls but won't have browse/queue functionality.
         *
         * @param controllerPackage The package name of the connecting controller
         * @param ownPackage This app's package name
         * @return The access level to grant
         */
        fun determineAccessLevel(
            controllerPackage: String,
            ownPackage: String
        ): ControllerAccessLevel {
            // Full access only for own app and legacy controller
            val isOwnApp = controllerPackage == ownPackage
            val isLegacyController = controllerPackage == MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME

            return if (isOwnApp || isLegacyController) {
                ControllerAccessLevel.FULL
            } else {
                ControllerAccessLevel.RESTRICTED
            }
        }

        /**
         * Restricted session commands for non-privileged controllers.
         * Minimal set - only rating (required by some OEM controllers).
         * Does not include library browsing or custom commands.
         */
        private val RESTRICTED_SESSION_COMMANDS: SessionCommands = SessionCommands.Builder()
            .add(SessionCommand.COMMAND_CODE_SESSION_SET_RATING)
            .build()

        /**
         * Restricted player commands for non-privileged controllers.
         * Basic playback control only - no queue manipulation or speed changes.
         *
         * Allows: PLAY_PAUSE, SEEK_*, STOP, GET_METADATA
         * Blocks: SKIP_TO_NEXT/PREVIOUS, SET_SPEED, SET_MEDIA_ITEM, etc.
         */
        @Suppress("DEPRECATION") // COMMAND_GET_MEDIA_ITEMS_METADATA needed for notification metadata
        private val RESTRICTED_PLAYER_COMMANDS: Player.Commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_GET_MEDIA_ITEMS_METADATA)
            .build()

        /**
         * Bind to the PlaybackService for local in-app access.
         *
         * Uses explicit ACTION_LOCAL_BIND to ensure deterministic binder type
         * (won't break if Media3 changes super.onBind() behavior).
         *
         * Recommended usage pattern:
         * ```
         * private var playbackService: PlaybackService? = null
         * private var bindingRequested = false
         *
         * private val serviceConnection = object : ServiceConnection {
         *     override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
         *         playbackService = (binder as PlaybackService.LocalBinder).getService()
         *         player?.let { playbackService?.initializeSession(it) }
         *     }
         *     override fun onServiceDisconnected(name: ComponentName?) {
         *         // Service crashed - reset so we can rebind on next onStart()
         *         playbackService = null
         *         bindingRequested = false
         *     }
         * }
         *
         * // In onStart() - guard against repeated binds:
         * if (!bindingRequested) {
         *     bindingRequested = PlaybackService.bind(requireContext(), serviceConnection)
         * }
         *
         * // In onDestroyView() BEFORE releasing player:
         * playbackService?.releaseSession()
         * if (bindingRequested) {
         *     requireContext().unbindService(serviceConnection)
         *     bindingRequested = false
         * }
         * player?.release()
         * ```
         *
         * @return true if binding was initiated successfully, false otherwise.
         *         Only call unbindService() if this returns true.
         */
        fun bind(context: Context, connection: ServiceConnection): Boolean {
            return try {
                val intent = Intent(context, PlaybackService::class.java).apply {
                    action = ACTION_LOCAL_BIND
                }
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind to PlaybackService", e)
                false
            }
        }
    }
}
