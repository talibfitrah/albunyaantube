package com.albunyaan.tube.ui.player

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.albunyaan.tube.data.extractor.QualityConstraintMode
import com.albunyaan.tube.player.QualityTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.ui.utils.isTablet
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentPlayerBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import com.albunyaan.tube.data.extractor.PlaybackSelection
import com.albunyaan.tube.data.extractor.QualitySelectionOrigin
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.SubtitleTrack
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadStatus
import com.albunyaan.tube.analytics.PlaybackMetricsCollector
import com.albunyaan.tube.player.AdaptiveBufferPolicy
import com.albunyaan.tube.player.BufferHealthMonitor
import com.albunyaan.tube.player.MediaSessionMetadataManager
import com.albunyaan.tube.player.CacheHitDecider
import com.albunyaan.tube.player.MediaSourceResult
import com.albunyaan.tube.player.PlaybackRecoveryManager
import com.albunyaan.tube.player.PlaybackService
import com.albunyaan.tube.player.StreamRequestTelemetry
import com.albunyaan.tube.player.StreamUrlRefreshManager
import javax.inject.Inject
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * P3-T4: PlayerFragment with Hilt DI
 *
 * Phase 8 scaffold for the playback screen. Hooks Media3 ExoPlayer with audio-only toggle state managed
 * by [PlayerViewModel]; real media sources will be supplied once backend wiring is available.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class PlayerFragment : Fragment(R.layout.fragment_player) {

    @Inject lateinit var metadataManager: MediaSessionMetadataManager
    @Inject lateinit var streamTelemetry: StreamRequestTelemetry
    @Inject lateinit var streamUrlRefreshManager: StreamUrlRefreshManager
    @Inject lateinit var hlsPoisonRegistry: com.albunyaan.tube.player.HlsPoisonRegistry
    @Inject lateinit var multiRepFactory: com.albunyaan.tube.player.MultiRepSyntheticDashMediaSourceFactory
    @Inject lateinit var coldStartQualityChooser: com.albunyaan.tube.player.ColdStartQualityChooser
    @Inject lateinit var degradationManager: com.albunyaan.tube.player.PlaybackDegradationManager
    @Inject lateinit var featureFlags: com.albunyaan.tube.player.PlaybackFeatureFlags
    @Inject lateinit var mpdRegistry: com.albunyaan.tube.player.SyntheticDashMpdRegistry
    @Inject lateinit var bufferPolicy: AdaptiveBufferPolicy

    private var binding: FragmentPlayerBinding? = null
    private var player: ExoPlayer? = null
    private var playbackListener: Player.Listener? = null
    private var trackSelector: QualityTrackSelector? = null
    private val viewModel: PlayerViewModel by viewModels()
    private val upNextAdapter = UpNextAdapter { item -> viewModel.playItem(item) }
    private var preparedStreamKey: Pair<String, Boolean>? = null
    /**
     * Tracks the currently prepared *source identity*:
     * - Adaptive: manifest URL (HLS/DASH)
     * - Progressive: selected video URL
     * - Audio-only: selected audio URL
     *
     * This prevents unnecessary re-prepares (and rebuffering) when the user changes only the
     * adaptive quality cap (track-selector constraint), which should not require rebuilding
     * the MediaSource.
     */
    private var preparedStreamUrl: String? = null
    private var pendingResumeStreamId: String? = null
    private var pendingResumePositionMs: Long? = null
    private var pendingResumePlayWhenReady: Boolean? = null
    private lateinit var gestureDetector: GestureDetector
    private var isFullscreen = false
    /** Current resize mode in fullscreen: ZOOM (fills screen, default) or FIT (letterbox). Toggled by double-tap. */
    private var fullscreenResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    /** Tracks whether we programmatically locked orientation (to avoid unlocking locks set by other code) */
    private var weLockedOrientation = false
    /** The target orientation we requested when locking (LANDSCAPE or PORTRAIT) */
    private var targetOrientationIsLandscape: Boolean? = null
    /** Handler for orientation unlock timeout (uses main looper for null-safe view operations) */
    private val orientationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Pending runnable for orientation unlock (fallback if config change doesn't fire) */
    private var orientationUnlockRunnable: Runnable? = null
    private var castContext: com.google.android.gms.cast.framework.CastContext? = null
    private var exoNextButton: View? = null
    private var exoPrevButton: View? = null
    /** Tracks whether current media source is adaptive (HLS/DASH) vs progressive */
    private var preparedIsAdaptive: Boolean = false
    /** Tracks the type of adaptive source prepared (SYNTHETIC_DASH needs special handling) */
    private var preparedAdaptiveType: MediaSourceResult.AdaptiveType =
        MediaSourceResult.AdaptiveType.NONE
    /**
     * Phase 1B: Tracks when the current stream was prepared (elapsedRealtime).
     * Used to detect "early 403" errors that should trigger HLS poisoning.
     * Set at prepare() time (not onIsPlayingChanged) to catch pre-playback 403s during buffering.
     * Reset to 0L when switching to a new stream; set again at next prepare().
     */
    private var streamPlaybackStartTimeMs: Long = 0L
    /** Last applied user cap for adaptive streams; used to update track selector without rebuilding. */
    private var preparedQualityCapHeight: Int? = null
    /**
     * The actual video track selected by the factory. For progressive/SYNTHETIC_DASH, this may differ
     * from selection.video when cold-start quality selection is applied in AUTO mode.
     * Used for accurate proactive downshift decisions (current quality = factory's choice, not ViewModel's).
     * Null for adaptive HLS/DASH (ABR handles quality) or audio-only mode.
     */
    private var factorySelectedVideoTrack: VideoTrack? = null
    /**
     * Sticky fallback flag: If adaptive creation failed for the current resolved streams,
     * don't keep re-attempting adaptive on every PlayerState emission. Reset on new stream
     * or manual refresh.
     */
    private var adaptiveFailedForCurrentStream: String? = null

    /** Manages automatic playback recovery for freeze detection and stall handling */
    private var recoveryManager: PlaybackRecoveryManager? = null

    /** Monitors buffer health and triggers proactive quality downshift for progressive streams */
    private var bufferHealthMonitor: BufferHealthMonitor? = null

    /** PlaybackService for MediaSession and background playback */
    private var playbackService: PlaybackService? = null
    /** Tracks whether we called bindService (must unbind even if onServiceConnected hasn't fired) */
    private var bindingRequested = false
    /** Tracks user's intended play state - used to preserve pause across lifecycle */
    private var userWantsToPlay = true
    /** Tracks the last item synced to MediaSession metadata to avoid redundant updates */
    private var lastMetadataSyncedItemId: String? = null

    private fun sourceIdentityForLog(url: String?): String {
        if (url.isNullOrBlank()) return "null"
        val hash = Integer.toHexString(url.hashCode())
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        return if (!host.isNullOrBlank()) "$host#$hash" else "hash#$hash"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playbackService = (binder as PlaybackService.LocalBinder).getService()
            // Initialize MediaSession with our player once service is bound
            player?.let { playbackService?.initializeSession(it) }
            // Mark UI as visible now that we have the service reference.
            // This handles the case where service connects after onStart() has already run.
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                playbackService?.setPlayerUiVisible(true)
            }
            // Wire artwork callback: when MetadataManager loads artwork, pass to PlaybackService
            val service = playbackService
            metadataManager.artworkCallback = { bitmap ->
                service?.setArtwork(bitmap)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Called when service crashes or is killed - not during normal unbind
            playbackService = null
            bindingRequested = false
            // Clear artwork callback since service is gone
            metadataManager.artworkCallback = null

            // Attempt immediate rebind only if fragment is visible (started state)
            // This handles service death while user is actively watching video
            // Using lifecycle check to avoid rebinding when app is in background
            if (view != null && isAdded && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                android.util.Log.w("PlayerFragment", "Service disconnected unexpectedly - attempting rebind")
                bindingRequested = PlaybackService.bind(requireContext(), this)
            }
        }
    }

    // Overlay controls are now always visible for better UX
    // Users need constant access to quality, cast, and minimize buttons
    private val castSessionListener = object : com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession> {
        override fun onSessionStarted(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {
            loadMediaToCast()
        }

        override fun onSessionEnded(session: com.google.android.gms.cast.framework.CastSession, error: Int) {
            // Resume local playback
            player?.playWhenReady = true
        }

        override fun onSessionResumed(session: com.google.android.gms.cast.framework.CastSession, wasSuspended: Boolean) {
            loadMediaToCast()
        }

        override fun onSessionStarting(session: com.google.android.gms.cast.framework.CastSession) {}
        override fun onSessionStartFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
        override fun onSessionEnding(session: com.google.android.gms.cast.framework.CastSession) {}
        override fun onSessionResuming(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
        override fun onSessionSuspended(session: com.google.android.gms.cast.framework.CastSession, reason: Int) {}
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Use MenuProvider API instead of deprecated setHasOptionsMenu(true)
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
        val binding = FragmentPlayerBinding.bind(view).also { binding = it }

        // Initialize Cast context
        try {
            castContext = com.google.android.gms.cast.framework.CastContext.getSharedInstance(requireContext())
        } catch (e: Exception) {
            // Cast not available
        }

        // Get playback arguments - check for playlist first, then single video
        val playlistId = arguments?.getString("playlistId")
        val videoId = arguments?.getString("videoId")
        val startIndex = arguments?.getInt("startIndex", 0) ?: 0
        val shuffled = arguments?.getBoolean("shuffled", false) ?: false
        // PR6.6: targetVideoId is the authoritative video to start at; startIndex is an optimization hint
        val targetVideoId = arguments?.getString("targetVideoId")

        // Extract metadata from nav args for fast-path playback (no backend fetch needed)
        val videoTitle = arguments?.getString("title") ?: getString(R.string.player_default_title)
        val channelName = arguments?.getString("channelName") ?: ""
        val thumbnailUrl = arguments?.getString("thumbnailUrl")
        val description = arguments?.getString("description")
        val durationSeconds = arguments?.getInt("durationSeconds", 0) ?: 0
        val viewCount = arguments?.getLong("viewCount", -1L)?.takeIf { it >= 0 }

        when {
            !playlistId.isNullOrEmpty() -> {
                // Playlist playback mode - load playlist and start from specified video
                // PR6.6: Pass targetVideoId as authoritative, startIndex as hint for fast path
                viewModel.loadPlaylist(playlistId, targetVideoId, startIndex, shuffled)
            }
            !videoId.isNullOrEmpty() -> {
                // Single video playback mode - fast path with metadata from nav args
                viewModel.loadVideo(
                    videoId = videoId,
                    title = videoTitle,
                    channelName = channelName,
                    thumbnailUrl = thumbnailUrl,
                    description = description,
                    durationSeconds = durationSeconds,
                    viewCount = viewCount
                )
            }
            // If no arguments, ViewModel will use default stub queue (for testing)
        }

        // Access Media3's internal navigation buttons
        // Note: These IDs are part of Media3's default player controls layout
        exoNextButton = binding.playerView.findViewById(androidx.media3.ui.R.id.exo_next)
        exoPrevButton = binding.playerView.findViewById(androidx.media3.ui.R.id.exo_prev)

        if (exoNextButton == null || exoPrevButton == null) {
            android.util.Log.w("PlayerFragment", "Media3 navigation buttons not found - check Media3 version compatibility")
        }

        // Ensure prev/next buttons are always visible (ExoPlayer may hide them if no playlist attached)
        exoNextButton?.visibility = View.VISIBLE
        exoPrevButton?.visibility = View.VISIBLE

        binding.audioOnlyToggle.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAudioOnly(isChecked)
        }
        binding.completeButton.setOnClickListener {
            viewModel.markCurrentComplete()
        }

        // Setup description dropdown
        binding.descriptionHeader.setOnClickListener {
            val isExpanded = binding.videoDescription.isVisible
            binding.videoDescription.isVisible = !isExpanded
            binding.descriptionArrow.rotation = if (isExpanded) 0f else 180f
        }

        // Setup action buttons
        binding.favoriteButton.setOnClickListener {
            val wasFavorite = viewModel.state.value.isFavorite
            viewModel.toggleFavorite()
            // Show toast feedback - state will be inverted after toggle
            val messageRes = if (wasFavorite) {
                R.string.player_removed_from_favorites
            } else {
                R.string.player_added_to_favorites
            }
            Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
        }

        binding.shareButton.setOnClickListener {
            shareCurrentVideo()
        }

        binding.audioButton.setOnClickListener {
            binding.audioOnlyToggle.isChecked = !binding.audioOnlyToggle.isChecked
        }

        // Download button - shows quality picker, downloads are always allowed (no EULA gating)
        binding.downloadButton.setOnClickListener {
            showDownloadQualityPicker()
        }

        // Register Fragment Result listener for download quality selection (survives process death)
        childFragmentManager.setFragmentResultListener(
            DownloadQualityDialog.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, result ->
            val targetHeight = result.getInt(DownloadQualityDialog.RESULT_TARGET_HEIGHT)
                .takeIf { it != DownloadQualityDialog.NO_HEIGHT }
            val isAudioOnly = result.getBoolean(DownloadQualityDialog.RESULT_IS_AUDIO_ONLY)
            val started = viewModel.downloadCurrent(targetHeight, isAudioOnly)
            if (started) {
                Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show()
            }
        }

        binding.minimizeButton.setOnClickListener {
            // Use navigateUp for predictable navigation back to previous screen
            findNavController().navigateUp()
        }

        binding.fullscreenButton.setOnClickListener {
            toggleFullscreen()
        }

        binding.qualityButton.setOnClickListener {
            showQualitySelector()
        }

        // Overlay controls are always visible now - no click listener needed

        // Setup error retry buttons
        binding.playerRetryButton.setOnClickListener {
            viewModel.retryCurrentStream()
        }
        binding.playerRefreshStreamButton.setOnClickListener {
            // Reset adaptive fallback flag - manual refresh should retry adaptive
            adaptiveFailedForCurrentStream = null
            // Manual refresh: ALWAYS invalidate MPD first, regardless of rate limit.
            // This is intentional - user-initiated refresh should clear stale URLs even if
            // rate-limited, so the next allowed refresh uses fresh URLs. Different from
            // auto-recovery path (line ~1475) where invalidation is conditional.
            viewModel.state.value.currentItem?.streamId?.let { videoId ->
                mpdRegistry.unregister(videoId)
            }
            // PR5: Show feedback if rate-limited
            if (!viewModel.forceRefreshCurrentStream()) {
                Toast.makeText(requireContext(), R.string.player_refresh_rate_limited, Toast.LENGTH_SHORT).show()
            }
        }

        // Setup recovery retry button (shown when auto-recovery exhausted)
        binding.playerRecoveryRetryButton.setOnClickListener {
            // User manual retry - resets recovery state and forces stream refresh
            recoveryManager?.resetRecoveryState()
            viewModel.clearRecoveringState()
            // Reset adaptive fallback flag - manual retry should retry adaptive
            adaptiveFailedForCurrentStream = null
            // Manual retry: ALWAYS invalidate MPD first, regardless of rate limit.
            // Same reasoning as refresh button - user action clears stale URLs.
            viewModel.state.value.currentItem?.streamId?.let { videoId ->
                mpdRegistry.unregister(videoId)
            }
            // PR5: Show feedback if rate-limited
            if (!viewModel.forceRefreshCurrentStream()) {
                Toast.makeText(requireContext(), R.string.player_refresh_rate_limited, Toast.LENGTH_SHORT).show()
            }
        }

        // Configure Cast button
        castContext?.let {
            com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(
                requireContext().applicationContext,
                binding.castButton
            )
        }

        setupUpNextList(binding)
        setupPlayer(binding)
        collectViewState(binding)
        collectUiEvents()
    }

    override fun onStart() {
        super.onStart()
        // Bind to PlaybackService for MediaSession and background playback
        // Using bind() instead of start() avoids Android O+ foreground service timing issues
        // Guard: only bind once per view lifecycle to prevent stacking binds on repeated start/stop
        if (!bindingRequested) {
            bindingRequested = PlaybackService.bind(requireContext(), serviceConnection)
            // Start as foreground service for background playback capability.
            // Note: The service manages its own notification visibility based on UI state.
            startPlaybackServiceForForeground()
        }

        // Mark player UI as visible - this hides the notification to prevent banner intrusion
        playbackService?.setPlayerUiVisible(true)

        // Restore user's intended play state - don't force resume if user had paused
        player?.playWhenReady = userWantsToPlay
        castContext?.sessionManager?.addSessionManagerListener(castSessionListener, com.google.android.gms.cast.framework.CastSession::class.java)
    }

    override fun onStop() {
        // Mark player UI as not visible - PlaybackService uses this combined with its own
        // ProcessLifecycleOwner observer to determine notification policy:
        // - Notification hidden only when: app foreground AND player UI visible
        // - Notification shown when: app background OR player UI not visible
        playbackService?.setPlayerUiVisible(false)

        // Don't pause playback - allow background audio
        castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, com.google.android.gms.cast.framework.CastSession::class.java)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Sync fullscreen state with actual orientation
        // This handles both user-initiated rotation and programmatic rotation
        val isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val orientationChanged = isLandscape != isFullscreen
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "onConfigurationChanged: isLandscape=$isLandscape, isFullscreen=$isFullscreen, orientationChanged=$orientationChanged")

        if (orientationChanged) {
            // Update state and UI when orientation changes
            isFullscreen = isLandscape
            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "onConfigurationChanged: calling updateFullscreenUi (orientation changed)")
            updateFullscreenUi()
        } else if (isFullscreen) {
            // Even if orientation didn't change (e.g., 180° rotation while in landscape),
            // reapply fullscreen UI to ensure system bars and layout remain correct.
            // Some devices/skins may reset UI state on configuration changes.
            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "onConfigurationChanged: calling updateFullscreenUi (already fullscreen)")
            updateFullscreenUi()
        }

        // Only release orientation lock when EXITING fullscreen and we've reached portrait.
        // When ENTERING fullscreen, we keep the lock active (SENSOR_LANDSCAPE) to prevent
        // the phone from rotating back to portrait while user is watching fullscreen.
        if (weLockedOrientation && targetOrientationIsLandscape != null) {
            val reachedTarget = (targetOrientationIsLandscape == true && isLandscape) ||
                               (targetOrientationIsLandscape == false && !isLandscape)
            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "onConfigurationChanged: weLockedOrientation=true, target=${if (targetOrientationIsLandscape == true) "LANDSCAPE" else "PORTRAIT"}, reachedTarget=$reachedTarget")
            if (reachedTarget) {
                // Only schedule unlock when exiting fullscreen (target=PORTRAIT)
                // When entering fullscreen (target=LANDSCAPE), keep the lock active
                if (targetOrientationIsLandscape == false) {
                    // Cancel fallback timeout since config change fired successfully
                    // Reschedule with shorter delay to allow orientation to stabilize
                    scheduleOrientationUnlock(ORIENTATION_UNLOCK_DELAY_MS)
                } else {
                    // Entering fullscreen - reached landscape, clear flags but keep locked
                    if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "onConfigurationChanged: reached fullscreen landscape, keeping lock active")
                    weLockedOrientation = false
                    targetOrientationIsLandscape = null
                    // Note: requestedOrientation remains SENSOR_LANDSCAPE
                }
            }
        }
    }

    override fun onDestroyView() {
        // Cancel any pending orientation unlock
        cancelOrientationUnlock()

        // Reset orientation state to allow normal rotation when leaving player
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        weLockedOrientation = false
        targetOrientationIsLandscape = null

        // CRITICAL: If leaving while in fullscreen, restore system bars and bottom nav
        // This prevents the fullscreen state from "leaking" to other screens
        if (isFullscreen) {
            restoreSystemUiOnExit()
        }
        // Unconditionally restore shell root — guards against edge case where
        // fitsSystemWindows was set to false but isFullscreen hasn't been set yet
        restoreShellRootView()

        // Clean up recovery manager
        recoveryManager?.cancel()
        recoveryManager = null

        // Clean up buffer health monitor
        bufferHealthMonitor?.release()
        bufferHealthMonitor = null

        // Release MediaSession BEFORE releasing player (service doesn't own player lifecycle)
        playbackService?.releaseSession()

        // Unbind from PlaybackService - must unbind if we requested binding,
        // even if onServiceConnected hasn't fired yet (prevents ServiceConnection leak)
        if (bindingRequested) {
            requireContext().unbindService(serviceConnection)
            bindingRequested = false
        }
        playbackService = null

        // Phase 0 metrics: end session for current video
        viewModel.state.value.currentItem?.streamId?.let { streamId ->
            viewModel.metrics.onSessionEnded(streamId)
        }

        // Clean up player resources - ExoPlayer handles its own callback cleanup during release
        binding?.playerView?.player = null
        preparedStreamKey = null
        preparedStreamUrl = null
        preparedIsAdaptive = false
        preparedAdaptiveType = MediaSourceResult.AdaptiveType.NONE
        preparedQualityCapHeight = null
        factorySelectedVideoTrack = null
        adaptiveFailedForCurrentStream = null
        lastMetadataSyncedItemId = null

        // Cancel any pending artwork loading and clear callback
        metadataManager.cancelArtworkLoading()
        metadataManager.artworkCallback = null

        // Release player asynchronously to avoid ExoTimeoutException during fragment destruction.
        // The player's internal threads may be blocked (e.g., waiting for audio hardware),
        // and synchronous release would block the main thread until timeout.
        //
        // We post release() to the player's application looper (the looper it was created on).
        // This ensures thread safety while deferring execution. The key insight: onDestroyView()
        // returns immediately, allowing fragment destruction to complete; release() runs on the
        // next main loop iteration. This mitigates but doesn't fully eliminate UI jank if the
        // underlying audio hardware is truly blocked - but at least the blocking happens after
        // navigation completes rather than during it.
        //
        // If release() throws (extremely rare), it's caught and logged rather than crashing.
        player?.let { playerToRelease ->
            player = null
            releasePlayerAsync(playerToRelease, "onDestroyView")
        }

        binding = null
        super.onDestroyView()
    }

    private fun setupUpNextList(binding: FragmentPlayerBinding) {
        binding.upNextList.apply {
            // Use grid layout on tablets for better use of screen real estate
            // Phone: 1 column (list), Tablet: 2 columns
            val isTablet = requireContext().isTablet()
            layoutManager = if (isTablet) {
                GridLayoutManager(requireContext(), 2)
            } else {
                LinearLayoutManager(requireContext())
            }
            adapter = upNextAdapter
            // Only add dividers for linear layout (list mode)
            if (!isTablet) {
                addItemDecoration(DividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL))
            }
        }
    }

    private val mediaSourceFactory by lazy {
        com.albunyaan.tube.player.MultiQualityMediaSourceFactory(
            requireContext(),
            hlsPoisonRegistry,
            multiRepFactory,
            coldStartQualityChooser,
            featureFlags
        )
    }

    private fun setupPlayer(binding: FragmentPlayerBinding) {
        // Configure load control with adaptive buffering based on device memory class
        // Uses AdaptiveBufferPolicy to prevent OOM on low-end devices while maximizing
        // buffer capacity on high-memory devices for minimal rebuffering
        val loadControl = bufferPolicy.buildLoadControl()

        // Configure track selector for adaptive streaming quality constraints
        // Phase 3: Use QualityTrackSelector for CAP/LOCK mode support
        val trackSelector = QualityTrackSelector.createForDiscreteQualities(requireContext()).also {
            this.trackSelector = it
        }

        val player = ExoPlayer.Builder(requireContext())
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Enable seek back/forward optimizations
            .setSeekBackIncrementMs(10000) // 10s back
            .setSeekForwardIncrementMs(10000) // 10s forward
            .build().also { this.player = it }

        // Optimize playback parameters for smoother experience
        player.playWhenReady = false // Don't auto-play until ready

        binding.playerView.player = player
        player.addListener(viewModel.playerListener)

        // Initialize MediaSession if service is already bound (e.g., after player rebuild during recovery)
        playbackService?.initializeSession(player)

        // Initialize PlaybackRecoveryManager for freeze detection and automatic recovery
        recoveryManager = PlaybackRecoveryManager(
            scope = viewLifecycleOwner.lifecycleScope,
            callbacks = createRecoveryCallbacks()
        )

        // Clean up existing buffer health monitor before recreating
        bufferHealthMonitor?.release()
        bufferHealthMonitor = null

        // Initialize BufferHealthMonitor for proactive quality downshift on progressive streams
        bufferHealthMonitor = BufferHealthMonitor(
            scope = viewLifecycleOwner.lifecycleScope,
            callbacks = createBufferHealthCallbacks()
        )

        // Add listener to auto-hide controls when playback starts and handle errors
        playbackListener = createPlaybackListener(player)
        playbackListener?.let { player.addListener(it) }

        // Configure auto-hide controls
        binding.playerView.apply {
            setControllerAutoShow(true)
            setControllerHideOnTouch(true)
            controllerShowTimeoutMs = 5000 // 5 seconds
            setShowFastForwardButton(true)
            setShowNextButton(true)
            setShowPreviousButton(true)

            // Sync custom overlay controls with Media3 controller visibility
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Controller visibility changed: $visibility, " +
                        "playerView.resizeMode=$resizeMode, playerView.width=$width, playerView.height=$height")
                    binding.playerOverlayControls.visibility = visibility

                    // Re-apply our custom prev/next button states when controller becomes visible
                    // This prevents Media3 from overriding our button configuration
                    if (visibility == View.VISIBLE) {
                        val state = viewModel.state.value
                        updatePlaylistNavigationButtons(state.hasPrevious, state.hasNext)
                    }

                    // DEBUG: Log after layout pass
                    post {
                        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "After controller visibility change layout: " +
                            "resizeMode=$resizeMode, width=$width, height=$height")
                    }
                }
            )
        }

        // Keep overlay controls visible initially (user needs to see quality button)
        binding.playerOverlayControls.visibility = View.VISIBLE

        // Setup gesture detector for seek gestures + resize mode toggle
        // Brightness/volume gestures removed to reduce complexity
        val playerGesture = PlayerGestureDetector(
            context = requireContext(),
            player = player,
            onCenterDoubleTap = { tryToggleFullscreenResizeMode() },
            // Use actual player view width for gesture zones (correct in split-screen, multi-window)
            viewWidthProvider = { binding.playerView.width }
        )
        gestureDetector = GestureDetector(requireContext(), playerGesture)

        // Attach gestures to player view
        binding.playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // Allow ExoPlayer to handle other touches
        }
    }

    private fun createPlaybackListener(player: ExoPlayer): Player.Listener {
        return object : Player.Listener {
            private var hasAutoHidden = false
            private var retryCount = 0
            private val maxRetries = 3
            private var streamRefreshCount = 0
            private val maxStreamRefreshes = 2
            private var lastStreamIdForRetries: String? = null

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "onIsPlayingChanged: isPlaying=$isPlaying, hasAutoHidden=$hasAutoHidden")
                val currentStreamId = viewModel.state.value.currentItem?.streamId
                if (isPlaying) {
                    // Phase 1B: Fallback - set start time if not already set at prepare().
                    // Normally set at prepare() for pre-playback 403 detection; this is a safety net.
                    if (streamPlaybackStartTimeMs == 0L) {
                        streamPlaybackStartTimeMs = android.os.SystemClock.elapsedRealtime()
                    }

                    // Phase 0 metrics: mark playback started
                    currentStreamId?.let { viewModel.metrics.onPlaybackStarted(it) }

                    // Note: PlaybackService is started as foreground in onStart() to satisfy
                    // Android's 5-second rule. MediaSessionService shows notification automatically
                    // once the MediaSession has a playing player attached.

                    // Notify recovery manager that playback is healthy
                    recoveryManager?.onPlaybackStarted()

                    // Notify buffer health monitor to start monitoring (progressive streams only)
                    bufferHealthMonitor?.onPlaybackStarted(player)

                    // PR5: Reset rate limiter backoff for successful playback
                    currentStreamId?.let { streamId ->
                        viewModel.onPlaybackSuccess(streamId)
                    }

                    // Phase 4: Notify degradation manager of successful playback (if enabled)
                    if (featureFlags.isDegradationManagerEnabled) {
                        currentStreamId?.let { streamId ->
                            degradationManager.onPlaybackSuccess(streamId)
                        }
                    }

                    if (!hasAutoHidden) {
                        // Auto-hide controls after playback starts
                        hasAutoHidden = true
                        retryCount = 0 // Reset retry count on successful playback

                        // Prefetch next items in queue when current video starts playing
                        viewModel.prefetchNextItems()

                        binding?.playerView?.postDelayed({
                            val currentBinding = binding ?: return@postDelayed
                            if (player.isPlaying) {
                                val pv = currentBinding.playerView
                                if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Auto-hiding controls: BEFORE - resizeMode=${pv.resizeMode}, " +
                                    "width=${pv.width}, height=${pv.height}")
                                currentBinding.playerView.hideController()
                                // Also explicitly hide overlay controls
                                currentBinding.playerOverlayControls.visibility = View.GONE
                                pv.post {
                                    if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Auto-hiding controls: AFTER - resizeMode=${pv.resizeMode}, " +
                                        "width=${pv.width}, height=${pv.height}")
                                }
                            }
                        }, 3000) // 3 seconds delay to give user time to see controls
                    }
                } else {
                    // Phase 0 metrics: mark playback paused
                    currentStreamId?.let { viewModel.metrics.onPlaybackPaused(it) }

                    // Pause buffer health monitoring when not playing
                    bufferHealthMonitor?.onPlaybackPaused()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "onPlaybackStateChanged: state=$playbackState")

                // Phase 0 metrics: track rebuffering
                if (playbackState == Player.STATE_BUFFERING) {
                    viewModel.state.value.currentItem?.streamId?.let { streamId ->
                        viewModel.metrics.onRebufferingStarted(streamId)
                    }
                }

                // Delegate to recovery manager for freeze detection
                recoveryManager?.onPlaybackStateChanged(player, playbackState)

                // When a new video loads, reset the auto-hide flag
                if (playbackState == Player.STATE_IDLE) {
                    val currentStreamId = viewModel.state.value.currentItem?.streamId
                    if (currentStreamId != lastStreamIdForRetries) {
                        lastStreamIdForRetries = currentStreamId
                        retryCount = 0
                        streamRefreshCount = 0
                    }
                    hasAutoHidden = false
                }

                // Handle playback ended
                if (playbackState == Player.STATE_ENDED) {
                    val advanced = viewModel.markCurrentComplete()
                    if (advanced) {
                        player.playWhenReady = true
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Track user intent for lifecycle restoration
                // Only update when change is due to user interaction (not system events like audio focus)
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    userWantsToPlay = playWhenReady
                }
                // Delegate to recovery manager for stuck-in-ready detection
                recoveryManager?.onPlayWhenReadyChanged(player, playWhenReady)
            }

            override fun onRenderedFirstFrame() {
                // Phase 0 metrics: track first frame rendered (for TTFF)
                viewModel.state.value.currentItem?.streamId?.let { streamId ->
                    viewModel.metrics.onFirstFrameRendered(streamId)
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "onVideoSizeChanged: width=${videoSize.width}, height=${videoSize.height}, " +
                    "pixelWidthHeightRatio=${videoSize.pixelWidthHeightRatio}, " +
                    "playerViewResizeMode=${binding?.playerView?.resizeMode}")

                // Force resize mode to stay at FIT (or user's fullscreen preference) after video size changes
                // This prevents any internal ExoPlayer behavior from changing the aspect ratio handling
                val targetResizeMode = if (isFullscreen) fullscreenResizeMode else AspectRatioFrameLayout.RESIZE_MODE_FIT
                binding?.playerView?.let { pv ->
                    if (pv.resizeMode != targetResizeMode) {
                        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Forcing resize mode from ${pv.resizeMode} to $targetResizeMode")
                        pv.resizeMode = targetResizeMode
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val httpResponseCode = findHttpResponseCode(error.cause)
                android.util.Log.e(
                    "PlayerFragment",
                    "Player error: ${error.errorCodeName}, retryCount=$retryCount, streamRefreshCount=$streamRefreshCount, http=$httpResponseCode",
                    error
                )

                val lifecycleOwner = viewLifecycleOwnerLiveData.value
                if (lifecycleOwner == null) {
                    android.util.Log.w("PlayerFragment", "Skipping error recovery: view lifecycle not available")
                    return
                }

                // Handle different error types
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                        // Network errors - retry with backoff
                        if (retryCount < maxRetries) {
                            retryCount++
                            val delayMs = retryCount * 1500L
                            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Retrying playback in ${delayMs}ms (attempt $retryCount)")
                            // Use lifecycle-aware coroutine to prevent crashes if fragment is destroyed
                            lifecycleOwner.lifecycleScope.launch {
                                kotlinx.coroutines.delay(delayMs)
                                player.prepare()
                                player.playWhenReady = true
                            }
                        } else {
                            // If prepare retries are exhausted, try re-resolving stream URLs as a last resort.
                            if (streamRefreshCount < maxStreamRefreshes) {
                                streamRefreshCount++
                                requestStreamRefreshAndResume(
                                    "network retries exhausted (${error.errorCodeName})"
                                )
                            } else {
                                // Use safe context access to prevent crash if fragment is detached
                                context?.let { ctx ->
                                    Toast.makeText(ctx, R.string.player_stream_error, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                        // HTTP error - check for 403 specifically for telemetry and geo-restriction handling
                        handle403OrHttpError(
                            player = player,
                            error = error,
                            httpResponseCode = httpResponseCode,
                            lifecycleOwner = lifecycleOwner,
                            currentStreamRefreshCount = streamRefreshCount,
                            maxStreamRefreshes = maxStreamRefreshes,
                            onStreamRefresh = { streamRefreshCount++ }
                        )
                    }
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> {
                        // Stream URL expired/invalid - re-resolve URLs and resume.
                        if (streamRefreshCount < maxStreamRefreshes) {
                            streamRefreshCount++
                            requestStreamRefreshAndResume(
                                "source invalid/expired (${error.errorCodeName}, http=${httpResponseCode ?: "n/a"})"
                            )
                        } else {
                            // Use safe context access to prevent crash if fragment is detached
                            context?.let { ctx ->
                                Toast.makeText(ctx, R.string.player_stream_unavailable, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    // Decoder errors - 4K/VP9/AV1 may not be supported on all devices
                    // Auto step-down to lower quality that the device can decode
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> {
                        android.util.Log.w("PlayerFragment", "Decoder error: ${error.errorCodeName} - attempting quality step-down")
                        handleDecoderError(player, error)
                    }
                    else -> {
                        // Other errors - show message with safe context access
                        context?.let { ctx ->
                            Toast.makeText(ctx, getString(R.string.player_stream_error) + ": ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun collectViewState(binding: FragmentPlayerBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                binding.audioOnlyToggle.isChecked = state.audioOnly
                updatePlayerStatus(binding, state)
                val currentItem = state.currentItem

                // Update new UI elements with real metadata
                if (currentItem != null) {
                    binding.videoTitle.text = currentItem.title
                    binding.authorName.text = currentItem.channelName

                    // Format view count and time
                    val viewText = currentItem.viewCount?.let { count ->
                        when {
                            count >= 1_000_000 -> getString(R.string.views_count_millions, count / 1_000_000.0)
                            count >= 1_000 -> getString(R.string.views_count_thousands, count / 1_000.0)
                            else -> getString(R.string.views_count, count.toInt())
                        }
                    } ?: getString(R.string.player_no_views)

                    binding.videoStats.text = viewText
                    binding.videoDescription.text = currentItem.description ?: getString(R.string.player_no_description)

                    // Note: MediaSession metadata sync moved to maybePrepareStream()
                    // to ensure player has a valid media source before updating metadata.
                } else {
                    binding.videoTitle.text = getString(R.string.player_no_current_item)
                    binding.authorName.text = ""
                    binding.videoStats.text = ""
                    binding.videoDescription.text = getString(R.string.player_no_description)
                }

                // Update favorite button UI based on state
                binding.favoriteIcon.setImageResource(
                    if (state.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                )
                binding.favoriteLabel.text = getString(
                    if (state.isFavorite) R.string.player_action_favorited else R.string.player_action_favorite
                )

            binding.currentlyPlaying.text = currentItem?.let {
                getString(R.string.player_current_item, it.title)
            } ?: getString(R.string.player_no_current_item)
            binding.completeButton.isEnabled = state.streamState is StreamState.Ready
            updateDownloadControls(binding, state)
            upNextAdapter.submitList(state.upNext)
            binding.upNextList.isVisible = state.upNext.isNotEmpty()
            binding.upNextEmpty.isVisible = state.upNext.isEmpty()
                // Update ExoPlayer prev/next buttons based on playlist position
                // Always keep buttons visible but visually indicate disabled state
                updatePlaylistNavigationButtons(state.hasPrevious, state.hasNext)
            binding.excludedMessage.isVisible = state.excludedItems.isNotEmpty()
            if (state.excludedItems.isNotEmpty()) {
                binding.excludedMessage.text = getString(
                    R.string.player_excluded_message,
                    state.excludedItems.size
                    )
                }
                binding.analyticsStatus.text = renderAnalytics(state.lastAnalyticsEvent)
                maybePrepareStream(state)
                // Future: apply real track selection when backend streams available.
            }
        }
    }

    /**
     * Collect UI events from ViewModel (errors, live stream refresh, etc).
     * Handles one-shot events that shouldn't be part of persistent state.
     */
    private fun collectUiEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiEvents.collect { event ->
                when (event) {
                    is PlayerUiEvent.FavoriteToggleFailed -> {
                        Toast.makeText(
                            requireContext(),
                            getString(event.messageRes),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is PlayerUiEvent.LiveStreamRefreshReady -> {
                        handleLiveStreamRefresh(event)
                    }
                }
            }
        }
    }

    /**
     * Handle seamless live stream URL refresh without stopping playback.
     * Saves current position, swaps media source, and resumes at same position.
     */
    private fun handleLiveStreamRefresh(event: PlayerUiEvent.LiveStreamRefreshReady) {
        val currentPlayer = player ?: return
        val currentStreamState = viewModel.state.value.streamState

        // Verify we're still playing the same stream
        if (currentStreamState !is StreamState.Ready || currentStreamState.streamId != event.streamId) {
            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Live refresh: stream changed, ignoring")
            return
        }

        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Live refresh: seamlessly swapping to fresh URLs for ${event.streamId}")

        // Save current playback state
        val currentPosition = currentPlayer.currentPosition
        val wasPlaying = currentPlayer.playWhenReady
        val audioOnly = viewModel.state.value.audioOnly

        // Create new media source with fresh URLs
        val resolved = event.newSelection.resolved
        val videoId = viewModel.state.value.currentItem?.streamId
        val mediaSourceResult = try {
            mediaSourceFactory.createMediaSourceWithType(
                resolved = resolved,
                audioOnly = audioOnly,
                selectedQuality = event.newSelection.video,
                userQualityCapHeight = event.newSelection.userQualityCapHeight,
                selectionOrigin = event.newSelection.selectionOrigin,
                forceProgressive = false, // Live streams use adaptive
                videoId = videoId // Phase 1B: for HLS poison check
            )
        } catch (e: Exception) {
            android.util.Log.e("PlayerFragment", "Live refresh: failed to create media source", e)
            return
        }

        // Seamless swap: set new source, prepare, seek to position, continue playing
        try {
            currentPlayer.setMediaSource(mediaSourceResult.source)
            currentPlayer.prepare()

            // Phase 1B: Set playback start time for HLS early 403 detection
            streamPlaybackStartTimeMs = android.os.SystemClock.elapsedRealtime()

            // For live streams, don't seek to old position if it's stale
            // Live streams may have moved forward; seekTo(currentPosition) could fail
            // Let the player start at the live edge instead
            if (!resolved.isLive && currentPosition > 0) {
                currentPlayer.seekTo(currentPosition)
            }

            currentPlayer.playWhenReady = wasPlaying

            // Update prepared state
            preparedStreamUrl = mediaSourceResult.actualSourceUrl
            preparedIsAdaptive = mediaSourceResult.isAdaptive
            preparedAdaptiveType = mediaSourceResult.adaptiveType

            // Force sync MediaSession metadata after source swap (setMediaSource may reset it)
            viewModel.state.value.currentItem?.let { item ->
                syncMediaSessionMetadata(item, force = true)
            }

            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Live refresh: seamless swap completed")
        } catch (e: Exception) {
            android.util.Log.e("PlayerFragment", "Live refresh: swap failed", e)
        }
    }

    /**
     * MenuProvider implementation for options menu handling.
     * Replaces deprecated setHasOptionsMenu(true) / onCreateOptionsMenu / onOptionsItemSelected pattern.
     */
    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.player_menu, menu)
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            return when (menuItem.itemId) {
                R.id.action_captions -> {
                    showCaptionSelector()
                    true
                }
                R.id.action_enter_pip -> {
                    enterPictureInPicture()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Start PlaybackService as a foreground service to enable media notification.
     *
     * MediaSessionService requires startForegroundService() (not just bindService) to properly
     * transition to foreground mode and show notifications. This is called when playback starts.
     *
     * **Lifecycle safety:** Only starts the service if the fragment is in STARTED state or later.
     * This prevents race conditions where the fragment navigates away before the service can
     * satisfy its foreground requirement.
     */
    private fun startPlaybackServiceForForeground() {
        val context = context ?: return

        // Safety check: Only start foreground service if fragment is in proper lifecycle state
        // This prevents starting the service during rapid navigation that could race with onStop
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Skipping startForegroundService - fragment not started")
            return
        }

        try {
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Started PlaybackService for foreground notification")
        } catch (e: Exception) {
            android.util.Log.w("PlayerFragment", "Failed to start PlaybackService for foreground", e)
        }
    }

    private fun showQualitySelector() {
        val streamState = viewModel.state.value.streamState
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "showQualitySelector: streamState = $streamState")

        if (streamState !is StreamState.Ready) {
            Toast.makeText(requireContext(), R.string.player_video_not_ready, Toast.LENGTH_SHORT).show()
            return
        }

        val videoTracks = streamState.selection.resolved.videoTracks
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Available video tracks: ${videoTracks.size}")
        if (BuildConfig.DEBUG) {
            videoTracks.forEachIndexed { index, track ->
                android.util.Log.d("PlayerFragment", "Track $index: height=${track.height}, qualityLabel=${track.qualityLabel}")
            }
        }

        val allQualities = viewModel.getAvailableQualities()
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "getAvailableQualities returned: ${allQualities.size} qualities")

        if (allQualities.isEmpty()) {
            Toast.makeText(requireContext(), R.string.player_quality_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        // Show ALL available qualities, sorted from HIGHEST to LOWEST (users prefer better quality first)
        val sortedQualities = allQualities.sortedByDescending { it.track.height ?: 0 }

        // Create enhanced labels with resolution info (e.g., "1080p (1920x1080)")
        val labels = sortedQualities.map { quality ->
            val track = quality.track
            when {
                track.width != null && track.height != null -> "${quality.label} (${track.width}x${track.height})"
                else -> quality.label
            }
        }.toTypedArray()

        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Quality labels: ${labels.joinToString()}")

        val currentQuality = streamState.selection.video?.qualityLabel
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Current quality: $currentQuality")

        val currentIndex = sortedQualities.indexOfFirst { it.label == currentQuality }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.player_quality_dialog_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selectedQuality = sortedQualities[which]
                viewModel.selectQuality(selectedQuality.track)
                Toast.makeText(requireContext(), getString(R.string.player_quality_switching, selectedQuality.label), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Shows a quality picker dialog for downloading the current video.
     *
     * Displays available video qualities from the resolved streams.
     * When a quality is selected, starts the download with the chosen targetHeight.
     *
     * Uses Fragment Result API for process-death safety. The listener is registered
     * in onViewCreated() so it survives configuration changes and process death.
     */
    private fun showDownloadQualityPicker() {
        val streamState = viewModel.state.value.streamState

        if (streamState !is StreamState.Ready) {
            Toast.makeText(requireContext(), R.string.player_stream_error, Toast.LENGTH_SHORT).show()
            return
        }

        DownloadQualityDialog.newInstance(
            resolvedStreams = streamState.selection.resolved
        ).show(childFragmentManager, DownloadQualityDialog.TAG)
    }

    // Overlay controls are now always visible - removed toggle functionality
    // This provides better UX as users always have access to quality/cast/minimize buttons

    private fun showCaptionSelector() {
        val subtitles = viewModel.getAvailableSubtitles()

        // Add "Off" option at the beginning
        val options = mutableListOf(getString(R.string.player_captions_off))
        options.addAll(subtitles.map { track: SubtitleTrack ->
            if (track.isAutoGenerated) {
                getString(R.string.player_captions_auto_generated, track.languageName)
            } else {
                track.languageName
            }
        })

        val currentSubtitle = viewModel.getSelectedSubtitle()
        val currentIndex = if (currentSubtitle == null) {
            0 // "Off" is selected
        } else {
            subtitles.indexOfFirst { it.languageCode == currentSubtitle.languageCode } + 1
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.player_captions_dialog_title)
            .setSingleChoiceItems(options.toTypedArray(), currentIndex) { dialog, which ->
                if (which == 0) {
                    // "Off" selected
                    viewModel.selectSubtitle(null)
                } else {
                    // Subtitle track selected (adjust index by -1 for "Off")
                    viewModel.selectSubtitle(subtitles[which - 1])
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        binding?.audioOnlyToggle?.isEnabled = !isInPictureInPictureMode
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val player = this.player ?: return

            // Calculate aspect ratio from video
            val videoFormat = player.videoFormat
            val aspectRatio = if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
                android.util.Rational(videoFormat.width, videoFormat.height)
            } else {
                android.util.Rational(16, 9) // Default 16:9
            }

            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()

            requireActivity().enterPictureInPictureMode(params)
        }
    }

    private fun renderAnalytics(event: PlaybackAnalyticsEvent?): String {
        return when (event) {
            is PlaybackAnalyticsEvent.QueueHydrated -> getString(
                R.string.player_event_queue_hydrated,
                event.totalItems,
                event.excludedItems
            )
            is PlaybackAnalyticsEvent.PlaybackStarted -> getString(
                R.string.player_event_play_started,
                event.item.title,
                getString(event.reason.labelRes)
            )
            is PlaybackAnalyticsEvent.PlaybackCompleted -> getString(
                R.string.player_event_play_completed,
                event.item.title
            )
            is PlaybackAnalyticsEvent.AudioOnlyToggled -> if (event.enabled) {
                getString(R.string.player_event_audio_only_on)
            } else {
                getString(R.string.player_event_audio_only_off)
            }
            null -> getString(R.string.player_analytics_none)
            is PlaybackAnalyticsEvent.StreamResolved -> getString(
                R.string.player_event_stream_resolved,
                event.qualityLabel ?: getString(R.string.player_event_stream_resolved_unknown)
            )
            is PlaybackAnalyticsEvent.StreamFailed -> getString(R.string.player_event_stream_failed)
            is PlaybackAnalyticsEvent.QualityChanged -> "Quality changed to ${event.qualityLabel}"
            is PlaybackAnalyticsEvent.SubtitleChanged -> "Subtitles: ${event.languageName}"
            is PlaybackAnalyticsEvent.VideoSkipped -> "Skipped unavailable video (${event.consecutiveSkipCount}/3)"
        }
    }

    private fun updatePlayerStatus(binding: FragmentPlayerBinding, state: PlayerState) {
        when (val streamState = state.streamState) {
            StreamState.Idle -> {
                binding.playerStatus.text = getString(R.string.player_status_initializing)
                binding.playerErrorOverlay.visibility = View.GONE
                binding.playerRecoveryOverlay.visibility = View.GONE
            }
            StreamState.Loading -> {
                binding.playerStatus.text = if (state.retryCount > 0) {
                    getString(R.string.player_retrying, state.retryCount + 1, 3)
                } else {
                    getString(R.string.player_status_resolving)
                }
                binding.playerErrorOverlay.visibility = View.GONE
                binding.playerRecoveryOverlay.visibility = View.GONE
            }
            is StreamState.Error -> {
                binding.playerStatus.text = getString(streamState.messageRes)
                // Show error overlay with retry options
                binding.playerErrorOverlay.visibility = View.VISIBLE
                binding.playerErrorMessage.text = getString(streamState.messageRes)
                binding.playerRecoveryOverlay.visibility = View.GONE
            }
            is StreamState.Ready -> {
                binding.playerStatus.text = when {
                    state.audioOnly -> getString(R.string.player_status_audio_only)
                    else -> getString(R.string.player_status_video_playing)
                }
                binding.playerErrorOverlay.visibility = View.GONE
                binding.playerRecoveryOverlay.visibility = View.GONE
            }
            is StreamState.Recovering -> {
                // Show recovery overlay with progress
                binding.playerRecoveryOverlay.visibility = View.VISIBLE
                binding.playerRecoveryProgress.visibility = View.VISIBLE
                binding.playerRecoveryMessage.text = getString(
                    R.string.player_recovering_attempt,
                    streamState.attempt,
                    PlaybackRecoveryManager.MAX_RECOVERY_ATTEMPTS
                )
                binding.playerRecoveryRetryButton.visibility = View.GONE
                binding.playerErrorOverlay.visibility = View.GONE
                binding.playerStatus.text = getString(R.string.player_recovering)
            }
            is StreamState.RecoveryExhausted -> {
                // Show recovery overlay with retry button (exhausted state)
                binding.playerRecoveryOverlay.visibility = View.VISIBLE
                binding.playerRecoveryProgress.visibility = View.GONE
                binding.playerRecoveryMessage.text = getString(R.string.player_recovery_exhausted_message)
                binding.playerRecoveryRetryButton.visibility = View.VISIBLE
                binding.playerErrorOverlay.visibility = View.GONE
                binding.playerStatus.text = getString(R.string.player_recovery_exhausted)
            }
        }
    }

    private fun updateDownloadControls(binding: FragmentPlayerBinding, state: PlayerState) {
        val button = binding.downloadButton
        val currentItem = state.currentItem
        val downloadEntry = state.currentDownload

        if (currentItem == null) {
            button.isEnabled = false
            button.setOnClickListener(null)
            return
        }

        when (downloadEntry?.status) {
            DownloadStatus.COMPLETED -> {
                button.isEnabled = true
                button.setOnClickListener { openDownloadedFile(downloadEntry) }
            }
            DownloadStatus.RUNNING -> {
                button.isEnabled = false
                button.setOnClickListener(null)
            }
            DownloadStatus.QUEUED -> {
                button.isEnabled = false
                button.setOnClickListener(null)
            }
            else -> {
                // No active download - show quality picker for new downloads
                button.isEnabled = true
                button.setOnClickListener {
                    showDownloadQualityPicker()
                }
            }
        }
    }

    private fun requestStreamRefreshAndResume(reason: String) {
        requestStreamRefreshAndResumeWithReason(
            reason = reason,
            refreshReason = com.albunyaan.tube.player.PlaybackDegradationManager.RefreshReason.RECOVERY
        )
    }

    /**
     * Phase 4: Refresh stream URLs with explicit reason for budget tracking.
     *
     * @param reason Human-readable reason for logging
     * @param refreshReason Degradation manager reason for budget consumption
     */
    private fun requestStreamRefreshAndResumeWithReason(
        reason: String,
        refreshReason: com.albunyaan.tube.player.PlaybackDegradationManager.RefreshReason
    ) {
        val currentItem = viewModel.state.value.currentItem ?: return
        val currentPlayer = player ?: return

        val resumePosition = currentPlayer.currentPosition.coerceAtLeast(0L)
        val wasPlayWhenReady = currentPlayer.playWhenReady

        android.util.Log.w(
            "PlayerFragment",
            "Refreshing stream for ${currentItem.streamId} at ${resumePosition}ms: $reason"
        )

        // Phase 4: Consume refresh budget and check for degradation action (if enabled)
        if (featureFlags.isDegradationManagerEnabled) {
            val degradationAction = degradationManager.consumeRefresh(currentItem.streamId, refreshReason)
            if (degradationAction !is com.albunyaan.tube.player.PlaybackDegradationManager.DegradationAction.None) {
                android.util.Log.w("PlayerFragment", "Degradation action required: $degradationAction")
                handleDegradationAction(currentItem.streamId, degradationAction, resumePosition, wasPlayWhenReady)
                return
            }
        }

        // PR5: Check if refresh is allowed BEFORE stopping player to avoid leaving playback stuck.
        // AUTO_RECOVERY has reserved budget that won't be blocked by manual limits.
        val refreshAllowed = viewModel.forceRefreshForAutoRecovery()

        if (!refreshAllowed) {
            // Rate-limited: don't stop player, show toast and let current playback continue.
            // This prevents the player from being stuck in stopped state when refresh is blocked.
            android.util.Log.w("PlayerFragment", "Stream refresh blocked by rate limiter, continuing playback")
            context?.let { ctx ->
                Toast.makeText(ctx, R.string.player_refresh_rate_limited, Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Refresh is proceeding - now safe to stop player and clear state
        pendingResumeStreamId = currentItem.streamId
        pendingResumePositionMs = resumePosition.takeIf { it > 0L }
        pendingResumePlayWhenReady = wasPlayWhenReady

        // CRITICAL: Invalidate cached MPD before refresh to prevent reusing stale signed URLs.
        // Without this, a refresh within TTL (2 min) can hit the MPD cache and reuse the same
        // failing URLs, causing 403 loops. This ensures the next media source build regenerates
        // the MPD with fresh URLs from the new extraction.
        mpdRegistry.unregister(currentItem.streamId)
        if (BuildConfig.DEBUG) {
            android.util.Log.d("PlayerFragment", "Invalidated cached MPD for ${currentItem.streamId} before refresh")
        }

        preparedStreamKey = null
        preparedStreamUrl = null
        preparedIsAdaptive = false
        preparedAdaptiveType = MediaSourceResult.AdaptiveType.NONE
        preparedQualityCapHeight = null
        factorySelectedVideoTrack = null
        currentPlayer.stop()

        context?.let { ctx ->
            Toast.makeText(ctx, R.string.player_status_resolving, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Phase 4: Handle degradation action when refresh budget is exhausted.
     *
     * Actions are applied in priority order:
     * 1. QualityStepDown - try lower quality
     * 2. SwitchToMuxed - switch from video-only to muxed
     * 3. ForceHlsFallback - poison current stream and try HLS
     * 4. ShowError - all options exhausted
     */
    private fun handleDegradationAction(
        videoId: String,
        action: com.albunyaan.tube.player.PlaybackDegradationManager.DegradationAction,
        resumePositionMs: Long,
        wasPlayWhenReady: Boolean
    ) {
        when (action) {
            is com.albunyaan.tube.player.PlaybackDegradationManager.DegradationAction.QualityStepDown -> {
                android.util.Log.i("PlayerFragment", "Degradation: stepping down to ${action.targetHeight}p")
                val state = viewModel.state.value
                val streamState = when (val s = state.streamState) {
                    is StreamState.Ready -> s
                    is StreamState.Recovering -> StreamState.Ready(s.streamId, s.selection)
                    is StreamState.RecoveryExhausted -> StreamState.Ready(s.streamId, s.selection)
                    else -> null
                }

                if (streamState != null) {
                    val targetHeight = action.targetHeight ?: 480
                    val nextLower = streamState.selection.resolved.videoTracks
                        .filter { (it.height ?: 0) <= targetHeight }
                        .maxByOrNull { it.height ?: 0 }

                    if (nextLower != null) {
                        viewModel.applyAutoQualityStepDown(nextLower)
                        degradationManager.onDegradationApplied(videoId, action)
                        return
                    }
                }

                // Couldn't step down - escalate to next action
                android.util.Log.w("PlayerFragment", "Quality step-down failed, escalating")
                val nextAction = com.albunyaan.tube.player.PlaybackDegradationManager.DegradationAction.SwitchToMuxed
                handleDegradationAction(videoId, nextAction, resumePositionMs, wasPlayWhenReady)
            }

            is com.albunyaan.tube.player.PlaybackDegradationManager.DegradationAction.SwitchToMuxed -> {
                android.util.Log.i("PlayerFragment", "Degradation: switching to muxed stream")
                val state = viewModel.state.value
                val streamState = when (val s = state.streamState) {
                    is StreamState.Ready -> s
                    is StreamState.Recovering -> StreamState.Ready(s.streamId, s.selection)
                    is StreamState.RecoveryExhausted -> StreamState.Ready(s.streamId, s.selection)
                    else -> null
                }

                if (streamState != null && streamState.selection.video?.isVideoOnly == true) {
                    val muxedTrack = streamState.selection.resolved.videoTracks
                        .filter { !it.isVideoOnly }
                        .maxByOrNull { it.height ?: 0 }

                    if (muxedTrack != null) {
                        viewModel.applyAutoQualityStepDown(muxedTrack)
                        degradationManager.onDegradationApplied(videoId, action)
                        return
                    }
                }

                // Couldn't switch to muxed - escalate
                android.util.Log.w("PlayerFragment", "Switch to muxed failed, escalating to HLS fallback")
                val nextAction = com.albunyaan.tube.player.PlaybackDegradationManager.DegradationAction.ForceHlsFallback
                handleDegradationAction(videoId, nextAction, resumePositionMs, wasPlayWhenReady)
            }

            is com.albunyaan.tube.player.PlaybackDegradationManager.DegradationAction.ForceHlsFallback -> {
                // Switch to alternate stream type by poisoning the current type:
                // - If currently HLS → poison HLS so next resolution uses DASH/progressive
                // - If currently DASH/synthetic/progressive → clear HLS poison to allow HLS retry
                val isCurrentlyHls = preparedAdaptiveType == MediaSourceResult.AdaptiveType.HLS
                if (isCurrentlyHls) {
                    android.util.Log.i("PlayerFragment", "Degradation: HLS failing, poisoning HLS to force DASH/progressive")
                    hlsPoisonRegistry.poisonHls(videoId, reason = "Degradation: HLS refresh budget exhausted")
                } else {
                    android.util.Log.i("PlayerFragment", "Degradation: DASH/synthetic/progressive failing, clearing HLS poison to try HLS")
                    hlsPoisonRegistry.clearPoison(videoId)
                }

                // Invalidate cached MPD to force fresh extraction with potentially different URLs
                mpdRegistry.unregister(videoId)
                android.util.Log.d("PlayerFragment", "Invalidated cached MPD for $videoId during fallback")

                degradationManager.onDegradationApplied(videoId, action)

                // Store resume state and force refresh
                pendingResumeStreamId = videoId
                pendingResumePositionMs = resumePositionMs.takeIf { it > 0L }
                pendingResumePlayWhenReady = wasPlayWhenReady

                // Clear prepared state to force rebuild
                preparedStreamKey = null
                preparedStreamUrl = null
                preparedIsAdaptive = false
                preparedAdaptiveType = MediaSourceResult.AdaptiveType.NONE
                preparedQualityCapHeight = null
                factorySelectedVideoTrack = null
                adaptiveFailedForCurrentStream = null
                player?.stop() ?: android.util.Log.w("PlayerFragment", "Degradation: player is null during ForceHlsFallback")

                // Force refresh which will now use alternate stream type
                val refreshAllowed = viewModel.forceRefreshForAutoRecovery()
                if (!refreshAllowed) {
                    android.util.Log.e("PlayerFragment", "Degradation: fallback refresh blocked, escalating to ShowError")
                    handleDegradationAction(videoId, com.albunyaan.tube.player.PlaybackDegradationManager.DegradationAction.ShowError, resumePositionMs, wasPlayWhenReady)
                }
            }

            is com.albunyaan.tube.player.PlaybackDegradationManager.DegradationAction.ShowError -> {
                android.util.Log.e("PlayerFragment", "Degradation: all options exhausted, showing error")
                degradationManager.onDegradationApplied(videoId, action)
                // Transition to RecoveryExhausted state
                viewModel.setRecoveryExhaustedState()
            }

            is com.albunyaan.tube.player.PlaybackDegradationManager.DegradationAction.None -> {
                // Should not happen, but handle gracefully
            }
        }
    }

    private fun findHttpResponseCode(throwable: Throwable?): Int? {
        var current = throwable
        while (current != null) {
            val invalid = current as? HttpDataSource.InvalidResponseCodeException
            if (invalid != null) return invalid.responseCode
            current = current.cause
        }
        return null
    }

    /**
     * Extract InvalidResponseCodeException from cause chain for telemetry.
     */
    private fun findInvalidResponseCodeException(throwable: Throwable?): HttpDataSource.InvalidResponseCodeException? {
        var current = throwable
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException) return current
            current = current.cause
        }
        return null
    }

    /**
     * Handle HTTP 403 and other HTTP errors with enhanced telemetry and recovery.
     *
     * For 403 errors specifically:
     * - Records detailed telemetry (URL, headers, response)
     * - Classifies failure type (expired, geo-restricted, rate-limited)
     * - Applies targeted recovery:
     *   - URL_EXPIRED: Force refresh with exponential backoff
     *   - GEO_RESTRICTED: Show user-facing error, no recovery possible
     *   - RATE_LIMITED: Back off and retry after delay
     *   - UNKNOWN_403: Attempt forced refresh as last resort
     */
    private fun handle403OrHttpError(
        player: ExoPlayer,
        error: PlaybackException,
        httpResponseCode: Int?,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        currentStreamRefreshCount: Int,
        maxStreamRefreshes: Int,
        onStreamRefresh: () -> Unit
    ) {
        val currentItem = viewModel.state.value.currentItem
        val videoId = currentItem?.streamId

        // Extract full exception details for telemetry
        val invalidResponseException = findInvalidResponseCodeException(error.cause)

        if (httpResponseCode == 403 && invalidResponseException != null) {
            // Record telemetry for 403 errors
            val requestHeaders = mutableMapOf<String, String>()
            // Headers would be captured by InstrumentedHttpDataSource if enabled

            val responseHeaders = invalidResponseException.headerFields
            val responseBody = invalidResponseException.responseBody.decodeToString()

            val failureType = streamTelemetry.recordFailure(
                videoId = videoId,
                streamType = determineCurrentStreamType(),
                requestUrl = invalidResponseException.dataSpec.uri.toString(),
                requestHeaders = requestHeaders,
                responseCode = 403,
                responseHeaders = responseHeaders,
                responseBody = responseBody,
                playbackPositionMs = player.currentPosition
            )

            android.util.Log.w(
                "PlayerFragment",
                "HTTP 403 detected: type=$failureType, videoId=$videoId, " +
                    "streamRefreshCount=$currentStreamRefreshCount"
            )

            // Phase 0 metrics: track 403 error with classification
            videoId?.let { id ->
                val classification = when (failureType) {
                    StreamRequestTelemetry.FailureType.URL_EXPIRED -> PlaybackMetricsCollector.ERROR_403_EXPIRED
                    StreamRequestTelemetry.FailureType.GEO_RESTRICTED -> PlaybackMetricsCollector.ERROR_403_GEO
                    StreamRequestTelemetry.FailureType.RATE_LIMITED -> PlaybackMetricsCollector.ERROR_403_RATE_LIMIT
                    else -> PlaybackMetricsCollector.ERROR_403_UNKNOWN
                }
                viewModel.metrics.on403Error(id, classification)
            }

            // Phase 1B: Reactive HLS poison - if 403 happened early and we're using HLS, poison it
            if (preparedAdaptiveType == MediaSourceResult.AdaptiveType.HLS && videoId != null) {
                val isEarly = hlsPoisonRegistry.isEarly403(streamPlaybackStartTimeMs)
                if (isEarly) {
                    hlsPoisonRegistry.poisonHls(
                        videoId = videoId,
                        reason = "Early 403: $failureType"
                    )
                    android.util.Log.w("PlayerFragment", "HLS poisoned for $videoId (early 403)")
                }
            }

            // Handle based on failure classification
            when (failureType) {
                StreamRequestTelemetry.FailureType.GEO_RESTRICTED -> {
                    // Geo-restriction - no recovery possible, show error
                    android.util.Log.e("PlayerFragment", "Video geo-restricted: $videoId")
                    player.stop()
                    // Clear prepared stream state to prevent stale cache hits in maybePrepareStream
                    preparedStreamKey = null
                    preparedStreamUrl = null
                    preparedIsAdaptive = false
                    preparedAdaptiveType = MediaSourceResult.AdaptiveType.NONE
                    preparedQualityCapHeight = null
                    factorySelectedVideoTrack = null
                    context?.let { ctx ->
                        Toast.makeText(ctx, R.string.player_geo_restricted, Toast.LENGTH_LONG).show()
                    }
                    viewModel.setErrorState(R.string.player_geo_restricted)
                    return
                }

                StreamRequestTelemetry.FailureType.RATE_LIMITED -> {
                    // Rate limited - back off and retry
                    val retryAfterHeader = responseHeaders["retry-after"]?.firstOrNull()
                    val safeRefreshCount = currentStreamRefreshCount.coerceAtLeast(0)
                    val backoffMs = retryAfterHeader?.toLongOrNull()?.times(1000)
                        ?: (2000L * (1 shl safeRefreshCount.coerceAtMost(4))) // Exponential: 2s, 4s, 8s, 16s, 32s

                    android.util.Log.w("PlayerFragment", "Rate limited, backing off ${backoffMs}ms")
                    context?.let { ctx ->
                        Toast.makeText(ctx, R.string.player_rate_limited, Toast.LENGTH_SHORT).show()
                    }

                    lifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(backoffMs)
                        if (currentStreamRefreshCount < maxStreamRefreshes) {
                            onStreamRefresh()
                            requestStreamRefreshAndResume("rate limited, retrying after ${backoffMs}ms")
                        } else {
                            context?.let { ctx ->
                                Toast.makeText(ctx, R.string.player_stream_unavailable, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    return
                }

                StreamRequestTelemetry.FailureType.URL_EXPIRED -> {
                    // URL expired - force refresh immediately
                    android.util.Log.w("PlayerFragment", "Stream URL expired, forcing refresh")
                    context?.let { ctx ->
                        Toast.makeText(ctx, R.string.player_stream_expired, Toast.LENGTH_SHORT).show()
                    }

                    if (currentStreamRefreshCount < maxStreamRefreshes) {
                        onStreamRefresh()
                        // Phase 4: Use TTL_EXPIRED reason for budget tracking
                        requestStreamRefreshAndResumeWithReason(
                            reason = "URL expired (403)",
                            refreshReason = com.albunyaan.tube.player.PlaybackDegradationManager.RefreshReason.TTL_EXPIRED
                        )
                    } else {
                        context?.let { ctx ->
                            Toast.makeText(ctx, R.string.player_stream_unavailable, Toast.LENGTH_SHORT).show()
                        }
                    }
                    return
                }

                StreamRequestTelemetry.FailureType.UNKNOWN_403,
                StreamRequestTelemetry.FailureType.HTTP_ERROR,
                StreamRequestTelemetry.FailureType.NETWORK_ERROR -> {
                    // Unknown 403 or other HTTP error - attempt refresh with linear backoff
                    val safeCount = currentStreamRefreshCount.coerceAtLeast(0)
                    val backoffMs = 1500L * (safeCount + 1)

                    android.util.Log.w("PlayerFragment", "Unknown 403/HTTP error, attempting refresh after ${backoffMs}ms")

                    lifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(backoffMs)
                        if (currentStreamRefreshCount < maxStreamRefreshes) {
                            onStreamRefresh()
                            // Phase 4: Use HTTP_403 reason for budget tracking
                            requestStreamRefreshAndResumeWithReason(
                                reason = "HTTP $httpResponseCode (${failureType.name})",
                                refreshReason = com.albunyaan.tube.player.PlaybackDegradationManager.RefreshReason.HTTP_403
                            )
                        } else {
                            context?.let { ctx ->
                                Toast.makeText(ctx, R.string.player_stream_unavailable, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    return
                }
            }
        }

        // Non-403 HTTP error or missing exception details - use standard handling
        if (currentStreamRefreshCount < maxStreamRefreshes) {
            onStreamRefresh()
            requestStreamRefreshAndResume(
                "HTTP error (${error.errorCodeName}, http=${httpResponseCode ?: "n/a"})"
            )
        } else {
            context?.let { ctx ->
                Toast.makeText(ctx, R.string.player_stream_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Determine the current stream type for telemetry.
     */
    private fun determineCurrentStreamType(): String {
        return when {
            preparedAdaptiveType == MediaSourceResult.AdaptiveType.HLS -> "HLS"
            preparedAdaptiveType == MediaSourceResult.AdaptiveType.DASH -> "DASH"
            preparedAdaptiveType == MediaSourceResult.AdaptiveType.SYNTHETIC_DASH -> "SYNTHETIC_DASH"
            preparedIsAdaptive -> "ADAPTIVE"
            viewModel.state.value.audioOnly -> "AUDIO_ONLY"
            else -> "PROGRESSIVE"
        }
    }

    /**
     * Extract the Format from a PlaybackException's cause chain.
     * This is more authoritative than currentTracks during decoder-init failures
     * when tracks may not yet be populated.
     *
     * Searches for ExoPlaybackException which contains the rendererFormat that failed to decode.
     */
    private fun extractFormatFromException(error: PlaybackException): androidx.media3.common.Format? {
        var current: Throwable? = error
        while (current != null) {
            // ExoPlaybackException contains rendererFormat for renderer (decoder) errors
            val exoException = current as? androidx.media3.exoplayer.ExoPlaybackException
            if (exoException != null && exoException.rendererFormat != null) {
                return exoException.rendererFormat
            }
            current = current.cause
        }
        return null
    }

    /**
     * Handle unrecoverable decoder error by stopping playback and transitioning to error state.
     * Used when no compatible quality is available for the device to decode.
     *
     * @param player The ExoPlayer instance to stop
     * @param logMessage Message to log for debugging
     */
    private fun handleUnrecoverableDecoderError(player: ExoPlayer, logMessage: String) {
        android.util.Log.w("PlayerFragment", logMessage)
        player.stop()
        context?.let { ctx ->
            Toast.makeText(ctx, R.string.player_decoder_no_compatible_quality, Toast.LENGTH_LONG).show()
        }
        viewModel.setErrorState(R.string.player_decoder_no_compatible_quality)
    }

    /**
     * Handle decoder errors (4K/VP9/AV1 not supported) by stepping down to a lower quality.
     *
     * When a video fails to decode (e.g., device doesn't support VP9 4K or AV1),
     * automatically tries the next lower quality that's more likely to be decodable
     * (e.g., 1080p H.264 instead of 4K VP9).
     *
     * @param player The ExoPlayer instance
     * @param error The PlaybackException that triggered this handler - used to extract
     *              the failing Format when currentTracks is empty/lagging
     */
    private fun handleDecoderError(player: ExoPlayer, error: PlaybackException) {
        val streamState = viewModel.state.value.streamState
        if (streamState !is StreamState.Ready) {
            android.util.Log.w("PlayerFragment", "handleDecoderError: no stream ready")
            context?.let { ctx ->
                Toast.makeText(ctx, R.string.player_stream_error, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val currentSelection = streamState.selection
        val currentVideo = factorySelectedVideoTrack ?: currentSelection.video
        val availableTracks = currentSelection.resolved.videoTracks

        if (currentVideo == null || availableTracks.isEmpty()) {
            handleUnrecoverableDecoderError(player, "handleDecoderError: no video track to step down from - stopping playback")
            return
        }

        // Find an alternative track to try, preferring H.264/AVC (most compatible codec)
        // Strategy: First try same-resolution with more compatible codec, then fall back to lower resolution
        //
        // NOTE: For adaptive HLS/DASH playback, codec swaps may be limited since
        // MultiQualityMediaSourceFactory applies constraints via DefaultTrackSelector height cap
        // rather than direct track selection. A same-height "swap" may be a no-op if the manifest
        // doesn't offer the desired codec at that resolution. This recovery is most effective for
        // progressive (URL-selected) playback.
        //
        // Height resolution priority (updated for adaptive correctness):
        // 1. Format from PlaybackException.cause - actual failing format (most authoritative)
        // 2. player.currentTracks selected video Format - current playback format
        // 3. selection.video.height - our track metadata (may be default selection, not ABR variant)
        // 4. player.videoSize.height - last resort (can be stale from previous item)
        //
        // IMPORTANT: For adaptive HLS/DASH, currentVideo.height may be the default selection (e.g., 720p)
        // while ABR is actually playing a higher variant (e.g., 2160p). The errorFormat from the exception
        // is the most authoritative source for what actually failed to decode, so prefer it first.

        // Extract Format from PlaybackException.cause chain - most authoritative for decoder errors
        // This is the actual Format that failed to decode, not our default selection
        val errorFormat = extractFormatFromException(error)

        // Verify errorFormat is a video format before doing video-quality step-down
        // Decoder errors can also be audio-renderer errors; don't "fix" audio errors with video step-down
        if (errorFormat != null) {
            val sampleMime = errorFormat.sampleMimeType?.lowercase(java.util.Locale.ROOT) ?: ""
            if (!sampleMime.startsWith("video/")) {
                android.util.Log.w("PlayerFragment", "handleDecoderError: errorFormat is not video (mime=$sampleMime), skipping video step-down")
                context?.let { ctx ->
                    // Use dedicated audio error message if it's an audio format, otherwise generic error
                    val messageRes = if (sampleMime.startsWith("audio/")) {
                        R.string.player_decoder_audio_error
                    } else {
                        R.string.player_stream_error
                    }
                    Toast.makeText(ctx, messageRes, Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

        // Get selected format from currentTracks (optimized: early-exit loop, no intermediate lists)
        val selectedVideoFormat: androidx.media3.common.Format? = run {
            for (group in player.currentTracks.groups) {
                if (group.type != androidx.media3.common.C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        return@run group.getTrackFormat(i)
                    }
                }
            }
            null
        }

        // Priority: errorFormat (actual failing format) > selectedVideoFormat (current playback) >
        // currentVideo (our selection metadata) > videoSize (fallback, may be stale)
        val currentHeight = errorFormat?.height?.takeIf { it > 0 }
            ?: selectedVideoFormat?.height?.takeIf { it > 0 }
            ?: currentVideo.height?.takeIf { it > 0 }
            ?: player.videoSize.height.takeIf { it > 0 }
        if (currentHeight == null || currentHeight <= 0) {
            handleUnrecoverableDecoderError(player, "handleDecoderError: no valid height from exception, currentTracks, selection, or videoSize - stopping playback")
            return
        }

        // Extract failing codec from errorFormat for adaptive correctness
        // For adaptive HLS/DASH, currentVideo.codec may be from the default selection, not the actual failing variant
        // Prefer codecs, but fall back to sampleMimeType which can be informative (e.g., video/avc, x-vnd.on2.vp9)
        val failingCodec: String? = errorFormat?.codecs?.takeIf { it.isNotBlank() }
            ?: errorFormat?.sampleMimeType?.takeIf { it.isNotBlank() }
            ?: selectedVideoFormat?.codecs?.takeIf { it.isNotBlank() }
            ?: selectedVideoFormat?.sampleMimeType?.takeIf { it.isNotBlank() }
            ?: currentVideo.codec

        // Helper to check if string contains actual codec hints (not just container type)
        // Accepts already-lowercased input for internal use to avoid double normalization
        fun hasCodecHintsLower(lower: String): Boolean {
            return lower.contains("avc") || lower.contains("h264") ||
                lower.contains("hvc") || lower.contains("hev") || lower.contains("hevc") || lower.contains("h265") ||
                lower.contains("vp9") || lower.contains("vp09") ||
                lower.contains("av1") || lower.contains("av01")
        }

        // Public version - case-insensitive to avoid footgun if caller forgets to lowercase
        fun hasCodecHints(str: String): Boolean = hasCodecHintsLower(str.lowercase(java.util.Locale.ROOT))

        // Helper to get codec compatibility score from raw codec string (lower = more compatible)
        // Common codec identifiers:
        // - H.264/AVC: "avc1", "avc", "h264"
        // - HEVC/H.265: "hvc1", "hev1", "hevc", "h265"
        // - VP9: "vp9", "vp09"
        // - AV1: "av1", "av01"
        fun getCodecScoreFromString(codecStr: String?): Int {
            val codec = codecStr?.lowercase(java.util.Locale.ROOT) ?: ""
            return when {
                // Check audio/* first - audio mime types should never reach here, but be safe
                // (prevents hypothetical "audio/vp9" from being mis-scored as VP9 video)
                codec.startsWith("audio/") -> 2
                codec.contains("avc") || codec.contains("h264") -> 0  // Most compatible
                codec.contains("hvc") || codec.contains("hev") || codec.contains("hevc") || codec.contains("h265") -> 1
                codec.contains("vp9") || codec.contains("vp09") -> 2
                codec.contains("av1") || codec.contains("av01") -> 3  // Least compatible (newest)
                codec.isBlank() -> 2 // Unknown codec - treat conservatively (VP9-level, try to swap away)
                // Container-only mime types (video/* without codec hints) lack codec info - treat as unknown
                // Use hasCodecHintsLower since codec is already lowercased above
                codec.startsWith("video/") && !hasCodecHintsLower(codec) -> 2
                else -> 1 // Non-blank codec string with actual codec info, treat as mid-level compatibility
            }
        }

        // Helper to extract codec string from VideoTrack with mimeType fallback
        fun extractCodecString(track: VideoTrack): String {
            // Prefer track.codec (always populated) over syntheticDashMetadata.codec (only for video-only progressive)
            val codecStr = track.codec ?: track.syntheticDashMetadata?.codec ?: ""
            if (codecStr.isNotBlank()) return codecStr

            // Only fall back to mimeType if it contains actual codec hints (not just container type)
            // hasCodecHints() lowercases internally, so we pass the original mimeType
            val mime = track.mimeType ?: ""
            return if (hasCodecHints(mime)) mime else ""
        }

        fun getCodecScore(track: VideoTrack): Int = getCodecScoreFromString(extractCodecString(track))

        // Log unknown codecs once before sorting to avoid spam during sorting loops
        // Only log in debug builds for development monitoring
        if (BuildConfig.DEBUG) {
            availableTracks.filter { it.codec.isNullOrBlank() && extractCodecString(it).isBlank() }
                .distinctBy { it.url } // Deduplicate by URL to log each track once
                .forEach { track ->
                    android.util.Log.d("PlayerFragment", "Unknown codec for track: height=${track.height}, mime=${track.mimeType}, label=${track.qualityLabel}")
                }
        }

        // Compute currentCodecScore from the actual failing codec (errorFormat/selectedVideoFormat)
        // not currentVideo which may be the default selection for adaptive streams
        val currentCodecScore = getCodecScoreFromString(failingCodec)

        // First: Try same resolution with a MORE COMPATIBLE codec (lower score = more compatible)
        // Prefer muxed streams and higher bitrate for quality
        val sameResolutionAlternative = availableTracks
            .filter { track ->
                val trackHeight = track.height ?: 0
                val trackCodecScore = getCodecScore(track)
                // Same resolution, but different and more compatible codec
                trackHeight == currentHeight &&
                    trackHeight > 0 &&
                    trackCodecScore < currentCodecScore
            }
            .sortedWith(
                compareBy<VideoTrack> { getCodecScore(it) }  // Most compatible codec first
                    .thenBy { it.isVideoOnly }  // Prefer muxed (false < true)
                    .thenByDescending { it.bitrate ?: 0 }  // Higher bitrate preferred
            )
            .firstOrNull()

        // Second: Fall back to lower resolution if no same-resolution alternative
        val lowerResolutionTrack = availableTracks
            .filter { track ->
                val trackHeight = track.height ?: 0
                // Must be lower resolution than current
                trackHeight < currentHeight && trackHeight > 0
            }
            .sortedWith(
                compareByDescending<VideoTrack> { it.height ?: 0 } // Prefer highest resolution under current
                    .thenBy { getCodecScore(it) } // Prefer more compatible codec
                    .thenBy { it.isVideoOnly } // Prefer muxed streams
                    .thenByDescending { it.bitrate ?: 0 } // Higher bitrate preferred
            )
            .firstOrNull()

        // Prefer same-resolution codec swap, fall back to lower resolution
        val lowerQualityTrack = sameResolutionAlternative ?: lowerResolutionTrack

        if (lowerQualityTrack == null) {
            handleUnrecoverableDecoderError(player, "handleDecoderError: no lower quality available - stopping playback")
            return
        }

        val isCodecSwap = sameResolutionAlternative != null
        val actionDesc = if (isCodecSwap) "codec swap" else "resolution step-down"
        // Log the actual failing format details for adaptive streams, not just currentVideo.qualityLabel
        // which may be the default selection rather than the failing variant
        val fromDesc = if (errorFormat != null) {
            val heightStr = errorFormat.height.takeIf { it > 0 }?.let { "${it}p" } ?: "?p"
            val codecStr = errorFormat.codecs?.takeIf { it.isNotBlank() }
                ?: errorFormat.sampleMimeType?.takeIf { it.isNotBlank() }
                ?: "unknown"
            "$heightStr/$codecStr"
        } else {
            currentVideo.qualityLabel ?: "${currentHeight}p"
        }
        android.util.Log.i(
            "PlayerFragment",
            "handleDecoderError: $actionDesc from $fromDesc to ${lowerQualityTrack.qualityLabel}"
        )

        // Show toast to inform user
        context?.let { ctx ->
            Toast.makeText(
                ctx,
                getString(R.string.player_decoder_stepping_down, lowerQualityTrack.qualityLabel ?: "lower quality"),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Apply the decoder error step-down via ViewModel
        // This clamps the user's quality cap to prevent track selector from re-selecting
        // the undecodable quality (unlike network step-down which preserves the cap)
        viewModel.applyDecoderErrorStepDown(lowerQualityTrack)
    }

    /**
     * Find the next lower quality track to step down to.
     * Delegates to [com.albunyaan.tube.player.QualityStepDownHelper] for testability.
     */
    private fun findNextLowerQualityTrack(
        current: com.albunyaan.tube.data.extractor.VideoTrack?,
        available: List<com.albunyaan.tube.data.extractor.VideoTrack>
    ): com.albunyaan.tube.data.extractor.VideoTrack? {
        val result = com.albunyaan.tube.player.QualityStepDownHelper.findNextLowerQualityTrack(current, available)
        if (result != null && current != null) {
            val currentHeight = current.height ?: 0
            val resultHeight = result.height ?: 0
            when {
                current.isVideoOnly && !result.isVideoOnly && resultHeight == currentHeight ->
                    if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Step-down: video-only -> muxed at ${currentHeight}p")
                resultHeight == currentHeight ->
                    if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Step-down: lower bitrate at ${currentHeight}p (${current.bitrate} -> ${result.bitrate})")
                else ->
                    if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Step-down: ${currentHeight}p -> ${resultHeight}p")
            }
        }
        return result
    }

    private fun maybePrepareStream(state: PlayerState) {
        val streamState = state.streamState
        if (streamState !is StreamState.Ready) {
            if (streamState is StreamState.Error) {
                player?.stop()
                preparedStreamKey = null
                preparedStreamUrl = null
                preparedIsAdaptive = false
                preparedAdaptiveType = MediaSourceResult.AdaptiveType.NONE
                preparedQualityCapHeight = null
                factorySelectedVideoTrack = null
            }
            return
        }

        // Ensure player is initialized - if not, set it up first
        // Note: setupPlayer creates a new player each time, so no duplicate listener issue.
        // This is a defensive fallback - normal flow sets up player in onViewCreated.
        val currentPlayer = player ?: run {
            android.util.Log.w("PlayerFragment", "Player is null during stream preparation - initializing")
            val b = binding ?: return
            setupPlayer(b)
            player ?: run {
                android.util.Log.e("PlayerFragment", "Failed to initialize player")
                return
            }
        }

        val key = streamState.streamId to state.audioOnly
        val selection = streamState.selection
        val resolved = selection.resolved

        // Compute expected source identity based on what we EXPECT to be prepared:
        // - audio-only: audio URL (always progressive)
        // - adaptive (if available): manifest URL
        // - progressive: selected video URL
        // Note: This is used for cache-hit detection. The actual prepared URL is set AFTER
        // factory.createMediaSourceWithType() returns, based on whether adaptive succeeded.
        val expectedSourceUrl = when {
            state.audioOnly -> selection.audio.url
            // If we're already prepared as adaptive, use manifest URL for comparison
            // If not yet prepared, assume adaptive will be used if manifests exist
            preparedIsAdaptive || (resolved.hlsUrl != null || resolved.dashUrl != null) ->
                resolved.hlsUrl ?: resolved.dashUrl
            else -> selection.video?.url
        }

        // Check if we need to rebuild: same stream but different quality or audio mode
        val isSameStream = preparedStreamKey?.first == key.first
        val isQualityChange = isSameStream && preparedStreamUrl != expectedSourceUrl
        val isAudioModeChange = isSameStream && preparedStreamKey?.second != key.second
        val isQualitySwitch = isQualityChange || isAudioModeChange

        // Check if adaptive failed for this stream previously (sticky fallback)
        val forceProgressive = adaptiveFailedForCurrentStream == streamState.streamId

        // Check if the currently prepared source matches what we would create
        when (val cacheHit = checkCacheHit(key, state, selection, resolved, forceProgressive)) {
            is CacheHitResult.Hit -> {
                // Apply quality constraint if needed (for adaptive streams).
                // Use LOCK for MANUAL selection, CAP for AUTO/AUTO_RECOVERY.
                cacheHit.qualityCapToApply?.let { cap ->
                    applyQualityConstraintByOrigin(cap, selection.selectionOrigin)
                    preparedQualityCapHeight = cap
                } ?: run {
                    // Check if we need to clear constraints (cap removed)
                    if (preparedIsAdaptive && selection.userQualityCapHeight == null && preparedQualityCapHeight != null) {
                        clearTrackSelectorConstraints()
                        preparedQualityCapHeight = null
                    }
                }
                if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Stream already prepared with same source, skipping")
                return
            }
            is CacheHitResult.Miss -> {
                // Continue to prepare new MediaSource
            }
        }

        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Preparing stream: id=${key.first}, audioOnly=${key.second}, expectedUrl=${sourceIdentityForLog(expectedSourceUrl)}, isQualitySwitch=$isQualitySwitch")

        // Reset state when switching to a different stream (not quality switch)
        if (!isSameStream) {
            // Phase 1B: Reset playback start time for HLS early 403 detection
            streamPlaybackStartTimeMs = 0L

            // Reset sticky fallback flag for adaptive streams
            if (adaptiveFailedForCurrentStream != null) {
                if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Clearing adaptive fallback flag (new stream)")
                adaptiveFailedForCurrentStream = null
            }
            // Reset fullscreen resize mode to ZOOM (default) - each video starts fresh
            if (fullscreenResizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                fullscreenResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                // Apply immediately if currently in fullscreen
                if (isFullscreen) {
                    binding?.playerView?.resizeMode = fullscreenResizeMode
                }
            }
        }

        val hasPendingResume = pendingResumeStreamId == streamState.streamId && pendingResumePositionMs != null
        if (pendingResumeStreamId != null && pendingResumeStreamId != streamState.streamId) {
            pendingResumeStreamId = null
            pendingResumePositionMs = null
            pendingResumePlayWhenReady = null
        }

        // Save position for seamless quality switching and stream refresh recovery.
        val savedPosition = when {
            hasPendingResume -> pendingResumePositionMs ?: 0L
            isQualitySwitch -> currentPlayer.currentPosition
            else -> 0L
        }

        val wasPlaying = currentPlayer.playWhenReady
        // For quality switches / refresh recovery: preserve user's play/pause state.
        // For new videos: auto-play (standard video player UX - user selected a video to watch).
        val shouldPlay = when {
            hasPendingResume -> pendingResumePlayWhenReady ?: wasPlaying
            isQualitySwitch -> wasPlaying
            else -> true
        }

        // Create multi-quality MediaSource from resolved streams
        // Apply user quality cap via track selector for adaptive streams, or select specific track for progressive
        val mediaSourceResult = try {
            val result = mediaSourceFactory.createMediaSourceWithType(
                resolved = selection.resolved,
                audioOnly = state.audioOnly,
                selectedQuality = selection.video,
                userQualityCapHeight = selection.userQualityCapHeight,
                selectionOrigin = selection.selectionOrigin,
                forceProgressive = forceProgressive,
                videoId = streamState.streamId // Phase 1B: for HLS poison check
            )
            preparedIsAdaptive = result.isAdaptive
            preparedAdaptiveType = result.adaptiveType

            // Phase 0 metrics: track media source creation and rebuilds
            viewModel.metrics.onMediaSourceCreated(streamState.streamId, result.adaptiveType, state.audioOnly)
            if (isQualitySwitch) {
                viewModel.metrics.onMediaSourceRebuilt(streamState.streamId, "quality_switch")
            }

            if (BuildConfig.DEBUG) android.util.Log.d(
                "PlayerFragment",
                "Created media source: isAdaptive=$preparedIsAdaptive, type=${result.adaptiveType}, qualityCap=${selection.userQualityCapHeight}p, actualSource=${sourceIdentityForLog(result.actualSourceUrl)}"
            )

            // Apply quality constraint to track selector for adaptive streams.
            // Use LOCK mode for MANUAL selection (user wants exact quality),
            // CAP mode for AUTO/AUTO_RECOVERY (ABR can go lower if needed).
            if (preparedIsAdaptive && selection.hasUserQualityCap) {
                applyQualityConstraintByOrigin(selection.userQualityCapHeight!!, selection.selectionOrigin)
                preparedQualityCapHeight = selection.userQualityCapHeight
                factorySelectedVideoTrack = null // ABR handles quality selection
            } else if (result.adaptiveType == MediaSourceResult.AdaptiveType.SYNTHETIC_DASH) {
                // SYNTHETIC_DASH: Track the quality cap and selected track.
                // checkCacheHit() uses height+bitrate comparison via factorySelectedVideoTrack.
                // No track selector constraints needed (single-track synthetic manifest).
                clearTrackSelectorConstraints()
                preparedQualityCapHeight = selection.userQualityCapHeight
                factorySelectedVideoTrack = result.selectedVideoTrack // Factory's actual choice
            } else {
                // Progressive or no cap - clear any lingering constraints.
                // For progressive, constraints don't affect playback (direct URL), but clearing
                // ensures clean state for future adaptive videos.
                clearTrackSelectorConstraints()
                preparedQualityCapHeight = null
                factorySelectedVideoTrack = result.selectedVideoTrack // Factory's actual choice
            }

            result
        } catch (e: Exception) {
            android.util.Log.w("PlayerFragment", "MediaSource creation failed, using fallback: ${e.message}")
            // Mark adaptive as failed for this stream to prevent rebuild loops
            if (!forceProgressive && (resolved.hlsUrl != null || resolved.dashUrl != null)) {
                adaptiveFailedForCurrentStream = streamState.streamId
                android.util.Log.w("PlayerFragment", "Adaptive failed for ${streamState.streamId} - will use progressive for this stream")
            }
            // Fallback to single quality if multi-quality fails
            // Progressive fallback - ensure preparedIsAdaptive is correctly set
            preparedIsAdaptive = false
            preparedAdaptiveType = MediaSourceResult.AdaptiveType.NONE
            preparedQualityCapHeight = null
            factorySelectedVideoTrack = null
            clearTrackSelectorConstraints() // Clear any lingering constraints from previous adaptive video
            val trackPair = selectTrack(streamState.selection, state.audioOnly)
            if (trackPair == null) {
                android.util.Log.e("PlayerFragment", "No video track available and audio-only not requested")
                context?.let { ctx ->
                    Toast.makeText(ctx, R.string.player_stream_error, Toast.LENGTH_SHORT).show()
                }
                preparedStreamKey = null
                preparedStreamUrl = null
                preparedIsAdaptive = false
                preparedAdaptiveType = MediaSourceResult.AdaptiveType.NONE
                preparedQualityCapHeight = null
                factorySelectedVideoTrack = null
                return
            }
            val (url, mimeType) = trackPair
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMimeType(mimeType)
                .build()
            val source = ProgressiveMediaSource.Factory(
                DefaultDataSource.Factory(requireContext())
            ).createMediaSource(mediaItem)
            // Create a fallback MediaSourceResult for the progressive source
            MediaSourceResult(
                source = source,
                isAdaptive = false,
                actualSourceUrl = url,
                adaptiveType = MediaSourceResult.AdaptiveType.NONE
            )
        }

        try {
            currentPlayer.setMediaSource(mediaSourceResult.source)
            currentPlayer.prepare()

            // Phase 1B: Set playback start time NOW (at prepare), not when onIsPlayingChanged fires.
            // This ensures early 403 detection works for errors during initial buffering/fetch,
            // before the player reports isPlaying=true.
            streamPlaybackStartTimeMs = android.os.SystemClock.elapsedRealtime()

            // Restore position for quality switching / stream refresh recovery.
            if ((isQualitySwitch || hasPendingResume) && savedPosition > 0) {
                currentPlayer.seekTo(savedPosition)
            }

            currentPlayer.playWhenReady = shouldPlay
            preparedStreamKey = key
            // Use the actual URL from the MediaSourceResult - this is the true source identity
            preparedStreamUrl = mediaSourceResult.actualSourceUrl
            if (BuildConfig.DEBUG) android.util.Log.d(
                "PlayerFragment",
                "Stream prepared successfully: ${streamState.streamId}, isAdaptive=$preparedIsAdaptive, type=${mediaSourceResult.adaptiveType}, actualSource=${sourceIdentityForLog(preparedStreamUrl)}"
            )

            // Notify recovery manager of new stream - pass streamId, adaptive flag, and live flag
            // Live streams use longer stall thresholds since they buffer more due to real-time data
            recoveryManager?.onNewStream(streamState.streamId, preparedIsAdaptive, resolved.isLive)

            // Notify buffer health monitor of new stream - only monitors progressive streams
            bufferHealthMonitor?.onNewStream(streamState.streamId, preparedIsAdaptive)

            // Phase 4: Initialize degradation manager for new stream (if enabled)
            if (featureFlags.isDegradationManagerEnabled) {
                val initialQualityHeight = factorySelectedVideoTrack?.height
                    ?: streamState.selection.video?.height ?: 0
                degradationManager.initVideo(streamState.streamId, initialQualityHeight)
            }

            // Sync MediaSession metadata now that player has a valid media source.
            // This must happen after setMediaSource/prepare to ensure currentMediaItem exists.
            // Force sync because setMediaSource may have reset MediaSession metadata.
            state.currentItem?.let { item ->
                syncMediaSessionMetadata(item, force = true)
            }

            if (hasPendingResume) {
                pendingResumeStreamId = null
                pendingResumePositionMs = null
                pendingResumePlayWhenReady = null
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerFragment", "Failed to prepare stream: ${e.message}", e)
            preparedStreamKey = null
            preparedStreamUrl = null
            preparedIsAdaptive = false
            preparedAdaptiveType = MediaSourceResult.AdaptiveType.NONE
            preparedQualityCapHeight = null
            factorySelectedVideoTrack = null
        }
    }

    private fun selectTrack(selection: PlaybackSelection, audioOnly: Boolean): Pair<String, String>? {
        return if (audioOnly) {
            val audio = selection.audio
            audio.url to (audio.mimeType ?: DEFAULT_AUDIO_MIME)
        } else {
            val video = selection.video
            video?.url?.let { url -> url to (video.mimeType ?: DEFAULT_VIDEO_MIME) }
        }
    }

    /**
     * Creates callbacks for PlaybackRecoveryManager to handle recovery actions.
     */
    private fun createRecoveryCallbacks(): PlaybackRecoveryManager.RecoveryCallbacks {
        return object : PlaybackRecoveryManager.RecoveryCallbacks {
            override fun onRecoveryStarted(step: PlaybackRecoveryManager.RecoveryStep, attempt: Int) {
                android.util.Log.i("PlayerFragment", "Recovery started: step=$step, attempt=$attempt")
                val state = viewModel.state.value
                val streamState = state.streamState

                // Handle Ready, Recovering, and RecoveryExhausted states to support multi-attempt updates and manual retry
                val (streamId, selection) = when (streamState) {
                    is StreamState.Ready -> streamState.streamId to streamState.selection
                    is StreamState.Recovering -> streamState.streamId to streamState.selection
                    is StreamState.RecoveryExhausted -> streamState.streamId to streamState.selection
                    else -> return // Can't recover from Idle/Loading/Error states
                }

                // Transition to (or update) Recovering state for UI
                viewModel.setRecoveringState(
                    streamId,
                    selection,
                    step.toViewModelStep(),
                    attempt
                )
            }

            override fun onRecoverySucceeded() {
                android.util.Log.i("PlayerFragment", "Recovery succeeded")
                // Transition back to Ready state
                viewModel.clearRecoveringState()
            }

            override fun onRecoveryExhausted() {
                android.util.Log.e("PlayerFragment", "Recovery exhausted - transitioning to exhausted state")
                // Transition to RecoveryExhausted state - UI will be updated via state observation
                viewModel.setRecoveryExhaustedState()
            }

            override fun onRequestQualityDownshift(): Boolean {
                val state = viewModel.state.value
                val streamState = when (val s = state.streamState) {
                    is StreamState.Ready -> s
                    is StreamState.Recovering -> StreamState.Ready(s.streamId, s.selection)
                    is StreamState.RecoveryExhausted -> StreamState.Ready(s.streamId, s.selection)
                    else -> return false
                }
                // Use factory-selected track if available (may differ from selection.video in AUTO mode).
                // This ensures downshift decisions are based on actual playing quality, not ViewModel's choice.
                val currentTrack = factorySelectedVideoTrack ?: streamState.selection.video
                val nextLower = findNextLowerQualityTrack(
                    current = currentTrack,
                    available = streamState.selection.resolved.videoTracks
                )
                return if (nextLower != null) {
                    android.util.Log.i("PlayerFragment", "Recovery: stepping down to ${nextLower.qualityLabel}")
                    // Returns false if URLs expired and refresh triggered instead
                    viewModel.applyAutoQualityStepDown(nextLower)
                } else {
                    false
                }
            }

            override fun onRequestStreamRefresh(resumePositionMs: Long) {
                android.util.Log.i("PlayerFragment", "Recovery: refreshing stream URLs, resume at ${resumePositionMs}ms")
                requestStreamRefreshAndResume("recovery ladder step: refresh URLs")
            }

            override fun onRequestPlayerRebuild(resumePositionMs: Long) {
                android.util.Log.i("PlayerFragment", "Recovery: rebuilding player at ${resumePositionMs}ms")
                // Save state, release player, recreate
                val currentItem = viewModel.state.value.currentItem ?: return
                pendingResumeStreamId = currentItem.streamId
                pendingResumePositionMs = resumePositionMs.takeIf { it > 0 }
                pendingResumePlayWhenReady = true

                // Cancel existing recovery manager before rebuild to prevent duplicate callbacks
                recoveryManager?.cancel()
                recoveryManager = null

                // Release and recreate player (setupPlayer creates new recoveryManager)
                player?.let { playerToRelease ->
                    player = null
                    releasePlayerAsync(playerToRelease, "rebuild")
                }
                binding?.let { setupPlayer(it) }

                // PR5: Force stream refresh using auto-recovery method - this is the final recovery step,
                // uses reserved budget that won't be blocked by manual limits.
                val refreshAllowed = viewModel.forceRefreshForAutoRecovery()
                if (!refreshAllowed) {
                    // Rebuild is the last recovery step - if blocked, transition to exhausted state
                    android.util.Log.w("PlayerFragment", "Rebuild refresh blocked, setting recovery exhausted")
                    viewModel.setRecoveryExhaustedState()
                }
            }
        }
    }

    private fun releasePlayerAsync(playerToRelease: ExoPlayer, reason: String) {
        // Remove listeners immediately to avoid duplicate callbacks while release is deferred.
        playerToRelease.removeListener(viewModel.playerListener)
        playbackListener?.let { playerToRelease.removeListener(it) }
        playbackListener = null
        // Stop playback immediately to avoid overlap while release() is deferred.
        try {
            playerToRelease.playWhenReady = false
            playerToRelease.stop()
        } catch (e: Exception) {
            android.util.Log.w("PlayerFragment", "Player stop failed ($reason): ${e.message}", e)
        }
        // Use the player's application looper to ensure we're on the correct thread
        val looper = playerToRelease.applicationLooper
        android.os.Handler(looper).post {
            try {
                playerToRelease.release()
            } catch (e: Exception) {
                android.util.Log.e("PlayerFragment", "Player release failed ($reason): ${e.message}", e)
            }
        }
    }

    /**
     * Extension to convert PlaybackRecoveryManager.RecoveryStep to viewmodel enum.
     */
    private fun PlaybackRecoveryManager.RecoveryStep.toViewModelStep(): RecoveryStep {
        return when (this) {
            PlaybackRecoveryManager.RecoveryStep.RE_PREPARE -> RecoveryStep.RE_PREPARE
            PlaybackRecoveryManager.RecoveryStep.SEEK_TO_CURRENT -> RecoveryStep.SEEK_TO_CURRENT
            PlaybackRecoveryManager.RecoveryStep.QUALITY_DOWNSHIFT -> RecoveryStep.QUALITY_DOWNSHIFT
            PlaybackRecoveryManager.RecoveryStep.REFRESH_URLS -> RecoveryStep.REFRESH_URLS
            PlaybackRecoveryManager.RecoveryStep.REBUILD_PLAYER -> RecoveryStep.REBUILD_PLAYER
        }
    }

    /**
     * Creates callbacks for BufferHealthMonitor to handle proactive quality downshift.
     * Coordinated with PlaybackRecoveryManager: proactive monitoring disabled during recovery.
     */
    private fun createBufferHealthCallbacks(): BufferHealthMonitor.BufferHealthCallbacks {
        return object : BufferHealthMonitor.BufferHealthCallbacks {
            override fun onProactiveDownshiftRequested(): Boolean {
                val state = viewModel.state.value
                val streamState = state.streamState

                // Only process when in Ready state (not during recovery)
                if (streamState !is StreamState.Ready) {
                    if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Proactive downshift skipped: not in Ready state")
                    return false
                }

                // Use factory-selected track if available (may differ from selection.video in AUTO mode).
                // This ensures downshift decisions are based on actual playing quality, not ViewModel's choice.
                val currentTrack = factorySelectedVideoTrack ?: streamState.selection.video
                val nextLower = findNextLowerQualityTrack(
                    current = currentTrack,
                    available = streamState.selection.resolved.videoTracks
                )

                return if (nextLower != null) {
                    if (BuildConfig.DEBUG) android.util.Log.i("PlayerFragment", "Proactive downshift: ${currentTrack?.qualityLabel} -> ${nextLower.qualityLabel}")
                    // Returns false if URLs expired and refresh triggered instead
                    viewModel.applyAutoQualityStepDown(nextLower)
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Proactive downshift: no lower quality available")
                    false
                }
            }

            override fun isInRecoveryState(): Boolean {
                return when (viewModel.state.value.streamState) {
                    is StreamState.Recovering, is StreamState.RecoveryExhausted -> true
                    else -> false
                }
            }
        }
    }

    private fun openDownloadedFile(entry: DownloadEntry) {
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "openDownloadedFile called with entry: ${entry.request.videoId}")
        val filePath = entry.filePath ?: run {
            android.util.Log.e("PlayerFragment", "No filePath in entry")
            Toast.makeText(requireContext(), R.string.downloads_no_file, Toast.LENGTH_SHORT).show()
            return
        }
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "File path: $filePath")
        val file = File(filePath)
        if (!file.exists()) {
            android.util.Log.e("PlayerFragment", "File does not exist: $filePath")
            Toast.makeText(requireContext(), getString(R.string.downloads_file_not_found, file.name), Toast.LENGTH_SHORT).show()
            return
        }
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "File exists, size: ${file.length()} bytes")
        val uri: Uri = FileProvider.getUriForFile(
            requireContext(),
            "${BuildConfig.APPLICATION_ID}.downloads.provider",
            file
        )
        val mimeType = if (entry.request.audioOnly) "audio/*" else "video/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Grant temporary read permission to all apps that can handle this intent
        val resolvedActivities = requireContext().packageManager.queryIntentActivities(intent, 0)
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Found ${resolvedActivities.size} apps that can handle video")
        for (resolvedInfo in resolvedActivities) {
            val packageName = resolvedInfo.activityInfo.packageName
            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Granting permission to: $packageName")
            requireContext().grantUriPermission(
                packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        if (resolvedActivities.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Starting activity with intent")
            try {
                startActivity(intent)
                if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Activity started successfully")
            } catch (e: Exception) {
                android.util.Log.e("PlayerFragment", "Failed to start activity", e)
                val errorMessage = e.localizedMessage ?: getString(R.string.error_unknown)
                Toast.makeText(requireContext(), getString(R.string.downloads_open_error, errorMessage), Toast.LENGTH_LONG).show()
            }
        } else {
            android.util.Log.e("PlayerFragment", "No apps found to handle video")
            Toast.makeText(requireContext(), R.string.downloads_no_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCurrentVideo() {
        val currentItem = viewModel.state.value.currentItem ?: return

        // Truncate title to 2 lines max (approximately 80 chars per line)
        val maxTitleChars = 160
        val title = if (currentItem.title.length > maxTitleChars) {
            currentItem.title.take(maxTitleChars - 3) + "..."
        } else {
            currentItem.title
        }

        // Use app deep link - directs users to install/open our app
        val videoDeepLink = "albunyaantube://video/${currentItem.streamId}"

        // Simple, clean share message - title, deep link, and app promo
        // Skip description as it often contains HTML tags (<br>, etc.)
        val shareMessage = buildString {
            append(title)
            append("\n\n")
            append(getString(R.string.share_watch_in_app))
            append("\n")
            append(videoDeepLink)
            append("\n\n")
            append(getString(R.string.share_app_promo))
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, shareMessage)
        }

        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_video_chooser)))
    }

    /**
     * Toggle fullscreen mode - called when user taps fullscreen button.
     * Requests orientation change which will trigger onConfigurationChanged -> updateFullscreenUi.
     *
     * **Orientation Lock Strategy:**
     * - Entering fullscreen: Lock to SENSOR_LANDSCAPE (allows 180° flip but not portrait).
     *   The lock remains until user explicitly exits fullscreen. This prevents the phone
     *   from rotating back to portrait due to sensor while user is watching fullscreen.
     * - Exiting fullscreen: Lock to PORTRAIT briefly, then unlock to UNSPECIFIED after
     *   stabilization. This allows natural rotation after exit.
     */
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen

        // Cancel any pending unlock from previous toggle
        cancelOrientationUnlock()

        val currentConfig = resources.configuration
        val isCurrentlyLandscape = currentConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        if (isFullscreen) {
            // Request landscape orientation - will trigger onConfigurationChanged
            // Mark that WE locked orientation - but DON'T schedule unlock for fullscreen entry.
            // We keep it locked to SENSOR_LANDSCAPE until user exits fullscreen.
            weLockedOrientation = true
            targetOrientationIsLandscape = true
            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "toggleFullscreen: lock set, target=LANDSCAPE, current=${if (isCurrentlyLandscape) "LANDSCAPE" else "PORTRAIT"} (no unlock scheduled)")
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            // NO scheduleOrientationUnlock() - lock stays until user exits fullscreen
        } else {
            // Request portrait orientation - will trigger onConfigurationChanged
            weLockedOrientation = true
            targetOrientationIsLandscape = false
            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "toggleFullscreen: lock set, target=PORTRAIT, current=${if (isCurrentlyLandscape) "LANDSCAPE" else "PORTRAIT"}")
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

            // Check if already in target orientation - onConfigurationChanged may not fire
            val alreadyInTarget = !isCurrentlyLandscape
            if (alreadyInTarget) {
                // Already in target orientation, use shorter delay since no rotation needed
                if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "toggleFullscreen: already in target, scheduling quick unlock")
                scheduleOrientationUnlock(ORIENTATION_UNLOCK_DELAY_MS)
            } else {
                // Schedule fallback unlock in case onConfigurationChanged doesn't fire
                // (e.g., sensor/display issue, or config change suppressed)
                scheduleOrientationUnlock(ORIENTATION_UNLOCK_FALLBACK_MS)
            }
        }

        // Update UI immediately (don't wait for orientation change callback)
        updateFullscreenUi()

        // Show one-time hint on first fullscreen entry
        if (isFullscreen) {
            showFullscreenZoomHintOnce()
        }
    }

    private fun showFullscreenZoomHintOnce() {
        val prefs = requireContext().getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("fullscreen_zoom_hint_shown", false)) return
        prefs.edit().putBoolean("fullscreen_zoom_hint_shown", true).apply()

        val view = binding?.root ?: return
        Snackbar.make(
            view,
            R.string.player_fullscreen_zoom_hint,
            Snackbar.LENGTH_LONG
        ).show()
    }

    /**
     * Schedule orientation unlock after a delay.
     * Uses main looper handler so it survives view detachment (unlike view.postDelayed).
     * Used both as fallback (longer delay) and after reaching target (shorter delay).
     */
    private fun scheduleOrientationUnlock(delayMs: Long) {
        cancelOrientationUnlock()
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "scheduleOrientationUnlock: scheduling in ${delayMs}ms")
        orientationUnlockRunnable = Runnable {
            // Use activity reference safely - may be null if fragment is detached
            val act = activity
            if (weLockedOrientation && act != null && !act.isFinishing) {
                if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "orientationUnlock: executing")
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                weLockedOrientation = false
                targetOrientationIsLandscape = null
            } else if (weLockedOrientation) {
                if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "orientationUnlock: skipped (activity null or finishing), weLockedOrientation reset")
                // Still reset our flags even if we can't unlock activity
                weLockedOrientation = false
                targetOrientationIsLandscape = null
            }
        }
        orientationHandler.postDelayed(orientationUnlockRunnable!!, delayMs)
    }

    /**
     * Cancel any pending orientation unlock.
     */
    private fun cancelOrientationUnlock() {
        orientationUnlockRunnable?.let { orientationHandler.removeCallbacks(it) }
        orientationUnlockRunnable = null
    }

    /**
     * Toggle resize mode between ZOOM and FIT when in fullscreen.
     * Called on double-tap in center area of player.
     * @return true if the gesture was handled (in fullscreen), false otherwise
     */
    private fun tryToggleFullscreenResizeMode(): Boolean {
        if (!isFullscreen) return false

        val binding = this.binding ?: return false

        fullscreenResizeMode = if (fullscreenResizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }

        binding.playerView.resizeMode = fullscreenResizeMode

        // Show a brief toast to indicate the mode change
        val modeName = if (fullscreenResizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            getString(R.string.player_resize_mode_zoom)
        } else {
            getString(R.string.player_resize_mode_fit)
        }
        Toast.makeText(requireContext(), modeName, Toast.LENGTH_SHORT).show()
        return true
    }

    /**
     * Update fullscreen UI state without changing orientation.
     * Called from both toggleFullscreen() and onConfigurationChanged().
     */
    private fun updateFullscreenUi() {
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "updateFullscreenUi: isFullscreen=$isFullscreen, fullscreenResizeMode=$fullscreenResizeMode", Exception("Stack trace"))
        val binding = this.binding ?: return
        val activity = requireActivity()
        val window = activity.window

        if (isFullscreen) {
            // Hide bottom navigation FIRST (this sets it to View.GONE)
            (activity as? com.albunyaan.tube.ui.MainActivity)?.setBottomNavVisibility(false)

            // Disable fitsSystemWindows on the parent shell fragment's root view.
            // Without this, the MainShellFragment's CoordinatorLayout (fitsSystemWindows=true)
            // adds status bar padding, creating a visible gap at the top in fullscreen.
            findShellRootView()?.let { shellRoot ->
                shellRoot.fitsSystemWindows = false
                shellRoot.setPadding(0, 0, 0, 0)
                shellRoot.setBackgroundColor(android.graphics.Color.BLACK)
            }

            // Enter fullscreen - Use WindowInsetsController on API 30+, fall back to legacy flags
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.insetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }

            // Handle display cutout (notch) - draw content into the cutout area
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            // Set background to pure black so no surface color peeks through
            binding.root.setBackgroundColor(android.graphics.Color.BLACK)

            // Hide scrollable content
            binding.playerScrollView.visibility = View.GONE

            // Expand AppBar to fill screen (do this before player container manipulation)
            binding.appBarLayout.layoutParams?.let { params ->
                if (params is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                binding.appBarLayout.layoutParams = params
            }

            // Expand CollapsingToolbarLayout to fill AppBarLayout
            binding.collapsingToolbar.layoutParams?.let { params ->
                if (params is com.google.android.material.appbar.AppBarLayout.LayoutParams) {
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                binding.collapsingToolbar.layoutParams = params
            }

            // Expand the player container (ConstraintLayout) within CollapsingToolbarLayout
            binding.playerContainer.layoutParams?.let { containerParams ->
                containerParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                containerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                binding.playerContainer.layoutParams = containerParams
            }

            // Set player to fill the screen in fullscreen mode
            // PlayerView is inside ConstraintLayout, so use ConstraintLayout.LayoutParams
            // Use 0dp (match_constraint) with full constraints - NOT MATCH_PARENT
            binding.playerView.layoutParams?.let { playerParams ->
                if (playerParams is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
                    // Use 0dp (match_constraint) for proper ConstraintLayout behavior
                    playerParams.height = 0
                    playerParams.width = 0
                    // Constrain to all edges of parent to fill the screen
                    playerParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    playerParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    playerParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    playerParams.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    // Remove dimension ratio constraint in fullscreen (constraints fill the space)
                    playerParams.dimensionRatio = null
                }
                binding.playerView.layoutParams = playerParams
            }

            // Use saved resize mode (FIT by default, toggled to ZOOM by double-tap center)
            if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "updateFullscreenUi: applying resizeMode=$fullscreenResizeMode (FIT=${AspectRatioFrameLayout.RESIZE_MODE_FIT}, ZOOM=${AspectRatioFrameLayout.RESIZE_MODE_ZOOM})")
            binding.playerView.resizeMode = fullscreenResizeMode

            // Update button icon
            binding.fullscreenButton.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            // Exit fullscreen - Restore system UI using WindowInsetsController on API 30+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
            } else {
                // On pre-API 30, we need to properly restore system UI flags.
                // LIGHT_STATUS_BAR should only be set in light theme (dark icons on light background).
                // In dark theme, we want light icons on dark background (no LIGHT_STATUS_BAR flag).
                val isNightMode = (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES

                @Suppress("DEPRECATION")
                val baseFlags = View.SYSTEM_UI_FLAG_VISIBLE or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (isNightMode) {
                    baseFlags  // Dark theme: light icons (no LIGHT_STATUS_BAR)
                } else {
                    baseFlags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR  // Light theme: dark icons
                }
                // Also clear status bar flags to restore normal appearance
                @Suppress("DEPRECATION")
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            // Restore default cutout mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }

            // Show bottom navigation - do this AFTER restoring system UI to avoid layout issues
            (activity as? com.albunyaan.tube.ui.MainActivity)?.setBottomNavVisibility(true)

            // Restore fitsSystemWindows on the parent shell fragment's root view
            restoreShellRootView()

            // Restore surface background color
            val typedValue = android.util.TypedValue()
            requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
            binding.root.setBackgroundColor(typedValue.data)

            // Show scrollable content
            binding.playerScrollView.visibility = View.VISIBLE

            // Restore AppBar to normal size
            binding.appBarLayout.layoutParams?.let { params ->
                if (params is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                binding.appBarLayout.layoutParams = params
            }

            // Restore CollapsingToolbarLayout to normal size
            binding.collapsingToolbar.layoutParams?.let { params ->
                if (params is com.google.android.material.appbar.AppBarLayout.LayoutParams) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                binding.collapsingToolbar.layoutParams = params
            }

            // Restore player container (ConstraintLayout) to normal wrap_content size
            // The 16:9 aspect ratio is enforced by the child PlayerView's dimension ratio
            binding.playerContainer.layoutParams?.let { containerParams ->
                containerParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                containerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                binding.playerContainer.layoutParams = containerParams
            }

            // Restore player to constraint-based size with 16:9 aspect ratio
            // Use 0dp (match_constraint) with full constraints - NOT MATCH_PARENT
            binding.playerView.layoutParams?.let { playerParams ->
                if (playerParams is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
                    // Restore constraint dimensions (0dp = match_constraint)
                    playerParams.height = 0
                    playerParams.width = 0
                    // Restore all constraints to parent edges (required for 0dp to work)
                    playerParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    playerParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    playerParams.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    // bottomToBottom is NOT set - 16:9 ratio determines height
                    playerParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                    // Restore 16:9 aspect ratio
                    playerParams.dimensionRatio = "16:9"
                }
                binding.playerView.layoutParams = playerParams
            }

            // Restore resize mode
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

            // Update button icon
            binding.fullscreenButton.setImageResource(R.drawable.ic_fullscreen)

            // Force insets to be reapplied after restoring system UI
            // This ensures the bottom nav gets correct insets after setDecorFitsSystemWindows(true)
            // Note: requestApplyInsets() is available since API 20, no version gate needed
            window.decorView.post {
                window.decorView.requestApplyInsets()
            }

            // Force layout refresh to ensure bottom nav and content are properly measured
            binding.root.requestLayout()
        }
    }

    /**
     * Restore system UI state when leaving PlayerFragment while in fullscreen.
     * This is called from onDestroyView() to prevent fullscreen state from leaking.
     * Unlike updateFullscreenUi(), this doesn't require binding and handles the case
     * where we're cleaning up during fragment destruction.
     */
    private fun restoreSystemUiOnExit() {
        val activity = activity ?: return
        val window = activity.window

        // Restore system bars using modern API or legacy flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
        } else {
            // On pre-API 30, we need to properly restore system UI flags.
            // LIGHT_STATUS_BAR should only be set in light theme (dark icons on light background).
            // In dark theme, we want light icons on dark background (no LIGHT_STATUS_BAR flag).
            val isNightMode = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

            @Suppress("DEPRECATION")
            val baseFlags = View.SYSTEM_UI_FLAG_VISIBLE or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (isNightMode) {
                baseFlags  // Dark theme: light icons (no LIGHT_STATUS_BAR)
            } else {
                baseFlags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR  // Light theme: dark icons
            }
            @Suppress("DEPRECATION")
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        // Restore default cutout mode (prevents leak to other screens)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }

        // Show bottom navigation
        (activity as? com.albunyaan.tube.ui.MainActivity)?.setBottomNavVisibility(true)

        // Restore fitsSystemWindows on the parent shell fragment's root view
        restoreShellRootView()

        // Force insets to be reapplied after restoring system UI
        // Note: requestApplyInsets() is available since API 20, no version gate needed
        window.decorView.post {
            window.decorView.requestApplyInsets()
        }
    }

    /**
     * Find the MainShellFragment's root view by traversing up the fragment hierarchy.
     * PlayerFragment → NavHostFragment → MainShellFragment
     */
    private fun findShellRootView(): View? {
        val shellRoot = parentFragment?.parentFragment?.view
        if (shellRoot == null && BuildConfig.DEBUG) {
            android.util.Log.w("PlayerFragment", "findShellRootView: could not traverse to MainShellFragment root (hierarchy changed?)")
        }
        return shellRoot
    }

    /**
     * Restore the MainShellFragment's root view after exiting fullscreen.
     */
    private fun restoreShellRootView() {
        findShellRootView()?.let { shellRoot ->
            shellRoot.fitsSystemWindows = true
            // Clear the explicit black background set during fullscreen.
            // The shell root originally has no explicit background (relies on theme window bg).
            shellRoot.background = null
            shellRoot.requestApplyInsets()
        }
    }

    private fun loadMediaToCast() {
        val currentItem = viewModel.state.value.currentItem ?: return
            val streamState = viewModel.state.value.streamState
            if (streamState !is StreamState.Ready) return

            val castSession = castContext?.sessionManager?.currentCastSession ?: return
            val remoteMediaClient = castSession.remoteMediaClient ?: return

        try {
            // Get the video URL from stream state
            val trackPair = selectTrack(streamState.selection, viewModel.state.value.audioOnly)
            if (trackPair == null) {
                android.util.Log.e("PlayerFragment", "Cannot cast: no video track available")
                context?.let { ctx ->
                    Toast.makeText(ctx, R.string.player_stream_error, Toast.LENGTH_SHORT).show()
                }
                return
            }
            val (videoUrl, _) = trackPair

            // Build Cast media metadata
            val metadata = com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE)
            metadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, currentItem.title)
            metadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_SUBTITLE, currentItem.channelName)

            // Add thumbnail if available
            currentItem.thumbnailUrl?.let { thumbUrl ->
                metadata.addImage(com.google.android.gms.common.images.WebImage(Uri.parse(thumbUrl)))
            }

            // Build media info
            val mediaInfo = com.google.android.gms.cast.MediaInfo.Builder(videoUrl)
                .setStreamType(com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(if (viewModel.state.value.audioOnly) "audio/mp4" else "video/mp4")
                .setMetadata(metadata)
                .build()

            // Load media to Cast device
            val request = com.google.android.gms.cast.MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .setCurrentTime(player?.currentPosition ?: 0)
                .build()

            remoteMediaClient.load(request)

            // Pause local playback
            player?.playWhenReady = false

            val deviceName = castSession.castDevice?.friendlyName
                ?: getString(R.string.player_cast_device_default)
            Toast.makeText(
                requireContext(),
                getString(R.string.player_cast_started, deviceName),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.player_cast_failed, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Updates the ExoPlayer's previous/next buttons based on playlist navigation state.
     *
     * Playlist Navigation Logic:
     * - First item: Previous disabled, Next enabled (if more items exist)
     * - Middle items: Both Previous and Next enabled
     * - Last item: Previous enabled, Next disabled
     * - Single item: Both disabled
     *
     * Buttons are always kept visible but visually indicate disabled state via alpha.
     */
    private fun updatePlaylistNavigationButtons(hasPrevious: Boolean, hasNext: Boolean) {
        // Always ensure buttons are visible (ExoPlayer may try to hide them)
        exoPrevButton?.visibility = View.VISIBLE
        exoNextButton?.visibility = View.VISIBLE

        // Previous button state
        exoPrevButton?.apply {
            isEnabled = hasPrevious
            alpha = if (hasPrevious) 1f else 0.3f
            setOnClickListener {
                if (hasPrevious) {
                    val moved = viewModel.skipToPrevious()
                    if (moved) {
                        player?.playWhenReady = true
                    }
                }
            }
        }

        // Next button state
        exoNextButton?.apply {
            isEnabled = hasNext
            alpha = if (hasNext) 1f else 0.3f
            setOnClickListener {
                if (hasNext) {
                    val advanced = viewModel.skipToNext()
                    if (advanced) {
                        player?.playWhenReady = true
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.player_up_next_empty), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Apply quality constraints based on selection origin.
     *
     * - MANUAL: User explicitly selected a resolution, use LOCK mode (fixed quality)
     * - AUTO_RECOVERY/AUTO: System selected, use CAP mode (ABR can go lower)
     *
     * @param height The desired video height
     * @param origin How the quality was selected
     */
    private fun applyQualityConstraintByOrigin(height: Int, origin: QualitySelectionOrigin) {
        val selector = trackSelector ?: return
        val mode = when (origin) {
            QualitySelectionOrigin.MANUAL -> QualityConstraintMode.LOCK
            QualitySelectionOrigin.AUTO, QualitySelectionOrigin.AUTO_RECOVERY -> QualityConstraintMode.CAP
        }
        selector.applyQualityConstraint(height, mode)
        if (BuildConfig.DEBUG) {
            android.util.Log.d("PlayerFragment", "Track selector: applied quality ${mode.name} ${height}p (origin: ${origin.name})")
        }
    }

    /**
     * Clear any quality constraints from the track selector, allowing ABR to choose freely.
     *
     * Phase 3: Delegates to QualityTrackSelector.selectAutoQuality().
     */
    private fun clearTrackSelectorConstraints() {
        val selector = trackSelector ?: return
        selector.selectAutoQuality()
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Track selector: cleared constraints (ABR free)")
    }

    /**
     * Result of cache-hit detection for stream preparation.
     * Helps determine whether to skip rebuilding the MediaSource.
     */
    private sealed class CacheHitResult {
        /** Same source is already prepared, skip preparation */
        data class Hit(val qualityCapToApply: Int?) : CacheHitResult()
        /** Different source or configuration, must prepare new MediaSource */
        object Miss : CacheHitResult()
    }

    /**
     * Determines if the currently prepared stream matches what we would create.
     * Extracted for testability and reduced complexity in maybePrepareStream.
     *
     * @param key The stream key (streamId, audioOnly) we want to prepare
     * @param state Current player state
     * @param selection Current playback selection
     * @param resolved Resolved streams with URLs
     * @param forceProgressive Whether adaptive streaming failed and we're forcing progressive
     * @return CacheHitResult indicating whether to skip preparation
     */
    private fun checkCacheHit(
        key: Pair<String, Boolean>,
        state: PlayerState,
        selection: PlaybackSelection,
        resolved: ResolvedStreams,
        forceProgressive: Boolean
    ): CacheHitResult {
        // Not the same key - must prepare
        if (preparedStreamKey != key || preparedStreamUrl == null) {
            return CacheHitResult.Miss
        }

        // Check if the prepared source matches what we'd create now
        // Include forceProgressive to prevent unnecessary rebuilds after adaptive fallback
        val wouldUseAdaptive = !state.audioOnly && !forceProgressive && (resolved.hlsUrl != null || resolved.dashUrl != null)

        // SYNTHETIC_DASH and SYNTH_ADAPTIVE special cases:
        // Delegate to production CacheHitDecider for testability and consistency.
        // See CacheHitDecider for invariants and rationale.
        if (preparedAdaptiveType == MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE ||
            preparedAdaptiveType == MediaSourceResult.AdaptiveType.SYNTHETIC_DASH
        ) {
            if (!state.audioOnly && !forceProgressive) {
                val preparedState = CacheHitDecider.PreparedState(
                    streamKey = preparedStreamKey,
                    streamUrl = preparedStreamUrl,
                    adaptiveType = preparedAdaptiveType,
                    qualityCapHeight = preparedQualityCapHeight,
                    factorySelectedVideoTrack = factorySelectedVideoTrack
                )
                val requestedState = CacheHitDecider.RequestedState(
                    streamKey = key,
                    audioOnly = state.audioOnly,
                    forceProgressive = forceProgressive,
                    qualityCapHeight = selection.userQualityCapHeight,
                    selectionOrigin = selection.selectionOrigin,
                    requestedVideoTrack = selection.video,
                    wouldUseAdaptive = wouldUseAdaptive,
                    adaptiveUrl = null // Not used for synthetic types
                )
                return when (val result = CacheHitDecider.evaluate(preparedState, requestedState)) {
                    is CacheHitDecider.Result.Hit -> CacheHitResult.Hit(result.qualityCapToApply)
                    is CacheHitDecider.Result.Miss -> CacheHitResult.Miss
                }
            }
        }

        val urlMatches = when {
            preparedIsAdaptive && wouldUseAdaptive -> {
                // Both are adaptive - compare against the actual URL we prepared with.
                // Use preparedAdaptiveType to match the correct manifest URL.
                // This prevents rebuild loops when HLS fails and we fall back to DASH:
                // preparedStreamUrl=DASH, but (resolved.hlsUrl ?: resolved.dashUrl)=HLS would mismatch.
                //
                // Note: SYNTHETIC_DASH and SYNTH_ADAPTIVE are handled in the isSyntheticSource block above.
                // They should not reach here, but for safety we handle them explicitly.
                val expectedAdaptiveUrl = when (preparedAdaptiveType) {
                    MediaSourceResult.AdaptiveType.HLS -> resolved.hlsUrl
                    MediaSourceResult.AdaptiveType.DASH -> resolved.dashUrl
                    MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE,
                    MediaSourceResult.AdaptiveType.SYNTHETIC_DASH -> {
                        // Defensive fallback: normally handled in isSyntheticSource block above.
                        // If we reach here, use preparedStreamUrl for identity comparison.
                        preparedStreamUrl
                    }
                    MediaSourceResult.AdaptiveType.NONE -> resolved.hlsUrl ?: resolved.dashUrl
                }
                preparedStreamUrl == expectedAdaptiveUrl
            }
            !preparedIsAdaptive && !wouldUseAdaptive -> {
                // Both are progressive - compare against correct URL (video or audio)
                if (state.audioOnly) {
                    preparedStreamUrl == selection.audio.url
                } else {
                    // For video progressive: Compare URLs carefully based on selection origin.
                    //
                    // - AUTO: Factory may apply cold-start quality selection and select a different
                    //   track than selection.video. Use factorySelectedVideoTrack for idempotent detection
                    //   (prevents rebuild loops when ViewModel emits same selection repeatedly).
                    //
                    // - MANUAL: User explicitly changed quality. MUST compare against selection.video
                    //   to detect the change and trigger rebuild. Using factorySelectedVideoTrack here
                    //   would incorrectly return "Hit" and block manual quality switches.
                    //
                    // - AUTO_RECOVERY: BufferHealthMonitor requested downshift. Same as MANUAL - must
                    //   compare against the new selection.video to detect and apply the change.
                    val effectiveVideoUrl = when (selection.selectionOrigin) {
                        QualitySelectionOrigin.AUTO -> factorySelectedVideoTrack?.url ?: selection.video?.url
                        QualitySelectionOrigin.MANUAL -> selection.video?.url
                        QualitySelectionOrigin.AUTO_RECOVERY -> selection.video?.url
                    }
                    preparedStreamUrl == effectiveVideoUrl
                }
            }
            else -> {
                // Mismatch: one is adaptive, other is progressive - must rebuild
                false
            }
        }

        if (!urlMatches) {
            return CacheHitResult.Miss
        }

        // URL matches - check if quality cap needs updating for adaptive streams
        val qualityCapToApply = if (preparedIsAdaptive) {
            val desiredCap = selection.userQualityCapHeight
            if (desiredCap != preparedQualityCapHeight) desiredCap else null
        } else {
            null
        }

        return CacheHitResult.Hit(qualityCapToApply)
    }

    /**
     * Syncs the current item's metadata to MediaSession for notification and lockscreen display.
     *
     * This only updates when the item changes (tracked by lastMetadataSyncedItemId) to avoid
     * redundant updates from repeated state emissions.
     *
     * @param item The current playing item
     * @param force Force metadata sync even if we already synced this item. Use this after
     *              stream rebuilds (e.g., setMediaSource/prepare) where MediaSession metadata
     *              may have been lost.
     */
    private fun syncMediaSessionMetadata(item: UpNextItem, force: Boolean = false) {
        // Skip if we already synced this item (unless forced)
        if (!force && lastMetadataSyncedItemId == item.id) return

        val currentPlayer = player ?: return

        // Update MediaSession metadata (handles artwork loading asynchronously)
        metadataManager.updateMetadata(
            player = currentPlayer,
            title = item.title,
            artist = item.channelName,
            thumbnailUrl = item.thumbnailUrl
        )

        // Update PlaybackService with current video ID for notification tap intent
        playbackService?.setCurrentVideoId(item.streamId)

        lastMetadataSyncedItemId = item.id
        if (BuildConfig.DEBUG) android.util.Log.d("PlayerFragment", "Synced MediaSession metadata for: ${item.title}${if (force) " (forced)" else ""}")
    }

    private companion object {
        private const val DEFAULT_AUDIO_MIME = "audio/mp4"
        private const val DEFAULT_VIDEO_MIME = "video/mp4"

        /** Delay before unlocking orientation after reaching target (allows stabilization) */
        private const val ORIENTATION_UNLOCK_DELAY_MS = 500L
        /** Fallback timeout to unlock orientation if config change doesn't fire.
         *  Set to 3s to accommodate slow devices/TVs where rotation animation takes longer. */
        private const val ORIENTATION_UNLOCK_FALLBACK_MS = 3000L
    }
}
