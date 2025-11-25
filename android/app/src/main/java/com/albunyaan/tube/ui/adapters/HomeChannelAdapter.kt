package com.albunyaan.tube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.albunyaan.tube.R
import java.util.Locale
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemHomeChannelBinding

/**
 * Horizontal adapter for displaying channels in home screen sections
 */
class HomeChannelAdapter(
    private val onChannelClick: (ContentItem.Channel) -> Unit
) : ListAdapter<ContentItem.Channel, HomeChannelAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHomeChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onChannelClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemHomeChannelBinding,
        private val onChannelClick: (ContentItem.Channel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: ContentItem.Channel) {
            binding.channelName.text = channel.name
            binding.subscriberCount.text = formatSubscriberCount(channel.subscribers)

            binding.channelAvatar.load(channel.thumbnailUrl) {
                transformations(CircleCropTransformation())
                placeholder(R.drawable.home_channel_avatar_bg)
                error(R.drawable.home_channel_avatar_bg)
            }

            binding.root.setOnClickListener {
                onChannelClick(channel)
            }
        }

        private fun formatSubscriberCount(count: Int): String {
            val formatted = when {
                count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
                count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
                else -> count.toString()
            }
            return binding.root.context.getString(R.string.channel_subscribers_format, formatted)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ContentItem.Channel>() {
            override fun areItemsTheSame(
                oldItem: ContentItem.Channel,
                newItem: ContentItem.Channel
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: ContentItem.Channel,
                newItem: ContentItem.Channel
            ): Boolean = oldItem == newItem
        }
    }
}
