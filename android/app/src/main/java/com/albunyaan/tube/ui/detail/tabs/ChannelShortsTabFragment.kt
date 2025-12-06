package com.albunyaan.tube.ui.detail.tabs

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelShort
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.databinding.FragmentChannelShortsTabBinding
import com.albunyaan.tube.ui.detail.ChannelDetailViewModel
import com.albunyaan.tube.ui.detail.adapters.ChannelShortsAdapter
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.launch

/**
 * Fragment for the Shorts tab in Channel Detail.
 * Displays shorts in a grid layout (9:16 thumbnails).
 */
@AndroidEntryPoint
class ChannelShortsTabFragment : Fragment(R.layout.fragment_channel_shorts_tab) {

    private var binding: FragmentChannelShortsTabBinding? = null

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
            shortsRecycler.layoutManager = GridLayoutManager(requireContext(), spanCount)
            shortsRecycler.adapter = adapter

            // Pagination scroll listener
            shortsRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0) {
                        val layoutManager = recyclerView.layoutManager as GridLayoutManager
                        val lastVisible = layoutManager.findLastVisibleItemPosition()
                        val total = layoutManager.itemCount
                        viewModel.onListScrolled(ChannelTab.SHORTS, lastVisible, total)
                    }
                }
            })
        }
    }

    private fun setupSwipeRefresh() {
        binding?.swipeRefresh?.setColorSchemeResources(R.color.primary_green)
        binding?.swipeRefresh?.setOnRefreshListener {
            viewModel.loadInitial(ChannelTab.SHORTS, forceRefresh = true)
        }
    }

    private fun setupErrorRetry() {
        binding?.shortsErrorState?.retryButton?.setOnClickListener {
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
                    shortsSkeletonContainer.isVisible = false
                    shortsRecycler.isVisible = true
                    shortsEmptyState.root.isVisible = false
                    shortsErrorState.root.isVisible = false
                    adapter.submitList(state.items)
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
