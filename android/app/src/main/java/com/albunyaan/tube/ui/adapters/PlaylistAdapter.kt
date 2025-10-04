package com.albunyaan.tube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemPlaylistBinding

class PlaylistAdapter(
    private val onPlaylistClick: (ContentItem.Playlist) -> Unit
) : ListAdapter<ContentItem.Playlist, PlaylistAdapter.PlaylistViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemPlaylistBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlaylistViewHolder(binding, onPlaylistClick)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PlaylistViewHolder(
        private val binding: ItemPlaylistBinding,
        private val onPlaylistClick: (ContentItem.Playlist) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: ContentItem.Playlist) {
            binding.playlistTitle.text = playlist.title
            binding.playlistMeta.text = "${playlist.itemCount} items • ${playlist.category}"

            // Load thumbnail
            binding.playlistThumbnail.load(playlist.thumbnailUrl) {
                placeholder(R.drawable.thumbnail_placeholder)
                error(R.drawable.thumbnail_placeholder)
                crossfade(true)
            }

            binding.root.setOnClickListener {
                onPlaylistClick(playlist)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ContentItem.Playlist>() {
            override fun areItemsTheSame(
                oldItem: ContentItem.Playlist,
                newItem: ContentItem.Playlist
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: ContentItem.Playlist,
                newItem: ContentItem.Playlist
            ): Boolean = oldItem == newItem
        }
    }
}
