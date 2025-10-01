package com.albunyaan.tube.ui.list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.data.model.ContentItem
import java.text.NumberFormat
import com.albunyaan.tube.databinding.ItemContentBinding

class ContentAdapter : PagingDataAdapter<ContentItem, ContentAdapter.ContentViewHolder>(DIFF) {

    private var onItemClick: ((ContentItem) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContentViewHolder {
        val binding = ItemContentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ContentViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(holder: ContentViewHolder, position: Int) {
        getItem(position)?.let(holder::bind)
    }

    fun setOnItemClickListener(listener: ((ContentItem) -> Unit)?) {
        onItemClick = listener
    }

    class ContentViewHolder(
        private val binding: ItemContentBinding,
        private val clickListener: ((ContentItem) -> Unit)?
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
            binding.root.setOnClickListener { clickListener?.invoke(item) }
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
