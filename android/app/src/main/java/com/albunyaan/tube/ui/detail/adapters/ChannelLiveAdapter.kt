package com.albunyaan.tube.ui.detail.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelLiveStream
import com.albunyaan.tube.databinding.ItemChannelLiveBinding
import java.text.NumberFormat

/**
 * Adapter for live streams in the Live tab.
 * Shows LIVE or UPCOMING badge on thumbnails.
 */
class ChannelLiveAdapter(
    private val onStreamClick: (ChannelLiveStream) -> Unit
) : ListAdapter<ChannelLiveStream, ChannelLiveAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelLiveBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onStreamClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemChannelLiveBinding,
        private val onStreamClick: (ChannelLiveStream) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(stream: ChannelLiveStream) {
            binding.streamTitle.text = stream.title

            // Show LIVE or UPCOMING badge
            when {
                stream.isLiveNow -> {
                    binding.liveBadge.isVisible = true
                    binding.liveBadge.setText(R.string.live_badge)
                    binding.liveBadge.setBackgroundResource(R.drawable.bg_live_badge)
                    binding.upcomingBadge.isVisible = false
                }
                stream.isUpcoming -> {
                    binding.liveBadge.isVisible = false
                    binding.upcomingBadge.isVisible = true
                    binding.upcomingBadge.setText(R.string.upcoming_badge)
                }
                else -> {
                    binding.liveBadge.isVisible = false
                    binding.upcomingBadge.isVisible = false
                }
            }

            // View count
            val views = stream.viewCount?.let {
                NumberFormat.getInstance().format(it) + if (stream.isLiveNow) " watching" else " views"
            } ?: ""
            binding.streamMeta.text = views

            // Load thumbnail
            binding.streamThumbnail.load(stream.thumbnailUrl) {
                placeholder(R.drawable.thumbnail_placeholder)
                error(R.drawable.thumbnail_placeholder)
                crossfade(true)
            }

            binding.root.setOnClickListener {
                onStreamClick(stream)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChannelLiveStream>() {
            override fun areItemsTheSame(oldItem: ChannelLiveStream, newItem: ChannelLiveStream): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChannelLiveStream, newItem: ChannelLiveStream): Boolean =
                oldItem == newItem
        }
    }
}
