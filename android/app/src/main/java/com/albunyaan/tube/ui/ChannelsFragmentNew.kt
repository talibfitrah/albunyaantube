package com.albunyaan.tube.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.FragmentChannelsNewBinding
import com.albunyaan.tube.ui.adapters.ChannelAdapter
import com.albunyaan.tube.ui.detail.ChannelDetailFragment

class ChannelsFragmentNew : Fragment(R.layout.fragment_channels_new) {

    private var binding: FragmentChannelsNewBinding? = null
    private lateinit var adapter: ChannelAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentChannelsNewBinding.bind(view)

        setupRecyclerView()
        loadChannels()
        setupCategoriesFab()
    }

    private fun setupCategoriesFab() {
        binding?.categoriesFab?.setOnClickListener {
            findNavController().navigate(R.id.categoriesFragment)
        }
    }

    private fun setupRecyclerView() {
        adapter = ChannelAdapter { channel ->
            val args = bundleOf(
                ChannelDetailFragment.ARG_CHANNEL_ID to channel.id,
                ChannelDetailFragment.ARG_CHANNEL_NAME to channel.name,
                ChannelDetailFragment.ARG_EXCLUDED to false
            )
            findNavController().navigate(R.id.action_channelsFragment_to_channelDetailFragment, args)
        }

        binding?.recyclerView?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ChannelsFragmentNew.adapter
        }
    }

    private fun loadChannels() {
        // TODO: Load from API - for now using mock data
        val mockChannels = listOf(
            ContentItem.Channel("1", "IslamQA", "Fiqh", 1200000, null, null, null, listOf("Fiqh", "Aqeedah", "Hadith")),
            ContentItem.Channel("2", "Omar Suleiman", "Seerah", 850000, null, null, null, listOf("Seerah", "Quran", "Reminders", "History")),
            ContentItem.Channel("3", "Mufti Menk", "Reminders", 600000, null, null, null, listOf("Reminders", "Fiqh", "Dawah")),
            ContentItem.Channel("4", "Nouman Ali Khan", "Quran", 450000, null, null, null, listOf("Quran", "Tafsir", "Arabic")),
            ContentItem.Channel("5", "Bilal Philips", "Dawah", 300000, null, null, null, listOf("Dawah", "Aqeedah", "Comparative Religion", "Islamic Studies", "Philosophy", "History", "Fiqh", "Hadith", "Quran", "Seerah")),
            ContentItem.Channel("6", "Yusha Evans", "Dawah", 200000, null, null, null, listOf("Dawah", "Convert Stories")),
            ContentItem.Channel("7", "Hamza Yusuf", "Sufism", 150000, null, null, null, listOf("Sufism", "Islamic Philosophy", "History", "Literature")),
            ContentItem.Channel("8", "Zaid Shakir", "Seerah", 100000, null, null, null, listOf("Seerah", "Fiqh"))
        )
        adapter.submitList(mockChannels)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
