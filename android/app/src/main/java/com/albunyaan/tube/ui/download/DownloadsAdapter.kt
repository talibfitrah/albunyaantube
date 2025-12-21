package com.albunyaan.tube.ui.download

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.ItemDownloadBinding
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadErrorCode
import com.albunyaan.tube.download.DownloadStatus

class DownloadsAdapter(
    private val onPauseResume: (DownloadEntry) -> Unit,
    private val onCancel: (DownloadEntry) -> Unit,
    private val onOpen: (DownloadEntry) -> Unit,
    private val onRetry: (DownloadEntry) -> Unit,
    private val onRemove: (DownloadEntry) -> Unit,
    private val onDelete: (DownloadEntry) -> Unit
) : ListAdapter<DownloadEntry, DownloadsAdapter.DownloadViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DownloadViewHolder(binding, onPauseResume, onCancel, onOpen, onRetry, onRemove, onDelete)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DownloadViewHolder(
        private val binding: ItemDownloadBinding,
        private val onPauseResume: (DownloadEntry) -> Unit,
        private val onCancel: (DownloadEntry) -> Unit,
        private val onOpen: (DownloadEntry) -> Unit,
        private val onRetry: (DownloadEntry) -> Unit,
        private val onRemove: (DownloadEntry) -> Unit,
        private val onDelete: (DownloadEntry) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: DownloadEntry) {
            // Title: prefer persisted metadata title (survives restart), fallback to request title
            val displayTitle = entry.metadata?.title ?: entry.request.title
            binding.downloadTitle.text = displayTitle
            binding.downloadStatus.text = binding.root.context.getString(statusText(entry.status))

            // Load thumbnail: prefer persisted metadata URL, fallback to request URL
            val thumbnailUrl = entry.metadata?.thumbnailUrl ?: entry.request.thumbnailUrl
            if (!thumbnailUrl.isNullOrBlank()) {
                binding.downloadThumbnail.load(thumbnailUrl) {
                    crossfade(true)
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                }
            } else {
                binding.downloadThumbnail.setImageResource(R.drawable.thumbnail_placeholder)
            }
            binding.downloadProgress.isVisible = entry.status == DownloadStatus.RUNNING
            binding.downloadProgress.progress = entry.progress

            // Show error message for failed downloads, otherwise show metadata details
            val details = when {
                entry.status == DownloadStatus.FAILED && !entry.message.isNullOrBlank() -> {
                    // Map error codes to localized strings
                    mapErrorCodeToString(entry.message, binding.root.context)
                }
                entry.metadata != null -> {
                    val metadata = entry.metadata
                    val context = binding.root.context
                    val size = Formatter.formatShortFileSize(context, metadata.sizeBytes)
                    val relativeTime = DateUtils.getRelativeTimeSpanString(
                        metadata.completedAtMillis,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    )
                    context.getString(R.string.download_details_format, size, relativeTime)
                }
                else -> null
            }
            binding.downloadDetails.isVisible = details != null
            binding.downloadDetails.text = details

            // Pause/Resume: only for active downloads (RUNNING, PAUSED, QUEUED)
            val pauseResumeText = when (entry.status) {
                DownloadStatus.RUNNING -> R.string.download_action_pause
                DownloadStatus.PAUSED, DownloadStatus.QUEUED -> R.string.download_action_resume
                else -> R.string.download_action_resume
            }
            binding.downloadPauseResume.isVisible = when (entry.status) {
                DownloadStatus.RUNNING, DownloadStatus.PAUSED, DownloadStatus.QUEUED -> true
                else -> false
            }
            binding.downloadPauseResume.setText(pauseResumeText)

            // Retry: only for FAILED or CANCELLED downloads
            binding.downloadRetry.isVisible = entry.status in listOf(
                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED
            )

            // Remove: only for FAILED or CANCELLED downloads (clears entry from list)
            binding.downloadRemove.isVisible = entry.status in listOf(
                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED
            )

            // Cancel: only for active downloads (RUNNING, PAUSED, QUEUED)
            binding.downloadCancel.isVisible = entry.status in listOf(
                DownloadStatus.RUNNING,
                DownloadStatus.PAUSED,
                DownloadStatus.QUEUED
            )

            // Open: only for completed downloads with a file
            binding.downloadOpen.isVisible = entry.status == DownloadStatus.COMPLETED && entry.filePath != null

            // Delete: only for completed downloads
            binding.downloadDelete.isVisible = entry.status == DownloadStatus.COMPLETED

            // Click handlers
            binding.downloadPauseResume.setOnClickListener { onPauseResume(entry) }
            binding.downloadCancel.setOnClickListener { onCancel(entry) }
            binding.downloadOpen.setOnClickListener { onOpen(entry) }
            binding.downloadRetry.setOnClickListener { onRetry(entry) }
            binding.downloadRemove.setOnClickListener { onRemove(entry) }
            binding.downloadDelete.setOnClickListener { onDelete(entry) }

            binding.root.contentDescription = binding.root.context.getString(
                R.string.download_item_content_description,
                displayTitle,
                binding.root.context.getString(statusText(entry.status)),
                entry.progress
            )
        }

        private fun statusText(status: DownloadStatus): Int = when (status) {
            DownloadStatus.QUEUED -> R.string.download_status_queued
            DownloadStatus.RUNNING -> R.string.download_status_running
            DownloadStatus.PAUSED -> R.string.download_status_paused
            DownloadStatus.COMPLETED -> R.string.download_status_completed
            DownloadStatus.FAILED -> R.string.download_status_failed
            DownloadStatus.CANCELLED -> R.string.download_status_cancelled
        }

        /**
         * Maps error codes from DownloadErrorCode to localized string resources.
         * Falls back to the raw error message if the code is not recognized,
         * providing graceful degradation for unknown or legacy error formats.
         */
        private fun mapErrorCodeToString(errorCode: String, context: Context): String {
            val resId = when (errorCode) {
                DownloadErrorCode.HTTP_403 -> R.string.download_error_http_403
                DownloadErrorCode.HTTP_429 -> R.string.download_error_http_429
                DownloadErrorCode.NETWORK -> R.string.download_error_network
                DownloadErrorCode.MERGE -> R.string.download_error_merge
                DownloadErrorCode.NO_STREAM -> R.string.download_error_no_stream
                DownloadErrorCode.NO_COMPATIBLE_VIDEO -> R.string.download_error_no_compatible_video
                DownloadErrorCode.VIDEO_AUDIO_MISMATCH -> R.string.download_error_video_audio_mismatch
                DownloadErrorCode.INVALID_INPUT -> R.string.download_error_invalid_input
                DownloadErrorCode.UNKNOWN -> R.string.download_error_unknown
                else -> null // Unknown code - fall back to raw message
            }
            // If we have a recognized code, use the localized string
            // Otherwise, show the raw error message (graceful fallback)
            return resId?.let { context.getString(it) } ?: errorCode
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<DownloadEntry>() {
        override fun areItemsTheSame(oldItem: DownloadEntry, newItem: DownloadEntry): Boolean =
            oldItem.request.id == newItem.request.id

        override fun areContentsTheSame(oldItem: DownloadEntry, newItem: DownloadEntry): Boolean =
            oldItem == newItem
    }
}
