package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.FragmentSimpleListBinding
import com.albunyaan.tube.ui.adapters.PlaylistAdapter
import com.albunyaan.tube.ui.detail.PlaylistDetailFragment

class PlaylistsFragmentNew : Fragment(R.layout.fragment_simple_list) {

    private var binding: FragmentSimpleListBinding? = null
    private lateinit var adapter: PlaylistAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSimpleListBinding.bind(view)

        setupRecyclerView()
        loadPlaylists()
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

    private fun loadPlaylists() {
        // TODO: Load from API - for now using mock data
        val mockPlaylists = listOf(
            ContentItem.Playlist("1", "Essentials of Faith", "Aqeedah", 24, null, null),
            ContentItem.Playlist("2", "Life of the Prophet", "Seerah", 45, null, null),
            ContentItem.Playlist("3", "Ramadan Reminders", "Reminders", 30, null, null),
            ContentItem.Playlist("4", "Quran Tafsir Series", "Quran", 114, null, null),
            ContentItem.Playlist("5", "Basics of Islam", "Dawah", 18, null, null),
            ContentItem.Playlist("6", "Stories from the Quran", "Quran", 25, null, null),
            ContentItem.Playlist("7", "Islamic History", "History", 60, null, null)
        )
        adapter.submitList(mockPlaylists)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
