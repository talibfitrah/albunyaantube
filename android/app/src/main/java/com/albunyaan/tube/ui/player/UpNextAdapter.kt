package com.albunyaan.tube.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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
            binding.upNextMeta.text = binding.root.context.getString(
                R.string.player_up_next_meta,
                item.channelName,
                formatDuration(item.durationSeconds)
            )
            binding.root.setOnClickListener { onItemClicked(item) }
        }

        private fun formatDuration(seconds: Int): String {
            val minutes = seconds / 60
            val remaining = seconds % 60
            return binding.root.context.getString(
                R.string.player_duration_minutes_seconds,
                minutes,
                remaining
            )
        }
    }
}
