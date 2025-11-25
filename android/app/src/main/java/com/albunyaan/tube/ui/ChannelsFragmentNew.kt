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
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.databinding.FragmentChannelsNewBinding
import com.albunyaan.tube.ui.adapters.ChannelAdapter
import com.albunyaan.tube.ui.detail.ChannelDetailFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class ChannelsFragmentNew : Fragment(R.layout.fragment_channels_new) {

    private var binding: FragmentChannelsNewBinding? = null
    private lateinit var adapter: ChannelAdapter

    @Inject
    @Named("real")
    lateinit var contentService: ContentService

    private val viewModel: ContentListViewModel by viewModels {
        ContentListViewModel.Factory(
            contentService,
            ContentType.CHANNELS
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentChannelsNewBinding.bind(view)

        setupRecyclerView()
        observeViewModel()
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

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.content.collect { state ->
                when (state) {
                    is ContentListViewModel.ContentState.Loading -> {
                        Log.d(TAG, "Loading channels...")
                    }
                    is ContentListViewModel.ContentState.Success -> {
                        val channels = state.items.filterIsInstance<ContentItem.Channel>()
                        Log.d(TAG, "Channels loaded: ${channels.size} items")
                        adapter.submitList(channels)
                    }
                    is ContentListViewModel.ContentState.Error -> {
                        Log.e(TAG, "Error loading channels: ${state.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ChannelsFragmentNew"
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
