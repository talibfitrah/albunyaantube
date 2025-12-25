package com.albunyaan.tube.ui.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemVideoGridBinding
import com.albunyaan.tube.locale.LocaleManager
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
            val context = binding.root.context
            val res = context.resources
            binding.videoTitle.text = video.title

            // Format duration (HH:mm:ss or mm:ss)
            binding.videoDuration.text = formatDuration(video.durationSeconds)

            // Format metadata (views + time ago) using app's per-app locale
            val appLocale = LocaleManager.getCurrentLocale(context)
            val views = video.viewCount?.let { viewCount ->
                val formattedCount = NumberFormat.getNumberInstance(appLocale).format(viewCount)
                res.getQuantityString(
                    R.plurals.video_views,
                    safeQuantityForPlural(viewCount),
                    formattedCount
                )
            } ?: ""
            val timeAgo = formatTimeAgo(context, video.uploadedDaysAgo)

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

        private fun formatTimeAgo(context: Context, daysAgo: Int): String {
            val res = context.resources
            return when {
                daysAgo <= 0 -> context.getString(R.string.video_uploaded_today)
                daysAgo < 7 -> res.getQuantityString(R.plurals.video_uploaded_days_ago, daysAgo, daysAgo)
                daysAgo < 30 -> {
                    val weeks = daysAgo / 7
                    res.getQuantityString(R.plurals.time_ago_weeks, weeks, weeks)
                }
                daysAgo < 365 -> {
                    val months = daysAgo / 30
                    res.getQuantityString(R.plurals.time_ago_months, months, months)
                }
                else -> {
                    val years = daysAgo / 365
                    res.getQuantityString(R.plurals.time_ago_years, years, years)
                }
            }
        }

        private fun safeQuantityForPlural(count: Long): Int {
            return count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
