package com.albunyaan.tube.ui.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import com.albunyaan.tube.data.extractor.PlaybackSelection
import com.albunyaan.tube.data.extractor.SubtitleTrack
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadStatus
import com.albunyaan.tube.player.PlaybackService
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * P3-T4: PlayerFragment with Hilt DI
 *
 * Phase 8 scaffold for the playback screen. Hooks ExoPlayer with audio-only toggle state managed
 * by [PlayerViewModel]; real media sources will be supplied once backend wiring is available.
 */
@AndroidEntryPoint
class PlayerFragment : Fragment(R.layout.fragment_player) {

    private var binding: FragmentPlayerBinding? = null
    private var player: ExoPlayer? = null
    private val viewModel: PlayerViewModel by viewModels()
    private val upNextAdapter = UpNextAdapter { item -> viewModel.playItem(item) }
    private var preparedStreamKey: Pair<String, Boolean>? = null
    private var preparedStreamUrl: String? = null // Track the actual URL to detect quality changes
    private lateinit var gestureDetector: GestureDetectorCompat
    private var isFullscreen = false
    private var castContext: com.google.android.gms.cast.framework.CastContext? = null
    private var exoNextButton: View? = null
    private var exoPrevButton: View? = null

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

        // Extract metadata from nav args for fast-path playback (no backend fetch needed)
        val videoTitle = arguments?.getString("title") ?: "Video"
        val channelName = arguments?.getString("channelName") ?: ""
        val thumbnailUrl = arguments?.getString("thumbnailUrl")
        val description = arguments?.getString("description")
        val durationSeconds = arguments?.getInt("durationSeconds", 0) ?: 0
        val viewCount = arguments?.getLong("viewCount", -1L)?.takeIf { it >= 0 }

        when {
            !playlistId.isNullOrEmpty() -> {
                // Playlist playback mode - load playlist and start from specified index
                viewModel.loadPlaylist(playlistId, startIndex, shuffled)
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

        // Access ExoPlayer's internal navigation buttons (requires ExoPlayer 2.x)
        // Note: These IDs are part of ExoPlayer's default player controls layout
        exoNextButton = binding.playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_next)
        exoPrevButton = binding.playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_prev)

