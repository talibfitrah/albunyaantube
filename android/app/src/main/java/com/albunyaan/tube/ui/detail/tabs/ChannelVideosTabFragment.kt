package com.albunyaan.tube.ui.detail.tabs

import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.data.channel.ChannelVideo
import com.albunyaan.tube.player.StreamPrefetchService
import com.albunyaan.tube.ui.detail.ChannelDetailViewModel
import com.albunyaan.tube.ui.detail.adapters.ChannelVideoAdapter
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Fragment for the Videos tab in Channel Detail.
 * Displays a paginated list of channel videos.
 */
@AndroidEntryPoint
class ChannelVideosTabFragment : BaseChannelListTabFragment<ChannelVideo>() {

    @Inject
    lateinit var prefetchService: StreamPrefetchService

    override val tab: ChannelTab = ChannelTab.VIDEOS
    override val emptyMessageRes: Int = R.string.channel_videos_empty

    private val channelId: String by lazy {
        requireNotNull(requireParentFragment().arguments?.getString("channelId")) {
            "ChannelVideosTabFragment requires channelId argument"
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
        ChannelVideoAdapter { video ->
            // Trigger prefetch before navigation (hides 2-5s extraction latency)
            // Use lifecycleScope (not viewLifecycleOwner) so prefetch survives navigation
            prefetchService.triggerPrefetch(video.id, lifecycleScope)

            // Navigate to video player
            findNavController().navigate(
                R.id.action_global_playerFragment,
                android.os.Bundle().apply {
                    putString("videoId", video.id)
                    putString("title", video.title)
                    putString("channelName", video.uploaderName ?: "")
                    putString("thumbnailUrl", video.thumbnailUrl ?: "")
                    putInt("durationSeconds", video.durationSeconds ?: 0)
                    putLong("viewCount", video.viewCount ?: -1L)
                }
            )
        }
    }

    override fun getState(): StateFlow<ChannelDetailViewModel.PaginatedState<ChannelVideo>> = viewModel.videosState

    override fun createAdapter(): RecyclerView.Adapter<*> = adapter

    override fun updateAdapterData(items: List<ChannelVideo>) {
        adapter.submitList(items)
    }
}
