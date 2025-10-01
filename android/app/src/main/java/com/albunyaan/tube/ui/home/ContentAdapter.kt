package com.albunyaan.tube.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.databinding.ItemContentBinding

class ContentAdapter : PagingDataAdapter<ContentItem, ContentAdapter.ContentViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContentViewHolder {
        val binding = ItemContentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ContentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContentViewHolder, position: Int) {
        getItem(position)?.let(holder::bind)
    }

    class ContentViewHolder(private val binding: ItemContentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ContentItem) {
            when (item) {
                is ContentItem.Video -> {
                    binding.title.text = item.title
                    binding.metadata.text = "${item.category} • ${item.durationMinutes} min"
                    binding.description.text = "Uploaded ${item.uploadedDaysAgo} days ago"
                }
                is ContentItem.Channel -> {
                    binding.title.text = item.name
                    binding.metadata.text = "${item.category} • ${item.subscribers} subscribers"
                    binding.description.text = "Curated channel from ${item.category}"
                }
                is ContentItem.Playlist -> {
                    binding.title.text = item.title
                    binding.metadata.text = "${item.category} • ${item.itemCount} items"
                    binding.description.text = "Playlist for ${item.category}"
                }
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
