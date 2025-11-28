package com.albunyaan.tube.ui.detail.tabs

import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelLiveStream
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.ui.detail.ChannelDetailViewModel
import com.albunyaan.tube.ui.detail.adapters.ChannelLiveAdapter
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.flow.StateFlow

/**
 * Fragment for the Live tab in Channel Detail.
 * Displays live and upcoming streams.
 */
@AndroidEntryPoint
class ChannelLiveTabFragment : BaseChannelListTabFragment<ChannelLiveStream>() {

    override val tab: ChannelTab = ChannelTab.LIVE
    override val emptyMessageRes: Int = R.string.channel_live_empty

    private val channelId: String by lazy {
        requireNotNull(requireParentFragment().arguments?.getString("channelId")) {
            "ChannelLiveTabFragment requires channelId argument"
        }
    }

    override val viewModel: ChannelDetailViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
        extrasProducer = {
            requireParentFragment().defaultViewModelCreationExtras.withCreationCallback<ChannelDetailViewModel.Factory> { factory ->
                factory.create(channelId)
            }
        }
    )

    private val adapter by lazy {
        ChannelLiveAdapter { stream ->
            // Navigate to video player for live streams
            findNavController().navigate(
                R.id.action_global_playerFragment,
                android.os.Bundle().apply {
                    putString("videoId", stream.id)
                    putString("videoTitle", stream.title)
                    putString("playlistId", "")
                    putBoolean("audioOnly", false)
                }
            )
        }
    }

    override fun getState(): StateFlow<ChannelDetailViewModel.PaginatedState<ChannelLiveStream>> = viewModel.liveState

    override fun createAdapter(): RecyclerView.Adapter<*> = adapter

    override fun updateAdapterData(items: List<ChannelLiveStream>) {
        adapter.submitList(items)
    }
}
