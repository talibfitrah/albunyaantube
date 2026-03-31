package com.albunyaan.tube.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.HomeSection
import com.albunyaan.tube.databinding.FragmentFeaturedListBinding
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.player.StreamPrefetchService
import com.albunyaan.tube.ui.adapters.FeaturedListAdapter
import com.albunyaan.tube.ui.adapters.HomeSectionAdapter
import com.albunyaan.tube.ui.detail.ChannelDetailFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FeaturedListFragment : Fragment(R.layout.fragment_featured_list) {

    @Inject
    lateinit var prefetchService: StreamPrefetchService

    private var binding: FragmentFeaturedListBinding? = null

    private val viewModel: FeaturedListViewModel by viewModels()

    private lateinit var flatAdapter: FeaturedListAdapter
    private lateinit var sectionAdapter: HomeSectionAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentFeaturedListBinding.bind(view)

        setupToolbar()
        setupAdapters()
        setupRecyclerView()
        setupRetryButton()
        observeViewModel()

        viewModel.loadFeatured()
    }

    private fun setupToolbar() {
        binding?.toolbar?.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        val categoryName = arguments?.getString("categoryName")
        if (!categoryName.isNullOrEmpty()) {
            binding?.toolbar?.title = categoryName
        }
    }

    private fun handleItemClick(item: ContentItem) {
        when (item) {
            is ContentItem.Video -> {
                prefetchService.triggerPrefetch(item.id, lifecycleScope)
                findNavController().navigate(
                    R.id.action_global_playerFragment,
                    bundleOf(
                        "videoId" to item.id,
                        "title" to item.title,
                        "channelName" to item.category,
                        "thumbnailUrl" to item.thumbnailUrl,
                        "description" to item.description,
                        "durationSeconds" to item.durationSeconds,
                        "viewCount" to (item.viewCount ?: -1L)
                    )
                )
            }
            is ContentItem.Playlist -> {
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
                findNavController().navigate(
                    R.id.action_global_channelDetailFragment,
                    bundleOf(
                        ChannelDetailFragment.ARG_CHANNEL_ID to item.id,
                        ChannelDetailFragment.ARG_CHANNEL_NAME to item.name
                    )
                )
            }
        }
    }

    private fun setupAdapters() {
        flatAdapter = FeaturedListAdapter { item -> handleItemClick(item) }

        sectionAdapter = HomeSectionAdapter(
            onItemClick = { item -> handleItemClick(item) },
            onSeeAllClick = { section ->
                // Navigate to FeaturedListFragment for this specific subcategory (flat list).
                // Use the destination ID directly instead of an action ID so this works
                // regardless of which fragment we are currently on (HomeFragment or
                // FeaturedListFragment itself).
                val safeContext = context ?: return@HomeSectionAdapter
                if (!isAdded || view == null) return@HomeSectionAdapter
                val locale = LocaleManager.getCurrentLocale(safeContext)
                val localizedName = section.localizedNames?.get(locale.language)
                findNavController().navigate(
                    R.id.featuredListFragment,
                    bundleOf(
                        "categoryId" to section.categoryId,
                        "categoryName" to (localizedName ?: section.categoryName)
                    )
                )
            }
        )

        // Calculate card widths for sections (same logic as HomeFragment)
        binding?.root?.post {
            val screenWidth = binding?.root?.width ?: return@post
            if (screenWidth == 0) return@post
            val margin = resources.getDimensionPixelSize(R.dimen.home_horizontal_margin)
            val spacing = resources.getDimensionPixelSize(R.dimen.home_card_spacing)
            val videoVisible = resources.getInteger(R.integer.home_cards_visible_videos)
            val channelVisible = resources.getInteger(R.integer.home_cards_visible_channels)
            sectionAdapter.videoCardWidth = calculateCardWidth(screenWidth, videoVisible, margin, spacing)
            sectionAdapter.channelCardWidth = calculateCardWidth(screenWidth, channelVisible, margin, spacing)
        }
    }

    private fun setupRecyclerView() {
        binding?.recyclerView?.apply {
            layoutManager = LinearLayoutManager(requireContext())

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    // User manually scrolled down — clear any load-error flag so auto-fill can retry
                    viewModel.clearLoadError()
                    val lm = recyclerView.layoutManager as LinearLayoutManager
                    val totalItemCount = lm.itemCount
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (lastVisible >= totalItemCount - 5 && viewModel.canLoadMore) {
                        viewModel.loadMore()
                    }
                }
            })
        }
    }

    private fun setupRetryButton() {
        binding?.retryButton?.setOnClickListener {
            viewModel.loadFeatured()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is FeaturedListViewModel.FeaturedState.Loading -> {
                            binding?.progressBar?.isVisible = true
                            binding?.errorContainer?.isVisible = false
                            binding?.recyclerView?.isVisible = false
                            binding?.loadingMoreIndicator?.isVisible = false
                        }
                        is FeaturedListViewModel.FeaturedState.Sections -> {
                            binding?.progressBar?.isVisible = false
                            binding?.errorContainer?.isVisible = false
                            binding?.recyclerView?.isVisible = true
                            binding?.loadingMoreIndicator?.isVisible = state.isLoadingMore

                            // Switch to section adapter if not already
                            if (binding?.recyclerView?.adapter !== sectionAdapter) {
                                binding?.recyclerView?.adapter = sectionAdapter
                            }
                            sectionAdapter.submitList(state.sections)

                            // Auto-load more if all items fit on screen (tablet/TV)
                            binding?.recyclerView?.post {
                                if (viewModel.canLoadMore) {
                                    val rv = binding?.recyclerView ?: return@post
                                    if (!rv.canScrollVertically(1)) {
                                        viewModel.loadMore()
                                    }
                                }
                            }
                        }
                        is FeaturedListViewModel.FeaturedState.FlatList -> {
                            binding?.progressBar?.isVisible = false
                            binding?.errorContainer?.isVisible = false
                            binding?.recyclerView?.isVisible = true
                            binding?.loadingMoreIndicator?.isVisible = state.isLoadingMore

                            // Switch to flat adapter if not already
                            if (binding?.recyclerView?.adapter !== flatAdapter) {
                                binding?.recyclerView?.adapter = flatAdapter
                            }
                            flatAdapter.submitList(state.items)

                            binding?.recyclerView?.post {
                                if (viewModel.canLoadMore) {
                                    val rv = binding?.recyclerView ?: return@post
                                    if (!rv.canScrollVertically(1)) {
                                        viewModel.loadMore()
                                    }
                                }
                            }
                        }
                        is FeaturedListViewModel.FeaturedState.Error -> {
                            binding?.progressBar?.isVisible = false
                            binding?.recyclerView?.isVisible = false
                            binding?.errorContainer?.isVisible = true
                            binding?.errorText?.text = state.message
                            binding?.loadingMoreIndicator?.isVisible = false
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun calculateCardWidth(screenWidth: Int, n: Int, margin: Int, spacing: Int): Int {
        if (n <= 0) return 0
        return ((screenWidth - 2 * margin - (n - 1) * spacing).toFloat() / n * 0.98f).toInt()
    }

    companion object {
        private const val TAG = "FeaturedListFragment"
    }
}
