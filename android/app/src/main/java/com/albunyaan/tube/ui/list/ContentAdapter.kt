package com.albunyaan.tube.ui.list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemContentBinding
import java.text.NumberFormat

class ContentAdapter(
    private val imageLoader: ImageLoader,
    private val enableImages: Boolean = true
) : PagingDataAdapter<ContentItem, ContentAdapter.ContentViewHolder>(DIFF) {

    private var onItemClick: ((ContentItem) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContentViewHolder {
        val binding = ItemContentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ContentViewHolder(binding, onItemClick, imageLoader, enableImages)
    }

    override fun onBindViewHolder(holder: ContentViewHolder, position: Int) {
        getItem(position)?.let(holder::bind)
    }

    fun setOnItemClickListener(listener: ((ContentItem) -> Unit)?) {
        onItemClick = listener
    }

    class ContentViewHolder(
        private val binding: ItemContentBinding,
        private val clickListener: ((ContentItem) -> Unit)?,
        private val imageLoader: ImageLoader,
        private val enableImages: Boolean
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ContentItem) {
            when (item) {
                is ContentItem.Video -> {
                    binding.title.text = item.title
                    val viewSuffix = item.viewCount?.let { " • ${NumberFormat.getInstance().format(it)} views" } ?: ""
                    binding.metadata.text = "${item.category} • ${item.durationMinutes} min$viewSuffix"
                    val description = item.description.takeIf { it.isNotBlank() }
                        ?: "Uploaded ${item.uploadedDaysAgo} days ago"
                    binding.description.text = description
                }
                is ContentItem.Channel -> {
                    binding.title.text = item.name
                    binding.metadata.text = "${item.category} • ${NumberFormat.getInstance().format(item.subscribers)} subscribers"
                    val description = item.description
                        ?: "Curated channel from ${item.category}"
                    binding.description.text = description
                }
                is ContentItem.Playlist -> {
                    binding.title.text = item.title
                    binding.metadata.text = "${item.category} • ${item.itemCount} items"
                    val description = item.description
                        ?: "Playlist for ${item.category}"
                    binding.description.text = description
                }
            }
            val thumbnailUrl = when (item) {
                is ContentItem.Video -> item.thumbnailUrl
                is ContentItem.Channel -> item.thumbnailUrl
                is ContentItem.Playlist -> item.thumbnailUrl
            }
            bindThumbnail(thumbnailUrl)
            binding.root.setOnClickListener { clickListener?.invoke(item) }
        }

        private fun bindThumbnail(url: String?) {
            if (!enableImages) {
                binding.thumbnail.isVisible = true
                binding.thumbnail.setImageResource(R.drawable.thumbnail_placeholder)
                return
            }
            if (url.isNullOrBlank()) {
                binding.thumbnail.isVisible = true
                binding.thumbnail.setImageResource(R.drawable.thumbnail_placeholder)
                return
            }
            binding.thumbnail.isVisible = true
            binding.thumbnail.load(url, imageLoader) {
                placeholder(R.drawable.thumbnail_placeholder)
                error(R.drawable.thumbnail_placeholder)
                crossfade(true)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ContentItem>() {
            override fun areItemsTheSame(oldItem: ContentItem, newItem: ContentItem): Boolean {
                return when {
                    oldItem is ContentItem.Video && newItem is ContentItem.Video -> oldItem.id == newItem.id
                    oldItem is ContentItem.Channel && newItem is ContentItem.Channel -> oldItem.id == newItem.id
                    oldItem is ContentItem.Playlist && newItem is ContentItem.Playlist -> oldItem.id == newItem.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: ContentItem, newItem: ContentItem): Boolean = oldItem == newItem
        }
    }
}
