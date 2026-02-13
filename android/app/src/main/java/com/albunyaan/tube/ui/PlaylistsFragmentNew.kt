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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.databinding.FragmentSimpleListBinding
import com.albunyaan.tube.ui.adapters.PlaylistAdapter
import com.albunyaan.tube.ui.detail.PlaylistDetailFragment
import com.albunyaan.tube.ui.utils.isTablet
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class PlaylistsFragmentNew : Fragment(R.layout.fragment_simple_list) {

    private var binding: FragmentSimpleListBinding? = null
    private lateinit var adapter: PlaylistAdapter

    @Inject
    @Named("real")
    lateinit var contentService: ContentService

    private val viewModel: ContentListViewModel by viewModels {
        ContentListViewModel.Factory(
            contentService,
            ContentType.PLAYLISTS
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
        adapter = PlaylistAdapter { playlist ->
            val args = bundleOf(
                PlaylistDetailFragment.ARG_PLAYLIST_ID to playlist.id,
                PlaylistDetailFragment.ARG_PLAYLIST_TITLE to playlist.title
            )
            findNavController().navigate(R.id.action_playlistsFragment_to_playlistDetailFragment, args)
        }

        binding?.recyclerView?.apply {
            // Use grid layout on tablets for better use of screen real estate
            // Phone: 1 column (list), Tablet: 3 columns, TV: 4 columns (from resources)
            layoutManager = if (requireContext().isTablet()) {
                val spanCount = resources.getInteger(R.integer.grid_span_count_default).coerceIn(2, 4)
                GridLayoutManager(requireContext(), spanCount)
            } else {
                LinearLayoutManager(requireContext())
            }
            adapter = this@PlaylistsFragmentNew.adapter

            // Infinite scroll listener with Fragment-side guards
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // Only trigger on scroll down
                    if (dy <= 0) return

                    // Fragment-side guard: early exit if cannot load more
                    if (!viewModel.canLoadMore) return

                    val lm = recyclerView.layoutManager
                    val totalItems = lm?.itemCount ?: return
                    val lastVisible = when (lm) {
                        is GridLayoutManager -> lm.findLastVisibleItemPosition()
                        is LinearLayoutManager -> lm.findLastVisibleItemPosition()
                        else -> return
                    }

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
                        Log.d(TAG, "Loading playlists (type=${state.type})...")
                        when (state.type) {
                            ContentListViewModel.LoadingType.INITIAL,
                            ContentListViewModel.LoadingType.REFRESH -> {
                                // Initial load or pull-to-refresh: show top swipeRefresh indicator
                                binding?.swipeRefresh?.isRefreshing = true
                                binding?.loadingMore?.visibility = View.GONE
                            }
                            ContentListViewModel.LoadingType.PAGINATION -> {
                                // Infinite scroll: show bottom loadingMore indicator only
                                binding?.swipeRefresh?.isRefreshing = false
                                binding?.loadingMore?.visibility = View.VISIBLE
                            }
                        }
                    }
                    is ContentListViewModel.ContentState.Success -> {
                        binding?.swipeRefresh?.isRefreshing = false
                        binding?.loadingMore?.visibility = View.GONE
                        val playlists = state.items.filterIsInstance<ContentItem.Playlist>()
                        Log.d(TAG, "Playlists loaded: ${playlists.size} items, hasMore=${state.hasMoreData}")
                        adapter.submitList(playlists)
                    }
                    is ContentListViewModel.ContentState.Error -> {
                        binding?.swipeRefresh?.isRefreshing = false
                        binding?.loadingMore?.visibility = View.GONE
                        Log.e(TAG, "Error loading playlists: ${state.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PlaylistsFragmentNew"
        private const val LOAD_MORE_THRESHOLD = 5
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
