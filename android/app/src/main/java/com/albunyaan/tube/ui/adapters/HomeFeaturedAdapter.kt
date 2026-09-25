package com.albunyaan.tube.ui.adapters

import com.albunyaan.tube.util.UploadAge
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
import com.albunyaan.tube.util.CountFormat
import com.albunyaan.tube.util.ImageLoading.loadThumbnail
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
        set(value) {
            if (field != value) {
                field = value
                if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_WIDTH)
            }
        }
    var channelCardWidth: Int = 0
        set(value) {
            if (field != value) {
                field = value
                if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_WIDTH)
            }
        }

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

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_WIDTH)) {
            // Width-only change — just update layout params without full rebind
            applyWidth(holder)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun applyWidth(holder: RecyclerView.ViewHolder) {
        val width = if (holder is ChannelViewHolder) channelCardWidth else cardWidth
        if (width > 0) {
            holder.itemView.layoutParams?.let { lp ->
                lp.width = width
                holder.itemView.layoutParams = lp
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        applyWidth(holder)
        val item = getItem(position)
        when (holder) {
            is ChannelViewHolder -> holder.bind(item as ContentItem.Channel)
            is PlaylistViewHolder -> holder.bind(item as ContentItem.Playlist)
            is VideoViewHolder -> holder.bind(item as ContentItem.Video)
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
            val locale = LocaleManager.getCurrentLocale(binding.root.context)
            val formatted = CountFormat.compact(count.toLong(), locale)
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
            val formattedViews = video.viewCount?.let {
                CountFormat.compact(it, appLocale)
            } ?: CountFormat.compact(0, appLocale)
            binding.videoMeta.text = metaLine(
                context.resources,
                context.getString(R.string.video_views_format, formattedViews),
                video.uploadedDaysAgo,
                video.category,
            )

            binding.videoDuration.text = formatDuration(video.durationSeconds)

            binding.videoThumbnail.loadThumbnail(video)

            val uploadedAgo = UploadAge.format(context.resources, video.uploadedDaysAgo)
            val viewsText = context.getString(R.string.video_views_format, formattedViews)
            val duration = formatDuration(video.durationSeconds)
            binding.root.contentDescription =
                videoDescription(context.resources, video.title, duration, viewsText, uploadedAgo)

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
        /**
         * a11y_video_item, minus its last field when the age is unknown. No age-less
         * string exists, so fill that field with a marker and cut at the separator
         * before it — whatever the locale's separator is (", " / "، ").
         */
        internal fun videoDescription(
            res: android.content.res.Resources,
            title: String,
            duration: String,
            views: String,
            age: String?,
        ): String {
            if (age != null) return res.getString(R.string.a11y_video_item, title, duration, views, age)
            val full = res.getString(R.string.a11y_video_item, title, duration, views, AGE_MARK)
            return cutAtMark(full, AGE_MARK)
        }

        private const val AGE_MARK = "\u0000"

        /**
         * [full] up to [mark], minus the separator before it. A translation whose
         * template lacks the age placeholder has no mark: keep it whole.
         */
        internal fun cutAtMark(full: String, mark: String): String {
            val at = full.indexOf(mark).takeIf { it >= 0 } ?: return full
            return full.substring(0, at).trimEnd { it == ',' || it == '،' || it.isWhitespace() }
        }

        /** Views • age • category — the same age ladder as every other list. */
        internal fun metaLine(
            res: android.content.res.Resources,
            views: String,
            daysAgo: Int?,
            category: String,
        ): String = UploadAge.joinMeta(views, UploadAge.format(res, daysAgo), category.takeIf { it.isNotBlank() })

        private const val PAYLOAD_WIDTH = "payload_width"
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
