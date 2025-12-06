package com.albunyaan.tube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemVideoGridBinding
import com.albunyaan.tube.util.ImageLoading.loadThumbnailUrl
import com.google.android.material.chip.Chip
import java.text.NumberFormat
import java.util.Locale

class VideoGridAdapter(
    private val onVideoClick: (ContentItem.Video) -> Unit
) : ListAdapter<ContentItem.Video, VideoGridAdapter.VideoViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoGridBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VideoViewHolder(binding, onVideoClick)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class VideoViewHolder(
        private val binding: ItemVideoGridBinding,
        private val onVideoClick: (ContentItem.Video) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: ContentItem.Video) {
            binding.videoTitle.text = video.title

            // Format duration (HH:mm:ss or mm:ss)
            binding.videoDuration.text = formatDuration(video.durationSeconds)

            // Format metadata (views + time ago)
            val views = video.viewCount?.let {
                NumberFormat.getInstance().format(it) + " views"
            } ?: ""
            val timeAgo = if (video.uploadedDaysAgo > 0) {
                when {
                    video.uploadedDaysAgo == 1 -> "1 day ago"
                    video.uploadedDaysAgo < 7 -> "${video.uploadedDaysAgo} days ago"
                    video.uploadedDaysAgo < 30 -> "${video.uploadedDaysAgo / 7} weeks ago"
                    else -> "${video.uploadedDaysAgo / 30} months ago"
                }
            } else {
                "today"
            }

            binding.videoMeta.text = if (views.isNotEmpty()) {
                "$views • $timeAgo"
            } else {
                timeAgo
            }

            // Load thumbnail with aggressive caching
            binding.videoThumbnail.loadThumbnailUrl(video.thumbnailUrl)

            // Add category chip
            binding.categoryChipsContainer.removeAllViews()
            val chip = Chip(binding.root.context).apply {
                text = video.category
                isClickable = false
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    binding.root.context.getColor(R.color.surface_variant)
                )
                setTextColor(binding.root.context.getColor(R.color.primary_green))
            }
            binding.categoryChipsContainer.addView(chip)

            binding.root.setOnClickListener {
                onVideoClick(video)
            }
        }

        /**
         * Format duration in seconds to HH:mm:ss (if >= 1 hour) or mm:ss (if < 1 hour)
         */
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
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ContentItem.Video>() {
            override fun areItemsTheSame(
                oldItem: ContentItem.Video,
                newItem: ContentItem.Video
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: ContentItem.Video,
                newItem: ContentItem.Video
            ): Boolean = oldItem == newItem
        }
    }
}
