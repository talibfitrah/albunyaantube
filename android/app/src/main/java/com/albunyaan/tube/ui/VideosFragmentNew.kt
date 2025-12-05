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
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.databinding.FragmentSimpleListBinding
import com.albunyaan.tube.ui.adapters.VideoGridAdapter
import com.albunyaan.tube.ui.utils.calculateGridSpanCount
import dagger.hilt.android.AndroidEntryPoint
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
        observeViewModel()
    }

    private fun setupSwipeRefresh() {
        binding?.swipeRefresh?.setOnRefreshListener {
            viewModel.refresh()
        }
    }

    private fun setupRecyclerView() {
        adapter = VideoGridAdapter { video ->
            navigateToPlayer(video.id)
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

    private fun navigateToPlayer(videoId: String) {
        val bundle = bundleOf("videoId" to videoId)
        // Navigate using global action since player is now in main_tabs_nav
        findNavController().navigate(R.id.action_global_playerFragment, bundle)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.content.collect { state ->
                when (state) {
                    is ContentListViewModel.ContentState.Loading -> {
                        Log.d(TAG, "Loading videos...")
                        binding?.swipeRefresh?.isRefreshing = true
                        binding?.loadingMore?.visibility = View.GONE
                    }
                    is ContentListViewModel.ContentState.Success -> {
                        binding?.swipeRefresh?.isRefreshing = false
                        // Show/hide bottom loading indicator
                        binding?.loadingMore?.visibility = if (state.isLoadingMore) View.VISIBLE else View.GONE
                        val videos = state.items.filterIsInstance<ContentItem.Video>()
                        Log.d(TAG, "Videos loaded: ${videos.size} items, loadingMore=${state.isLoadingMore}, hasMore=${state.hasMoreData}")
                        adapter.submitList(videos)
                    }
                    is ContentListViewModel.ContentState.Error -> {
                        binding?.swipeRefresh?.isRefreshing = false
                        binding?.loadingMore?.visibility = View.GONE
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
