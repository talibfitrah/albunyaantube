package com.albunyaan.tube.ui.download

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.ItemDownloadBinding
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadStatus

class DownloadsAdapter(
    private val onPauseResume: (DownloadEntry) -> Unit,
    private val onCancel: (DownloadEntry) -> Unit,
    private val onOpen: (DownloadEntry) -> Unit
) : ListAdapter<DownloadEntry, DownloadsAdapter.DownloadViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DownloadViewHolder(binding, onPauseResume, onCancel, onOpen)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DownloadViewHolder(
        private val binding: ItemDownloadBinding,
        private val onPauseResume: (DownloadEntry) -> Unit,
        private val onCancel: (DownloadEntry) -> Unit,
        private val onOpen: (DownloadEntry) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: DownloadEntry) {
            binding.downloadTitle.text = entry.request.title
            binding.downloadStatus.text = binding.root.context.getString(statusText(entry.status))
            binding.downloadProgress.isVisible = entry.status == DownloadStatus.RUNNING
            binding.downloadProgress.progress = entry.progress

            val details = entry.metadata?.let { metadata ->
                val context = binding.root.context
                val size = Formatter.formatShortFileSize(context, metadata.sizeBytes)
                val relativeTime = DateUtils.getRelativeTimeSpanString(
                    metadata.completedAtMillis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
                context.getString(R.string.download_details_format, size, relativeTime)
            }
            binding.downloadDetails.isVisible = details != null
            binding.downloadDetails.text = details

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

            // For completed downloads, show as "Delete" button
            // For active downloads, show as "Cancel" button
            binding.downloadCancel.isVisible = when (entry.status) {
                DownloadStatus.CANCELLED -> false  // Hide for cancelled items
                else -> true  // Show for all other statuses including COMPLETED
            }

            val cancelButtonText = when (entry.status) {
                DownloadStatus.COMPLETED -> "Delete"  // TODO: Add string resource
                else -> binding.root.context.getString(R.string.download_action_cancel)
            }
            binding.downloadCancel.text = cancelButtonText

            binding.downloadOpen.isVisible = entry.status == DownloadStatus.COMPLETED && entry.filePath != null

            binding.downloadPauseResume.setOnClickListener { onPauseResume(entry) }
            binding.downloadCancel.setOnClickListener { onCancel(entry) }
            binding.downloadOpen.setOnClickListener { onOpen(entry) }

            binding.root.contentDescription = binding.root.context.getString(
                R.string.download_item_content_description,
                entry.request.title,
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
    }

    private object DiffCallback : DiffUtil.ItemCallback<DownloadEntry>() {
        override fun areItemsTheSame(oldItem: DownloadEntry, newItem: DownloadEntry): Boolean =
            oldItem.request.id == newItem.request.id

        override fun areContentsTheSame(oldItem: DownloadEntry, newItem: DownloadEntry): Boolean =
            oldItem == newItem
    }
}

