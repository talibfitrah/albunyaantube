package com.albunyaan.tube.ui.player

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
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
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.view.GestureDetectorCompat
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import com.albunyaan.tube.data.extractor.PlaybackSelection
import com.albunyaan.tube.data.extractor.QualitySelectionOrigin
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.SubtitleTrack
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadStatus
import com.albunyaan.tube.player.BufferHealthMonitor
import com.albunyaan.tube.player.MediaSourceResult
import com.albunyaan.tube.player.PlaybackRecoveryManager
import com.albunyaan.tube.player.PlaybackService
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

    private var binding: FragmentPlayerBinding? = null
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
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
    private lateinit var gestureDetector: GestureDetectorCompat
    private var isFullscreen = false
    private var castContext: com.google.android.gms.cast.framework.CastContext? = null
    private var exoNextButton: View? = null
    private var exoPrevButton: View? = null
    /** Tracks whether current media source is adaptive (HLS/DASH) vs progressive */
    private var preparedIsAdaptive: Boolean = false
    /** Tracks the type of adaptive source prepared (SYNTHETIC_DASH needs special handling) */
    private var preparedAdaptiveType: MediaSourceResult.AdaptiveType =
        MediaSourceResult.AdaptiveType.NONE
    /** Last applied user cap for adaptive streams; used to update track selector without rebuilding. */
    private var preparedQualityCapHeight: Int? = null
    /** For SYNTHETIC_DASH: The video track URL used by factory. Used for idempotent cache-hit detection. */
    private var preparedSyntheticVideoUrl: String? = null
    /**
     * The actual video track selected by the factory. For progressive/SYNTHETIC_DASH, this may differ
     * from selection.video when DEFAULT_INITIAL_QUALITY_HEIGHT is applied in AUTO mode.
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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Called when service crashes or is killed - not during normal unbind
            playbackService = null
            bindingRequested = false

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
        setHasOptionsMenu(true)
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
        val videoTitle = arguments?.getString("title") ?: "Video"
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
        binding.descriptionHeader?.setOnClickListener {
            val isExpanded = binding.videoDescription?.isVisible == true
            binding.videoDescription?.isVisible = !isExpanded
            binding.descriptionArrow?.rotation = if (isExpanded) 0f else 180f
        }

        // Setup action buttons
        binding.likeButton?.setOnClickListener {
            Toast.makeText(requireContext(), R.string.player_like_coming_soon, Toast.LENGTH_SHORT).show()
        }

        binding.shareButton?.setOnClickListener {
            shareCurrentVideo()
        }

        binding.audioButton?.setOnClickListener {
            binding.audioOnlyToggle.isChecked = !binding.audioOnlyToggle.isChecked
        }

        // Download button - downloads are always allowed (no EULA gating)
        binding.downloadButton?.setOnClickListener {
            val started = viewModel.downloadCurrent()
            if (started) {
                Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show()
            }
        }

        binding.minimizeButton?.setOnClickListener {
            // Use navigateUp for predictable navigation back to previous screen
            findNavController().navigateUp()
        }

        binding.fullscreenButton?.setOnClickListener {
            toggleFullscreen()
        }

        binding.qualityButton?.setOnClickListener {
            showQualitySelector()
        }

        // Overlay controls are always visible now - no click listener needed

        // Setup error retry buttons
        binding.playerRetryButton?.setOnClickListener {
            viewModel.retryCurrentStream()
        }
        binding.playerRefreshStreamButton?.setOnClickListener {
            // Reset adaptive fallback flag - manual refresh should retry adaptive
            adaptiveFailedForCurrentStream = null
            // PR5: Show feedback if rate-limited
            if (!viewModel.forceRefreshCurrentStream()) {
                Toast.makeText(requireContext(), R.string.player_refresh_rate_limited, Toast.LENGTH_SHORT).show()
            }
        }

        // Setup recovery retry button (shown when auto-recovery exhausted)
        binding.playerRecoveryRetryButton?.setOnClickListener {
            // User manual retry - resets recovery state and forces stream refresh
            recoveryManager?.resetRecoveryState()
            viewModel.clearRecoveringState()
            // Reset adaptive fallback flag - manual retry should retry adaptive
            adaptiveFailedForCurrentStream = null
            // PR5: Show feedback if rate-limited
            if (!viewModel.forceRefreshCurrentStream()) {
                Toast.makeText(requireContext(), R.string.player_refresh_rate_limited, Toast.LENGTH_SHORT).show()
            }
        }

        // Configure Cast button
        castContext?.let { context ->
            binding.castButton?.let { button ->
                androidx.mediarouter.media.MediaControlIntent.CATEGORY_LIVE_VIDEO
                com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(
                    requireContext().applicationContext,
                    button
                )
            }
        }

        setupUpNextList(binding)
        setupPlayer(binding)
        collectViewState(binding)
    }

    override fun onStart() {
        super.onStart()
        // Bind to PlaybackService for MediaSession and background playback
        // Using bind() instead of start() avoids Android O+ foreground service timing issues
        // Guard: only bind once per view lifecycle to prevent stacking binds on repeated start/stop
        if (!bindingRequested) {
            bindingRequested = PlaybackService.bind(requireContext(), serviceConnection)
        }
        // Restore user's intended play state - don't force resume if user had paused
        player?.playWhenReady = userWantsToPlay
        castContext?.sessionManager?.addSessionManagerListener(castSessionListener, com.google.android.gms.cast.framework.CastSession::class.java)
    }

    override fun onStop() {
        // Don't pause playback - allow background audio
        castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, com.google.android.gms.cast.framework.CastSession::class.java)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Auto-enter fullscreen in landscape, exit in portrait
        val shouldBeFullscreen = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (shouldBeFullscreen != isFullscreen) {
            toggleFullscreen()
        }
    }

    override fun onDestroyView() {
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

        // Clean up player resources - ExoPlayer handles its own callback cleanup during release
        binding?.playerView?.player = null
        preparedStreamKey = null
        preparedStreamUrl = null
        preparedIsAdaptive = false
        preparedAdaptiveType = MediaSourceResult.AdaptiveType.NONE
        preparedQualityCapHeight = null
        preparedSyntheticVideoUrl = null
        factorySelectedVideoTrack = null
        adaptiveFailedForCurrentStream = null

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
            // Use the player's application looper to ensure we're on the correct thread
            val looper = playerToRelease.applicationLooper
            android.os.Handler(looper).post {
                try {
                    playerToRelease.release()
                } catch (e: Exception) {
                    android.util.Log.e("PlayerFragment", "Player release failed: ${e.message}", e)
                }
            }
        }

        binding = null
        super.onDestroyView()
    }

    private fun setupUpNextList(binding: FragmentPlayerBinding) {
        binding.upNextList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = upNextAdapter
            addItemDecoration(DividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL))
        }
    }

    private val mediaSourceFactory by lazy {
        com.albunyaan.tube.player.MultiQualityMediaSourceFactory(requireContext())
    }

    private fun setupPlayer(binding: FragmentPlayerBinding) {
        // Configure load control for better buffering - optimized for long videos
        val loadControl = DefaultLoadControl.Builder()
            // Buffering tuned for long video stability:
            // - Larger buffer to handle network variance on 10+ min videos
            // - Faster initial playback to reduce perceived latency
            .setBufferDurationsMs(
                /* minBufferMs= */ 30000,         // Keep ~30s buffered (increased for long videos)
                /* maxBufferMs= */ 180000,        // Buffer up to 3 minutes ahead (increased)
                /* bufferForPlaybackMs= */ 2000,  // Start after 2s buffered (faster start)
                /* bufferForPlaybackAfterRebufferMs= */ 4000 // Resume after 4s buffered
            )
            // Back buffer for seek-back performance (60 seconds)
            .setBackBuffer(60000, true)
            // Prioritize minimum rebuffer time over memory
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Configure track selector for adaptive streaming quality constraints
        // This allows applying user quality caps to HLS/DASH streams
        val trackSelector = DefaultTrackSelector(requireContext()).also {
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
        player.addListener(object : Player.Listener {
            private var hasAutoHidden = false
            private var retryCount = 0
            private val maxRetries = 3
            private var streamRefreshCount = 0
            private val maxStreamRefreshes = 2
            private var lastStreamIdForRetries: String? = null

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                android.util.Log.d("PlayerFragment", "onIsPlayingChanged: isPlaying=$isPlaying, hasAutoHidden=$hasAutoHidden")
                if (isPlaying) {
                    // Notify recovery manager that playback is healthy
                    recoveryManager?.onPlaybackStarted()

                    // Notify buffer health monitor to start monitoring (progressive streams only)
                    player?.let { bufferHealthMonitor?.onPlaybackStarted(it) }

                    // PR5: Reset rate limiter backoff for successful playback
                    viewModel.state.value.currentItem?.streamId?.let { streamId ->
                        viewModel.onPlaybackSuccess(streamId)
                    }

                    if (!hasAutoHidden) {
                        // Auto-hide controls after playback starts
                        hasAutoHidden = true
                        retryCount = 0 // Reset retry count on successful playback

                        // Prefetch next items in queue when current video starts playing
                        viewModel.prefetchNextItems()

                        binding.playerView.postDelayed({
                            if (player?.isPlaying == true) {
                                android.util.Log.d("PlayerFragment", "Auto-hiding controls now")
                                binding.playerView.hideController()
                                // Also explicitly hide overlay controls
                                binding.playerOverlayControls?.visibility = View.GONE
                            }
                        }, 3000) // 3 seconds delay to give user time to see controls
                    }
                } else {
                    // Pause buffer health monitoring when not playing
                    bufferHealthMonitor?.onPlaybackPaused()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                android.util.Log.d("PlayerFragment", "onPlaybackStateChanged: state=$playbackState")

                // Delegate to recovery manager for freeze detection
                player?.let { p -> recoveryManager?.onPlaybackStateChanged(p, playbackState) }

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
                        player?.playWhenReady = true
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
                player?.let { p -> recoveryManager?.onPlayWhenReadyChanged(p, playWhenReady) }
            }

            override fun onPlayerError(error: PlaybackException) {
                val httpResponseCode = findHttpResponseCode(error.cause)
                android.util.Log.e(
                    "PlayerFragment",
                    "Player error: ${error.errorCodeName}, retryCount=$retryCount, streamRefreshCount=$streamRefreshCount, http=$httpResponseCode",
                    error
                )

                // Handle different error types
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                        // Network errors - retry with backoff
                        if (retryCount < maxRetries) {
                            retryCount++
                            val delayMs = retryCount * 1500L
                            android.util.Log.d("PlayerFragment", "Retrying playback in ${delayMs}ms (attempt $retryCount)")
                            // Use lifecycle-aware coroutine to prevent crashes if fragment is destroyed
                            viewLifecycleOwner.lifecycleScope.launch {
                                kotlinx.coroutines.delay(delayMs)
                                player?.let {
                                    it.prepare()
                                    it.playWhenReady = true
                                }
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
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> {
                        // Stream URL expired/invalid (or a hard HTTP failure) - re-resolve URLs and resume.
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
                    else -> {
                        // Other errors - show message with safe context access
                        context?.let { ctx ->
                            Toast.makeText(ctx, getString(R.string.player_stream_error) + ": ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        })

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
                    android.util.Log.d("PlayerFragment", "Controller visibility changed: $visibility")
                    binding.playerOverlayControls?.visibility = visibility

                    // Re-apply our custom prev/next button states when controller becomes visible
                    // This prevents Media3 from overriding our button configuration
                    if (visibility == View.VISIBLE) {
                        val state = viewModel.state.value
                        updatePlaylistNavigationButtons(state.hasPrevious, state.hasNext)
                    }
                }
            )
        }

        // Keep overlay controls visible initially (user needs to see quality button)
        binding.playerOverlayControls?.visibility = View.VISIBLE

        // Setup gesture detector for brightness/volume/seek gestures
        val window = requireActivity().window
        val playerGesture = PlayerGestureDetector(requireContext(), player, window)
        gestureDetector = GestureDetectorCompat(requireContext(), playerGesture)

        // Attach gestures to player view
        binding.playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // Allow ExoPlayer to handle other touches
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
                    binding.videoTitle?.text = currentItem.title
                    binding.authorName?.text = currentItem.channelName

                    // Format view count and time
                    val viewText = currentItem.viewCount?.let { count ->
                        val formatted = if (count >= 1_000_000) {
                            String.format("%.1fM", count / 1_000_000.0)
                        } else if (count >= 1_000) {
                            String.format("%.1fK", count / 1_000.0)
                        } else {
                            count.toString()
                        }
                        "$formatted views"
                    } ?: "No views yet"

                    binding.videoStats?.text = viewText
                    binding.videoDescription?.text = currentItem.description ?: "No description available"
                } else {
                    binding.videoTitle?.text = "No video playing"
                    binding.authorName?.text = ""
                    binding.videoStats?.text = ""
                    binding.videoDescription?.text = "No description available"
                }

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

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.player_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_captions -> {
                showCaptionSelector()
                true
            }
            R.id.action_enter_pip -> {
                enterPictureInPicture()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showQualitySelector() {
        val streamState = viewModel.state.value.streamState
        android.util.Log.d("PlayerFragment", "showQualitySelector: streamState = $streamState")

        if (streamState !is StreamState.Ready) {
            Toast.makeText(requireContext(), "Video not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        val videoTracks = streamState.selection.resolved.videoTracks
        android.util.Log.d("PlayerFragment", "Available video tracks: ${videoTracks.size}")
        videoTracks.forEachIndexed { index, track ->
            android.util.Log.d("PlayerFragment", "Track $index: height=${track.height}, qualityLabel=${track.qualityLabel}")
        }

        val allQualities = viewModel.getAvailableQualities()
        android.util.Log.d("PlayerFragment", "getAvailableQualities returned: ${allQualities.size} qualities")

        if (allQualities.isEmpty()) {
            Toast.makeText(requireContext(), "No quality options available (videoTracks=${videoTracks.size})", Toast.LENGTH_LONG).show()
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

        android.util.Log.d("PlayerFragment", "Quality labels: ${labels.joinToString()}")

        val currentQuality = streamState.selection.video?.qualityLabel
        android.util.Log.d("PlayerFragment", "Current quality: $currentQuality")

        val currentIndex = sortedQualities.indexOfFirst { it.label == currentQuality }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Video Quality")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selectedQuality = sortedQualities[which]
                viewModel.selectQuality(selectedQuality.track)
                Toast.makeText(requireContext(), "Switching to ${selectedQuality.label}...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Overlay controls are now always visible - removed toggle functionality
    // This provides better UX as users always have access to quality/cast/minimize buttons

    private fun showCaptionSelector() {
        val subtitles = viewModel.getAvailableSubtitles()

        // Add "Off" option at the beginning
        val options = mutableListOf("Off")
        options.addAll(subtitles.map { track: SubtitleTrack ->
            if (track.isAutoGenerated) {
                "${track.languageName} (Auto-generated)"
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
            .setTitle("Select Captions")
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
            .setNegativeButton("Cancel", null)
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
                binding.playerErrorOverlay?.visibility = View.GONE
                binding.playerRecoveryOverlay?.visibility = View.GONE
            }
            StreamState.Loading -> {
                binding.playerStatus.text = if (state.retryCount > 0) {
                    getString(R.string.player_retrying, state.retryCount + 1, 3)
                } else {
                    getString(R.string.player_status_resolving)
                }
                binding.playerErrorOverlay?.visibility = View.GONE
                binding.playerRecoveryOverlay?.visibility = View.GONE
            }
            is StreamState.Error -> {
                binding.playerStatus.text = getString(streamState.messageRes)
                // Show error overlay with retry options
                binding.playerErrorOverlay?.visibility = View.VISIBLE
                binding.playerErrorMessage?.text = getString(streamState.messageRes)
                binding.playerRecoveryOverlay?.visibility = View.GONE
            }
            is StreamState.Ready -> {
                binding.playerStatus.text = when {
                    state.audioOnly -> getString(R.string.player_status_audio_only)
                    else -> getString(R.string.player_status_video_playing)
                }
                binding.playerErrorOverlay?.visibility = View.GONE
                binding.playerRecoveryOverlay?.visibility = View.GONE
            }
            is StreamState.Recovering -> {
                // Show recovery overlay with progress
                binding.playerRecoveryOverlay?.visibility = View.VISIBLE
                binding.playerRecoveryProgress?.visibility = View.VISIBLE
                binding.playerRecoveryMessage?.text = getString(
                    R.string.player_recovering_attempt,
                    streamState.attempt,
                    PlaybackRecoveryManager.MAX_RECOVERY_ATTEMPTS
                )
                binding.playerRecoveryRetryButton?.visibility = View.GONE
                binding.playerErrorOverlay?.visibility = View.GONE
                binding.playerStatus.text = getString(R.string.player_recovering)
            }
            is StreamState.RecoveryExhausted -> {
                // Show recovery overlay with retry button (exhausted state)
                binding.playerRecoveryOverlay?.visibility = View.VISIBLE
                binding.playerRecoveryProgress?.visibility = View.GONE
                binding.playerRecoveryMessage?.text = getString(R.string.player_recovery_exhausted_message)
                binding.playerRecoveryRetryButton?.visibility = View.VISIBLE
                binding.playerErrorOverlay?.visibility = View.GONE
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
                button.isEnabled = true
                button.setOnClickListener {
                    viewModel.downloadCurrent()
                }
                // Status text no longer shown in new UI
            }
        }
    }

    private fun requestStreamRefreshAndResume(reason: String) {
        val currentItem = viewModel.state.value.currentItem ?: return
        val currentPlayer = player ?: return

        val resumePosition = currentPlayer.currentPosition.coerceAtLeast(0L)
        pendingResumeStreamId = currentItem.streamId
        pendingResumePositionMs = resumePosition.takeIf { it > 0L }
        pendingResumePlayWhenReady = currentPlayer.playWhenReady

        android.util.Log.w(
            "PlayerFragment",
            "Refreshing stream for ${currentItem.streamId} at ${resumePosition}ms: $reason"
        )

        preparedStreamKey = null
        preparedStreamUrl = null
        preparedIsAdaptive = false
        preparedAdaptiveType = MediaSourceResult.AdaptiveType.NONE
        preparedQualityCapHeight = null
        preparedSyntheticVideoUrl = null
        factorySelectedVideoTrack = null
        currentPlayer.stop()

        context?.let { ctx ->
            Toast.makeText(ctx, R.string.player_status_resolving, Toast.LENGTH_SHORT).show()
        }

        // PR5: Use auto-recovery method - this is called from error handlers and recovery ladder,
        // not from user buttons. AUTO_RECOVERY has reserved budget that won't be blocked by manual limits.
        viewModel.forceRefreshForAutoRecovery()
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
                    android.util.Log.d("PlayerFragment", "Step-down: video-only -> muxed at ${currentHeight}p")
                resultHeight == currentHeight ->
                    android.util.Log.d("PlayerFragment", "Step-down: lower bitrate at ${currentHeight}p (${current.bitrate} -> ${result.bitrate})")
                else ->
                    android.util.Log.d("PlayerFragment", "Step-down: ${currentHeight}p -> ${resultHeight}p")
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
                preparedSyntheticVideoUrl = null
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
                // Apply quality cap if needed (for adaptive streams)
                cacheHit.qualityCapToApply?.let { cap ->
                    applyQualityCapToTrackSelector(cap)
                    preparedQualityCapHeight = cap
                } ?: run {
                    // Check if we need to clear constraints (cap removed)
                    if (preparedIsAdaptive && selection.userQualityCapHeight == null && preparedQualityCapHeight != null) {
                        clearTrackSelectorConstraints()
                        preparedQualityCapHeight = null
                    }
                }
                android.util.Log.d("PlayerFragment", "Stream already prepared with same source, skipping")
                return
            }
            is CacheHitResult.Miss -> {
                // Continue to prepare new MediaSource
            }
        }

        android.util.Log.d("PlayerFragment", "Preparing stream: id=${key.first}, audioOnly=${key.second}, expectedUrl=$expectedSourceUrl, isQualitySwitch=$isQualitySwitch")

        // Reset sticky fallback flag when switching to a different stream
        if (adaptiveFailedForCurrentStream != null && adaptiveFailedForCurrentStream != streamState.streamId) {
            android.util.Log.d("PlayerFragment", "Clearing adaptive fallback flag (new stream)")
            adaptiveFailedForCurrentStream = null
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
                forceProgressive = forceProgressive
            )
            preparedIsAdaptive = result.isAdaptive
            preparedAdaptiveType = result.adaptiveType
            android.util.Log.d(
                "PlayerFragment",
                "Created media source: isAdaptive=$preparedIsAdaptive, type=${result.adaptiveType}, qualityCap=${selection.userQualityCapHeight}p, actualSource=${sourceIdentityForLog(result.actualSourceUrl)}"
            )

            // Apply quality cap to track selector for adaptive streams
            if (preparedIsAdaptive && selection.hasUserQualityCap) {
                applyQualityCapToTrackSelector(selection.userQualityCapHeight!!)
                preparedQualityCapHeight = selection.userQualityCapHeight
                preparedSyntheticVideoUrl = null // Not SYNTHETIC_DASH
                factorySelectedVideoTrack = null // ABR handles quality selection
            } else if (result.adaptiveType == MediaSourceResult.AdaptiveType.SYNTHETIC_DASH) {
                // SYNTHETIC_DASH: Track the quality cap and video URL used by factory.
                // These are needed by checkCacheHit() to detect changes that require rebuild.
                // No track selector constraints needed (single-track synthetic manifest).
                clearTrackSelectorConstraints()
                preparedQualityCapHeight = selection.userQualityCapHeight
                preparedSyntheticVideoUrl = result.actualSourceUrl
                factorySelectedVideoTrack = result.selectedVideoTrack // Factory's actual choice
            } else {
                // Progressive or no cap - clear any lingering constraints.
                // For progressive, constraints don't affect playback (direct URL), but clearing
                // ensures clean state for future adaptive videos.
                clearTrackSelectorConstraints()
                preparedQualityCapHeight = null
                preparedSyntheticVideoUrl = null
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
            preparedSyntheticVideoUrl = null
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
                preparedSyntheticVideoUrl = null
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

            // Restore position for quality switching / stream refresh recovery.
            if ((isQualitySwitch || hasPendingResume) && savedPosition > 0) {
                currentPlayer.seekTo(savedPosition)
            }

            currentPlayer.playWhenReady = shouldPlay
            preparedStreamKey = key
            // Use the actual URL from the MediaSourceResult - this is the true source identity
            preparedStreamUrl = mediaSourceResult.actualSourceUrl
            android.util.Log.d(
                "PlayerFragment",
                "Stream prepared successfully: ${streamState.streamId}, isAdaptive=$preparedIsAdaptive, type=${mediaSourceResult.adaptiveType}, actualSource=${sourceIdentityForLog(preparedStreamUrl)}"
            )

            // Notify recovery manager of new stream - pass streamId and adaptive flag
            recoveryManager?.onNewStream(streamState.streamId, preparedIsAdaptive)

            // Notify buffer health monitor of new stream - only monitors progressive streams
            bufferHealthMonitor?.onNewStream(streamState.streamId, preparedIsAdaptive)

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
            preparedSyntheticVideoUrl = null
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
                player?.release()
                player = null
                binding?.let { setupPlayer(it) }

                // PR5: Force stream refresh using auto-recovery method - this is the final recovery step,
                // uses reserved budget that won't be blocked by manual limits.
                viewModel.forceRefreshForAutoRecovery()
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
                    android.util.Log.d("PlayerFragment", "Proactive downshift skipped: not in Ready state")
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
                    android.util.Log.i("PlayerFragment", "Proactive downshift: ${currentTrack?.qualityLabel} -> ${nextLower.qualityLabel}")
                    // Returns false if URLs expired and refresh triggered instead
                    viewModel.applyAutoQualityStepDown(nextLower)
                } else {
                    android.util.Log.d("PlayerFragment", "Proactive downshift: no lower quality available")
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
        android.util.Log.d("PlayerFragment", "openDownloadedFile called with entry: ${entry.request.videoId}")
        val filePath = entry.filePath ?: run {
            android.util.Log.e("PlayerFragment", "No filePath in entry")
            Toast.makeText(requireContext(), "No file path available", Toast.LENGTH_SHORT).show()
            return
        }
        android.util.Log.d("PlayerFragment", "File path: $filePath")
        val file = File(filePath)
        if (!file.exists()) {
            android.util.Log.e("PlayerFragment", "File does not exist: $filePath")
            Toast.makeText(requireContext(), "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }
        android.util.Log.d("PlayerFragment", "File exists, size: ${file.length()} bytes")
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
        android.util.Log.d("PlayerFragment", "Found ${resolvedActivities.size} apps that can handle video")
        for (resolvedInfo in resolvedActivities) {
            val packageName = resolvedInfo.activityInfo.packageName
            android.util.Log.d("PlayerFragment", "Granting permission to: $packageName")
            requireContext().grantUriPermission(
                packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        if (resolvedActivities.isNotEmpty()) {
            android.util.Log.d("PlayerFragment", "Starting activity with intent")
            try {
                startActivity(intent)
                android.util.Log.d("PlayerFragment", "Activity started successfully")
            } catch (e: Exception) {
                android.util.Log.e("PlayerFragment", "Failed to start activity", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            android.util.Log.e("PlayerFragment", "No apps found to handle video")
            Toast.makeText(requireContext(), "No video player found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCurrentVideo() {
        val currentItem = viewModel.state.value.currentItem ?: return

        // Truncate title and description to 2 lines max (approximately 80 chars per line)
        val maxChars = 160
        val title = if (currentItem.title.length > maxChars) {
            currentItem.title.take(maxChars - 3) + "..."
        } else {
            currentItem.title
        }

        val description = currentItem.description?.let { desc ->
            if (desc.length > maxChars) {
                desc.take(maxChars - 3) + "..."
            } else {
                desc
            }
        } ?: ""

        // Build share message with YouTube URL (since we don't have a domain yet)
        val videoUrl = "https://www.youtube.com/watch?v=${currentItem.streamId}"

        val shareMessage = buildString {
            append(title)
            if (description.isNotEmpty()) {
                append("\n\n")
                append(description)
            }
            append("\n\n")
            append("Watch this video:\n")
            append(videoUrl)
            append("\n\n")
            append("Get Albunyaan Tube app for ad-free Islamic content!")
        }

        // For now, share text only (thumbnail sharing requires downloading the image first)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, shareMessage)
        }

        startActivity(Intent.createChooser(shareIntent, "Share video"))
    }

    private fun toggleFullscreen() {
        val binding = this.binding ?: return
        isFullscreen = !isFullscreen

        if (isFullscreen) {
            // Hide bottom navigation FIRST (this sets it to View.GONE)
            (requireActivity() as? com.albunyaan.tube.ui.MainActivity)?.setBottomNavVisibility(false)

            // Enter fullscreen - Use system UI flags for status bar and system navigation
            // Don't use HIDE_NAVIGATION flag as it conflicts with our custom bottom nav
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )

            // Hide scrollable content
            binding.playerScrollView?.visibility = View.GONE

            // Expand AppBar to fill screen (do this before player container manipulation)
            binding.appBarLayout?.layoutParams?.let { params ->
                if (params is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                binding.appBarLayout?.layoutParams = params
            }

            // Center the player container (FrameLayout) within AppBarLayout
            binding.playerView?.parent?.let { parent ->
                if (parent is android.widget.FrameLayout) {
                    parent.layoutParams?.let { frameParams ->
                        if (frameParams is com.google.android.material.appbar.AppBarLayout.LayoutParams) {
                            frameParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                            frameParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                        }
                        parent.layoutParams = frameParams
                    }
                    // Set gravity on the FrameLayout itself to center its children
                    (parent as? android.widget.FrameLayout)?.foregroundGravity = android.view.Gravity.CENTER
                }
            }

            // Set player to wrap content (aspect ratio) and center it
            binding.playerView?.layoutParams?.let { playerParams ->
                if (playerParams is android.widget.FrameLayout.LayoutParams) {
                    playerParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    playerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                    playerParams.gravity = android.view.Gravity.CENTER
                }
                binding.playerView?.layoutParams = playerParams
            }

            // Make player resize mode to FIT for proper aspect ratio
            binding.playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

            // Update button icon
            binding.fullscreenButton?.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            // Exit fullscreen - Clear system UI flags
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

            // Show bottom navigation FIRST
            (requireActivity() as? com.albunyaan.tube.ui.MainActivity)?.setBottomNavVisibility(true)

            // Show scrollable content
            binding.playerScrollView?.visibility = View.VISIBLE

            // Restore AppBar to normal size
            binding.appBarLayout?.layoutParams?.let { params ->
                if (params is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                binding.appBarLayout?.layoutParams = params
            }

            // Restore player container (FrameLayout) to normal size
            binding.playerView?.parent?.let { parent ->
                if (parent is android.widget.FrameLayout) {
                    parent.layoutParams?.let { frameParams ->
                        if (frameParams is com.google.android.material.appbar.AppBarLayout.LayoutParams) {
                            frameParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            frameParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                        }
                        parent.layoutParams = frameParams
                    }
                    // Remove gravity
                    (parent as? android.widget.FrameLayout)?.foregroundGravity = android.view.Gravity.NO_GRAVITY
                }
            }

            // Restore player to normal size
            binding.playerView?.layoutParams?.let { playerParams ->
                if (playerParams is android.widget.FrameLayout.LayoutParams) {
                    playerParams.height = resources.getDimensionPixelSize(R.dimen.player_portrait_height)
                    playerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                    playerParams.gravity = android.view.Gravity.NO_GRAVITY
                }
                binding.playerView?.layoutParams = playerParams
            }

            // Restore resize mode
            binding.playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

            // Update button icon
            binding.fullscreenButton?.setImageResource(R.drawable.ic_fullscreen)
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
            metadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_SUBTITLE, currentItem.channelName ?: "")

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

            Toast.makeText(
                requireContext(),
                "Casting to ${castSession.castDevice?.friendlyName ?: "device"}",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to cast: ${e.message}",
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
     * Apply quality cap constraints to the track selector for adaptive streams (HLS/DASH).
     * This limits the maximum video resolution ABR can select while still allowing it
     * to drop lower when network conditions require it.
     */
    private fun applyQualityCapToTrackSelector(capHeight: Int) {
        val selector = trackSelector ?: return
        val params = selector.buildUponParameters()
            .setMaxVideoSize(Int.MAX_VALUE, capHeight)  // Cap height, unlimited width
            .setForceHighestSupportedBitrate(false)      // Allow ABR to choose
            .build()
        selector.setParameters(params)
        android.util.Log.d("PlayerFragment", "Track selector: applied quality cap ${capHeight}p")
    }

    /**
     * Clear any quality constraints from the track selector, allowing ABR to choose freely.
     */
    private fun clearTrackSelectorConstraints() {
        val selector = trackSelector ?: return
        val params = selector.buildUponParameters()
            .clearVideoSizeConstraints()
            .setForceHighestSupportedBitrate(false)
            .build()
        selector.setParameters(params)
        android.util.Log.d("PlayerFragment", "Track selector: cleared constraints (ABR free)")
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

        // SYNTHETIC_DASH special case: The factory selects a different video track (video-only)
        // than what's in selection.video (typically muxed). For SYNTHETIC_DASH, we need to check:
        // SYNTHETIC_DASH cache-hit logic:
        // 1. Quality cap changes require rebuild (factory uses userQualityCapHeight for track selection)
        // 2. Track changes (MANUAL or AUTO_RECOVERY) require rebuild
        // 3. AUTO origin with same cap is idempotent (factory's choice is stable)
        val isSyntheticDash = preparedAdaptiveType == MediaSourceResult.AdaptiveType.SYNTHETIC_DASH
        if (isSyntheticDash && !state.audioOnly && !forceProgressive) {
            // Quality cap change - factory uses this for track selection, must rebuild
            if (selection.userQualityCapHeight != preparedQualityCapHeight) {
                return CacheHitResult.Miss
            }
            // MANUAL or AUTO_RECOVERY: Check if the requested track URL differs from what we prepared.
            // MANUAL: User explicitly selected a different quality - must honor their choice.
            // AUTO_RECOVERY: BufferHealthMonitor requested downshift - must apply the change.
            // This makes the check idempotent - once we've rebuilt with the new track,
            // subsequent emissions with the same selection won't force more rebuilds.
            if (selection.selectionOrigin == QualitySelectionOrigin.MANUAL ||
                selection.selectionOrigin == QualitySelectionOrigin.AUTO_RECOVERY) {
                val requestedVideoUrl = selection.video?.url
                if (requestedVideoUrl != null && requestedVideoUrl != preparedSyntheticVideoUrl) {
                    return CacheHitResult.Miss
                }
                // Already prepared with this track - don't rebuild
            }
            // Key matches, same cap, track matches (or AUTO with stable factory choice) - safe to reuse
            return CacheHitResult.Hit(null)
        }

        val urlMatches = when {
            preparedIsAdaptive && wouldUseAdaptive -> {
                // Both are adaptive - manifest URL comparison
                preparedStreamUrl == (resolved.hlsUrl ?: resolved.dashUrl)
            }
            !preparedIsAdaptive && !wouldUseAdaptive -> {
                // Both are progressive - compare against correct URL (video or audio)
                if (state.audioOnly) {
                    preparedStreamUrl == selection.audio.url
                } else {
                    // For video progressive: Compare URLs carefully based on selection origin.
                    //
                    // - AUTO: Factory may apply DEFAULT_INITIAL_QUALITY_HEIGHT and select a different
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

    private companion object {
        private const val DEFAULT_AUDIO_MIME = "audio/mp4"
        private const val DEFAULT_VIDEO_MIME = "video/mp4"
    }
}
