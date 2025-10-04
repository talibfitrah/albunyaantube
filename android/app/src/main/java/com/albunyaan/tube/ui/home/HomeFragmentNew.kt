package com.albunyaan.tube.ui.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentHomeNewBinding

class HomeFragmentNew : Fragment(R.layout.fragment_home_new) {

    private var binding: FragmentHomeNewBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentHomeNewBinding.bind(view)

        setupUI()
        loadContent()
    }

    private fun setupUI() {
        binding?.apply {
            // Setup horizontal RecyclerViews
            channelsRecyclerView.layoutManager = LinearLayoutManager(
                requireContext(),
                LinearLayoutManager.HORIZONTAL,
                false
            )

            playlistsRecyclerView.layoutManager = LinearLayoutManager(
                requireContext(),
                LinearLayoutManager.HORIZONTAL,
                false
            )

            videosRecyclerView.layoutManager = LinearLayoutManager(
                requireContext(),
                LinearLayoutManager.HORIZONTAL,
                false
            )

            // Setup click listeners
            categoryChip.setOnClickListener {
                // TODO: Show category picker dialog
            }

            searchButton.setOnClickListener {
                // TODO: Open search
            }

            channelsSeeAll.setOnClickListener {
                // TODO: Navigate to channels tab
            }

            playlistsSeeAll.setOnClickListener {
                // TODO: Navigate to playlists tab
            }

            videosSeeAll.setOnClickListener {
                // TODO: Navigate to videos tab
            }
        }
    }

    private fun loadContent() {
        // TODO: Load content from API
        // For now, showing empty sections
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
