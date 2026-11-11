package com.albunyaan.tube.ui.adapters

import com.albunyaan.tube.util.UploadAge
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemVideoGridBinding
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.util.CountFormat
import com.albunyaan.tube.util.DurationFormat
import com.albunyaan.tube.util.ImageLoading.loadYouTubeThumbnail
import com.google.android.material.chip.Chip

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
            val badge = DurationFormat.badge(video.durationSeconds)
            binding.videoDuration.text = badge ?: ""
            binding.videoDuration.isVisible = badge != null

            // Format metadata (views + time ago) using app's per-app locale
            val appLocale = LocaleManager.getCurrentLocale(context)
            val views = video.viewCount?.let { viewCount ->
                val formattedCount = CountFormat.compact(viewCount, appLocale)
                res.getQuantityString(
                    R.plurals.video_views,
                    safeQuantityForPlural(CountFormat.compactPluralCount(viewCount)),
                    formattedCount
                )
            } ?: ""
            val timeAgo = UploadAge.format(res, video.uploadedDaysAgo)

            binding.videoMeta.text = UploadAge.joinMeta(views, timeAgo)

            // Load thumbnail with YouTube fallback chain
            binding.videoThumbnail.loadYouTubeThumbnail(video.thumbnailUrl, video.id)

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
