package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentSimpleListBinding

class VideosFragmentNew : Fragment(R.layout.fragment_simple_list) {

    private var binding: FragmentSimpleListBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSimpleListBinding.bind(view)

        setupRecyclerView()
        loadVideos()
    }

    private fun setupRecyclerView() {
        binding?.recyclerView?.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            // TODO: Set adapter
        }
    }

    private fun loadVideos() {
        // TODO: Load videos from API
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
