package com.albunyaan.tube.ui.me

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.me.MeFeedVideo
import com.albunyaan.tube.databinding.ItemMeVideoBinding
import com.albunyaan.tube.databinding.ItemMeVideosHeaderBinding

/**
 * Vertical list of long-form videos across subscribed channels. Renders
 * a fixed "Latest videos" header followed by the items.
 */
class MeVideosAdapter(
    private val onClick: (MeFeedVideo) -> Unit,
) {
    private val listAdapter = InnerAdapter(onClick)
    private val headerAdapter = HeaderAdapter()

    val sectionAdapter: RecyclerView.Adapter<out RecyclerView.ViewHolder> =
        ConcatAdapter(headerAdapter, listAdapter)

    fun submit(items: List<MeFeedVideo>) {
        headerAdapter.show = items.isNotEmpty()
        listAdapter.submitList(items)
    }

    private class HeaderAdapter : RecyclerView.Adapter<HeaderVH>() {
        var show: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                if (value) notifyItemInserted(0) else notifyItemRemoved(0)
            }

        override fun getItemCount(): Int = if (show) 1 else 0
        override fun getItemViewType(position: Int) = VIDEOS_HEADER_VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderVH {
            val binding = ItemMeVideosHeaderBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return HeaderVH(binding)
        }

        override fun onBindViewHolder(holder: HeaderVH, position: Int) = Unit
    }

    class HeaderVH(val binding: ItemMeVideosHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    private class InnerAdapter(
        private val onClick: (MeFeedVideo) -> Unit,
    ) : ListAdapter<MeFeedVideo, VideoVH>(DIFF) {

        override fun getItemViewType(position: Int): Int = VIDEO_VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoVH {
            val binding = ItemMeVideoBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VideoVH(binding, onClick)
        }

        override fun onBindViewHolder(holder: VideoVH, position: Int) {
            holder.bind(getItem(position))
        }
    }

    class VideoVH(
        private val binding: ItemMeVideoBinding,
        private val onClick: (MeFeedVideo) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MeFeedVideo) {
            binding.videoTitle.text = item.title
            binding.videoMeta.text = buildMeta(item)

            val url = item.thumbnailUrl
            if (!url.isNullOrBlank()) {
                binding.videoThumbnail.load(url) {
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                }
            } else {
                // F-CR10 (CodeRabbit): use Coil's load() even for the
                // placeholder so any in-flight request from a prior bind on
                // this recycled view is cancelled. setImageResource() alone
                // doesn't dispose the request, so a slow network thumbnail
                // could overwrite the placeholder mid-frame.
                binding.videoThumbnail.load(R.drawable.thumbnail_placeholder)
            }
            binding.root.setOnClickListener { onClick(item) }
        }

        private fun buildMeta(item: MeFeedVideo): CharSequence {
            val relative = if (item.uploadedAt > 0L) {
                DateUtils.getRelativeTimeSpanString(
                    item.uploadedAt,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE,
                ).toString()
            } else ""
            return buildString {
                append(item.channelName)
                if (relative.isNotEmpty()) append(" • ").append(relative)
            }
        }
    }

    companion object {
        const val VIDEO_VIEW_TYPE = 301
        const val VIDEOS_HEADER_VIEW_TYPE = 302

        private val DIFF = object : DiffUtil.ItemCallback<MeFeedVideo>() {
            override fun areItemsTheSame(old: MeFeedVideo, new: MeFeedVideo) = old.videoId == new.videoId
            override fun areContentsTheSame(old: MeFeedVideo, new: MeFeedVideo) = old == new
        }
    }
}
