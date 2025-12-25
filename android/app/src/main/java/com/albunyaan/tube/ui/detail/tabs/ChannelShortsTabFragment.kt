package com.albunyaan.tube.ui.detail.tabs

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelShort
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.databinding.FragmentChannelShortsTabBinding
import com.albunyaan.tube.player.StreamPrefetchService
import com.albunyaan.tube.ui.detail.ChannelDetailViewModel
import com.albunyaan.tube.ui.detail.adapters.ChannelShortsAdapter
import com.albunyaan.tube.ui.detail.adapters.ListFooterAdapter
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fragment for the Shorts tab in Channel Detail.
 * Displays shorts in a grid layout (9:16 thumbnails).
 */
@AndroidEntryPoint
class ChannelShortsTabFragment : Fragment(R.layout.fragment_channel_shorts_tab) {

    @Inject
    lateinit var prefetchService: StreamPrefetchService

    private var binding: FragmentChannelShortsTabBinding? = null

    // Track autofill attempts to prevent excessive fetches on large screens
    private var autofillAttempts = 0

    // Footer adapter for pagination (Load More button, loading spinner, error)
    private var footerAdapter: ListFooterAdapter? = null

    private val channelId: String by lazy {
        requireNotNull(requireParentFragment().arguments?.getString("channelId")) {
            "ChannelShortsTabFragment requires channelId argument"
        }
    }

    private val channelName: String by lazy {
        requireParentFragment().arguments?.getString("channelName") ?: ""
    }

