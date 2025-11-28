package com.albunyaan.tube.ui.detail.tabs

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.databinding.FragmentChannelListTabBinding
import com.albunyaan.tube.ui.detail.ChannelDetailViewModel
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
            tabRecycler.adapter = createAdapter()

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

    private fun setupSwipeRefresh() {
        binding?.swipeRefresh?.setColorSchemeResources(R.color.primary_green)
        binding?.swipeRefresh?.setOnRefreshListener {
            viewModel.loadInitial(tab, forceRefresh = true)
        }
    }

    private fun setupErrorRetry() {
        binding?.tabErrorState?.retryButton?.setOnClickListener {
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
                    // Keep list visible, show snackbar or inline error
                    tabSkeletonContainer.isVisible = false
                    tabRecycler.isVisible = true
                    tabEmptyState.root.isVisible = false
                    tabErrorState.root.isVisible = false
                    updateAdapterData(state.items)
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
        binding = null
        super.onDestroyView()
    }
}
