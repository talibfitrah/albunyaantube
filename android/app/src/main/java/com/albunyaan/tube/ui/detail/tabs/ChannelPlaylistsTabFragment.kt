package com.albunyaan.tube.ui.detail.tabs

import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelPlaylist
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.ui.detail.ChannelDetailViewModel
import com.albunyaan.tube.ui.detail.adapters.ChannelPlaylistsAdapter
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.flow.StateFlow

/**
 * Fragment for the Playlists tab in Channel Detail.
 * Displays channel playlists in a list.
 */
@AndroidEntryPoint
class ChannelPlaylistsTabFragment : BaseChannelListTabFragment<ChannelPlaylist>() {

    override val tab: ChannelTab = ChannelTab.PLAYLISTS
    override val emptyMessageRes: Int = R.string.channel_playlists_empty

    private val channelId: String by lazy {
        requireNotNull(requireParentFragment().arguments?.getString("channelId")) {
            "ChannelPlaylistsTabFragment requires channelId argument"
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
        ChannelPlaylistsAdapter(
            onPlaylistClick = { playlist ->
                // Navigate to playlist detail using global action
                findNavController().navigate(
                    R.id.action_global_playlistDetailFragment,
                    android.os.Bundle().apply {
                        putString("playlistId", playlist.id)
                        putString("playlistTitle", playlist.title)
                        putBoolean("excluded", false)
                    }
                )
            },
            onPlaylistLongPress = { playlist ->
                // Long-press a playlist within a channel → report it with
                // parent=CHANNEL so admin resolve adds the playlist id to
                // the channel's playlists exclusion bucket.
                com.albunyaan.tube.ui.report.ContentReportBottomSheet.newInstance(
                    targetType = com.albunyaan.tube.data.report.ReportTargetType.PLAYLIST,
                    targetId = playlist.id,
                    parentType = com.albunyaan.tube.data.report.ReportTargetType.CHANNEL,
                    parentId = channelId,
                    contentSubType = null,
                ).show(parentFragmentManager, com.albunyaan.tube.ui.report.ContentReportBottomSheet.TAG)
            },
        )
    }

    override fun getState(): StateFlow<ChannelDetailViewModel.PaginatedState<ChannelPlaylist>> = viewModel.playlistsState

    override fun createAdapter(): RecyclerView.Adapter<*> = adapter

    override fun matchesQuery(item: ChannelPlaylist, lowerQuery: String): Boolean =
        item.title.lowercase().contains(lowerQuery) ||
        item.uploaderName?.lowercase()?.contains(lowerQuery) == true

    override fun updateAdapterData(items: List<ChannelPlaylist>) {
        adapter.submitList(items)
    }
}
