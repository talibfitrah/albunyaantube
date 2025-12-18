package com.albunyaan.tube.ui.detail.tabs

import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelLiveStream
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.player.StreamPrefetchService
import com.albunyaan.tube.ui.detail.ChannelDetailViewModel
import com.albunyaan.tube.ui.detail.adapters.ChannelLiveAdapter
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Fragment for the Live tab in Channel Detail.
 * Displays live and upcoming streams.
 */
@AndroidEntryPoint
class ChannelLiveTabFragment : BaseChannelListTabFragment<ChannelLiveStream>() {

    @Inject
    lateinit var prefetchService: StreamPrefetchService

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
            // Trigger prefetch before navigation (hides 2-5s extraction latency)
            // Use lifecycleScope (not viewLifecycleOwner) so prefetch survives navigation
            prefetchService.triggerPrefetch(stream.id, lifecycleScope)

            // Navigate to video player for live streams
            findNavController().navigate(
                R.id.action_global_playerFragment,
                android.os.Bundle().apply {
                    putString("videoId", stream.id)
                    putString("title", stream.title)
                    putString("channelName", stream.uploaderName ?: "")
                    putString("thumbnailUrl", stream.thumbnailUrl ?: "")
                    putLong("viewCount", stream.viewCount ?: -1L)
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
