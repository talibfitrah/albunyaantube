package com.albunyaan.tube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemVideoGridBinding
import com.google.android.material.chip.Chip
import java.text.NumberFormat

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

            // Format duration
            binding.videoDuration.text = "${video.durationMinutes}:00"

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

            // Load thumbnail
            binding.videoThumbnail.load(video.thumbnailUrl) {
                placeholder(R.drawable.thumbnail_placeholder)
                error(R.drawable.thumbnail_placeholder)
                crossfade(true)
            }

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
