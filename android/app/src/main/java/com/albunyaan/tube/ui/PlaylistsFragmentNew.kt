package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentSimpleListBinding
import com.albunyaan.tube.ui.detail.PlaylistDetailFragment

class PlaylistsFragmentNew : Fragment(R.layout.fragment_simple_list) {

    private var binding: FragmentSimpleListBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSimpleListBinding.bind(view)

        setupRecyclerView()
        loadPlaylists()
    }

    private fun setupRecyclerView() {
        binding?.recyclerView?.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            // TODO: Set adapter
        }
    }

    private fun loadPlaylists() {
        // TODO: Load playlists from API
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
