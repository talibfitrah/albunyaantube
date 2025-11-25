package com.albunyaan.tube.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import dagger.hilt.android.AndroidEntryPoint
import com.albunyaan.tube.databinding.FragmentHomeNewBinding
import com.albunyaan.tube.ui.adapters.HomeChannelAdapter
import com.albunyaan.tube.ui.adapters.HomePlaylistAdapter
import com.albunyaan.tube.ui.adapters.HomeVideoAdapter
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment(R.layout.fragment_home_new) {

    private var binding: FragmentHomeNewBinding? = null

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var channelAdapter: HomeChannelAdapter
    private lateinit var playlistAdapter: HomePlaylistAdapter
    private lateinit var videoAdapter: HomeVideoAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeNewBinding.bind(view).also { this.binding = it }

        setupAdapters()
        setupRecyclerViews(binding)
        setupClickListeners(binding)
        observeViewModel(binding)
    }

    private fun setupAdapters() {
        channelAdapter = HomeChannelAdapter { channel ->
            Log.d(TAG, "Channel clicked: ${channel.name}")
            findNavController().navigate(
                R.id.action_global_channelDetailFragment,
                bundleOf("channelId" to channel.id, "channelName" to channel.name)
            )
        }

        playlistAdapter = HomePlaylistAdapter { playlist ->
            Log.d(TAG, "Playlist clicked: ${playlist.title}")
            findNavController().navigate(
                R.id.action_global_playlistDetailFragment,
                bundleOf(
                    "playlistId" to playlist.id,
                    "playlistTitle" to playlist.title,
                    "playlistCategory" to playlist.category,
                    "playlistCount" to playlist.itemCount
                )
            )
        }

        videoAdapter = HomeVideoAdapter { video ->
            Log.d(TAG, "Video clicked: ${video.title}")
            findNavController().navigate(
                R.id.action_global_playerFragment,
                bundleOf("videoId" to video.id)
            )
        }
    }

    private fun setupRecyclerViews(binding: FragmentHomeNewBinding) {
        binding.channelsRecyclerView.apply {
            adapter = channelAdapter
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
        }

        binding.playlistsRecyclerView.apply {
            adapter = playlistAdapter
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
        }

        binding.videosRecyclerView.apply {
            adapter = videoAdapter
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
        }
    }

    private fun setupClickListeners(binding: FragmentHomeNewBinding) {
        // Category pill click listener
        binding.categoryPillCard.setOnClickListener {
            Log.d(TAG, "Category pill clicked")
            findNavController().navigate(R.id.action_homeFragment_to_categoriesFragment)
        }

        // See All click listeners - navigate to respective tabs
        binding.channelsSeeAll.setOnClickListener {
            Log.d(TAG, "Channels See All clicked")
            navigateToTab(R.id.channelsFragment)
        }

        binding.playlistsSeeAll.setOnClickListener {
            Log.d(TAG, "Playlists See All clicked")
            navigateToTab(R.id.playlistsFragment)
        }

        binding.videosSeeAll.setOnClickListener {
            Log.d(TAG, "Videos See All clicked")
            navigateToTab(R.id.videosFragment)
        }

        // Search button
        binding.searchButton.setOnClickListener {
            Log.d(TAG, "Search clicked")
            findNavController().navigate(R.id.searchFragment)
        }

        // Menu button
        binding.menuButton.setOnClickListener {
            Log.d(TAG, "Menu clicked")
            findNavController().navigate(R.id.settingsFragment)
        }
    }

    private fun observeViewModel(binding: FragmentHomeNewBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.homeContent.collect { state ->
                when (state) {
                    is HomeViewModel.HomeContentState.Loading -> {
                        Log.d(TAG, "Loading home content...")
                        // You could show a loading indicator here
                    }
                    is HomeViewModel.HomeContentState.Success -> {
                        Log.d(TAG, "Home content loaded: " +
                                "${state.channels.size} channels, " +
                                "${state.playlists.size} playlists, " +
                                "${state.videos.size} videos")

                        channelAdapter.submitList(state.channels)
                        playlistAdapter.submitList(state.playlists)
                        videoAdapter.submitList(state.videos)

                        // Show/hide sections based on content
                        binding.channelsRecyclerView.isVisible = state.channels.isNotEmpty()
                        binding.playlistsRecyclerView.isVisible = state.playlists.isNotEmpty()
                        binding.videosRecyclerView.isVisible = state.videos.isNotEmpty()
                    }
                    is HomeViewModel.HomeContentState.Error -> {
                        Log.e(TAG, "Error loading home content: ${state.message}")
                        // You could show an error message here
                    }
                }
            }
        }
    }

    private fun navigateToTab(destinationId: Int) {
        val bottomNav = requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.mainBottomNav
        )
        bottomNav?.selectedItemId = destinationId
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        private const val TAG = "HomeFragment"
    }
}
