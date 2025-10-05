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
    private lateinit var gestureDetector: GestureDetectorCompat

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        val binding = FragmentPlayerBinding.bind(view).also { binding = it }

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
            val currentItem = viewModel.state.value.currentItem
            if (currentItem != null) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Watch ${currentItem.title} on Albunyaan Tube")
                }
                startActivity(Intent.createChooser(shareIntent, "Share video"))
            }
        }

        binding.audioButton?.setOnClickListener {
            binding.audioOnlyToggle.isChecked = !binding.audioOnlyToggle.isChecked
        }

        setupUpNextList(binding)
        setupPlayer(binding)
        collectViewState(binding)
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.start(requireContext())
        player?.playWhenReady = true
    }

    override fun onStop() {
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroyView() {
        binding?.playerView?.player = null
        preparedStreamKey = null
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

    private fun setupPlayer(binding: FragmentPlayerBinding) {
        val player = ExoPlayer.Builder(requireContext()).build().also { this.player = it }
        binding.playerView.player = player
        player.addListener(viewModel.playerListener)

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
            R.id.action_quality -> {
                showQualitySelector()
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
        val qualities = viewModel.getAvailableQualities()
        if (qualities.isEmpty()) {
            Toast.makeText(requireContext(), "No quality options available", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = qualities.map { it.label }.toTypedArray()
        val currentQuality = viewModel.state.value.streamState
            .let { it as? StreamState.Ready }
            ?.selection?.video?.qualityLabel

        val currentIndex = qualities.indexOfFirst { it.label == currentQuality }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Quality")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                viewModel.selectQuality(qualities[which].track)
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
            }
            return
        }
        val key = streamState.streamId to state.audioOnly
        if (preparedStreamKey == key) return
        val (url, mimeType) = selectTrack(streamState.selection, state.audioOnly) ?: return
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(mimeType)
            .build()
        player?.let {
            it.setMediaItem(mediaItem)
            it.prepare()
            it.playWhenReady = true
        }
        preparedStreamKey = key
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

    private companion object {
        private const val DEFAULT_AUDIO_MIME = "audio/mp4"
        private const val DEFAULT_VIDEO_MIME = "video/mp4"
    }
}
