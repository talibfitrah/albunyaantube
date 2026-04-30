package com.albunyaan.tube.ui.detail.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.channel.ChannelPlaylist
import com.albunyaan.tube.databinding.ItemPlaylistBinding

/**
 * Adapter for playlists in the Playlists tab.
 * Reuses the existing item_playlist layout.
 */
class ChannelPlaylistsAdapter(
    private val onPlaylistClick: (ChannelPlaylist) -> Unit
) : ListAdapter<ChannelPlaylist, ChannelPlaylistsAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlaylistBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onPlaylistClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemPlaylistBinding,
        private val onPlaylistClick: (ChannelPlaylist) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: ChannelPlaylist) {
            binding.playlistTitle.text = playlist.title

            // Item count as meta text
            val itemCount = playlist.itemCount ?: 0
            val metaText = binding.root.context.resources.getQuantityString(
                R.plurals.video_count,
                itemCount.toInt(),
                itemCount.toInt()
            )
            // Add uploader name if available
            binding.playlistMeta.text = if (!playlist.uploaderName.isNullOrBlank()) {
                "$metaText • ${playlist.uploaderName}"
            } else {
                metaText
            }

            // Load thumbnail
            binding.playlistThumbnail.load(playlist.thumbnailUrl) {
                placeholder(R.drawable.thumbnail_placeholder)
                error(R.drawable.thumbnail_placeholder)
                crossfade(true)
            }

            // Set accessibility content description for the entire card
            binding.root.contentDescription = binding.root.context.getString(
                R.string.a11y_playlist_item,
                playlist.title,
                itemCount
            )

            binding.root.setOnClickListener {
                onPlaylistClick(playlist)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChannelPlaylist>() {
            override fun areItemsTheSame(oldItem: ChannelPlaylist, newItem: ChannelPlaylist): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChannelPlaylist, newItem: ChannelPlaylist): Boolean =
                oldItem == newItem
        }
    }
}
