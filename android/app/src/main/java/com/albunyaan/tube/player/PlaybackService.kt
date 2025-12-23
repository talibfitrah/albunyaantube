package com.albunyaan.tube.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.MediaMetadata
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
    private var playerListener: Player.Listener? = null
    /** Cached artwork bitmap for notification (loaded by MediaSessionMetadataManager) */
    @Volatile
    private var cachedArtwork: Bitmap? = null
    /** Current video ID being played - used for notification tap intent */
    @Volatile
    private var currentVideoId: String? = null

    /**
     * Tracks whether the app is in the foreground (any activity visible).
     * Updated by ProcessLifecycleOwner which reliably detects app-level foreground/background.
     * When true, the notification should be hidden to avoid banner intrusion.
     * When false (app in background), notification is shown for media controls.
     */
    @Volatile
    private var isAppInForeground: Boolean = true

    /**
     * Tracks whether the player UI (PlayerFragment) is currently visible.
     * Set by PlayerFragment via setPlayerUiVisible() in onStart/onStop.
     * Used in combination with isAppInForeground to determine notification policy:
     * - Hide notification only when BOTH app is foreground AND player UI is visible
     * - Show notification when app is background OR user navigated away from player
     */
    @Volatile
    private var isPlayerUiVisible: Boolean = false

    /**
     * Observes app-level lifecycle to detect foreground/background transitions.
     * Combined with isPlayerUiVisible to implement notification policy:
     * - ProcessLifecycleOwner detects when app goes to background (show notification)
     * - isPlayerUiVisible distinguishes player-visible vs in-app navigation
     */
    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // App came to foreground
            Log.d(TAG, "App moved to foreground (ProcessLifecycleOwner.onStart)")
            isAppInForeground = true
            // Hide notification when app is in foreground
            mediaSession?.player?.let { updateForegroundState(it) }
        }

        override fun onStop(owner: LifecycleOwner) {
            // App went to background
            Log.d(TAG, "App moved to background (ProcessLifecycleOwner.onStop)")
            isAppInForeground = false
            // Show notification when app is in background (if playing)
            mediaSession?.player?.let { updateForegroundState(it) }
        }
    }

    /**
     * Session-unique token for validating dismiss intents.
     * Prevents third-party apps from sending spoofed ACTION_DISMISS intents.
     * Generated once per service instance; changes on service recreation.
     */
    private val dismissToken: Long = System.nanoTime()

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PlaybackService created (Android ${Build.VERSION.SDK_INT})")
        createNotificationChannel()

        // Register for app-level lifecycle events to detect foreground/background
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)

        // Sync initial state: observer callbacks only fire on TRANSITIONS, not current state.
        // If service starts while app is already foregrounded, onStart() won't be called.
        // Explicitly check current state to ensure correct initial value.
        val appLifecycle = ProcessLifecycleOwner.get().lifecycle
        isAppInForeground = appLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        Log.d(TAG, "Initial app foreground state: $isAppInForeground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called (action=${intent?.action}, flags=$flags, startId=$startId)")

        // Handle notification action intents
        when (intent?.action) {
            ACTION_PLAY -> {
                Log.d(TAG, "ACTION_PLAY received")
                mediaSession?.player?.let { player ->
                    player.play()
                    // After starting playback, update foreground state
                    // This enters foreground mode if UI is not visible
                    updateForegroundState(player)
                }
            }
            ACTION_PAUSE -> {
                Log.d(TAG, "ACTION_PAUSE received")
                mediaSession?.player?.let { player ->
                    player.pause()
                    // After pausing, update foreground state
                    // This updates notification to show play button
                    updateForegroundState(player)
                }
            }
            ACTION_SKIP_PREV -> {
                Log.d(TAG, "ACTION_SKIP_PREV received")
                mediaSession?.player?.let { player ->
                    if (player.hasPreviousMediaItem()) {
                        player.seekToPreviousMediaItem()
                    } else {
                        player.seekTo(0)
                    }
                }
            }
            ACTION_SKIP_NEXT -> {
                Log.d(TAG, "ACTION_SKIP_NEXT received")
                mediaSession?.player?.let { player ->
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                    }
                }
            }
            ACTION_DISMISS -> {
                // Validate token to prevent spoofed dismiss intents from third-party apps
                val intentToken = intent.getLongExtra(EXTRA_DISMISS_TOKEN, 0L)
                if (intentToken != dismissToken) {
                    Log.w(TAG, "ACTION_DISMISS rejected: invalid or missing token")
                    return START_NOT_STICKY
                }
                // User swiped away the paused notification
                // Stop service since there's no playback and no way to resume
                Log.d(TAG, "ACTION_DISMISS received - notification swiped away, stopping service")
                mediaSession?.player?.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // CRITICAL: On Android 8+, when started via startForegroundService(), we MUST call
        // startForeground() within 5 seconds or the app will crash with:
        // "Context.startForegroundService() did not then call Service.startForeground()"
        //
        // MediaSessionService only calls startForeground() when a player is attached AND playing.
        // If the player isn't ready yet (still loading), the crash occurs. To prevent this,
        // we immediately start foreground with a placeholder notification, which MediaSessionService
        // will replace with the proper media notification once the player starts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mediaSession == null) {
            Log.d(TAG, "Starting foreground immediately with placeholder notification (no session yet)")
            startForeground(NOTIFICATION_ID, createPlaceholderNotification())
        }

        // Delegate to MediaSessionService which handles the full media notification
        // when a player is attached and playing
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Creates a minimal placeholder notification for foreground service compliance.
     * This is shown briefly until MediaSessionService replaces it with the proper
     * media notification once the player is attached and playing.
     */
    private fun createPlaceholderNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(R.string.player_notification_loading))
            .setContentIntent(createPlayerPendingIntent())
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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
            removePlayerListener()
            mediaSession?.release()
            mediaSession = null
        }

        Log.d(TAG, "Initializing MediaSession with player (playWhenReady=${player.playWhenReady}, state=${player.playbackState})")
        try {
            mediaSession = MediaSession.Builder(this, player)
                .setSessionActivity(createPlayerPendingIntent())
                .setCallback(MediaSessionCallback())
                .build()

            // Add listener to update notification when playback state or metadata changes
            addPlayerListener(player)

            Log.i(TAG, "MediaSession initialized successfully - notification should appear when playback starts")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaSession", e)
        }
    }

    /**
     * Set the artwork bitmap for notification display.
     * Called by MediaSessionMetadataManager when artwork is loaded.
     */
    fun setArtwork(bitmap: Bitmap?) {
        cachedArtwork = bitmap
        // Trigger notification update with new artwork
        mediaSession?.player?.let { player ->
            if (player.isPlaying || player.playbackState == Player.STATE_READY) {
                updateMediaNotification(player)
            }
        }
    }

    private fun addPlayerListener(player: Player) {
        removePlayerListener()
        playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying, uiVisible=$isPlayerUiVisible")
                // Update foreground state based on new play state
                // This handles: playback started/stopped from any source (notification, Bluetooth, etc.)
                updateForegroundState(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "onPlaybackStateChanged: state=$playbackState, playWhenReady=${player.playWhenReady}")
                // Update foreground state when playback state changes
                // Covers buffering→ready transitions where playback may start/resume
                updateForegroundState(player)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                Log.d(TAG, "onMediaMetadataChanged: title=${mediaMetadata.title}")
                // Metadata change only needs notification update, not foreground state change
                if (!isPlayerUiVisible && (player.isPlaying || player.playbackState == Player.STATE_READY)) {
                    updateMediaNotification(player)
                }
            }
        }
        player.addListener(playerListener!!)
    }

    private fun removePlayerListener() {
        playerListener?.let { listener ->
            mediaSession?.player?.removeListener(listener)
        }
        playerListener = null
    }

    /**
     * Build a MediaStyle notification with current player state.
     * Common helper used by both updateMediaNotification and enterForegroundMode.
     *
     * @param player The player to get state from
     * @param includeDeleteIntent Whether to include delete intent for swipe-to-dismiss (when paused)
     */
    private fun buildMediaNotification(player: Player, includeDeleteIntent: Boolean): Notification? {
        val session = mediaSession ?: return null

        val metadata = player.mediaMetadata
        val title = metadata.title?.toString() ?: getString(R.string.player_default_title)
        val artist = metadata.artist?.toString() ?: metadata.albumArtist?.toString() ?: ""

        @Suppress("DEPRECATION")
        val sessionToken = session.sessionCompatToken

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(createPlayerPendingIntent())
            .setOngoing(player.isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(createSkipPrevAction())
            .addAction(if (player.isPlaying) createPauseAction() else createPlayAction())
            .addAction(createSkipNextAction())
            .apply {
                cachedArtwork?.let { bitmap -> setLargeIcon(bitmap) }
                if (includeDeleteIntent && !player.isPlaying) {
                    setDeleteIntent(createDismissPendingIntent())
                }
            }
            .build()
    }

    /**
     * Update the foreground notification with proper media controls.
     * This replaces the placeholder "Loading..." notification with a full media notification.
     *
     * Uses MediaStyle notification for proper media control integration with the system.
     * Actions dispatch directly to this service which handles them via onStartCommand.
     *
     * Note: Notification is only shown when player UI is NOT visible (background playback).
     */
    private fun updateMediaNotification(player: Player) {
        // Don't show notification if player UI is visible - user has direct controls
        if (isPlayerUiVisible) {
            Log.d(TAG, "Skipping notification update - UI is visible")
            return
        }

        val notification = buildMediaNotification(player, includeDeleteIntent = true) ?: return

        Log.d(TAG, "Updating media notification: isPlaying=${player.isPlaying}")

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)

        Log.d(TAG, "Media notification updated successfully")
    }

    private fun createPlayAction(): NotificationCompat.Action {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_PLAY
        }
        val pendingIntent = PendingIntent.getService(
            this, REQUEST_CODE_PLAY, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_play,
            getString(R.string.player_action_play),
            pendingIntent
        ).build()
    }

    private fun createPauseAction(): NotificationCompat.Action {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_PAUSE
        }
        val pendingIntent = PendingIntent.getService(
            this, REQUEST_CODE_PAUSE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_pause,
            getString(R.string.player_action_pause),
            pendingIntent
        ).build()
    }

    private fun createSkipPrevAction(): NotificationCompat.Action {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_SKIP_PREV
        }
        val pendingIntent = PendingIntent.getService(
            this, REQUEST_CODE_SKIP_PREV, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_skip_previous,
            getString(R.string.player_action_previous),
            pendingIntent
        ).build()
    }

    private fun createSkipNextAction(): NotificationCompat.Action {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_SKIP_NEXT
        }
        val pendingIntent = PendingIntent.getService(
            this, REQUEST_CODE_SKIP_NEXT, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_skip_next,
            getString(R.string.player_action_next),
            pendingIntent
        ).build()
    }

    /**
     * Create PendingIntent for notification dismiss (swipe-away).
     * Used as deleteIntent when notification is dismissible (paused state).
     * Includes a session-unique token to prevent spoofed dismiss intents.
     */
    private fun createDismissPendingIntent(): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_DISMISS_TOKEN, dismissToken)
        }
        return PendingIntent.getService(
            this, REQUEST_CODE_DISMISS, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Release the MediaSession without releasing the player.
     * The player lifecycle is managed by PlayerFragment.
     *
     * Call this BEFORE releasing the player in PlayerFragment.
     */
    fun releaseSession() {
        Log.d(TAG, "Releasing MediaSession")
        removePlayerListener()
        cachedArtwork = null
        mediaSession?.release()
        mediaSession = null
    }

    override fun onDestroy() {
        Log.d(TAG, "PlaybackService destroyed")
        // Unregister lifecycle observer to prevent leaks
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
        releaseSession()
        // Clear static field when service is destroyed
        activeVideoId = null
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
                // Include videoId so PlayerFragment can display the currently playing video
                currentVideoId?.let { putExtra(EXTRA_VIDEO_ID, it) }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Get the current video ID being played.
     * Used by MainActivity to check if notification tap should navigate or just bring to foreground.
     */
    fun getCurrentVideoId(): String? = currentVideoId

    /**
     * Set the current video ID for notification intents.
     * Called by PlayerFragment when the current item changes.
     */
    fun setCurrentVideoId(videoId: String?) {
        if (currentVideoId != videoId) {
            currentVideoId = videoId
            // Also update static field for MainActivity access without binding
            activeVideoId = videoId
            Log.d(TAG, "setCurrentVideoId: $videoId")
            // Update notification to use new intent with videoId
            mediaSession?.player?.let { player ->
                if (player.isPlaying || player.playbackState == Player.STATE_READY) {
                    updateMediaNotification(player)
                }
            }
        }
    }

    /**
     * Set whether the player UI is currently visible.
     *
     * When UI is visible:
     * - Stop foreground mode and hide notification (user has direct controls)
     * - Service continues running via binding
     *
     * When UI is not visible (background):
     * - Start foreground mode with notification for media controls
     * - This is required for background playback on Android O+
     *
     * @param visible true if PlayerFragment is on-screen, false if in background
     */
    fun setPlayerUiVisible(visible: Boolean) {
        if (isPlayerUiVisible == visible) return
        isPlayerUiVisible = visible
        Log.d(TAG, "setPlayerUiVisible: $visible")

        // Use unified foreground state management
        // This handles all visibility transitions consistently
        mediaSession?.player?.let { player ->
            updateForegroundState(player)
        }
    }

    /**
     * Enter foreground mode with proper notification.
     * Called when app goes to background with active playback.
     */
    private fun enterForegroundMode(player: Player) {
        val notification = buildMediaNotification(player, includeDeleteIntent = false) ?: return

        Log.d(TAG, "Entering foreground mode")

        // Use startForeground() to properly enter foreground state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "Foreground mode started")
    }

    /**
     * Exit foreground mode and remove notification.
     * Called when player UI becomes visible.
     */
    private fun exitForegroundMode() {
        // STOP_FOREGROUND_REMOVE removes the notification and demotes from foreground
        // This is the proper way to hide notification without violating FGS rules
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.d(TAG, "Foreground mode stopped (UI visible)")
    }

    /**
     * Exit foreground mode but keep the notification visible.
     * Called when paused in background - demotes from FGS to save battery
     * while keeping a non-FGS notification for quick resume.
     */
    private fun exitForegroundModeKeepNotification(player: Player) {
        // STOP_FOREGROUND_DETACH demotes from FGS but keeps notification visible
        // The notification becomes a regular (non-foreground) notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            // Pre-N: stopForeground(false) keeps notification but demotes from FGS
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        // Update notification to show paused state (play button instead of pause)
        updateMediaNotification(player)
        Log.d(TAG, "Foreground mode stopped (paused in background), notification retained")
    }

    /**
     * Unified foreground state management.
     *
     * This is the single source of truth for determining if the service should be in foreground mode.
     * Called from: notification actions, player listener callbacks, app lifecycle changes.
     *
     * **Decision logic:**
     * - If app is in foreground → exit foreground (user has direct controls)
     * - If app is in background AND playback is active → enter foreground (background playback)
     * - If app is in background AND playback is NOT active → demote from foreground, show paused notification
     *
     * "Playback active" = isPlaying OR (STATE_READY/BUFFERING AND playWhenReady)
     *
     * **App foreground detection:**
     * Uses ProcessLifecycleOwner (isAppInForeground) which reliably detects app-level
     * foreground/background state regardless of which fragment is visible.
     *
     * **Paused-in-background policy:**
     * When paused while backgrounded, we demote from FGS but post a regular notification.
     * This saves battery (no FGS overhead) while allowing quick resume via notification.
     * Android may eventually dismiss the notification if user swipes away or system reclaims.
     */
    private fun updateForegroundState(player: Player) {
        val isPlaybackActive = player.isPlaying ||
            ((player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
                && player.playWhenReady)

        // Combined policy: hide notification only when BOTH conditions are met:
        // 1. App is in foreground (ProcessLifecycleOwner)
        // 2. Player UI is visible (PlayerFragment.setPlayerUiVisible)
        // This ensures notification shows when:
        // - App is in background (regardless of player UI state)
        // - App is in foreground but user navigated away from player
        val shouldHideNotification = isAppInForeground && isPlayerUiVisible

        Log.d(TAG, "updateForegroundState: appInForeground=$isAppInForeground, playerUiVisible=$isPlayerUiVisible, " +
            "playbackActive=$isPlaybackActive, isPlaying=${player.isPlaying}, state=${player.playbackState}")

        when {
            shouldHideNotification -> {
                // App is in foreground - don't need foreground service, user has direct controls
                exitForegroundMode()
            }
            isPlaybackActive -> {
                // Background with active playback - must be in foreground
                enterForegroundMode(player)
            }
            else -> {
                // Background but not playing - demote from FGS and show paused notification
                // This conserves battery while allowing quick resume via notification tap
                exitForegroundModeKeepNotification(player)
            }
        }
    }

    /**
     * Creates the notification channel for playback notifications.
     *
     * The channel is created with IMPORTANCE_LOW to avoid heads-up banners.
     * We NEVER delete existing channels as that would override user preferences.
     * If the channel already exists, we respect whatever importance the user has set.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Check if current channel exists
            val existingChannel = manager.getNotificationChannel(CHANNEL_ID)
            if (existingChannel != null) {
                // Channel exists - respect user's settings, don't modify or delete
                // If user changed importance, that's their choice to keep
                Log.d(TAG, "Notification channel '$CHANNEL_ID' exists with importance ${existingChannel.importance}")
                return
            }

            // Create new channel with LOW importance
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.player_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.player_notification_channel_desc)
                // Explicitly disable heads-up/peek behavior
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // Disable vibration and sound for media playback notifications
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
            Log.i(TAG, "Created notification channel '$CHANNEL_ID' with IMPORTANCE_LOW")
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
        /** Notification ID for foreground service - must be unique and non-zero */
        private const val NOTIFICATION_ID = 1001
        const val ACTION_OPEN_PLAYER = "com.albunyaan.tube.OPEN_PLAYER"
        /** Extra key for videoId in notification tap intent */
        const val EXTRA_VIDEO_ID = "video_id"
        /** Action for local in-app binding (distinguishes from MediaSession binding) */
        const val ACTION_LOCAL_BIND = "com.albunyaan.tube.LOCAL_BIND"

        /**
         * Static reference to the currently playing video ID.
         * Updated by setCurrentVideoId() for quick access from MainActivity
         * without needing to bind to the service.
         *
         * **Thread safety:**
         * - PlaybackService is a singleton (only one instance runs)
         * - Updated on main thread via setCurrentVideoId()
         * - Read on main thread by MainActivity's handleOpenPlayerIntent()
         *
         * **Process death behavior:**
         * This is in-memory state that does NOT survive process death.
         * After process death, activeVideoId will be null. This is intentional:
         * - ExoPlayer state is lost on process death anyway
         * - Fragment's ViewModel doesn't persist playback position
         * - MainActivity handles null gracefully (brings app to foreground without navigation)
         *
         * If "resume after process death" is ever required, playback state would need
         * to be persisted to disk (SharedPreferences/Room) and restored on service creation.
         * This is not currently implemented as it adds complexity for minimal user benefit.
         */
        @Volatile
        @JvmStatic
        var activeVideoId: String? = null
            private set

        // Notification action intents
        private const val ACTION_PLAY = "com.albunyaan.tube.ACTION_PLAY"
        private const val ACTION_PAUSE = "com.albunyaan.tube.ACTION_PAUSE"
        private const val ACTION_SKIP_PREV = "com.albunyaan.tube.ACTION_SKIP_PREV"
        private const val ACTION_SKIP_NEXT = "com.albunyaan.tube.ACTION_SKIP_NEXT"
        private const val ACTION_DISMISS = "com.albunyaan.tube.ACTION_DISMISS"

        // Request codes for PendingIntents (must be unique)
        private const val REQUEST_CODE_PLAY = 1
        private const val REQUEST_CODE_PAUSE = 2
        private const val REQUEST_CODE_SKIP_PREV = 3
        private const val REQUEST_CODE_SKIP_NEXT = 4
        private const val REQUEST_CODE_DISMISS = 5

        /** Extra key for dismiss token validation (prevents spoofed dismiss intents) */
        private const val EXTRA_DISMISS_TOKEN = "dismiss_token"

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
