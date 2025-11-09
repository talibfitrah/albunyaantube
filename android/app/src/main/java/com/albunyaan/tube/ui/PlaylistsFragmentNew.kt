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
import com.albunyaan.tube.R
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.databinding.FragmentSimpleListBinding
import com.albunyaan.tube.ui.adapters.PlaylistAdapter
import com.albunyaan.tube.ui.detail.PlaylistDetailFragment
import kotlinx.coroutines.launch

class PlaylistsFragmentNew : Fragment(R.layout.fragment_simple_list) {

    private var binding: FragmentSimpleListBinding? = null
    private lateinit var adapter: PlaylistAdapter

    private val viewModel: ContentListViewModel by viewModels {
        ContentListViewModel.Factory(
            ServiceLocator.provideContentService(),
            ContentType.PLAYLISTS
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSimpleListBinding.bind(view)

        setupRecyclerView()
        observeViewModel()
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
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PlaylistsFragmentNew.adapter
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.content.collect { state ->
                when (state) {
                    is ContentListViewModel.ContentState.Loading -> {
                        Log.d(TAG, "Loading playlists...")
                    }
                    is ContentListViewModel.ContentState.Success -> {
                        val playlists = state.items.filterIsInstance<ContentItem.Playlist>()
                        Log.d(TAG, "Playlists loaded: ${playlists.size} items")
                        adapter.submitList(playlists)
                    }
                    is ContentListViewModel.ContentState.Error -> {
                        Log.e(TAG, "Error loading playlists: ${state.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PlaylistsFragmentNew"
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}

