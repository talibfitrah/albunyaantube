package com.albunyaan.tube.ui.home

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.databinding.FragmentHomeNewBinding
import com.albunyaan.tube.ui.HomeViewModel
import com.albunyaan.tube.ui.adapters.HomeChannelAdapter
import com.albunyaan.tube.ui.adapters.HomePlaylistAdapter
import com.albunyaan.tube.ui.adapters.HomeVideoAdapter
import kotlinx.coroutines.launch

class HomeFragmentNew : Fragment(R.layout.fragment_home_new) {

    private var binding: FragmentHomeNewBinding? = null

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(ServiceLocator.provideContentService())
    }

    private lateinit var channelAdapter: HomeChannelAdapter
    private lateinit var playlistAdapter: HomePlaylistAdapter
    private lateinit var videoAdapter: HomeVideoAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentHomeNewBinding.bind(view)

        // Disable overscroll on the root scroll view to prevent bounce/jump
        binding?.homeScrollView?.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false
        }

        setupAdapters()
        setupUI()
        observeViewModel()
    }

    private fun setupAdapters() {
        channelAdapter = HomeChannelAdapter { channel ->
            Log.d(TAG, "Channel clicked: ${channel.name}")
            navigateToChannelDetail(channel.id, channel.name)
        }

        playlistAdapter = HomePlaylistAdapter { playlist ->
            Log.d(TAG, "Playlist clicked: ${playlist.title}")
            navigateToPlaylistDetail(playlist.id, playlist.title, playlist.category, playlist.itemCount)
        }

        videoAdapter = HomeVideoAdapter { video ->
            Log.d(TAG, "Video clicked: ${video.title}")
            navigateToPlayer(video.id)
        }
    }

    private fun navigateToChannelDetail(channelId: String, channelName: String?) {
        try {
            findNavController().navigate(
                R.id.action_global_channelDetailFragment,
                bundleOf(
                    "channelId" to channelId,
                    "channelName" to channelName
                )
            )
            Log.d(TAG, "Navigated to channel detail: $channelId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate to channel detail", e)
        }
    }

    private fun navigateToPlaylistDetail(playlistId: String, title: String?, category: String?, itemCount: Int) {
        try {
            findNavController().navigate(
                R.id.action_global_playlistDetailFragment,
                bundleOf(
                    "playlistId" to playlistId,
                    "playlistTitle" to title,
                    "playlistCategory" to category,
                    "playlistCount" to itemCount
                )
            )
            Log.d(TAG, "Navigated to playlist detail: $playlistId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate to playlist detail", e)
        }
    }

    private fun navigateToPlayer(videoId: String) {
        try {
            findNavController().navigate(
                R.id.action_global_playerFragment,
                bundleOf("videoId" to videoId)
            )
            Log.d(TAG, "Navigated to player: $videoId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate to player", e)
        }
    }

    private fun setupUI() {
        binding?.apply {
            // Setup horizontal RecyclerViews with adapters
            channelsRecyclerView.apply {
                adapter = channelAdapter
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                setHasFixedSize(true)
            }

            playlistsRecyclerView.apply {
                adapter = playlistAdapter
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                setHasFixedSize(true)
            }

            videosRecyclerView.apply {
                adapter = videoAdapter
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                setHasFixedSize(true)
            }

            // Setup click listeners
            categoryChip.setOnClickListener {
                Log.d(TAG, "Category chip clicked")
                try {
                    val navController = findNavController()
                    Log.d(TAG, "NavController: $navController")
                    Log.d(TAG, "Current destination: ${navController.currentDestination?.label}")
                    navController.navigate(R.id.categoriesFragment)
                    Log.d(TAG, "Navigation to categories successful")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to navigate to categories", e)
                }
            }

            searchButton.setOnClickListener {
                Log.d(TAG, "Search clicked")
                findNavController().navigate(R.id.searchFragment)
            }

            menuButton.setOnClickListener { view ->
                showMenu(view)
            }

            channelsSeeAll.setOnClickListener {
                Log.d(TAG, "Channels See All clicked")
                navigateToTab(R.id.channelsFragment)
            }

            playlistsSeeAll.setOnClickListener {
                Log.d(TAG, "Playlists See All clicked")
                navigateToTab(R.id.playlistsFragment)
            }

            videosSeeAll.setOnClickListener {
                Log.d(TAG, "Videos See All clicked")
                navigateToTab(R.id.videosFragment)
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.homeContent.collect { state ->
                when (state) {
                    is HomeViewModel.HomeContentState.Loading -> {
                        Log.d(TAG, "Loading home content...")
                    }
                    is HomeViewModel.HomeContentState.Success -> {
                        Log.d(TAG, "Home content loaded: " +
                                "${state.channels.size} channels, " +
                                "${state.playlists.size} playlists, " +
                                "${state.videos.size} videos")

                        channelAdapter.submitList(state.channels) {
                            Log.d(TAG, "Channels adapter updated")
                            binding?.channelsRecyclerView?.requestLayout()
                        }
                        playlistAdapter.submitList(state.playlists) {
                            Log.d(TAG, "Playlists adapter updated")
                            binding?.playlistsRecyclerView?.requestLayout()
                        }
                        videoAdapter.submitList(state.videos) {
                            Log.d(TAG, "Videos adapter updated")
                            binding?.videosRecyclerView?.requestLayout()
                        }

                        // Always show RecyclerViews, let empty state be handled by adapters
                        binding?.apply {
                            channelsRecyclerView.isVisible = true
                            playlistsRecyclerView.isVisible = true
                            videosRecyclerView.isVisible = true
                        }
                    }
                    is HomeViewModel.HomeContentState.Error -> {
                        Log.e(TAG, "Error loading home content: ${state.message}")
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

    private fun showMenu(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.home_menu, popup.menu)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_settings -> {
                    findNavController().navigate(R.id.settingsFragment)
                    true
                }
                R.id.action_downloads -> {
                    findNavController().navigate(R.id.downloadsFragment)
                    true
                }
                else -> false
            }
        }

        popup.show()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "HomeFragmentNew"
    }
}

