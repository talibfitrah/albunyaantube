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
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.FragmentFeaturedListBinding
import com.albunyaan.tube.player.StreamPrefetchService
import com.albunyaan.tube.ui.adapters.FeaturedListAdapter
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

    private lateinit var adapter: FeaturedListAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentFeaturedListBinding.bind(view)

        setupToolbar()
        setupRecyclerView()
        setupRetryButton()
        observeViewModel()

        // Explicitly load featured content when fragment is first displayed
        viewModel.loadFeatured()
    }

    private fun setupToolbar() {
        binding?.toolbar?.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        // Set toolbar title from navigation argument (category name)
        val categoryName = arguments?.getString("categoryName")
        if (!categoryName.isNullOrEmpty()) {
            binding?.toolbar?.title = categoryName
        }
    }

    private fun setupRecyclerView() {
        adapter = FeaturedListAdapter { item ->
            when (item) {
                is ContentItem.Video -> {
                    Log.d(TAG, "Video clicked: ${item.id}")
                    // Trigger prefetch before navigation (hides 2-5s extraction latency)
                    // Use lifecycleScope (not viewLifecycleOwner) so prefetch survives navigation
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
                    Log.d(TAG, "Playlist clicked: ${item.id}")
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
                    Log.d(TAG, "Channel clicked: ${item.id}")
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

        binding?.recyclerView?.apply {
            // Use single column for consistent layout with mixed content types
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@FeaturedListFragment.adapter
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
                            Log.d(TAG, "Loading featured content...")
                            binding?.progressBar?.isVisible = true
                            binding?.errorContainer?.isVisible = false
                            binding?.recyclerView?.isVisible = false
                        }
                        is FeaturedListViewModel.FeaturedState.Success -> {
                            Log.d(TAG, "Featured content loaded: ${state.items.size} items")
                            binding?.progressBar?.isVisible = false
                            binding?.errorContainer?.isVisible = false
                            binding?.recyclerView?.isVisible = true
                            adapter.submitList(state.items)
                        }
                        is FeaturedListViewModel.FeaturedState.Error -> {
                            Log.e(TAG, "Error loading featured: ${state.message}")
                            binding?.progressBar?.isVisible = false
                            binding?.recyclerView?.isVisible = false
                            binding?.errorContainer?.isVisible = true
                            binding?.errorText?.text = state.message
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

    companion object {
        private const val TAG = "FeaturedListFragment"
    }
}
