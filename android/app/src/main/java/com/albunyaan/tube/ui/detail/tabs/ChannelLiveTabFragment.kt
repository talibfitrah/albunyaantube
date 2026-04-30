package com.albunyaan.tube.ui.detail.tabs

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelLiveStream
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.player.PlaybackFeatureFlags
import com.albunyaan.tube.player.PredictivePrefetchController
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

    @Inject
    lateinit var featureFlags: PlaybackFeatureFlags

    private var prefetchController: PredictivePrefetchController? = null

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
        ChannelLiveAdapter(
            onStreamClick = { stream ->
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
            },
            onStreamLongPress = { stream ->
                // Long-press a livestream → report with contentSubType=LIVESTREAM
                // so admin resolve lands it in the channel's livestreams bucket.
                com.albunyaan.tube.ui.report.ContentReportBottomSheet.newInstance(
                    targetType = com.albunyaan.tube.data.report.ReportTargetType.VIDEO,
                    targetId = stream.id,
                    parentType = com.albunyaan.tube.data.report.ReportTargetType.CHANNEL,
                    parentId = channelId,
                    contentSubType = com.albunyaan.tube.data.report.ReportContentSubType.LIVESTREAM,
                ).show(parentFragmentManager, com.albunyaan.tube.ui.report.ContentReportBottomSheet.TAG)
            },
        )
    }

    override fun getState(): StateFlow<ChannelDetailViewModel.PaginatedState<ChannelLiveStream>> = viewModel.liveState

    override fun createAdapter(): RecyclerView.Adapter<*> = adapter

    override fun matchesQuery(item: ChannelLiveStream, lowerQuery: String): Boolean =
        item.title.lowercase().contains(lowerQuery) ||
        item.uploaderName?.lowercase()?.contains(lowerQuery) == true

    override fun updateAdapterData(items: List<ChannelLiveStream>) {
        adapter.submitList(items)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (featureFlags.isPredictivePrefetchEnabled) {
            prefetchController = PredictivePrefetchController(
                prefetchService,
                viewLifecycleOwner.lifecycleScope,
                videoIdResolver = { pos -> adapter.currentList.getOrNull(pos)?.id }
            )
            binding?.tabRecycler?.let { prefetchController?.attach(it) }
        }
    }

    override fun onDestroyView() {
        prefetchController?.detach()
        prefetchController = null
        super.onDestroyView()
    }
}
