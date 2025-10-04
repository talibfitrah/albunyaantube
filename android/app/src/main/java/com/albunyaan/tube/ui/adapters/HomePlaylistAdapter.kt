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

/**
 * Horizontal adapter for displaying playlists in home screen sections
 */
class HomePlaylistAdapter(
    private val onPlaylistClick: (ContentItem.Playlist) -> Unit
) : ListAdapter<ContentItem.Playlist, HomePlaylistAdapter.ViewHolder>(DIFF_CALLBACK) {

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
        private val onPlaylistClick: (ContentItem.Playlist) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: ContentItem.Playlist) {
            binding.playlistTitle.text = playlist.title
            binding.playlistMeta.text = "${playlist.itemCount} items • ${playlist.category}"

            binding.playlistThumbnail.load(playlist.thumbnailUrl) {
                placeholder(R.drawable.onboarding_icon_bg)
                error(R.drawable.onboarding_icon_bg)
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
