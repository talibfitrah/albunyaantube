package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.FragmentHomeNewBinding

class HomeFragment : Fragment(R.layout.fragment_home_new) {

    private var binding: FragmentHomeNewBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeNewBinding.bind(view).also { this.binding = it }

        // Category chip click listener
        binding.categoryChip.setOnClickListener {
            // TODO: Show category selection dialog
        }

        // See All click listeners - navigate to respective tabs
        binding.channelsSeeAll.setOnClickListener {
            navigateToTab(R.id.channelsFragment)
        }

        binding.playlistsSeeAll.setOnClickListener {
            navigateToTab(R.id.playlistsFragment)
        }

        binding.videosSeeAll.setOnClickListener {
            navigateToTab(R.id.videosFragment)
        }

        // Search button
        binding.searchButton.setOnClickListener {
            // TODO: Navigate to search
        }

        // Menu button
        binding.menuButton.setOnClickListener {
            // TODO: Show menu
        }
    }

    private fun navigateToTab(destinationId: Int) {
        val bottomNav = requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.mainBottomNav
        )
        bottomNav?.selectedItemId = destinationId
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
