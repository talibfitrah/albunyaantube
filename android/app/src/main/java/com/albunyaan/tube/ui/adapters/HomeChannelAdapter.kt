package com.albunyaan.tube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemChannelBinding
import com.google.android.material.chip.Chip
import java.text.NumberFormat

/**
 * Horizontal adapter for displaying channels in home screen sections
 */
class HomeChannelAdapter(
    private val onChannelClick: (ContentItem.Channel) -> Unit
) : ListAdapter<ContentItem.Channel, HomeChannelAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelBinding.inflate(
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
        private val binding: ItemChannelBinding,
        private val onChannelClick: (ContentItem.Channel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: ContentItem.Channel) {
            binding.channelName.text = channel.name

            val formattedSubs = NumberFormat.getInstance().format(channel.subscribers)
            binding.subscriberCount.text = "$formattedSubs subscribers"

            binding.channelAvatar.load(channel.thumbnailUrl) {
                transformations(CircleCropTransformation())
                placeholder(R.drawable.onboarding_icon_bg)
                error(R.drawable.onboarding_icon_bg)
            }

            // Show first category
            binding.categoryChipsContainer.removeAllViews()
            val categories = channel.categories ?: listOf(channel.category)
            val firstCategory = categories.firstOrNull() ?: channel.category
            val remainingCount = categories.size - 1

            val chipText = if (remainingCount > 0) {
                "$firstCategory +$remainingCount"
            } else {
                firstCategory
            }

            val chip = Chip(binding.root.context).apply {
                text = chipText
                isClickable = false
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    context.getColor(R.color.surface_variant)
                )
                setTextColor(context.getColor(R.color.primary_green))
            }
            binding.categoryChipsContainer.addView(chip)

            binding.root.setOnClickListener {
                onChannelClick(channel)
            }
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
