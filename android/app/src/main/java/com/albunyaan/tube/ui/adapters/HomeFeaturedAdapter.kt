package com.albunyaan.tube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemHomeFeaturedBinding
import com.albunyaan.tube.util.ImageLoading.loadThumbnail
import java.text.NumberFormat
import java.util.Locale

/**
 * Horizontal adapter for displaying mixed content types (videos, playlists, channels)
 * in the Featured section on the home screen. Each card displays a type badge to
 * indicate what type of content it represents.
 */
class HomeFeaturedAdapter(
    private val onItemClick: (ContentItem) -> Unit
) : ListAdapter<ContentItem, HomeFeaturedAdapter.ViewHolder>(DIFF_CALLBACK) {

    var cardWidth: Int = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHomeFeaturedBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onItemClick, ::getItem)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (cardWidth > 0) {
            val params = holder.itemView.layoutParams
            params.width = cardWidth
            holder.itemView.layoutParams = params
        }
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemHomeFeaturedBinding,
        private val onItemClick: (ContentItem) -> Unit,
        private val getItemAt: (Int) -> ContentItem
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItemAt(position))
                }
            }
        }

        fun bind(item: ContentItem) {
            val context = binding.root.context

            when (item) {
                is ContentItem.Video -> {
                    binding.featuredTitle.text = item.title
                    binding.featuredMeta.text = buildVideoMeta(item)
                    binding.typeBadge.text = context.getString(R.string.type_video)
                    binding.typeBadge.isVisible = true
                    binding.durationBadge.text = formatDuration(item.durationSeconds)
                    binding.durationBadge.isVisible = true
                    binding.featuredThumbnail.loadThumbnail(item)

                    // Accessibility - only include views if available
                    binding.root.contentDescription = buildVideoA11yDescription(item)
                }
                is ContentItem.Playlist -> {
                    binding.featuredTitle.text = item.title
                    binding.featuredMeta.text = context.resources.getQuantityString(
                        R.plurals.video_count,
                        item.itemCount,
                        item.itemCount
                    )
                    binding.typeBadge.text = context.getString(R.string.type_playlist)
                    binding.typeBadge.isVisible = true
                    binding.durationBadge.isVisible = false
                    binding.featuredThumbnail.loadThumbnail(item)

                    // Accessibility
                    binding.root.contentDescription = context.getString(
                        R.string.a11y_playlist_item,
                        item.title,
                        item.itemCount
                    )
                }
                is ContentItem.Channel -> {
                    binding.featuredTitle.text = item.name
                    val subscriberText = formatSubscriberCount(item.subscribers)
                    binding.featuredMeta.text = context.getString(R.string.channel_subscribers_format, subscriberText)
                    binding.typeBadge.text = context.getString(R.string.type_channel)
                    binding.typeBadge.isVisible = true
                    binding.durationBadge.isVisible = false
                    binding.featuredThumbnail.loadThumbnail(item)

                    // Accessibility
                    binding.root.contentDescription = context.getString(
                        R.string.a11y_channel_item,
                        item.name,
                        context.getString(R.string.channel_subscribers_format, subscriberText)
                    )
                }
            }
        }

        private fun buildVideoMeta(video: ContentItem.Video): String {
            val context = binding.root.context
            val parts = mutableListOf<String>()
            video.viewCount?.let {
                parts.add(context.getString(R.string.video_views_format, formatViewCount(it)))
            }
            parts.add(formatUploadedAgo(video.uploadedDaysAgo))
            return parts.joinToString(" • ")
        }

        private fun buildVideoA11yDescription(video: ContentItem.Video): String {
            val context = binding.root.context
            val parts = mutableListOf<String>()
            parts.add(context.getString(R.string.type_video))
            parts.add(video.title)
            parts.add(context.getString(R.string.a11y_duration_format, formatDuration(video.durationSeconds)))
            video.viewCount?.let {
                parts.add(context.getString(R.string.video_views_format, formatViewCount(it)))
            }
            parts.add(formatUploadedAgo(video.uploadedDaysAgo))
            return parts.joinToString(", ")
        }

        private fun formatViewCount(count: Long): String {
            return when {
                count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
                count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
                else -> NumberFormat.getInstance(Locale.US).format(count)
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

        private fun formatSubscriberCount(count: Int): String {
            return when {
                count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
                count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
                else -> NumberFormat.getInstance(Locale.US).format(count)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ContentItem>() {
            override fun areItemsTheSame(
                oldItem: ContentItem,
                newItem: ContentItem
            ): Boolean {
                return when {
                    oldItem is ContentItem.Video && newItem is ContentItem.Video -> oldItem.id == newItem.id
                    oldItem is ContentItem.Playlist && newItem is ContentItem.Playlist -> oldItem.id == newItem.id
                    oldItem is ContentItem.Channel && newItem is ContentItem.Channel -> oldItem.id == newItem.id
                    else -> false
                }
            }

            override fun areContentsTheSame(
                oldItem: ContentItem,
                newItem: ContentItem
            ): Boolean = oldItem == newItem
        }
    }
}
