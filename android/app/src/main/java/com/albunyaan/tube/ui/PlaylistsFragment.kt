package com.albunyaan.tube.ui

import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.ui.detail.DownloadPolicy
import com.albunyaan.tube.ui.detail.PlaylistDetailFragment
import com.albunyaan.tube.ui.list.ContentListFragment

class PlaylistsFragment : ContentListFragment() {
    override val contentType: ContentType = ContentType.PLAYLISTS

    override fun onContentClicked(item: ContentItem) {
        if (item is ContentItem.Playlist) {
            val policy = evaluateDownloadPolicy(item)
            val args = bundleOf(
                PlaylistDetailFragment.ARG_PLAYLIST_ID to item.id,
                PlaylistDetailFragment.ARG_PLAYLIST_TITLE to item.title,
                PlaylistDetailFragment.ARG_PLAYLIST_CATEGORY to item.category,
                PlaylistDetailFragment.ARG_PLAYLIST_COUNT to item.itemCount,
                PlaylistDetailFragment.ARG_DOWNLOAD_POLICY to policy.name
            )
            findNavController().navigate(R.id.action_playlistsFragment_to_playlistDetailFragment, args)
        }
    }

    private fun evaluateDownloadPolicy(item: ContentItem.Playlist): DownloadPolicy {
        return when {
            item.itemCount == 0 -> DownloadPolicy.DISABLED
            item.itemCount > 50 -> DownloadPolicy.QUEUED
            else -> DownloadPolicy.ENABLED
        }
    }
}
