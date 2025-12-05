package com.albunyaan.tube.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.databinding.ItemUpNextBinding

class UpNextAdapter(
    private val onItemClicked: (UpNextItem) -> Unit
) : ListAdapter<UpNextItem, UpNextAdapter.UpNextViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UpNextViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemUpNextBinding.inflate(inflater, parent, false)
        return UpNextViewHolder(binding, onItemClicked)
    }

    override fun onBindViewHolder(holder: UpNextViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private object DiffCallback : DiffUtil.ItemCallback<UpNextItem>() {
        override fun areItemsTheSame(oldItem: UpNextItem, newItem: UpNextItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: UpNextItem, newItem: UpNextItem): Boolean =
            oldItem == newItem
    }

    class UpNextViewHolder(
        private val binding: ItemUpNextBinding,
        private val onItemClicked: (UpNextItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: UpNextItem) {
            binding.upNextTitle.text = item.title
            val duration = formatDuration(item.durationSeconds)
            val viewCount = formatViewCount(item.viewCount)
            val metaParts = listOfNotNull(
                item.channelName.takeIf { it.isNotBlank() },
                viewCount
            )
            binding.upNextMeta.text = metaParts.joinToString(" \u2022 ")
            binding.upNextMeta.isVisible = metaParts.isNotEmpty()
            binding.upNextDuration.text = duration
            binding.upNextDuration.isVisible = duration.isNotEmpty()
            binding.upNextThumbnail.load(item.thumbnailUrl) {
                placeholder(R.drawable.thumbnail_placeholder)
                error(R.drawable.thumbnail_placeholder)
                crossfade(true)
            }
            binding.root.setOnClickListener { onItemClicked(item) }
        }

        private fun formatDuration(seconds: Int): String {
            if (seconds <= 0) return ""
            val minutes = seconds / 60
            val remaining = seconds % 60
            return binding.root.context.getString(
                R.string.player_duration_minutes_seconds,
                minutes,
                remaining
            )
        }

        private fun formatViewCount(viewCount: Long?): String? {
            val count = viewCount ?: return null
            return when {
                count >= 1_000_000_000 -> String.format("%.1fB", count / 1_000_000_000.0)
                count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
                count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
                else -> count.toString()
            }
        }
    }
}
