package com.albunyaan.tube.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.databinding.FragmentSimpleListBinding
import com.albunyaan.tube.ui.adapters.VideoGridAdapter
import com.albunyaan.tube.ui.utils.calculateGridSpanCount
import com.albunyaan.tube.player.StreamPrefetchService
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class VideosFragmentNew : Fragment(R.layout.fragment_simple_list) {

    private var binding: FragmentSimpleListBinding? = null
    private lateinit var adapter: VideoGridAdapter

    @Inject
    @Named("real")
    lateinit var contentService: ContentService

    @Inject
    lateinit var prefetchService: StreamPrefetchService

    @Inject
    lateinit var filterManager: FilterManager

    private val viewModel: ContentListViewModel by viewModels {
        ContentListViewModel.Factory(
            contentService,
            ContentType.VIDEOS
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSimpleListBinding.bind(view)

        setupRecyclerView()
        setupSwipeRefresh()
        observeFilters()
        observeViewModel()
    }

    private fun observeFilters() {
        viewLifecycleOwner.lifecycleScope.launch {
            filterManager.state.collectLatest { filterState ->
                Log.d(TAG, "Filter state changed: category=${filterState.category}")
                viewModel.setFilters(filterState)
                updateFilterChip(filterState.category)
            }
        }
    }

    private fun updateFilterChip(categoryId: String?) {
        binding?.filterChip?.apply {
            if (categoryId.isNullOrEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = getString(R.string.filtering_by_category, categoryId)
                setOnCloseIconClickListener {
                    Log.d(TAG, "Clearing category filter")
                    filterManager.setCategory(null)
                }
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding?.swipeRefresh?.setOnRefreshListener {
            viewModel.refresh()
        }
    }

    private fun setupRecyclerView() {
        adapter = VideoGridAdapter { video ->
            navigateToPlayer(video)
        }

        binding?.recyclerView?.apply {
            // Dynamic grid span calculation for responsive layout
            // Phone: 2 columns, Tablet: 3-4 columns, TV: 4-6 columns
            val spanCount = requireContext().calculateGridSpanCount(itemMinWidthDp = 180)
            layoutManager = GridLayoutManager(requireContext(), spanCount)
            adapter = this@VideosFragmentNew.adapter

            // Infinite scroll listener with Fragment-side guards
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // Only trigger on scroll down
                    if (dy <= 0) return

                    // Fragment-side guard: early exit if cannot load more
                    if (!viewModel.canLoadMore) return

                    val layoutManager = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val totalItems = layoutManager.itemCount
                    val lastVisible = layoutManager.findLastVisibleItemPosition()

                    // Load more when 5 items from bottom (threshold for smooth UX)
                    if (lastVisible >= totalItems - LOAD_MORE_THRESHOLD) {
                        viewModel.loadMore()
                    }
                }
            })
        }
    }

    private fun navigateToPlayer(video: ContentItem.Video) {
        // Start prefetch immediately when user taps - hides latency behind navigation animation
        prefetchService.triggerPrefetch(video.id, viewLifecycleOwner.lifecycleScope)

        val bundle = bundleOf(
            "videoId" to video.id,
            "title" to video.title,
            "channelName" to video.category,
            "thumbnailUrl" to video.thumbnailUrl,
            "description" to video.description,
            "durationSeconds" to video.durationSeconds,
            "viewCount" to (video.viewCount ?: -1L)
        )
        // Navigate using global action since player is now in main_tabs_nav
        findNavController().navigate(R.id.action_global_playerFragment, bundle)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.content.collect { state ->
                when (state) {
                    is ContentListViewModel.ContentState.Loading -> {
                        Log.d(TAG, "Loading videos (type=${state.type})...")
                        when (state.type) {
                            ContentListViewModel.LoadingType.INITIAL,
                            ContentListViewModel.LoadingType.REFRESH -> {
                                // Initial load or pull-to-refresh: show top swipeRefresh indicator
                                binding?.swipeRefresh?.isRefreshing = true
                                binding?.loadingMore?.visibility = View.GONE
                                binding?.emptyState?.visibility = View.GONE
                            }
                            ContentListViewModel.LoadingType.PAGINATION -> {
                                // Infinite scroll: show bottom loadingMore indicator only
                                binding?.swipeRefresh?.isRefreshing = false
                                binding?.loadingMore?.visibility = View.VISIBLE
                            }
                        }
                    }
                    is ContentListViewModel.ContentState.Success -> {
                        val videos = state.items.filterIsInstance<ContentItem.Video>()
                        Log.d(TAG, "Videos loaded: ${videos.size} items, hasMore=${state.hasMoreData}")
                        binding?.let { binding ->
                            binding.swipeRefresh.isRefreshing = false
                            binding.loadingMore.visibility = View.GONE

                            // Surface pagination errors as a transient message while keeping content visible
                            state.paginationError?.let { errorMessage ->
                                val message = if (errorMessage.isBlank()) {
                                    getString(R.string.list_error_title)
                                } else {
                                    errorMessage
                                }
                                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                            }

                            adapter.submitList(videos)

                            if (videos.isEmpty()) {
                                binding.emptyState.visibility = View.VISIBLE
                                binding.recyclerView.visibility = View.GONE
                                binding.emptyIcon.setImageResource(R.drawable.ic_videos)
                                binding.emptyTitle.text = getString(R.string.videos_empty_title)
                                binding.emptySubtitle.text = getString(R.string.videos_empty_subtitle)
                            } else {
                                binding.emptyState.visibility = View.GONE
                                binding.recyclerView.visibility = View.VISIBLE
                            }
                        }
                    }
                    is ContentListViewModel.ContentState.Error -> {
                        binding?.let { binding ->
                            binding.swipeRefresh.isRefreshing = false
                            binding.loadingMore.visibility = View.GONE

                            val message = if (state.message.isBlank()) {
                                getString(R.string.list_error_title)
                            } else {
                                state.message
                            }
                            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()

                            if (adapter.currentList.isEmpty()) {
                                // Initial load/refresh failure with no data - show error empty state
                                binding.emptyState.visibility = View.VISIBLE
                                binding.recyclerView.visibility = View.GONE
                                binding.emptyIcon.setImageResource(R.drawable.ic_error)
                                binding.emptyTitle.text = getString(R.string.list_error_title)
                                binding.emptySubtitle.text = getString(R.string.list_error_description)
                            } else {
                                // Existing content present - keep list visible and rely on Snackbar for feedback
                                binding.emptyState.visibility = View.GONE
                                binding.recyclerView.visibility = View.VISIBLE
                            }
                        }
                        Log.e(TAG, "Error loading videos: ${state.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "VideosFragmentNew"
        private const val LOAD_MORE_THRESHOLD = 5
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
