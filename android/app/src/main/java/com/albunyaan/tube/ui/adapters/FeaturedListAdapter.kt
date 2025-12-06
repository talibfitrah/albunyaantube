package com.albunyaan.tube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemChannelBinding
import com.albunyaan.tube.databinding.ItemPlaylistBinding
import com.albunyaan.tube.databinding.ItemVideoListBinding
import com.albunyaan.tube.util.ImageLoading.loadThumbnailUrl
import com.google.android.material.chip.Chip
import java.text.NumberFormat
import java.util.Locale

/**
 * Vertical list adapter for displaying mixed content types (videos, playlists, channels)
 * in the Featured list screen. Uses the same layouts as the tab list screens.
 */
class FeaturedListAdapter(
    private val onItemClick: (ContentItem) -> Unit
) : ListAdapter<ContentItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

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
                ChannelViewHolder(binding, onItemClick)
            }
            VIEW_TYPE_PLAYLIST -> {
                val binding = ItemPlaylistBinding.inflate(inflater, parent, false)
                PlaylistViewHolder(binding, onItemClick)
            }
            VIEW_TYPE_VIDEO -> {
                val binding = ItemVideoListBinding.inflate(inflater, parent, false)
                VideoViewHolder(binding, onItemClick)
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
        private val onItemClick: (ContentItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: ContentItem.Channel) {
            binding.channelName.text = channel.name

            val formattedSubs = NumberFormat.getInstance().format(channel.subscribers)
            binding.subscriberCount.text = binding.root.context.getString(
                R.string.channel_subscribers_format,
                formattedSubs
            )

            binding.channelAvatar.loadThumbnailUrl(
                url = channel.thumbnailUrl,
                placeholder = R.drawable.onboarding_icon_bg,
                circleCrop = true
            )

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
        private val onItemClick: (ContentItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: ContentItem.Playlist) {
            binding.playlistTitle.text = playlist.title
            binding.playlistMeta.text = "${playlist.itemCount} items"

            binding.playlistThumbnail.loadThumbnailUrl(playlist.thumbnailUrl)

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
        private val onItemClick: (ContentItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: ContentItem.Video) {
            binding.videoTitle.text = video.title
            binding.videoDuration.text = formatDuration(video.durationSeconds)

            val views = video.viewCount?.let {
                NumberFormat.getInstance().format(it) + " views"
            } ?: ""
            val timeAgo = if (video.uploadedDaysAgo > 0) {
                when {
                    video.uploadedDaysAgo == 1 -> "1 day ago"
                    video.uploadedDaysAgo < 7 -> "${video.uploadedDaysAgo} days ago"
                    video.uploadedDaysAgo < 30 -> {
                        val weeks = video.uploadedDaysAgo / 7
                        if (weeks == 1) "1 week ago" else "$weeks weeks ago"
                    }
                    else -> {
                        val months = video.uploadedDaysAgo / 30
                        if (months == 1) "1 month ago" else "$months months ago"
                    }
                }
            } else {
                "today"
            }

            binding.videoMeta.text = if (views.isNotEmpty()) {
                "$views • $timeAgo"
            } else {
                timeAgo
            }

            binding.videoThumbnail.loadThumbnailUrl(video.thumbnailUrl)

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
    }

    companion object {
        private const val VIEW_TYPE_CHANNEL = 0
        private const val VIEW_TYPE_PLAYLIST = 1
        private const val VIEW_TYPE_VIDEO = 2

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
