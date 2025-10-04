package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.FragmentSimpleListBinding
import com.albunyaan.tube.ui.adapters.VideoGridAdapter

class VideosFragmentNew : Fragment(R.layout.fragment_simple_list) {

    private var binding: FragmentSimpleListBinding? = null
    private lateinit var adapter: VideoGridAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSimpleListBinding.bind(view)

        setupRecyclerView()
        loadVideos()
    }

    private fun setupRecyclerView() {
        adapter = VideoGridAdapter { video ->
            navigateToPlayer(video.id)
        }

        binding?.recyclerView?.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = this@VideosFragmentNew.adapter
        }
    }

    private fun navigateToPlayer(videoId: String) {
        val bundle = bundleOf("videoId" to videoId)
        // Navigate using parent nav controller since player is in parent graph
        val navController = requireActivity().findNavController(R.id.nav_host_fragment)
        navController.navigate(R.id.action_mainShellFragment_to_playerFragment, bundle)
    }

    private fun loadVideos() {
        // TODO: Load from API - for now using mock data
        val mockVideos = listOf(
            ContentItem.Video("1", "The Essence of Faith", "Aqeedah", 15, 7, "Deep dive into the foundations of Islamic faith", null, 125000L),
            ContentItem.Video("2", "Understanding Tawheed", "Aqeedah", 22, 14, "Understanding the oneness of Allah", null, 98000L),
            ContentItem.Video("3", "Daily Dhikr Guide", "Reminders", 8, 3, "Essential daily remembrances", null, 205000L),
            ContentItem.Video("4", "Surah Al-Baqarah Tafsir Pt 1", "Quran", 45, 21, "Detailed explanation of Surah Al-Baqarah", null, 178000L),
            ContentItem.Video("5", "The Purpose of Life", "Dawah", 12, 45, "Why are we here?", null, 310000L),
            ContentItem.Video("6", "Stories of the Sahaba", "Seerah", 18, 10, "Inspiring stories of the companions", null, 92000L),
            ContentItem.Video("7", "Islamic Manners", "Fiqh", 28, 5, "Good character in Islam", null, 67000L),
            ContentItem.Video("8", "Battle of Badr", "History", 35, 30, "The first major battle in Islamic history", null, 145000L),
            ContentItem.Video("9", "Fiqh of Salah", "Fiqh", 20, 12, "Detailed rulings on prayer", null, 88000L),
            ContentItem.Video("10", "Ramadan Prep", "Reminders", 10, 2, "Getting ready for the blessed month", null, 256000L)
        )
        adapter.submitList(mockVideos)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
