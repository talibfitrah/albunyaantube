package com.albunyaan.tube.ui.detail.tabs

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.databinding.FragmentChannelListTabBinding
import com.albunyaan.tube.ui.detail.ChannelDetailViewModel
import com.albunyaan.tube.ui.detail.adapters.ListFooterAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Base fragment for channel tab content that displays a list of items.
 * Handles skeleton loading, empty state, error state, and pagination.
 */
abstract class BaseChannelListTabFragment<T> : Fragment(R.layout.fragment_channel_list_tab) {

    protected var binding: FragmentChannelListTabBinding? = null
    protected abstract val tab: ChannelTab
    protected abstract val emptyMessageRes: Int

    protected abstract fun getState(): StateFlow<ChannelDetailViewModel.PaginatedState<T>>
    protected abstract val viewModel: ChannelDetailViewModel
    protected abstract fun createAdapter(): RecyclerView.Adapter<*>

    // Track autofill attempts to prevent excessive fetches on large screens
    private var autofillAttempts = 0

    // Track delayed recheck retries to prevent unbounded retry loops
    private var delayedRecheckRetries = 0

    // Footer adapter for pagination (Load More button, loading spinner, error)
    private var footerAdapter: ListFooterAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentChannelListTabBinding.bind(view)

        setupRecyclerView()
        setupSwipeRefresh()
        setupErrorRetry()
        observeState()
    }

    private fun setupRecyclerView() {
        binding?.apply {
            tabRecycler.layoutManager = LinearLayoutManager(requireContext())

            // Create footer adapter for pagination footer
            footerAdapter = ListFooterAdapter(
                onLoadMoreClick = { onLoadMoreClicked() },
                onRetryClick = { onRetryAppendClicked() }
            )

            // Use ConcatAdapter to combine content adapter with footer
            val contentAdapter = createAdapter()
            tabRecycler.adapter = ConcatAdapter(contentAdapter, footerAdapter!!)

            // Pagination scroll listener
            tabRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0) { // Scrolling down
                        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                        val lastVisible = layoutManager.findLastVisibleItemPosition()
                        val total = layoutManager.itemCount
                        viewModel.onListScrolled(tab, lastVisible, total)
                    }
                }
            })
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
        val recyclerView = binding?.tabRecycler ?: return

        // Only autofill if we have more pages available and not already loading
        if (nextPage == null) {
            autofillAttempts = 0 // Reset counter when no more pages
            return
        }
        if (isAppending) return // Already loading

        // Cap autofill attempts to prevent excessive fetches on large screens (tablets/TVs)
        if (autofillAttempts >= getMaxAutofillAttempts()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "$tab: autofill capped at ${getMaxAutofillAttempts()} attempts, $itemCount items")
            }
            // Signal UI to show "Load More" button since autofill is capped but more pages exist
            viewModel.setShowLoadMoreFooter(tab, true)
            return
        }

        // Post to ensure layout is complete before checking scroll capability
        recyclerView.post {
            // Lifecycle safety: check if view is still attached
            if (binding == null || !isAdded || !isResumed) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "$tab: skipping autofill - view no longer active")
                }
                return@post
            }

            // If can't scroll down, but there are more pages, load them
            if (!recyclerView.canScrollVertically(1)) {
                // Only increment attempts if request is accepted (not rate-limited or already loading)
                val accepted = viewModel.loadNextPage(tab)
                if (accepted) {
                    autofillAttempts++
                    delayedRecheckRetries = 0  // Reset delayed retry counter on success
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "$tab: autofill triggered (attempt $autofillAttempts), $itemCount items, canScroll=false")
                    }
                } else {
                    // Request was rate-limited or already loading; schedule delayed re-check
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "$tab: autofill request rejected (rate limited or already loading), scheduling re-check")
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
     *
     * @param _itemCount Unused - current state is re-read for up-to-date values
     * @param _nextPage Unused - current state is re-read for up-to-date values
     */
    @Suppress("UNUSED_PARAMETER")
    private fun scheduleDelayedAutofillRecheck(_itemCount: Int, _nextPage: Page?) {
        // Prevent unbounded retry loops
        if (delayedRecheckRetries >= MAX_DELAYED_RECHECK_RETRIES) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "$tab: delayed recheck limit reached ($MAX_DELAYED_RECHECK_RETRIES), stopping")
            }
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            delay(AUTOFILL_RECHECK_DELAY_MS)
            delayedRecheckRetries++  // Increment after delay to prevent premature limit hits
            // Re-check only if view is still active
            if (binding != null && isAdded && isResumed) {
                // Get current state to check actual isAppending value
                val currentState = getState().value
                if (currentState is ChannelDetailViewModel.PaginatedState.Loaded) {
                    checkAutofillPagination(currentState.items.size, currentState.nextPage, currentState.isAppending)
                }
            }
        }
    }

    /**
     * Reset autofill counter when doing a fresh load (pull-to-refresh or initial load).
     */
    private fun resetAutofillCounter() {
        autofillAttempts = 0
        delayedRecheckRetries = 0
        // Clear the load more footer flag when resetting
        viewModel.setShowLoadMoreFooter(tab, false)
    }

    /**
     * Get the maximum autofill attempts based on screen size.
     * Tablets/TVs get a higher limit since they have larger viewports.
     */
    private fun getMaxAutofillAttempts(): Int {
        val isLargeScreen = resources.configuration.smallestScreenWidthDp >= 600
        return if (isLargeScreen) MAX_AUTOFILL_ATTEMPTS_TABLET else MAX_AUTOFILL_ATTEMPTS_PHONE
    }

    /**
     * Called when user taps "Load More" button in the footer.
     * Resets autofill counter and triggers next page load.
     */
    protected fun onLoadMoreClicked() {
        resetAutofillCounter()
        viewModel.loadNextPage(tab)
    }

    /**
     * Called when user taps "Retry" after an append error.
     */
    protected fun onRetryAppendClicked() {
        viewModel.retryAppend(tab)
    }

    private fun setupSwipeRefresh() {
        binding?.swipeRefresh?.setColorSchemeResources(R.color.primary_green)
        binding?.swipeRefresh?.setOnRefreshListener {
            resetAutofillCounter()
            viewModel.loadInitial(tab, forceRefresh = true)
        }
    }

    private fun setupErrorRetry() {
        binding?.tabErrorState?.retryButton?.setOnClickListener {
            resetAutofillCounter() // Reset counter when retrying after error
            viewModel.retryInitial(tab)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            getState().collect { state ->
                updateUI(state)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected open fun updateUI(state: ChannelDetailViewModel.PaginatedState<T>) {
        binding?.apply {
            swipeRefresh.isRefreshing = false

            when (state) {
                is ChannelDetailViewModel.PaginatedState.Idle -> {
                    // Show skeleton immediately while waiting for data.
                    // Note: We do NOT call loadInitial here anymore because:
                    // 1. For VIDEOS tab: ViewModel pre-loads in loadHeader() to avoid double-fetch
                    // 2. For other tabs: Load is triggered in onResume when tab becomes visible
                    tabSkeletonContainer.isVisible = true
                    tabRecycler.isVisible = false
                    tabEmptyState.root.isVisible = false
                    tabErrorState.root.isVisible = false
                }
                is ChannelDetailViewModel.PaginatedState.LoadingInitial -> {
                    tabSkeletonContainer.isVisible = true
                    tabRecycler.isVisible = false
                    tabEmptyState.root.isVisible = false
                    tabErrorState.root.isVisible = false
                }
                is ChannelDetailViewModel.PaginatedState.Loaded -> {
                    tabSkeletonContainer.isVisible = false
                    tabRecycler.isVisible = true
                    tabEmptyState.root.isVisible = false
                    tabErrorState.root.isVisible = false
                    updateAdapterData(state.items)

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
                    tabSkeletonContainer.isVisible = false
                    tabRecycler.isVisible = false
                    tabEmptyState.root.isVisible = true
                    tabErrorState.root.isVisible = false
                    tabEmptyState.emptyBody.setText(emptyMessageRes)
                }
                is ChannelDetailViewModel.PaginatedState.ErrorInitial -> {
                    tabSkeletonContainer.isVisible = false
                    tabRecycler.isVisible = false
                    tabEmptyState.root.isVisible = false
                    tabErrorState.root.isVisible = true
                }
                is ChannelDetailViewModel.PaginatedState.ErrorAppend -> {
                    // Keep list visible, show error in footer with retry option
                    tabSkeletonContainer.isVisible = false
                    tabRecycler.isVisible = true
                    tabEmptyState.root.isVisible = false
                    tabErrorState.root.isVisible = false
                    updateAdapterData(state.items)
                    // Show error in footer with retry button
                    footerAdapter?.setState(ListFooterAdapter.FooterState.Error(state.message))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Load content when tab becomes visible, but only if still in Idle state
        // This avoids double-loading: Videos is pre-loaded by ViewModel.loadHeader(),
        // other tabs load when first viewed
        if (getState().value is ChannelDetailViewModel.PaginatedState.Idle) {
            viewModel.loadInitial(tab)
        }
    }

    protected abstract fun updateAdapterData(items: List<T>)

    override fun onDestroyView() {
        footerAdapter = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "BaseChannelListTab"
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

        /**
         * Maximum number of delayed recheck retries to prevent unbounded retry loops.
         */
        private const val MAX_DELAYED_RECHECK_RETRIES = 5
    }
}
