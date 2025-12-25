package com.albunyaan.tube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemHomeChannelBinding
import com.albunyaan.tube.databinding.ItemHomePlaylistBinding
import com.albunyaan.tube.databinding.ItemHomeVideoBinding
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.util.ImageLoading.loadThumbnail
import java.text.NumberFormat
import java.util.Locale

/**
 * Horizontal adapter for displaying mixed content types (videos, playlists, channels)
 * in the Featured section on the home screen. Each item uses the same layout as its
 * corresponding section (channels use circular avatars, videos/playlists use cards).
 */
class HomeFeaturedAdapter(
    private val onItemClick: (ContentItem) -> Unit
) : ListAdapter<ContentItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    var cardWidth: Int = 0
    var channelCardWidth: Int = 0

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
                val binding = ItemHomeChannelBinding.inflate(inflater, parent, false)
                ChannelViewHolder(binding, onItemClick)
            }
            VIEW_TYPE_PLAYLIST -> {
                val binding = ItemHomePlaylistBinding.inflate(inflater, parent, false)
                PlaylistViewHolder(binding, onItemClick)
            }
            VIEW_TYPE_VIDEO -> {
                val binding = ItemHomeVideoBinding.inflate(inflater, parent, false)
                VideoViewHolder(binding, onItemClick)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ChannelViewHolder -> {
                if (channelCardWidth > 0) {
                    holder.itemView.layoutParams?.let { lp ->
                        lp.width = channelCardWidth
                        holder.itemView.layoutParams = lp
                    }
                }
                holder.bind(item as ContentItem.Channel)
            }
            is PlaylistViewHolder -> {
                if (cardWidth > 0) {
                    holder.itemView.layoutParams?.let { lp ->
                        lp.width = cardWidth
                        holder.itemView.layoutParams = lp
                    }
                }
                holder.bind(item as ContentItem.Playlist)
            }
            is VideoViewHolder -> {
                if (cardWidth > 0) {
                    holder.itemView.layoutParams?.let { lp ->
                        lp.width = cardWidth
                        holder.itemView.layoutParams = lp
                    }
                }
                holder.bind(item as ContentItem.Video)
            }
        }
    }

    // ========== Channel ViewHolder ==========
    class ChannelViewHolder(
        private val binding: ItemHomeChannelBinding,
        private val onItemClick: (ContentItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: ContentItem.Channel) {
            binding.channelName.text = channel.name
            val subscriberText = formatSubscriberCount(channel.subscribers)
            binding.subscriberCount.text = subscriberText

            binding.channelAvatar.loadThumbnail(channel)

            binding.root.contentDescription = binding.root.context.getString(
                R.string.a11y_channel_item,
                channel.name,
                subscriberText
            )

            binding.root.setOnClickListener {
                onItemClick(channel)
            }
        }

        private fun formatSubscriberCount(count: Int): String {
            val formatted = when {
                count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
                count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
                else -> count.toString()
            }
            return binding.root.context.getString(R.string.channel_subscribers_format, formatted)
        }
    }

    // ========== Playlist ViewHolder ==========
    class PlaylistViewHolder(
        private val binding: ItemHomePlaylistBinding,
        private val onItemClick: (ContentItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: ContentItem.Playlist) {
            binding.playlistTitle.text = playlist.title
            binding.channelName.text = playlist.category

            val videoCountText = binding.root.context.resources.getQuantityString(
                R.plurals.video_count,
                playlist.itemCount,
                playlist.itemCount
            )
            binding.videoCount.text = videoCountText

            binding.playlistThumbnail.loadThumbnail(playlist)

            binding.root.contentDescription = binding.root.context.getString(
                R.string.a11y_playlist_item,
                playlist.title,
                playlist.itemCount
            )

            binding.root.setOnClickListener {
                onItemClick(playlist)
            }
        }
    }

    // ========== Video ViewHolder ==========
    class VideoViewHolder(
        private val binding: ItemHomeVideoBinding,
        private val onItemClick: (ContentItem) -> Unit
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
                onItemClick(video)
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