        if (exoNextButton == null || exoPrevButton == null) {
            android.util.Log.w("PlayerFragment", "ExoPlayer navigation buttons not found - check ExoPlayer version compatibility")
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
        binding.playerAudioOnlyButton?.setOnClickListener {
            viewModel.retryWithAudioOnly()
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
        PlaybackService.start(requireContext())
        player?.playWhenReady = true
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
        // Clean up player resources - ExoPlayer handles its own callback cleanup during release
        binding?.playerView?.player = null
        preparedStreamKey = null
        preparedStreamUrl = null
        player?.release()
        player = null
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
        // Configure load control for better buffering and faster startup
        val loadControl = com.google.android.exoplayer2.DefaultLoadControl.Builder()
            // Increase buffer for smoother playback (default is 50s max)
            .setBufferDurationsMs(
                /* minBufferMs= */ 2500,         // Start playback after 2.5s (faster startup)
                /* maxBufferMs= */ 60000,        // Buffer up to 60s ahead
                /* bufferForPlaybackMs= */ 1500, // Resume after rebuffer at 1.5s (faster)
                /* bufferForPlaybackAfterRebufferMs= */ 2500 // Faster recovery from stalls
            )
            // Prioritize minimum rebuffer time over memory
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(requireContext())
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(com.google.android.exoplayer2.C.WAKE_MODE_NETWORK)
            // Enable seek back/forward optimizations
            .setSeekBackIncrementMs(10000) // 10s back
            .setSeekForwardIncrementMs(10000) // 10s forward
            .build().also { this.player = it }

        // Optimize playback parameters for smoother experience
        player.playWhenReady = false // Don't auto-play until ready

        binding.playerView.player = player
        player.addListener(viewModel.playerListener)

        // Add listener to auto-hide controls when playback starts and handle errors
        player.addListener(object : com.google.android.exoplayer2.Player.Listener {
            private var hasAutoHidden = false
            private var retryCount = 0
            private val maxRetries = 2

            // Stall detection
            private var bufferingStartTime: Long = 0L
            private var stallCheckJob: kotlinx.coroutines.Job? = null
            private val stallThresholdMs = 8000L // 8s of buffering = stall
            private var hasAutoDowngraded = false

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                android.util.Log.d("PlayerFragment", "onIsPlayingChanged: isPlaying=$isPlaying, hasAutoHidden=$hasAutoHidden")
                if (isPlaying && !hasAutoHidden) {
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
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                android.util.Log.d("PlayerFragment", "onPlaybackStateChanged: state=$playbackState")
                // When a new video loads, reset the auto-hide flag and stall tracking
                if (playbackState == com.google.android.exoplayer2.Player.STATE_IDLE) {
                    hasAutoHidden = false
                    hasAutoDowngraded = false
                    bufferingStartTime = 0L
                    stallCheckJob?.cancel()
                }

                // Stall detection: track buffering duration
                when (playbackState) {
                    com.google.android.exoplayer2.Player.STATE_BUFFERING -> {
                        if (bufferingStartTime == 0L) {
                            bufferingStartTime = System.currentTimeMillis()
                            android.util.Log.d("PlayerFragment", "Buffering started")

                            // Start stall check job
                            stallCheckJob?.cancel()
                            stallCheckJob = viewLifecycleOwner.lifecycleScope.launch {
                                kotlinx.coroutines.delay(stallThresholdMs)
                                // Still buffering after threshold - consider stalled
                                if (player?.playbackState == com.google.android.exoplayer2.Player.STATE_BUFFERING && !hasAutoDowngraded) {
                                    val bufferDuration = System.currentTimeMillis() - bufferingStartTime
                                    android.util.Log.w("PlayerFragment", "Stall detected: buffering for ${bufferDuration}ms")

                                    // Auto-switch to audio-only if not already
                                    val state = viewModel.state.value
                                    if (!state.audioOnly) {
                                        hasAutoDowngraded = true
                                        android.util.Log.d("PlayerFragment", "Auto-downgrading to audio-only due to stall")
                                        context?.let { ctx ->
                                            Toast.makeText(ctx, R.string.player_auto_audio_stall, Toast.LENGTH_SHORT).show()
                                        }
                                        viewModel.setAudioOnly(true)
                                    }
                                }
                            }
                        }
                    }
                    com.google.android.exoplayer2.Player.STATE_READY -> {
                        if (bufferingStartTime > 0L) {
                            val bufferDuration = System.currentTimeMillis() - bufferingStartTime
                            android.util.Log.d("PlayerFragment", "Buffering ended after ${bufferDuration}ms")
                        }
                        bufferingStartTime = 0L
                        stallCheckJob?.cancel()
                    }
                    com.google.android.exoplayer2.Player.STATE_ENDED -> {
                        bufferingStartTime = 0L
                        stallCheckJob?.cancel()
                        val advanced = viewModel.markCurrentComplete()
                        if (advanced) {
                            player?.playWhenReady = true
                            hasAutoDowngraded = false // Reset for next video
                        }
                    }
                }
            }

            override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                android.util.Log.e("PlayerFragment", "Player error: ${error.errorCodeName}, retryCount=$retryCount", error)

                // Handle different error types
                when (error.errorCode) {
                    com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
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
                            // Use safe context access to prevent crash if fragment is detached
                            context?.let { ctx ->
                                Toast.makeText(ctx, R.string.player_stream_error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> {
                        // Stream URL expired or malformed - clear cache and retry once
                        if (retryCount < 1) {
                            retryCount++
                            android.util.Log.d("PlayerFragment", "Stream parsing failed - clearing cache and retrying")
                            preparedStreamKey = null
                            preparedStreamUrl = null
                            // Reload the video to get fresh URLs, preserving all metadata
                            viewModel.state.value.currentItem?.let { item ->
                                viewModel.loadVideo(
                                    videoId = item.streamId,
                                    title = item.title,
                                    channelName = item.channelName,
                                    thumbnailUrl = item.thumbnailUrl,
                                    description = item.description,
                                    durationSeconds = item.durationSeconds,
                                    viewCount = item.viewCount
                                )
                            }
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

            // Sync custom overlay controls with ExoPlayer controller visibility
            setControllerVisibilityListener(
                com.google.android.exoplayer2.ui.StyledPlayerView.ControllerVisibilityListener { visibility ->
                    android.util.Log.d("PlayerFragment", "Controller visibility changed: $visibility")
                    binding.playerOverlayControls?.visibility = visibility

                    // Re-apply our custom prev/next button states when controller becomes visible
                    // This prevents ExoPlayer from overriding our button configuration
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
        }
    }

    private fun updatePlayerStatus(binding: FragmentPlayerBinding, state: PlayerState) {
        when (val streamState = state.streamState) {
            StreamState.Idle -> {
                binding.playerStatus.text = getString(R.string.player_status_initializing)
                binding.playerErrorOverlay?.visibility = View.GONE
            }
            StreamState.Loading -> {
                binding.playerStatus.text = if (state.retryCount > 0) {
                    getString(R.string.player_retrying, state.retryCount + 1, 3)
                } else {
                    getString(R.string.player_status_resolving)
                }
                binding.playerErrorOverlay?.visibility = View.GONE
            }
            is StreamState.Error -> {
                binding.playerStatus.text = getString(streamState.messageRes)
                // Show error overlay with retry options
                binding.playerErrorOverlay?.visibility = View.VISIBLE
                binding.playerErrorMessage?.text = getString(streamState.messageRes)
            }
            is StreamState.Ready -> {
                binding.playerStatus.text = when {
                    state.audioOnly -> getString(R.string.player_status_audio_only)
                    else -> getString(R.string.player_status_video_playing)
                }
                binding.playerErrorOverlay?.visibility = View.GONE
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

    private fun maybePrepareStream(state: PlayerState) {
        val streamState = state.streamState
        if (streamState !is StreamState.Ready) {
            if (streamState is StreamState.Error) {
                player?.stop()
                preparedStreamKey = null
                preparedStreamUrl = null
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
        // Track the actual video URL to detect quality changes
        val currentVideoUrl = streamState.selection.video?.url

        // Check if we need to rebuild: same stream but different quality or audio mode
        val isSameStream = preparedStreamKey?.first == key.first
        val isQualityChange = isSameStream && preparedStreamUrl != currentVideoUrl
        val isAudioModeChange = isSameStream && preparedStreamKey?.second != key.second
        val isQualitySwitch = isQualityChange || isAudioModeChange

        // If it's the exact same stream AND same video URL, don't reload
        if (preparedStreamKey == key && preparedStreamUrl == currentVideoUrl) {
            android.util.Log.d("PlayerFragment", "Stream already prepared with same URL, skipping")
            return
        }

        android.util.Log.d("PlayerFragment", "Preparing stream: id=${key.first}, audioOnly=${key.second}, url=$currentVideoUrl, isQualitySwitch=$isQualitySwitch")

        // Save current position for seamless quality switching
        val savedPosition = if (isQualitySwitch) {
            currentPlayer.currentPosition
        } else {
            0
        }
        val wasPlaying = currentPlayer.playWhenReady
        // For quality switches: preserve user's play/pause state
        // For new videos: auto-play (standard video player UX - user selected a video to watch)
        val shouldPlay = if (isQualitySwitch) wasPlaying else true

        // Create multi-quality MediaSource from resolved streams
        val mediaSource = try {
            val selectedQuality = streamState.selection.video
            mediaSourceFactory.createMediaSource(
                resolved = streamState.selection.resolved,
                audioOnly = state.audioOnly,
                selectedQuality = selectedQuality
            )
        } catch (e: Exception) {
            // Fallback to single quality if multi-quality fails
            val (url, mimeType) = selectTrack(streamState.selection, state.audioOnly) ?: return
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMimeType(mimeType)
                .build()
            com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(
                com.google.android.exoplayer2.upstream.DefaultDataSource.Factory(requireContext())
            ).createMediaSource(mediaItem)
        }

        try {
            currentPlayer.setMediaSource(mediaSource)
            currentPlayer.prepare()

            // Restore position for seamless quality switching
            if (isQualitySwitch && savedPosition > 0) {
                currentPlayer.seekTo(savedPosition)
            }

            currentPlayer.playWhenReady = shouldPlay
            preparedStreamKey = key
            preparedStreamUrl = streamState.selection.video?.url
            android.util.Log.d("PlayerFragment", "Stream prepared successfully: ${streamState.streamId}")
        } catch (e: Exception) {
            android.util.Log.e("PlayerFragment", "Failed to prepare stream: ${e.message}", e)
            preparedStreamKey = null
            preparedStreamUrl = null
        }
    }

    private fun selectTrack(selection: PlaybackSelection, audioOnly: Boolean): Pair<String, String>? {
        return if (audioOnly) {
            val audio = selection.audio
            audio.url to (audio.mimeType ?: DEFAULT_AUDIO_MIME)
        } else {
            val video = selection.video
            if (video != null) {
                video.url to (video.mimeType ?: DEFAULT_VIDEO_MIME)
            } else {
                val audio = selection.audio
                audio.url to (audio.mimeType ?: DEFAULT_AUDIO_MIME)
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
            binding.playerView?.resizeMode = com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

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
            binding.playerView?.resizeMode = com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

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
            val (videoUrl, _) = selectTrack(streamState.selection, viewModel.state.value.audioOnly) ?: return

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

    private companion object {
        private const val DEFAULT_AUDIO_MIME = "audio/mp4"
        private const val DEFAULT_VIDEO_MIME = "video/mp4"
    }
}
