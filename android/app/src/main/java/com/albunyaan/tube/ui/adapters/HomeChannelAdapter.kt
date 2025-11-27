package com.albunyaan.tube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import java.util.Locale
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemHomeChannelBinding
import com.albunyaan.tube.util.ImageLoading.loadThumbnail

/**
 * Horizontal adapter for displaying channels in home screen sections
 */
class HomeChannelAdapter(
    private val onChannelClick: (ContentItem.Channel) -> Unit
) : ListAdapter<ContentItem.Channel, HomeChannelAdapter.ViewHolder>(DIFF_CALLBACK) {

    var cardWidth: Int = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHomeChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onChannelClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (cardWidth > 0) {
            holder.itemView.layoutParams?.apply {
                width = cardWidth
            }
        }
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemHomeChannelBinding,
        private val onChannelClick: (ContentItem.Channel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: ContentItem.Channel) {
            binding.channelName.text = channel.name
            val subscriberText = formatSubscriberCount(channel.subscribers)
            binding.subscriberCount.text = subscriberText

            binding.channelAvatar.loadThumbnail(channel)

            // Accessibility content description using localized string resource
            binding.root.contentDescription = binding.root.context.getString(
                R.string.a11y_channel_item,
                channel.name,
                subscriberText
            )

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
