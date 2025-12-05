package com.albunyaan.tube.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.databinding.FragmentChannelsNewBinding
import com.albunyaan.tube.ui.adapters.ChannelAdapter
import com.albunyaan.tube.ui.detail.ChannelDetailFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class ChannelsFragmentNew : Fragment(R.layout.fragment_channels_new) {

    private var binding: FragmentChannelsNewBinding? = null
    private lateinit var adapter: ChannelAdapter

    @Inject
    @Named("real")
    lateinit var contentService: ContentService

    private val viewModel: ContentListViewModel by viewModels {
        ContentListViewModel.Factory(
            contentService,
            ContentType.CHANNELS
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentChannelsNewBinding.bind(view)

        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()
        setupCategoriesFab()
    }

    private fun setupCategoriesFab() {
        binding?.categoriesFab?.setOnClickListener {
            findNavController().navigate(R.id.categoriesFragment)
        }
    }

    private fun setupSwipeRefresh() {
        binding?.swipeRefresh?.setOnRefreshListener {
            viewModel.refresh()
        }
    }

    private fun setupRecyclerView() {
        adapter = ChannelAdapter { channel ->
            val args = bundleOf(
                ChannelDetailFragment.ARG_CHANNEL_ID to channel.id,
                ChannelDetailFragment.ARG_CHANNEL_NAME to channel.name,
                ChannelDetailFragment.ARG_EXCLUDED to false
            )
            findNavController().navigate(R.id.action_channelsFragment_to_channelDetailFragment, args)
        }

        binding?.recyclerView?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ChannelsFragmentNew.adapter

            // Infinite scroll listener with Fragment-side guards
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // Only trigger on scroll down
                    if (dy <= 0) return

                    // Fragment-side guard: early exit if cannot load more
                    if (!viewModel.canLoadMore) return

                    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
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

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.content.collect { state ->
                when (state) {
                    is ContentListViewModel.ContentState.Loading -> {
                        Log.d(TAG, "Loading channels...")
                        binding?.swipeRefresh?.isRefreshing = true
                        binding?.loadingMore?.visibility = View.GONE
                    }
                    is ContentListViewModel.ContentState.Success -> {
                        binding?.swipeRefresh?.isRefreshing = false
                        // Show/hide bottom loading indicator
                        binding?.loadingMore?.visibility = if (state.isLoadingMore) View.VISIBLE else View.GONE
                        val channels = state.items.filterIsInstance<ContentItem.Channel>()
                        Log.d(TAG, "Channels loaded: ${channels.size} items, loadingMore=${state.isLoadingMore}, hasMore=${state.hasMoreData}")
                        adapter.submitList(channels)
                    }
                    is ContentListViewModel.ContentState.Error -> {
                        binding?.swipeRefresh?.isRefreshing = false
                        binding?.loadingMore?.visibility = View.GONE
                        Log.e(TAG, "Error loading channels: ${state.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ChannelsFragmentNew"
        private const val LOAD_MORE_THRESHOLD = 5
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
