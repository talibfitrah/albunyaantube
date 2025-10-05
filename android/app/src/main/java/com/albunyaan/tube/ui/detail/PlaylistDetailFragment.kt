package com.albunyaan.tube.ui.detail

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.databinding.FragmentPlaylistDetailBinding
import com.albunyaan.tube.ui.adapters.VideoGridAdapter
import kotlinx.coroutines.launch

class PlaylistDetailFragment : Fragment(R.layout.fragment_playlist_detail) {

    private var binding: FragmentPlaylistDetailBinding? = null

    private val playlistId: String by lazy { arguments?.getString("playlistId").orEmpty() }
    private val playlistTitleArg: String? by lazy { arguments?.getString("playlistTitle") }
    private val playlistCategoryArg: String? by lazy { arguments?.getString("playlistCategory") }
    private val playlistCount: Int by lazy { arguments?.getInt("playlistCount", 0) ?: 0 }
    private val downloadPolicy: DownloadPolicy by lazy {
        val policyStr = arguments?.getString("downloadPolicy") ?: DownloadPolicy.ENABLED.name
        try {
            DownloadPolicy.valueOf(policyStr)
        } catch (e: Exception) {
            DownloadPolicy.ENABLED
        }
    }
    private val isExcluded: Boolean by lazy { arguments?.getBoolean("excluded", false) ?: false }

    private val viewModel: PlaylistDetailViewModel by viewModels {
        PlaylistDetailViewModel.Factory(
            ServiceLocator.provideContentService(),
            playlistId
        )
    }

    private lateinit var videoAdapter: VideoGridAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentPlaylistDetailBinding.bind(view)

        setupUI()
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupUI() {
        binding?.apply {
            // Setup toolbar
            toolbar.setNavigationOnClickListener {
                findNavController().navigateUp()
            }

            // Show exclusion banner if needed
            exclusionBanner.isVisible = isExcluded

            // Configure download button
            configureDownloadButton(downloadPolicy, isExcluded)
        }
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoGridAdapter { video ->
            Log.d(TAG, "Video clicked: ${video.title}")
            // Navigate to player
            findNavController().navigate(
                R.id.action_global_playerFragment,
                Bundle().apply {
                    putString("videoId", video.id)
                    putString("playlistId", playlistId)
                }
            )
        }

        binding?.videosRecyclerView?.apply {
            adapter = videoAdapter
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playlistState.collect { state ->
                when (state) {
                    is PlaylistDetailViewModel.PlaylistState.Loading -> {
                        Log.d(TAG, "Loading playlist details...")
                        binding?.apply {
                            progressBar.isVisible = true
                            contentContainer.isVisible = false
                            errorText.isVisible = false
                        }
                    }
                    is PlaylistDetailViewModel.PlaylistState.Success -> {
                        Log.d(TAG, "Playlist loaded: ${state.playlist.title}")
                        binding?.apply {
                            progressBar.isVisible = false
                            contentContainer.isVisible = true
                            errorText.isVisible = false

                            // Update toolbar and content
                            toolbar.title = state.playlist.title
                            playlistTitle.text = state.playlist.title
                            playlistCategory.text = state.playlist.category
                            playlistItemCount.text = getString(
                                R.string.playlist_detail_item_count,
                                state.playlist.itemCount
                            )

                            if (!state.playlist.description.isNullOrBlank()) {
                                playlistDescription.text = state.playlist.description
                                playlistDescription.isVisible = true
                            } else {
                                playlistDescription.isVisible = false
                            }

                            // Set hero initial
                            heroInitial.text = state.playlist.title.firstOrNull()
                                ?.uppercaseChar()?.toString() ?: "P"
                        }
                    }
                    is PlaylistDetailViewModel.PlaylistState.Error -> {
                        Log.e(TAG, "Error loading playlist: ${state.message}")
                        binding?.apply {
                            progressBar.isVisible = false
                            contentContainer.isVisible = false
                            errorText.isVisible = true
                            errorText.text = state.message
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.videosState.collect { state ->
                when (state) {
                    is PlaylistDetailViewModel.VideosState.Loading -> {
                        Log.d(TAG, "Loading videos...")
                        binding?.videosProgressBar?.isVisible = true
                    }
                    is PlaylistDetailViewModel.VideosState.Success -> {
                        Log.d(TAG, "Videos loaded: ${state.videos.size}")
                        binding?.videosProgressBar?.isVisible = false
                        videoAdapter.submitList(state.videos)
                    }
                    is PlaylistDetailViewModel.VideosState.Error -> {
                        Log.e(TAG, "Error loading videos: ${state.message}")
                        binding?.videosProgressBar?.isVisible = false
                    }
                }
            }
        }
    }

    private fun configureDownloadButton(policy: DownloadPolicy, excluded: Boolean) {
        val button = binding?.downloadButton ?: return
        when (policy) {
            DownloadPolicy.ENABLED -> {
                button.text = getString(R.string.playlist_detail_download)
                button.isEnabled = !excluded
            }
            DownloadPolicy.QUEUED -> {
                button.text = getString(R.string.playlist_detail_downloading)
                button.isEnabled = false
            }
            DownloadPolicy.DISABLED -> {
                button.text = getString(R.string.playlist_detail_download_disabled)
                button.isEnabled = false
            }
        }

        button.setOnClickListener {
            Log.d(TAG, "Download button clicked for playlist: $playlistId")
            // TODO: Implement download functionality
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "PlaylistDetailFragment"
        const val ARG_PLAYLIST_ID = "playlistId"
        const val ARG_PLAYLIST_TITLE = "playlistTitle"
        const val ARG_PLAYLIST_CATEGORY = "playlistCategory"
        const val ARG_PLAYLIST_COUNT = "playlistCount"
        const val ARG_DOWNLOAD_POLICY = "downloadPolicy"
        const val ARG_EXCLUDED = "excluded"
    }
}

enum class DownloadPolicy { ENABLED, QUEUED, DISABLED }
