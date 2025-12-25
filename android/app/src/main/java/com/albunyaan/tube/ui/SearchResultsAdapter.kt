package com.albunyaan.tube.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemChannelBinding
import com.albunyaan.tube.databinding.ItemPlaylistBinding
import com.albunyaan.tube.databinding.ItemVideoListBinding
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.util.ImageLoading.loadThumbnailUrl
import com.google.android.material.chip.Chip
import java.text.NumberFormat
import java.util.Locale

/**
 * Search results adapter using the same layouts as FeaturedListAdapter
 * for consistent visual design across the app.
 */
class SearchResultsAdapter(
    private val imageLoader: ImageLoader,
    private val enableImages: Boolean = true,
    private val onItemClick: (ContentItem) -> Unit
) : ListAdapter<ContentItem, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ContentItem.Channel -> VIEW_TYPE_CHANNEL
            is ContentItem.Playlist -> VIEW_TYPE_PLAYLIST
            is ContentItem.Video -> VIEW_TYPE_VIDEO
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_CHANNEL -> {
                val binding = ItemChannelBinding.inflate(inflater, parent, false)
                ChannelViewHolder(binding, onItemClick, enableImages)
            }
            VIEW_TYPE_PLAYLIST -> {
                val binding = ItemPlaylistBinding.inflate(inflater, parent, false)
                PlaylistViewHolder(binding, onItemClick, enableImages)
            }
            VIEW_TYPE_VIDEO -> {
                val binding = ItemVideoListBinding.inflate(inflater, parent, false)
                VideoViewHolder(binding, onItemClick, enableImages)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ChannelViewHolder -> holder.bind(item as ContentItem.Channel)
            is PlaylistViewHolder -> holder.bind(item as ContentItem.Playlist)
            is VideoViewHolder -> holder.bind(item as ContentItem.Video)
        }
    }

    // ========== Channel ViewHolder ==========
    class ChannelViewHolder(
        private val binding: ItemChannelBinding,
        private val onItemClick: (ContentItem) -> Unit,
        private val enableImages: Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: ContentItem.Channel) {
            val context = binding.root.context
            binding.channelName.text = channel.name

            val appLocale = LocaleManager.getCurrentLocale(context)
            val formattedSubs = NumberFormat.getNumberInstance(appLocale).format(channel.subscribers)
            binding.subscriberCount.text = context.getString(
                R.string.channel_subscribers_format,
                formattedSubs
            )

            if (enableImages) {
                binding.channelAvatar.loadThumbnailUrl(
                    url = channel.thumbnailUrl,
                    placeholder = R.drawable.onboarding_icon_bg,
                    circleCrop = true
                )
            } else {
                binding.channelAvatar.setImageResource(R.drawable.onboarding_icon_bg)
            }

            binding.categoryChipsContainer.removeAllViews()
            val categories = channel.categories ?: listOf(channel.category)
            val firstCategory = categories.firstOrNull() ?: channel.category
            val remainingCount = categories.size - 1

            val chipText = if (remainingCount > 0) {
                val formattedCount = NumberFormat.getNumberInstance(appLocale).format(remainingCount)
                context.getString(R.string.category_with_overflow, firstCategory, formattedCount)
            } else {
                firstCategory
            }

            val chip = Chip(binding.root.context).apply {
                text = chipText
                isClickable = false
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    binding.root.context.getColor(R.color.surface_variant)
                )
                setTextColor(binding.root.context.getColor(R.color.primary_green))
            }
            binding.categoryChipsContainer.addView(chip)

            binding.root.setOnClickListener {
                onItemClick(channel)
            }
        }
    }

    // ========== Playlist ViewHolder ==========
    class PlaylistViewHolder(
        private val binding: ItemPlaylistBinding,
        private val onItemClick: (ContentItem) -> Unit,
        private val enableImages: Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: ContentItem.Playlist) {
            binding.playlistTitle.text = playlist.title
            binding.playlistMeta.text = binding.root.context.resources.getQuantityString(
                R.plurals.playlist_item_count,
                playlist.itemCount,
                playlist.itemCount
            )

            if (enableImages) {
                binding.playlistThumbnail.loadThumbnailUrl(playlist.thumbnailUrl)
            } else {
                binding.playlistThumbnail.setImageResource(R.drawable.thumbnail_placeholder)
            }

            binding.categoryChipsContainer.removeAllViews()
            val chip = Chip(binding.root.context).apply {
                text = playlist.category
                isClickable = false
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    binding.root.context.getColor(R.color.surface_variant)
                )
                setTextColor(binding.root.context.getColor(R.color.primary_green))
            }
            binding.categoryChipsContainer.addView(chip)

            binding.root.setOnClickListener {
                onItemClick(playlist)
            }
        }
    }

    // ========== Video ViewHolder ==========
    class VideoViewHolder(
        private val binding: ItemVideoListBinding,
        private val onItemClick: (ContentItem) -> Unit,
        private val enableImages: Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: ContentItem.Video) {
            binding.videoTitle.text = video.title
            binding.videoDuration.text = formatDuration(video.durationSeconds)

            val context = binding.root.context
            val res = context.resources
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

            if (enableImages) {
                binding.videoThumbnail.loadThumbnailUrl(video.thumbnailUrl)
            } else {
                binding.videoThumbnail.setImageResource(R.drawable.thumbnail_placeholder)
            }

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
                onItemClick(video)
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
    }

    companion object {
        private const val VIEW_TYPE_CHANNEL = 0
        private const val VIEW_TYPE_PLAYLIST = 1
        private const val VIEW_TYPE_VIDEO = 2

        /**
         * Safely converts a Long count to Int for plural quantity selection.
         * Clamps to Int.MAX_VALUE to prevent overflow for very large counts.
         */
        private fun safeQuantityForPlural(count: Long): Int {
            return count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private val DIFF = object : DiffUtil.ItemCallback<ContentItem>() {
            override fun areItemsTheSame(oldItem: ContentItem, newItem: ContentItem): Boolean {
                return when {
                    oldItem is ContentItem.Video && newItem is ContentItem.Video -> oldItem.id == newItem.id
                    oldItem is ContentItem.Channel && newItem is ContentItem.Channel -> oldItem.id == newItem.id
                    oldItem is ContentItem.Playlist && newItem is ContentItem.Playlist -> oldItem.id == newItem.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: ContentItem, newItem: ContentItem): Boolean = oldItem == newItem
        }
    }
}
