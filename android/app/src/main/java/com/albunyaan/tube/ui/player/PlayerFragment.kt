package com.albunyaan.tube.ui.player

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentPlayerBinding
import com.albunyaan.tube.data.extractor.PlaybackSelection
import com.albunyaan.tube.player.PlaybackService
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ExoPlayer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phase 8 scaffold for the playback screen. Hooks ExoPlayer with audio-only toggle state managed
 * by [PlayerViewModel]; real media sources will be supplied once backend wiring is available.
 */
class PlayerFragment : Fragment(R.layout.fragment_player) {

    private var binding: FragmentPlayerBinding? = null
    private var player: ExoPlayer? = null
    private val viewModel: PlayerViewModel by viewModels {
        PlayerViewModel.Factory(ServiceLocator.providePlayerRepository())
    }
    private val upNextAdapter = UpNextAdapter { item -> viewModel.playItem(item) }
    private var preparedStreamKey: Pair<String, Boolean>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        val binding = FragmentPlayerBinding.bind(view).also { binding = it }
        binding.audioOnlyToggle.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAudioOnly(isChecked)
        }
        binding.completeButton.setOnClickListener {
            viewModel.markCurrentComplete()
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
    }

    private fun collectViewState(binding: FragmentPlayerBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                binding.audioOnlyToggle.isChecked = state.audioOnly
                updatePlayerStatus(binding, state)
                val currentItem = state.currentItem
                binding.currentlyPlaying.text = currentItem?.let {
                    getString(R.string.player_current_item, it.title)
                } ?: getString(R.string.player_no_current_item)
                binding.completeButton.isEnabled = state.streamState is StreamState.Ready
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
            R.id.action_enter_pip -> {
                enterPictureInPicture()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        binding?.audioOnlyToggle?.isEnabled = !isInPictureInPictureMode
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder().build()
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

    private companion object {
        private const val DEFAULT_AUDIO_MIME = "audio/mp4"
        private const val DEFAULT_VIDEO_MIME = "video/mp4"
    }
}
