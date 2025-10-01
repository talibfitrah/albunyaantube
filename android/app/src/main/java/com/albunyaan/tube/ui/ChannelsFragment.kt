package com.albunyaan.tube.ui

import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.ui.detail.ChannelDetailFragment
import com.albunyaan.tube.ui.list.ContentListFragment

class ChannelsFragment : ContentListFragment() {
    override val contentType: ContentType = ContentType.CHANNELS

    override fun onContentClicked(item: ContentItem) {
        if (item is ContentItem.Channel) {
            val args = bundleOf(
                ChannelDetailFragment.ARG_CHANNEL_ID to item.id,
                ChannelDetailFragment.ARG_CHANNEL_NAME to item.name
            )
            findNavController().navigate(R.id.action_channelsFragment_to_channelDetailFragment, args)
        }
    }
}
