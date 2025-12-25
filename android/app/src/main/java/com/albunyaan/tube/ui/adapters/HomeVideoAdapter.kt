package com.albunyaan.tube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemHomeVideoBinding
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.util.ImageLoading.loadThumbnail
import java.text.NumberFormat
import java.util.Locale

/**
 * Horizontal adapter for displaying videos in home screen sections
 */
class HomeVideoAdapter(
    private val onVideoClick: (ContentItem.Video) -> Unit
) : ListAdapter<ContentItem.Video, HomeVideoAdapter.ViewHolder>(DIFF_CALLBACK) {

    var cardWidth: Int = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHomeVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onVideoClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (cardWidth > 0) {
            holder.itemView.layoutParams?.let { lp ->
                lp.width = cardWidth
                holder.itemView.layoutParams = lp
            }
        }
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemHomeVideoBinding,
        private val onVideoClick: (ContentItem.Video) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val context get() = binding.root.context

        fun bind(video: ContentItem.Video) {
            binding.videoTitle.text = video.title

            val appLocale = LocaleManager.getCurrentLocale(context)
            val numberFormat = NumberFormat.getNumberInstance(appLocale)
            val formattedViews = video.viewCount?.let {
                numberFormat.format(it)
            } ?: numberFormat.format(0)
            val metaParts = mutableListOf<String>()
            metaParts.add(context.getString(R.string.video_views_format, formattedViews))
            metaParts.add(formatUploadedAgo(video.uploadedDaysAgo))
            if (video.category.isNotBlank()) {
                metaParts.add(video.category)
            }
            binding.videoMeta.text = metaParts.joinToString(" • ")

            binding.videoDuration.text = formatDuration(video.durationSeconds)

            binding.videoThumbnail.loadThumbnail(video)

            // Accessibility content description using localized string resource
            val uploadedAgo = formatUploadedAgo(video.uploadedDaysAgo)
            val viewsText = context.getString(R.string.video_views_format, formattedViews)
            val duration = formatDuration(video.durationSeconds)
            binding.root.contentDescription = context.getString(
                R.string.a11y_video_item,
                video.title,
                duration,
                viewsText,
                uploadedAgo
            )

            binding.root.setOnClickListener {
                onVideoClick(video)
            }
        }

        private fun formatUploadedAgo(days: Int): String {
            val res = binding.root.context.resources
            return if (days <= 0) {
                res.getString(R.string.video_uploaded_today)
            } else {
                res.getQuantityString(R.plurals.video_uploaded_days_ago, days, days)
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
