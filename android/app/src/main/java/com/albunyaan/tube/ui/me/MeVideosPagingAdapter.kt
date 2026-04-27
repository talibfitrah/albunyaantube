package com.albunyaan.tube.ui.me

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.me.MeFeedVideo
import com.albunyaan.tube.databinding.ItemMeVideoBinding
import com.albunyaan.tube.databinding.ItemMeVideosHeaderBinding

/**
 * T11: paged replacement for the legacy [MeVideosAdapter].
 *
 * Behaviour preserved:
 *  - Fixed "Latest videos" header above the rows, hidden on empty.
 *  - VIDEO_VIEW_TYPE = 301 so [MeFragment.spanSizeLookup] keeps mapping
 *    long-form rows to a span of 1 on tablet/TV grids.
 *  - VideoVH.bind() body — channel + relative-date metadata, Coil
 *    thumbnail load with placeholder fallback, click forward — copied
 *    verbatim from the legacy adapter so the UI does not regress.
 *
 * Behaviour changed:
 *  - Rows arrive via [submitData] from a Paging 3 [androidx.paging.PagingData]
 *    flow rather than `submitList(List<MeFeedVideo>)`. The header is
 *    toggled by an [addLoadStateListener] on the inner adapter rather than
 *    by the caller telling it whether the list is empty.
 *
 * Expose [sectionAdapter] (a [ConcatAdapter] of the header + paging
 * adapter) so [MeFragment]'s outer [ConcatAdapter] composition is
 * unchanged.
 */
class MeVideosPagingAdapter(
    private val onClick: (MeFeedVideo) -> Unit,
) {
    private val pagingAdapter = InnerPagingAdapter(onClick)
    private val headerAdapter = HeaderAdapter()

    val sectionAdapter: RecyclerView.Adapter<out RecyclerView.ViewHolder> =
        ConcatAdapter(headerAdapter, pagingAdapter)

    init {
        // Toggle the header based on the paging state. We check Refresh /
        // Append for `NotLoading.endOfPaginationReached == true` to decide
        // when the load is settled, then hide the header iff itemCount is
        // 0. This mirrors the prior `submit(items)` call's
        // `headerAdapter.show = items.isNotEmpty()` flip without leaking
        // PagingData internals to the fragment.
        pagingAdapter.addLoadStateListener { states ->
            val refresh = states.refresh
            // Stay shown until the first non-loading frame so the header
            // doesn't flicker during the initial load.
            if (refresh is LoadState.NotLoading || refresh is LoadState.Error) {
                headerAdapter.show = pagingAdapter.itemCount > 0
            }
        }
    }

    suspend fun submitData(pagingData: androidx.paging.PagingData<MeFeedVideo>) {
        pagingAdapter.submitData(pagingData)
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

    private class InnerPagingAdapter(
        private val onClick: (MeFeedVideo) -> Unit,
    ) : PagingDataAdapter<MeFeedVideo, VideoVH>(DIFF) {

        override fun getItemViewType(position: Int): Int = VIDEO_VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoVH {
            val binding = ItemMeVideoBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VideoVH(binding, onClick)
        }

        override fun onBindViewHolder(holder: VideoVH, position: Int) {
            // PagingDataAdapter#getItem may return null when a placeholder
            // is being shown. We disable placeholders in PagingConfig so
            // this should never happen in practice, but the adapter API
            // is null-tolerant — guard for safety.
            val item = getItem(position) ?: return
            holder.bind(item)
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

        /**
         * Me-feed videos show only channel name + relative upload date.
         * Duration and view count are intentionally NOT displayed: the ATOM
         * source ([com.albunyaan.tube.data.me.AtomChannelFeedFetcher]) does
         * not expose them, and we do not want to fall back to NewPipe
         * scraping just to render two metadata fields per row. The data
         * model fields [MeFeedVideo.durationSeconds] / [MeFeedVideo.viewCount]
         * remain on the type for cache-layer compatibility but are always
         * null for items refreshed via ATOM. ANDROID-PERSONAL-02 / spec §8.
         */
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
