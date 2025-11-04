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
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.databinding.FragmentPlayerBinding
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
 * Phase 8 scaffold for the playback screen. Hooks ExoPlayer with audio-only toggle state managed
 * by [PlayerViewModel]; real media sources will be supplied once backend wiring is available.
 */
class PlayerFragment : Fragment(R.layout.fragment_player) {

    private var binding: FragmentPlayerBinding? = null
    private var player: ExoPlayer? = null
    private val viewModel: PlayerViewModel by viewModels {
        PlayerViewModel.Factory(
            ServiceLocator.providePlayerRepository(),
            ServiceLocator.provideDownloadRepository(),
            ServiceLocator.provideEulaManager(),
            ServiceLocator.provideContentService()
        )
    }
    private val upNextAdapter = UpNextAdapter { item -> viewModel.playItem(item) }
    private var preparedStreamKey: Pair<String, Boolean>? = null
    private var preparedStreamUrl: String? = null // Track the actual URL to detect quality changes
    private lateinit var gestureDetector: GestureDetectorCompat
    private var isFullscreen = false
    private var castContext: com.google.android.gms.cast.framework.CastContext? = null

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

        // Get video ID from arguments
        val videoId = arguments?.getString("videoId")
        if (!videoId.isNullOrEmpty()) {
            viewModel.loadVideo(videoId)
        }

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
            Toast.makeText(requireContext(), "Like feature coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.shareButton?.setOnClickListener {
            shareCurrentVideo()
        }

        binding.audioButton?.setOnClickListener {
            binding.audioOnlyToggle.isChecked = !binding.audioOnlyToggle.isChecked
        }

        // Download button - initial setup (will be overridden by updateDownloadControls)
        binding.downloadButton?.setOnClickListener {
            if (viewModel.state.value.isEulaAccepted) {
                val started = viewModel.downloadCurrent()
                if (!started) {
                    showEulaDialog()
                } else {
                    Toast.makeText(requireContext(), "Download started", Toast.LENGTH_SHORT).show()
                }
            } else {
                showEulaDialog()
            }
        }

        binding.minimizeButton?.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.fullscreenButton?.setOnClickListener {
            toggleFullscreen()
        }

        binding.qualityButton?.setOnClickListener {
            showQualitySelector()
        }

        // Overlay controls are always visible now - no click listener needed

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
        val player = ExoPlayer.Builder(requireContext())
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(com.google.android.exoplayer2.C.WAKE_MODE_NETWORK)
            .build().also { this.player = it }

        binding.playerView.player = player
        player.addListener(viewModel.playerListener)

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
            StreamState.Idle -> binding.playerStatus.text = getString(R.string.player_status_initializing)
            StreamState.Loading -> binding.playerStatus.text = getString(R.string.player_status_resolving)
            is StreamState.Error -> binding.playerStatus.text = getString(streamState.messageRes)
            is StreamState.Ready -> binding.playerStatus.text = when {
                state.audioOnly -> getString(R.string.player_status_audio_only)
                else -> getString(R.string.player_status_video_playing)
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

        if (!state.isEulaAccepted) {
            button.isEnabled = true
            button.setOnClickListener { showEulaDialog() }
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
                    val started = viewModel.downloadCurrent()
                    if (!started) {
                        showEulaDialog()
                    }
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
        val key = streamState.streamId to state.audioOnly

        // Check if we're switching quality (same stream, different audio/video mode)
        val isQualitySwitch = preparedStreamKey != null && preparedStreamKey?.first == key.first && preparedStreamKey != key

        // If it's the exact same stream configuration, don't reload
        if (preparedStreamKey == key) return

        // Save current position for seamless quality switching
        val savedPosition = if (isQualitySwitch) {
            player?.currentPosition ?: 0
        } else {
            0
        }
        val wasPlaying = player?.playWhenReady == true

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

        player?.let {
            it.setMediaSource(mediaSource)
            it.prepare()

            // Restore position for seamless quality switching
            if (isQualitySwitch && savedPosition > 0) {
                it.seekTo(savedPosition)
            }

            it.playWhenReady = wasPlaying
        }
        preparedStreamKey = key
        preparedStreamUrl = streamState.selection.video?.url
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
        val filePath = entry.filePath ?: run {
            Toast.makeText(requireContext(), R.string.download_toast_no_viewer, Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(requireContext(), R.string.download_toast_no_viewer, Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            requireContext(),
            "${BuildConfig.APPLICATION_ID}.downloads.provider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            type = if (entry.request.audioOnly) "audio/*" else "video/*"
        }
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), R.string.download_toast_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEulaDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.eula_dialog_title)
            .setMessage(R.string.eula_dialog_body)
            .setNegativeButton(R.string.eula_dialog_decline, null)
            .setPositiveButton(R.string.eula_dialog_accept) { _, _ ->
                viewModel.acceptEula()
            }
            .show()
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
            // Enter fullscreen
            requireActivity().window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

            // Hide scrollable content
            binding.playerScrollView?.visibility = View.GONE

            // Hide bottom navigation
            (requireActivity() as? com.albunyaan.tube.ui.MainActivity)?.setBottomNavVisibility(false)

            // Expand player to fill screen
            binding.appBarLayout?.layoutParams?.let { params ->
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                binding.appBarLayout?.layoutParams = params
            }

            binding.playerView?.layoutParams?.let { playerParams ->
                playerParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                binding.playerView?.layoutParams = playerParams
            }

            // Update button icon
            binding.fullscreenButton?.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            // Exit fullscreen
            requireActivity().window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

            // Show scrollable content
            binding.playerScrollView?.visibility = View.VISIBLE

            // Show bottom navigation
            (requireActivity() as? com.albunyaan.tube.ui.MainActivity)?.setBottomNavVisibility(true)

            // Restore player to normal size
            binding.appBarLayout?.layoutParams?.let { params ->
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                binding.appBarLayout?.layoutParams = params
            }

            binding.playerView?.layoutParams?.let { playerParams ->
                playerParams.height = (240 * resources.displayMetrics.density).toInt()
                binding.playerView?.layoutParams = playerParams
            }

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

    private fun showCastDeviceSelector() {
        try {
            val castContext = com.google.android.gms.cast.framework.CastContext.getSharedInstance(requireContext())
            val castSession = castContext.sessionManager.currentCastSession

            if (castSession != null && castSession.isConnected) {
                // Already connected, show disconnect option
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Chromecast")
                    .setMessage("Connected to ${castSession.castDevice?.friendlyName ?: "device"}")
                    .setNegativeButton("Disconnect") { _, _ ->
                        castContext.sessionManager.endCurrentSession(true)
                    }
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                // Show MediaRouteButton-like dialog by programmatically triggering route chooser
                Toast.makeText(
                    requireContext(),
                    "Tap the Cast button in the toolbar or enable Cast from your device's Quick Settings",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Chromecast not available: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private companion object {
        private const val DEFAULT_AUDIO_MIME = "audio/mp4"
        private const val DEFAULT_VIDEO_MIME = "video/mp4"
    }
}