    private val viewModel: ChannelDetailViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
        extrasProducer = {
            requireParentFragment().defaultViewModelCreationExtras.withCreationCallback<ChannelDetailViewModel.Factory> { factory ->
                factory.create(channelId)
            }
        }
    )

    private val adapter by lazy {
        ChannelShortsAdapter { short ->
            // Trigger prefetch before navigation (hides 2-5s extraction latency)
            // Use lifecycleScope (not viewLifecycleOwner) so prefetch survives navigation
            prefetchService.triggerPrefetch(short.id, lifecycleScope)

            // Navigate to video player for shorts
            findNavController().navigate(
                R.id.action_global_playerFragment,
                Bundle().apply {
                    putString("videoId", short.id)
                    putString("title", short.title)
                    putString("channelName", channelName)
                    putString("thumbnailUrl", short.thumbnailUrl ?: "")
                    putInt("durationSeconds", short.durationSeconds ?: 0)
                    putLong("viewCount", short.viewCount ?: -1L)
                }
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentChannelShortsTabBinding.bind(view)

        setupRecyclerView()
        setupSwipeRefresh()
        setupErrorRetry()
        observeState()
    }

    private fun setupRecyclerView() {
        binding?.apply {
            // Get span count from resources (2 for phone, 4-5 for tablet/TV)
            val spanCount = resources.getInteger(R.integer.channel_shorts_span_count)
            val layoutManager = GridLayoutManager(requireContext(), spanCount)

            // Create footer adapter for pagination footer
            footerAdapter = ListFooterAdapter(
                onLoadMoreClick = { onLoadMoreClicked() },
                onRetryClick = {
                    resetAutofillCounter()
                    viewModel.retryAppend(ChannelTab.SHORTS)
                }
            )

            // Use ConcatAdapter to combine content adapter with footer
            val concatAdapter = ConcatAdapter(adapter, footerAdapter!!)
            shortsRecycler.layoutManager = layoutManager
            shortsRecycler.adapter = concatAdapter

            // Make footer span full width in grid layout
            layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    // Footer is always the last item when visible
                    // If position >= content adapter item count, it's the footer
                    return if (position >= adapter.itemCount) spanCount else 1
                }
            }

            // Pagination scroll listener
            shortsRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0) {
                        val lm = recyclerView.layoutManager as GridLayoutManager
                        val lastVisible = lm.findLastVisibleItemPosition()
                        val total = lm.itemCount
                        viewModel.onListScrolled(ChannelTab.SHORTS, lastVisible, total)
                    }
                }
            })
        }
    }

    /**
     * Called when user taps "Load More" button in the footer.
     * Resets autofill counter and triggers next page load.
     */
    private fun onLoadMoreClicked() {
        resetAutofillCounter()
        viewModel.loadNextPage(ChannelTab.SHORTS)
    }

    private fun setupSwipeRefresh() {
        binding?.swipeRefresh?.setColorSchemeResources(R.color.primary_green)
        binding?.swipeRefresh?.setOnRefreshListener {
            resetAutofillCounter()
            viewModel.loadInitial(ChannelTab.SHORTS, forceRefresh = true)
        }
    }

    /**
     * Reset autofill counter when doing a fresh load (pull-to-refresh or initial load).
     */
    private fun resetAutofillCounter() {
        autofillAttempts = 0
        // Clear the load more footer flag when resetting
        viewModel.setShowLoadMoreFooter(ChannelTab.SHORTS, false)
    }

    /**
     * Get the maximum autofill attempts based on screen size.
     * Tablets/TVs get a higher limit since they have larger viewports.
     */
    private fun getMaxAutofillAttempts(): Int {
        val isLargeScreen = resources.configuration.smallestScreenWidthDp >= 600
        return if (isLargeScreen) MAX_AUTOFILL_ATTEMPTS_TABLET else MAX_AUTOFILL_ATTEMPTS_PHONE
    }

    private fun setupErrorRetry() {
        binding?.shortsErrorState?.retryButton?.setOnClickListener {
            resetAutofillCounter() // Reset counter when retrying after error
            viewModel.retryInitial(ChannelTab.SHORTS)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.shortsState.collect { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: ChannelDetailViewModel.PaginatedState<ChannelShort>) {
        binding?.apply {
            swipeRefresh.isRefreshing = false

            when (state) {
                is ChannelDetailViewModel.PaginatedState.Idle -> {
                    viewModel.loadInitial(ChannelTab.SHORTS)
                }
                is ChannelDetailViewModel.PaginatedState.LoadingInitial -> {
                    shortsSkeletonContainer.isVisible = true
                    shortsRecycler.isVisible = false
                    shortsEmptyState.root.isVisible = false
                    shortsErrorState.root.isVisible = false
                }
                is ChannelDetailViewModel.PaginatedState.Loaded -> {
                    shortsSkeletonContainer.isVisible = false
                    shortsRecycler.isVisible = true
                    shortsEmptyState.root.isVisible = false
                    shortsErrorState.root.isVisible = false
                    adapter.submitList(state.items)

                    // Update footer state
                    footerAdapter?.setState(
                        when {
                            state.isAppending -> ListFooterAdapter.FooterState.Loading
                            state.showLoadMoreFooter && state.nextPage != null -> ListFooterAdapter.FooterState.LoadMore
                            else -> ListFooterAdapter.FooterState.Hidden
                        }
                    )

                    // Auto-load more if list can't scroll (too few items to fill viewport)
                    checkAutofillPagination(state.items.size, state.nextPage, state.isAppending)
                }
                is ChannelDetailViewModel.PaginatedState.Empty -> {
                    shortsSkeletonContainer.isVisible = false
                    shortsRecycler.isVisible = false
                    shortsEmptyState.root.isVisible = true
                    shortsErrorState.root.isVisible = false
                    shortsEmptyState.emptyBody.setText(R.string.channel_shorts_empty)
                }
                is ChannelDetailViewModel.PaginatedState.ErrorInitial -> {
                    shortsSkeletonContainer.isVisible = false
                    shortsRecycler.isVisible = false
                    shortsEmptyState.root.isVisible = false
                    shortsErrorState.root.isVisible = true
                }
                is ChannelDetailViewModel.PaginatedState.ErrorAppend -> {
                    // Keep list visible, show error in footer with retry option
                    shortsSkeletonContainer.isVisible = false
                    shortsRecycler.isVisible = true
                    shortsEmptyState.root.isVisible = false
                    shortsErrorState.root.isVisible = false
                    adapter.submitList(state.items)
                    // Show error in footer with retry button
                    footerAdapter?.setState(ListFooterAdapter.FooterState.Error(state.message))
                }
            }
        }
    }

    /**
     * Check if we need to auto-load more items because the list can't scroll.
     * This handles the case where the first page has too few items to fill the viewport,
     * so scroll-based pagination never triggers.
     *
     * @param itemCount Current number of items in the list
     * @param nextPage The next page token if available
     * @param isAppending Whether a load is already in progress
     */
    private fun checkAutofillPagination(itemCount: Int, nextPage: Page?, isAppending: Boolean) {
        val recyclerView = binding?.shortsRecycler ?: return

        // Only autofill if we have more pages available and not already loading
        if (nextPage == null) {
            autofillAttempts = 0 // Reset counter when no more pages
            return
        }
        if (isAppending) return // Already loading

        // Cap autofill attempts to prevent excessive fetches on large screens (tablets/TVs)
        if (autofillAttempts >= getMaxAutofillAttempts()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "SHORTS: autofill capped at ${getMaxAutofillAttempts()} attempts, $itemCount items")
            }
            // Signal UI to show "Load More" button since autofill is capped but more pages exist
            viewModel.setShowLoadMoreFooter(ChannelTab.SHORTS, true)
            return
        }

        // Post to ensure layout is complete before checking scroll capability
        recyclerView.post {
            // Lifecycle safety: check if view is still attached
            if (binding == null || !isAdded || !isResumed) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "SHORTS: skipping autofill - view no longer active")
                }
                return@post
            }

            // If can't scroll down, but there are more pages, load them
            if (!recyclerView.canScrollVertically(1)) {
                // Only increment attempts if request is accepted (not rate-limited or already loading)
                val accepted = viewModel.loadNextPage(ChannelTab.SHORTS)
                if (accepted) {
                    autofillAttempts++
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "SHORTS: autofill triggered (attempt $autofillAttempts), $itemCount items, canScroll=false")
                    }
                } else {
                    // Request was rate-limited or already loading; schedule delayed re-check
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "SHORTS: autofill request rejected (rate limited or already loading), scheduling re-check")
                    }
                    scheduleDelayedAutofillRecheck(itemCount, nextPage)
                }
            } else {
                // List is now scrollable, reset counter
                autofillAttempts = 0
            }
        }
    }

    /**
     * Schedule a delayed re-check for autofill pagination after rate limiting.
     * This ensures we retry after the rate limit window expires.
     */
    private fun scheduleDelayedAutofillRecheck(itemCount: Int, nextPage: Page?) {
        viewLifecycleOwner.lifecycleScope.launch {
            delay(AUTOFILL_RECHECK_DELAY_MS)
            // Re-check only if view is still active
            if (binding != null && isAdded && isResumed) {
                checkAutofillPagination(itemCount, nextPage, isAppending = false)
            }
        }
    }

    override fun onDestroyView() {
        footerAdapter = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "ChannelShortsTab"
        /**
         * Maximum number of autofill pagination attempts for phones.
         * Lower limit since phone viewports are smaller.
         */
        private const val MAX_AUTOFILL_ATTEMPTS_PHONE = 3

        /**
         * Maximum number of autofill pagination attempts for tablets/TVs.
         * Higher limit since larger screens can display more items and need more
         * pages to become scrollable.
         */
        private const val MAX_AUTOFILL_ATTEMPTS_TABLET = 5

        /**
         * Delay before re-checking autofill pagination after rate-limiting.
         * Set slightly above the rate limit interval (1000ms) to ensure the next request is accepted.
         */
        private const val AUTOFILL_RECHECK_DELAY_MS = 1100L
    }
}
