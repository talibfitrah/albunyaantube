package com.albunyaan.tube.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.databinding.FragmentSimpleListBinding
import com.albunyaan.tube.ui.adapters.VideoGridAdapter
import com.albunyaan.tube.ui.utils.calculateGridSpanCount
import kotlinx.coroutines.launch

class VideosFragmentNew : Fragment(R.layout.fragment_simple_list) {

    private var binding: FragmentSimpleListBinding? = null
    private lateinit var adapter: VideoGridAdapter

    private val viewModel: ContentListViewModel by viewModels {
        ContentListViewModel.Factory(
            ServiceLocator.provideContentService(),
            ContentType.VIDEOS
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSimpleListBinding.bind(view)

        setupRecyclerView()
        observeViewModel()
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
                    }
                    is ContentListViewModel.ContentState.Success -> {
                        val videos = state.items.filterIsInstance<ContentItem.Video>()
                        Log.d(TAG, "Videos loaded: ${videos.size} items")
                        adapter.submitList(videos)
                    }
                    is ContentListViewModel.ContentState.Error -> {
                        Log.e(TAG, "Error loading videos: ${state.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "VideosFragmentNew"
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
