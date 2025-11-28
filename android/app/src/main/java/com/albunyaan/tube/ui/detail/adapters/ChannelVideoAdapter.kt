package com.albunyaan.tube.ui.detail.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelVideo
import com.albunyaan.tube.databinding.ItemVideoListBinding
import java.text.NumberFormat
import java.util.Locale

/**
 * Adapter for channel videos in the Videos tab.
 * Reuses the item_video_list layout.
 */
class ChannelVideoAdapter(
    private val onVideoClick: (ChannelVideo) -> Unit
) : ListAdapter<ChannelVideo, ChannelVideoAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoListBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onVideoClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemVideoListBinding,
        private val onVideoClick: (ChannelVideo) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: ChannelVideo) {
            binding.videoTitle.text = video.title

            // Format duration
            binding.videoDuration.text = video.durationSeconds?.let { formatDuration(it) } ?: ""

            // Format metadata (views + time)
            val views = video.viewCount?.let {
                NumberFormat.getInstance().format(it) + " views"
            } ?: ""
            val timeAgo = video.publishedTime ?: ""

            binding.videoMeta.text = if (views.isNotEmpty() && timeAgo.isNotEmpty()) {
                "$views • $timeAgo"
            } else {
                views.ifEmpty { timeAgo }
            }

            // Load thumbnail
            binding.videoThumbnail.load(video.thumbnailUrl) {
                placeholder(R.drawable.thumbnail_placeholder)
                error(R.drawable.thumbnail_placeholder)
                crossfade(true)
            }

            // Hide category chips (not available from NewPipe channel videos)
            binding.categoryChipsContainer.visibility = android.view.View.GONE

            binding.root.setOnClickListener {
                onVideoClick(video)
            }
        }

        private fun formatDuration(totalSeconds: Int): String {
            val hours = totalSeconds / 3600
            val mins = (totalSeconds % 3600) / 60
            val secs = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
            } else {
                String.format(Locale.US, "%d:%02d", mins, secs)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChannelVideo>() {
            override fun areItemsTheSame(oldItem: ChannelVideo, newItem: ChannelVideo): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChannelVideo, newItem: ChannelVideo): Boolean =
                oldItem == newItem
        }
    }
}
