package com.albunyaan.tube.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.player.StreamPrefetchService
import dagger.hilt.android.AndroidEntryPoint
import com.albunyaan.tube.databinding.FragmentHomeNewBinding
import javax.inject.Inject
import com.albunyaan.tube.ui.adapters.HomeSectionAdapter
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment(R.layout.fragment_home_new) {

    private var binding: FragmentHomeNewBinding? = null

    private val viewModel: HomeViewModel by viewModels()

    @Inject
    lateinit var prefetchService: StreamPrefetchService

    private lateinit var sectionAdapter: HomeSectionAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeNewBinding.bind(view).also { this.binding = it }

        setupAdapter()
        setupRecyclerView(binding)
        setupClickListeners(binding)
        setupScrollListener(binding)
        observeViewModel(binding)

        // Postpone card width calculation until the layout has been measured
        view.post { calculateAndSetCardWidths(binding) }
    }

    private fun calculateAndSetCardWidths(binding: FragmentHomeNewBinding) {
        val screenWidth = binding.root.width
        if (screenWidth == 0) return

        val margin = resources.getDimensionPixelSize(R.dimen.home_horizontal_margin)
        val spacing = resources.getDimensionPixelSize(R.dimen.home_card_spacing)

        sectionAdapter.videoCardWidth = calculateCardWidth(
            screenWidth,
            resources.getInteger(R.integer.home_cards_visible_videos),
            margin,
            spacing
        )
        sectionAdapter.channelCardWidth = calculateCardWidth(
            screenWidth,
            resources.getInteger(R.integer.home_cards_visible_channels),
            margin,
            spacing
        )
    }

    private fun calculateCardWidth(screenWidth: Int, n: Int, margin: Int, spacing: Int): Int {
        if (n <= 0) return 0
        return ((screenWidth - 2 * margin - (n - 1) * spacing).toFloat() / n * 0.95f).toInt()
    }

    private fun setupAdapter() {
        sectionAdapter = HomeSectionAdapter(
            onItemClick = { item -> handleItemClick(item) },
            onSeeAllClick = { section ->
                Log.d(TAG, "See All clicked for category: ${section.categoryName}")
                // Navigate to categories screen for now
                findNavController().navigate(R.id.action_homeFragment_to_categoriesFragment)
            }
        )
    }

    private fun handleItemClick(item: ContentItem) {
        when (item) {
            is ContentItem.Video -> {
                Log.d(TAG, "Video clicked: ${item.title}")
                navigateToPlayer(item)
            }
            is ContentItem.Playlist -> {
                Log.d(TAG, "Playlist clicked: ${item.title}")
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
                Log.d(TAG, "Channel clicked: ${item.name}")
                findNavController().navigate(
                    R.id.action_global_channelDetailFragment,
                    bundleOf("channelId" to item.id, "channelName" to item.name)
                )
            }
        }
    }

    private fun navigateToPlayer(video: ContentItem.Video) {
        prefetchService.triggerPrefetch(video.id, viewLifecycleOwner.lifecycleScope)

        findNavController().navigate(
            R.id.action_global_playerFragment,
            bundleOf(
                "videoId" to video.id,
                "title" to video.title,
                "channelName" to video.category,
                "thumbnailUrl" to video.thumbnailUrl,
                "description" to video.description,
                "durationSeconds" to video.durationSeconds,
                "viewCount" to (video.viewCount ?: -1L)
            )
        )
    }

    private fun setupRecyclerView(binding: FragmentHomeNewBinding) {
        binding.homeSectionsRecyclerView.apply {
            adapter = sectionAdapter
            layoutManager = LinearLayoutManager(context)
            isNestedScrollingEnabled = false
        }
    }

    private fun setupScrollListener(binding: FragmentHomeNewBinding) {
        binding.homeScrollView.setOnScrollChangeListener { v: View, _, scrollY, _, _ ->
            val scrollView = v as androidx.core.widget.NestedScrollView
            val child = scrollView.getChildAt(0)
            if (child != null) {
                val diff = child.height - (scrollView.height + scrollY)
                // Load more when within 300px of the bottom
                if (diff < 300 && viewModel.canLoadMore) {
                    viewModel.loadMoreSections()
                }
            }
        }
    }

    private fun setupClickListeners(binding: FragmentHomeNewBinding) {
        binding.categoryPillCard.setOnClickListener {
            Log.d(TAG, "Category pill clicked")
            findNavController().navigate(R.id.action_homeFragment_to_categoriesFragment)
        }

        binding.homeError.retryButton.setOnClickListener {
            Log.d(TAG, "Retry clicked")
            viewModel.refresh()
        }

        binding.searchButton.setOnClickListener {
            Log.d(TAG, "Search clicked")
            findNavController().navigate(R.id.searchFragment)
        }

        binding.menuButton.setOnClickListener { view ->
            Log.d(TAG, "Menu clicked")
            showOverflowMenu(view)
        }
    }

    private fun observeViewModel(binding: FragmentHomeNewBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.homeState.collect { state ->
                    when (state) {
                        is HomeViewModel.HomeState.Loading -> {
                            Log.d(TAG, "Loading home feed...")
                            binding.homeSkeleton.root.isVisible = true
                            binding.homeError.root.isVisible = false
                            binding.homeEmpty.root.isVisible = false
                            binding.homeSectionsRecyclerView.isVisible = false
                            binding.loadingMoreIndicator.isVisible = false
                        }
                        is HomeViewModel.HomeState.Success -> {
                            Log.d(TAG, "Home feed loaded: ${state.sections.size} sections")
                            binding.homeSkeleton.root.isVisible = false
                            binding.homeError.root.isVisible = false
                            binding.homeEmpty.root.isVisible = false
                            binding.homeSectionsRecyclerView.isVisible = true
                            binding.loadingMoreIndicator.isVisible = state.isLoadingMore

                            sectionAdapter.submitList(state.sections)

                            // Auto-load more if content fits on screen (tablet/TV)
                            binding.homeSectionsRecyclerView.post {
                                if (viewModel.canLoadMore) {
                                    val scrollView = binding.homeScrollView
                                    val child = scrollView.getChildAt(0)
                                    if (child != null && child.height <= scrollView.height) {
                                        viewModel.loadMoreSections()
                                    }
                                }
                            }
                        }
                        is HomeViewModel.HomeState.Error -> {
                            Log.e(TAG, "Error loading home feed: ${state.message}")
                            binding.homeSkeleton.root.isVisible = false
                            binding.homeSectionsRecyclerView.isVisible = false
                            binding.homeEmpty.root.isVisible = false
                            binding.homeError.root.isVisible = true
                            binding.loadingMoreIndicator.isVisible = false
                        }
                        is HomeViewModel.HomeState.Empty -> {
                            Log.d(TAG, "Home feed empty")
                            binding.homeSkeleton.root.isVisible = false
                            binding.homeSectionsRecyclerView.isVisible = false
                            binding.homeError.root.isVisible = false
                            binding.homeEmpty.root.isVisible = true
                            binding.homeEmpty.emptyMessage.setText(R.string.home_empty_content)
                            binding.loadingMoreIndicator.isVisible = false
                        }
                    }
                }
            }
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor, android.view.Gravity.END)
        popup.menuInflater.inflate(R.menu.home_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    findNavController().navigate(R.id.settingsFragment)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        private const val TAG = "HomeFragment"
    }
}
