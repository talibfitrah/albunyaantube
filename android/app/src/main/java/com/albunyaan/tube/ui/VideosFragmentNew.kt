package com.albunyaan.tube.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
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
import com.albunyaan.tube.ui.utils.AutofillPaginationHelper
import com.albunyaan.tube.ui.utils.calculateGridSpanCount
import com.albunyaan.tube.ui.utils.updateCategoryFilter
import com.albunyaan.tube.player.PlaybackFeatureFlags
import com.albunyaan.tube.player.PredictivePrefetchController
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
    lateinit var featureFlags: PlaybackFeatureFlags

    @Inject
    lateinit var filterManager: FilterManager

    private var prefetchController: PredictivePrefetchController? = null

    private val viewModel: ContentListViewModel by viewModels {
        ContentListViewModel.Factory(
            contentService,
            ContentType.VIDEOS
        )
    }

    private val autofillHelper = AutofillPaginationHelper(TAG)
    private val searchHandler = Handler(Looper.getMainLooper())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSimpleListBinding.bind(view)

        setupRecyclerView()
        if (featureFlags.isPredictivePrefetchEnabled) {
            prefetchController = PredictivePrefetchController(
                prefetchService,
                viewLifecycleOwner.lifecycleScope,
                videoIdResolver = { pos -> adapter.currentList.getOrNull(pos)?.id }
            )
            binding?.recyclerView?.let { prefetchController?.attach(it) }
        }
        setupSwipeRefresh()
        setupSearch()
        observeFilters()
        observeViewModel()
    }

    private fun setupSearch() {
        val editText = binding?.searchEditText ?: return
        val clearBtn = binding?.searchClearButton ?: return

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                clearBtn.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                searchHandler.removeCallbacksAndMessages(null)
                searchHandler.postDelayed({
                    autofillHelper.reset()
                    viewModel.setSearchQuery(query)
                }, SEARCH_DEBOUNCE_MS)
            }
        })

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchHandler.removeCallbacksAndMessages(null)
                val query = editText.text?.toString() ?: ""
                autofillHelper.reset()
                viewModel.setSearchQuery(query)
                true
            } else false
        }

        clearBtn.setOnClickListener {
            searchHandler.removeCallbacksAndMessages(null)
            editText.text?.clear()
            autofillHelper.reset()
            viewModel.setSearchQuery("")
        }
    }

    private fun observeFilters() {
        viewLifecycleOwner.lifecycleScope.launch {
            filterManager.state.collectLatest { filterState ->
                Log.d(TAG, "Filter state changed: category=${filterState.category}")
                autofillHelper.reset()
                viewModel.setFilters(filterState)
                binding?.filterChip.updateCategoryFilter(filterState.category, filterState.categoryName) {
                    Log.d(TAG, "Clearing category filter")
                    filterManager.setCategory(null)
                }
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding?.swipeRefresh?.setOnRefreshListener {
            autofillHelper.reset()
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
                            ContentListViewModel.LoadingType.INITIAL -> {
                                // Initial load: show skeleton placeholders, hide list/empty/spinner
                                binding?.swipeRefresh?.isRefreshing = false
                                binding?.swipeRefresh?.visibility = View.GONE
                                binding?.listSkeleton?.root?.visibility = View.VISIBLE
                                binding?.loadingMore?.visibility = View.GONE
                                binding?.emptyState?.visibility = View.GONE
                            }
                            ContentListViewModel.LoadingType.REFRESH -> {
                                // Pull-to-refresh: keep existing list visible with the spinner overlay
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
                        Log.d(TAG, "Videos loaded: ${videos.size} items, hasMore=${state.hasMoreData}, search=${state.isSearchActive}")
                        binding?.let { binding ->
                            binding.listSkeleton.root.visibility = View.GONE
                            binding.swipeRefresh.visibility = View.VISIBLE
                            binding.swipeRefresh.isRefreshing = false
                            binding.swipeRefresh.isEnabled = !state.isSearchActive
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

                            val screenWidthDp = resources.configuration.smallestScreenWidthDp
                            val rv = binding.recyclerView
                            adapter.submitList(videos) {
                                autofillHelper.check(
                                    itemCount = videos.size,
                                    hasMoreData = state.hasMoreData,
                                    hasPaginationError = state.paginationError != null,
                                    smallestScreenWidthDp = screenWidthDp,
                                    recyclerView = rv,
                                    isViewActive = { this@VideosFragmentNew.binding != null && isAdded },
                                    canLoadMore = { viewModel.canLoadMore },
                                    loadMore = { viewModel.loadMore() }
                                )
                            }

                            if (videos.isEmpty()) {
                                binding.emptyState.visibility = View.VISIBLE
                                binding.recyclerView.visibility = View.GONE
                                if (state.isSearchActive) {
                                    binding.emptyIcon.setImageResource(R.drawable.ic_search)
                                    binding.emptyTitle.text = getString(R.string.search_no_results)
                                    binding.emptySubtitle.text = getString(R.string.search_try_different_hint)
                                } else {
                                    binding.emptyIcon.setImageResource(R.drawable.ic_videos)
                                    binding.emptyTitle.text = getString(R.string.videos_empty_title)
                                    binding.emptySubtitle.text = getString(R.string.videos_empty_subtitle)
                                }
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

                            if (adapter.currentList.isEmpty()) {
                                // Initial load failure (typically offline): keep skeleton placeholders.
                                // The global offline banner from MainShellFragment communicates the cause.
                                binding.listSkeleton.root.visibility = View.VISIBLE
                                binding.swipeRefresh.visibility = View.GONE
                                binding.emptyState.visibility = View.GONE
                            } else {
                                // Existing content present - keep list visible and surface a toast.
                                binding.listSkeleton.root.visibility = View.GONE
                                binding.swipeRefresh.visibility = View.VISIBLE
                                binding.emptyState.visibility = View.GONE
                                val message = if (state.message.isBlank()) {
                                    getString(R.string.list_error_title)
                                } else {
                                    state.message
                                }
                                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
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
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    override fun onDestroyView() {
        searchHandler.removeCallbacksAndMessages(null)
        prefetchController?.detach()
        prefetchController = null
        autofillHelper.reset()
        binding = null
        super.onDestroyView()
    }
}
