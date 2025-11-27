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
import com.albunyaan.tube.ui.adapters.HomeFeaturedAdapter
import com.albunyaan.tube.ui.adapters.HomePlaylistAdapter
import com.albunyaan.tube.ui.adapters.HomeVideoAdapter
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment(R.layout.fragment_home_new) {

    private var binding: FragmentHomeNewBinding? = null

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var featuredAdapter: HomeFeaturedAdapter
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

        // Postpone card width calculation until the layout has been measured
        view.post { calculateAndSetCardWidths(binding) }
    }

    private fun calculateAndSetCardWidths(binding: FragmentHomeNewBinding) {
        val screenWidth = binding.root.width
        if (screenWidth == 0) return // Not measured yet

        val margin = resources.getDimensionPixelSize(R.dimen.home_horizontal_margin)
        val spacing = resources.getDimensionPixelSize(R.dimen.home_card_spacing)

        // Featured section uses different card widths based on content type
        // Videos/playlists use wider cards, channels use narrower cards with circular avatars
        featuredAdapter.cardWidth = calculateCardWidth(
            screenWidth,
            resources.getInteger(R.integer.home_cards_visible_videos),
            margin,
            spacing
        )
        featuredAdapter.channelCardWidth = calculateCardWidth(
            screenWidth,
            resources.getInteger(R.integer.home_cards_visible_channels),
            margin,
            spacing
        )
        channelAdapter.cardWidth = calculateCardWidth(
            screenWidth,
            resources.getInteger(R.integer.home_cards_visible_channels),
            margin,
            spacing
        )
        playlistAdapter.cardWidth = calculateCardWidth(
            screenWidth,
            resources.getInteger(R.integer.home_cards_visible_playlists),
            margin,
            spacing
        )
        videoAdapter.cardWidth = calculateCardWidth(
            screenWidth,
            resources.getInteger(R.integer.home_cards_visible_videos),
            margin,
            spacing
        )

        // Force rebind of any existing ViewHolders to apply the new cardWidth
        featuredAdapter.notifyDataSetChanged()
        channelAdapter.notifyDataSetChanged()
        playlistAdapter.notifyDataSetChanged()
        videoAdapter.notifyDataSetChanged()
    }

    private fun calculateCardWidth(screenWidth: Int, n: Int, margin: Int, spacing: Int): Int {
        if (n <= 0) return 0
        // The formula from the plan includes a 0.95 multiplier to show a partial peek of the next card
        return ((screenWidth - 2 * margin - (n - 1) * spacing).toFloat() / n * 0.95f).toInt()
    }

    private fun setupAdapters() {
        featuredAdapter = HomeFeaturedAdapter { item ->
            when (item) {
                is ContentItem.Video -> {
                    Log.d(TAG, "Featured video clicked: ${item.title}")
                    findNavController().navigate(
                        R.id.action_global_playerFragment,
                        bundleOf("videoId" to item.id)
                    )
                }
                is ContentItem.Playlist -> {
                    Log.d(TAG, "Featured playlist clicked: ${item.title}")
                    findNavController().navigate(
                        R.id.action_global_playlistDetailFragment,
                        bundleOf(
                            "playlistId" to item.id,
                            "playlistTitle" to item.title,
                            "playlistCategory" to item.category,
                            "playlistCount" to item.itemCount
                        )
                    )
                }
                is ContentItem.Channel -> {
                    Log.d(TAG, "Featured channel clicked: ${item.name}")
                    findNavController().navigate(
                        R.id.action_global_channelDetailFragment,
                        bundleOf("channelId" to item.id, "channelName" to item.name)
                    )
                }
            }
        }

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
        binding.featuredRecyclerView.apply {
            adapter = featuredAdapter
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
        }

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

        // See All click listeners - navigate to respective tabs/screens
        binding.featuredSeeAll.setOnClickListener {
            Log.d(TAG, "Featured See All clicked")
            // Navigate to featured list screen
            findNavController().navigate(R.id.action_homeFragment_to_featuredListFragment)
        }

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

        // Retry button click listeners for error states
        binding.featuredError.retryButton.setOnClickListener {
            Log.d(TAG, "Retry featured clicked")
            viewModel.loadFeatured()
        }

        binding.channelsError.retryButton.setOnClickListener {
            Log.d(TAG, "Retry channels clicked")
            viewModel.loadChannels()
        }

        binding.playlistsError.retryButton.setOnClickListener {
            Log.d(TAG, "Retry playlists clicked")
            viewModel.loadPlaylists()
        }

        binding.videosError.retryButton.setOnClickListener {
            Log.d(TAG, "Retry videos clicked")
            viewModel.loadVideos()
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
        // Observe featured section
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.featuredState.collect { state ->
                when (state) {
                    is HomeViewModel.SectionState.Loading -> {
                        Log.d(TAG, "Loading featured...")
                        binding.featuredSkeleton.root.isVisible = true
                        binding.featuredError.root.isVisible = false
                        binding.featuredRecyclerView.isVisible = false
                        binding.featuredSection.isVisible = true
                    }
                    is HomeViewModel.SectionState.Success -> {
                        Log.d(TAG, "Featured loaded: ${state.items.size}")
                        binding.featuredSkeleton.root.isVisible = false
                        binding.featuredError.root.isVisible = false
                        featuredAdapter.submitList(state.items)

                        if (state.items.isNotEmpty()) {
                            binding.featuredSection.isVisible = true
                            binding.featuredRecyclerView.isVisible = true
                        } else {
                            binding.featuredSection.isVisible = false
                        }
                    }
                    is HomeViewModel.SectionState.Error -> {
                        Log.e(TAG, "Error loading featured: ${state.message}")
                        binding.featuredSkeleton.root.isVisible = false
                        binding.featuredRecyclerView.isVisible = false
                        binding.featuredError.root.isVisible = true
                        binding.featuredSection.isVisible = true
                        featuredAdapter.submitList(emptyList())
                    }
                }
            }
        }

        // Observe channels section
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.channelsState.collect { state ->
                when (state) {
                    is HomeViewModel.SectionState.Loading -> {
                        Log.d(TAG, "Loading channels...")
                        binding.channelsSkeleton.root.isVisible = true
                        binding.channelsError.root.isVisible = false
                        binding.channelsRecyclerView.isVisible = false
                        binding.channelsSection.isVisible = true
                    }
                    is HomeViewModel.SectionState.Success -> {
                        Log.d(TAG, "Channels loaded: ${state.items.size}")
                        binding.channelsSkeleton.root.isVisible = false
                        binding.channelsError.root.isVisible = false
                        channelAdapter.submitList(state.items)

                        if (state.items.isNotEmpty()) {
                            binding.channelsSection.isVisible = true
                            binding.channelsRecyclerView.isVisible = true
                        } else {
                            binding.channelsSection.isVisible = false
                        }
                    }
                    is HomeViewModel.SectionState.Error -> {
                        Log.e(TAG, "Error loading channels: ${state.message}")
                        binding.channelsSkeleton.root.isVisible = false
                        binding.channelsRecyclerView.isVisible = false
                        binding.channelsError.root.isVisible = true
                        binding.channelsSection.isVisible = true
                        channelAdapter.submitList(emptyList())
                    }
                }
            }
        }

        // Observe playlists section
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playlistsState.collect { state ->
                when (state) {
                    is HomeViewModel.SectionState.Loading -> {
                        Log.d(TAG, "Loading playlists...")
                        binding.playlistsSkeleton.root.isVisible = true
                        binding.playlistsError.root.isVisible = false
                        binding.playlistsRecyclerView.isVisible = false
                        binding.playlistsSection.isVisible = true
                    }
                    is HomeViewModel.SectionState.Success -> {
                        Log.d(TAG, "Playlists loaded: ${state.items.size}")
                        binding.playlistsSkeleton.root.isVisible = false
                        binding.playlistsError.root.isVisible = false
                        playlistAdapter.submitList(state.items)

                        if (state.items.isNotEmpty()) {
                            binding.playlistsSection.isVisible = true
                            binding.playlistsRecyclerView.isVisible = true
                        } else {
                            binding.playlistsSection.isVisible = false
                        }
                    }
                    is HomeViewModel.SectionState.Error -> {
                        Log.e(TAG, "Error loading playlists: ${state.message}")
                        binding.playlistsSkeleton.root.isVisible = false
                        binding.playlistsRecyclerView.isVisible = false
                        binding.playlistsError.root.isVisible = true
                        binding.playlistsSection.isVisible = true
                        playlistAdapter.submitList(emptyList())
                    }
                }
            }
        }

        // Observe videos section
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.videosState.collect { state ->
                when (state) {
                    is HomeViewModel.SectionState.Loading -> {
                        Log.d(TAG, "Loading videos...")
                        binding.videosSkeleton.root.isVisible = true
                        binding.videosError.root.isVisible = false
                        binding.videosRecyclerView.isVisible = false
                        binding.videosSection.isVisible = true
                    }
                    is HomeViewModel.SectionState.Success -> {
                        Log.d(TAG, "Videos loaded: ${state.items.size}")
                        binding.videosSkeleton.root.isVisible = false
                        binding.videosError.root.isVisible = false
                        videoAdapter.submitList(state.items)

                        if (state.items.isNotEmpty()) {
                            binding.videosSection.isVisible = true
                            binding.videosRecyclerView.isVisible = true
                        } else {
                            binding.videosSection.isVisible = false
                        }
                    }
                    is HomeViewModel.SectionState.Error -> {
                        Log.e(TAG, "Error loading videos: ${state.message}")
                        binding.videosSkeleton.root.isVisible = false
                        binding.videosRecyclerView.isVisible = false
                        binding.videosError.root.isVisible = true
                        binding.videosSection.isVisible = true
                        videoAdapter.submitList(emptyList())
                    }
                }
            }
        }
    }

    private fun navigateToTab(destinationId: Int) {
        // HomeFragment is inside NavHostFragment which is inside MainShellFragment
        // The NavigationBarView (BottomNav or NavigationRail) is in MainShellFragment's view
        // parentFragment is the NavHostFragment, and its parentFragment is MainShellFragment
        val mainShellFragment = parentFragment?.parentFragment
        val navigationView = mainShellFragment?.view?.findViewById<com.google.android.material.navigation.NavigationBarView>(
            R.id.mainBottomNav
        )
        if (navigationView != null) {
            navigationView.selectedItemId = destinationId
        } else {
            Log.e(TAG, "Could not find mainBottomNav - parentFragment: $parentFragment, mainShell: $mainShellFragment")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        private const val TAG = "HomeFragment"
    }
}
