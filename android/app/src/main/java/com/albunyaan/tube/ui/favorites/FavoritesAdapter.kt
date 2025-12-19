package com.albunyaan.tube.ui.favorites

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.albunyaan.tube.R
import com.albunyaan.tube.data.local.FavoriteVideo

/**
 * Adapter for displaying favorite videos in a RecyclerView.
 *
 * @param onItemClick Callback when a favorite video is clicked (for playback)
 * @param onRemoveClick Callback when the remove button is clicked
 */
class FavoritesAdapter(
    private val onItemClick: (FavoriteVideo) -> Unit,
    private val onRemoveClick: (FavoriteVideo) -> Unit
) : ListAdapter<FavoriteVideo, FavoritesAdapter.FavoriteViewHolder>(FavoriteDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_video, parent, false)
        return FavoriteViewHolder(view, onItemClick, onRemoveClick)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FavoriteViewHolder(
        itemView: View,
        private val onItemClick: (FavoriteVideo) -> Unit,
        private val onRemoveClick: (FavoriteVideo) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        private val title: TextView = itemView.findViewById(R.id.title)
        private val channelName: TextView = itemView.findViewById(R.id.channelName)
        private val duration: TextView = itemView.findViewById(R.id.duration)
        private val removeButton: ImageButton = itemView.findViewById(R.id.removeButton)

        fun bind(favorite: FavoriteVideo) {
            title.text = favorite.title
            channelName.text = favorite.channelName
            duration.text = formatDuration(favorite.durationSeconds)

            // Accessibility: set content descriptions for screen readers
            thumbnail.contentDescription = favorite.title
            removeButton.contentDescription = itemView.context.getString(
                R.string.favorites_remove_description, favorite.title
            )

            // Load thumbnail with Coil
            val cornerRadius = itemView.context.resources.getDimension(R.dimen.thumbnail_corner_radius)
            favorite.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { url ->
                thumbnail.load(url) {
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                    transformations(RoundedCornersTransformation(cornerRadius))
                }
            } ?: run {
                thumbnail.setImageResource(R.drawable.thumbnail_placeholder)
            }

            itemView.setOnClickListener { onItemClick(favorite) }
            removeButton.setOnClickListener { onRemoveClick(favorite) }
        }

        private fun formatDuration(seconds: Int): String {
            val absoluteSeconds = seconds.coerceAtLeast(0)
            val hours = absoluteSeconds / 3600
            val minutes = (absoluteSeconds % 3600) / 60
            val secs = absoluteSeconds % 60

            return when {
                hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
                else -> String.format("%d:%02d", minutes, secs)
            }
        }
    }

    private object FavoriteDiffCallback : DiffUtil.ItemCallback<FavoriteVideo>() {
        override fun areItemsTheSame(oldItem: FavoriteVideo, newItem: FavoriteVideo): Boolean {
            return oldItem.videoId == newItem.videoId
        }

        override fun areContentsTheSame(oldItem: FavoriteVideo, newItem: FavoriteVideo): Boolean {
            return oldItem == newItem
        }
    }
}
