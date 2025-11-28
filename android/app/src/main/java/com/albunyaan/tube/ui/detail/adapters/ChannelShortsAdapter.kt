package com.albunyaan.tube.ui.detail.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelShort
import com.albunyaan.tube.databinding.ItemChannelShortBinding
import java.text.NumberFormat

/**
 * Adapter for Shorts in the Shorts tab.
 * Displays 9:16 vertical thumbnails in a grid.
 */
class ChannelShortsAdapter(
    private val onShortClick: (ChannelShort) -> Unit
) : ListAdapter<ChannelShort, ChannelShortsAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelShortBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onShortClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemChannelShortBinding,
        private val onShortClick: (ChannelShort) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(short: ChannelShort) {
            binding.shortTitle.text = short.title

            // Format view count
            val views = short.viewCount?.let {
                formatViewCount(it)
            } ?: ""
            binding.shortViews.text = views

            // Load thumbnail
            binding.shortThumbnail.load(short.thumbnailUrl) {
                placeholder(R.drawable.thumbnail_placeholder)
                error(R.drawable.thumbnail_placeholder)
                crossfade(true)
            }

            binding.root.setOnClickListener {
                onShortClick(short)
            }
        }

        private fun formatViewCount(count: Long): String {
            val context = binding.root.context
            return when {
                count >= 1_000_000_000 -> context.getString(
                    R.string.views_count_billions,
                    count / 1_000_000_000.0
                )
                count >= 1_000_000 -> context.getString(
                    R.string.views_count_millions,
                    count / 1_000_000.0
                )
                count >= 1_000 -> context.getString(
                    R.string.views_count_thousands,
                    count / 1_000.0
                )
                else -> context.getString(R.string.views_count, count.toInt())
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChannelShort>() {
            override fun areItemsTheSame(oldItem: ChannelShort, newItem: ChannelShort): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChannelShort, newItem: ChannelShort): Boolean =
                oldItem == newItem
        }
    }
}
