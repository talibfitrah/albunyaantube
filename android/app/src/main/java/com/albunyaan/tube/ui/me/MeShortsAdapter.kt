package com.albunyaan.tube.ui.me

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.me.MeFeedVideo
import com.albunyaan.tube.databinding.ItemMeShortBinding
import com.albunyaan.tube.databinding.ItemMeShortsSectionBinding

/**
 * Horizontal shorts strip for the Me feed.
 */
class MeShortsAdapter(
    private val onClick: (MeFeedVideo) -> Unit,
) {
    private val inner = InnerAdapter(onClick)

    fun submit(items: List<MeFeedVideo>) {
        inner.submitList(items)
    }

    val sectionAdapter: RecyclerView.Adapter<*> = object : RecyclerView.Adapter<SectionVH>() {
        override fun getItemCount(): Int = if (inner.itemCount == 0) 0 else 1
        override fun getItemViewType(position: Int) = SHORTS_SECTION_VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionVH {
            val binding = ItemMeShortsSectionBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            binding.shortsRecycler.layoutManager = LinearLayoutManager(
                parent.context, LinearLayoutManager.HORIZONTAL, false
            )
            binding.shortsRecycler.adapter = inner
            return SectionVH(binding)
        }

        override fun onBindViewHolder(holder: SectionVH, position: Int) {
            // no-op
        }
    }

    class SectionVH(val binding: ItemMeShortsSectionBinding) : RecyclerView.ViewHolder(binding.root)

    private class InnerAdapter(
        private val onClick: (MeFeedVideo) -> Unit,
    ) : ListAdapter<MeFeedVideo, ShortVH>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortVH {
            val binding = ItemMeShortBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ShortVH(binding, onClick)
        }

        override fun onBindViewHolder(holder: ShortVH, position: Int) {
            holder.bind(getItem(position))
        }
    }

    class ShortVH(
        private val binding: ItemMeShortBinding,
        private val onClick: (MeFeedVideo) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MeFeedVideo) {
            binding.shortTitle.text = item.title
            val url = item.thumbnailUrl
            if (!url.isNullOrBlank()) {
                binding.shortThumbnail.load(url) {
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                }
            } else {
                binding.shortThumbnail.setImageResource(R.drawable.thumbnail_placeholder)
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        const val SHORTS_SECTION_VIEW_TYPE = 201

        private val DIFF = object : DiffUtil.ItemCallback<MeFeedVideo>() {
            override fun areItemsTheSame(old: MeFeedVideo, new: MeFeedVideo) = old.videoId == new.videoId
            override fun areContentsTheSame(old: MeFeedVideo, new: MeFeedVideo) = old == new
        }
    }
}
