package com.albunyaan.tube.ui.detail.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.playlist.PlaylistItem
import com.albunyaan.tube.databinding.ItemPlaylistVideoBinding
import com.albunyaan.tube.download.DownloadStatus
import com.albunyaan.tube.locale.LocaleManager
import com.albunyaan.tube.util.CountFormat
import com.albunyaan.tube.util.DurationFormat

/**
 * UI model for playlist video item with download state.
 */
data class PlaylistVideoUiItem(
    val item: PlaylistItem,
    val downloadStatus: DownloadStatus? = null,
    val downloadProgress: Int = 0
)

/**
 * Adapter for playlist videos with position numbers and download indicators.
 * Supports showing download state per item.
 */
class PlaylistVideosAdapter(
    private val onVideoClick: (PlaylistItem, Int) -> Unit
) : ListAdapter<PlaylistVideoUiItem, PlaylistVideosAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlaylistVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onVideoClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemPlaylistVideoBinding,
        private val onVideoClick: (PlaylistItem, Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(uiItem: PlaylistVideoUiItem) {
            val item = uiItem.item
            val context = binding.root.context

            // Position number (1-based)
            binding.positionNumber.text = item.position.toString()

            // Video title
            binding.videoTitle.text = item.title

            // Format duration
            val badge = DurationFormat.badge(item.durationSeconds)
            binding.videoDuration.text = badge ?: ""
            binding.videoDuration.isVisible = badge != null

            // Format metadata (channel + views or just channel), views compacted per locale
            val channelName = item.channelName ?: ""
            val views = item.viewCount?.let { count ->
                val formattedCount = CountFormat.compact(count, LocaleManager.getCurrentLocale(context))
                context.getString(R.string.video_views_format, formattedCount)
            } ?: ""

            binding.videoMeta.text = when {
                channelName.isNotEmpty() && views.isNotEmpty() -> "$channelName • $views"
                channelName.isNotEmpty() -> channelName
                views.isNotEmpty() -> views
                else -> ""
            }

            // Load thumbnail
            binding.videoThumbnail.load(item.thumbnailUrl) {
                placeholder(R.drawable.thumbnail_placeholder)
                error(R.drawable.thumbnail_placeholder)
                crossfade(true)
            }

            // Download indicator
            bindDownloadState(uiItem)

            // Click handler with position validation
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onVideoClick(item, position)
                }
            }

            // Accessibility content description
            binding.root.contentDescription = context.getString(
                R.string.a11y_playlist_video,
                item.position,
                item.title,
                badge ?: "",
                binding.videoMeta.text
            )
        }

        private fun bindDownloadState(uiItem: PlaylistVideoUiItem) {
            val context = binding.root.context
            val status = uiItem.downloadStatus

            if (status == null) {
                binding.downloadIndicatorContainer.isVisible = false
                binding.downloadStatusBadge.isVisible = false
                return
            }

            binding.downloadIndicatorContainer.isVisible = true

            when (status) {
                DownloadStatus.COMPLETED -> {
                    binding.downloadedIcon.isVisible = true
                    binding.downloadingProgress.isVisible = false
                    binding.downloadStatusBadge.isVisible = true
                    binding.downloadStatusBadge.text = context.getString(R.string.playlist_video_downloaded)
                }
                DownloadStatus.RUNNING -> {
                    binding.downloadedIcon.isVisible = false
                    binding.downloadingProgress.isVisible = true
                    binding.downloadingProgress.isIndeterminate = false
                    val boundedProgress = uiItem.downloadProgress.coerceIn(0, 100)
                    binding.downloadingProgress.progress = boundedProgress
                    binding.downloadStatusBadge.isVisible = true
                    binding.downloadStatusBadge.text = context.getString(
                        R.string.playlist_video_downloading,
                        boundedProgress
                    )
                }
                DownloadStatus.QUEUED -> {
                    binding.downloadedIcon.isVisible = false
                    binding.downloadingProgress.isVisible = true
                    binding.downloadingProgress.isIndeterminate = true
                    binding.downloadStatusBadge.isVisible = true
                    binding.downloadStatusBadge.text = context.getString(R.string.playlist_video_queued)
                }
                DownloadStatus.PAUSED -> {
                    binding.downloadedIcon.isVisible = false
                    binding.downloadingProgress.isVisible = true
                    binding.downloadingProgress.isIndeterminate = false
                    binding.downloadingProgress.progress = uiItem.downloadProgress.coerceIn(0, 100)
                    binding.downloadStatusBadge.isVisible = true
                    binding.downloadStatusBadge.text = context.getString(R.string.download_status_paused)
                }
                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                    binding.downloadIndicatorContainer.isVisible = false
                    binding.downloadStatusBadge.isVisible = false
                }
            }
        }
    }

    /**
     * Updates the list with new items, mapping download states.
     */
    fun submitItems(
        items: List<PlaylistItem>,
        downloadStates: Map<String, Pair<DownloadStatus, Int>>
    ) {
        val uiItems = items.map { item ->
            val state = downloadStates[item.videoId]
            PlaylistVideoUiItem(
                item = item,
                downloadStatus = state?.first,
                downloadProgress = state?.second ?: 0
            )
        }
        submitList(uiItems)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PlaylistVideoUiItem>() {
            override fun areItemsTheSame(
                oldItem: PlaylistVideoUiItem,
                newItem: PlaylistVideoUiItem
            ): Boolean = oldItem.item.videoId == newItem.item.videoId

            override fun areContentsTheSame(
                oldItem: PlaylistVideoUiItem,
                newItem: PlaylistVideoUiItem
            ): Boolean = oldItem == newItem
        }
    }
}
