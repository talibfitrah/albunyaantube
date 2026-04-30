package com.albunyaan.tube.ui.me

import android.text.format.DateUtils
import java.text.NumberFormat
import java.util.Locale
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.albunyaan.tube.R
import com.albunyaan.tube.data.me.MeFeedVideo
import com.albunyaan.tube.data.me.WeekBucket
import com.albunyaan.tube.data.me.WeekContent
import com.albunyaan.tube.databinding.ItemMeShortBinding
import com.albunyaan.tube.databinding.ItemMeShortsSectionBinding
import com.albunyaan.tube.databinding.ItemMeVideoBinding
import com.albunyaan.tube.databinding.ViewMeWeekHeaderBinding
import com.albunyaan.tube.util.ImageLoading.loadYouTubeThumbnail

/**
 * ANDROID-PERSONAL-03 / T6: a single rendered week's content.
 *
 * Wraps three inner adapters into a [ConcatAdapter]:
 *   - HeaderAdapter (1 item): the localised "This week" / "Last week" /
 *     "N weeks ago" label
 *   - ShortsAdapter (0 or 1 item): a horizontal RV of shorts. Hidden when
 *     the week has no shorts.
 *   - VideosAdapter (M items): the long-form videos grid for this week
 *
 * Each MeWeekSectionAdapter is created once per week and added to the
 * fragment's outer [ConcatAdapter] in [MeFragment]. New weeks come from
 * [MeViewModel.weeks].
 *
 * View types are unique constants so [MeFragment]'s spanSizeLookup can
 * make headers + shorts rows full-width on tablet/TV grids.
 */
class MeWeekSectionAdapter(
    private val initial: WeekContent,
    private val onClick: (MeFeedVideo) -> Unit,
    private val getChannelAvatar: (channelId: String) -> String?,
    private val onChannelClick: (channelId: String) -> Unit,
    /**
     * Optional callback fired when a video or short cell is bound — used
     * by [MeFragment] to warm the stream-prefetch cache so a tap on the
     * cell starts playback without waiting for NewPipe extraction. Default
     * is a no-op so existing call sites continue to compile.
     */
    private val onItemBind: (videoId: String) -> Unit = {},
) {

    private val headerAdapter = HeaderAdapter()
    private val shortsAdapter = ShortsRowAdapter(onClick, onItemBind)
    private val videosAdapter = VideosAdapter(onClick, getChannelAvatar, onChannelClick, onItemBind)

    /**
     * The outer adapter [MeFragment] wires into its ConcatAdapter for
     * this week.
     *
     * `isolateViewTypes = false`: REQUIRED to avoid a
     * ClassCastException collision across multiple week sections. The
     * fragment's outer ConcatAdapter is also configured with
     * `isolateViewTypes = false` (so its spanSizeLookup can compare raw
     * inner view types). If THIS inner ConcatAdapter remapped its 3
     * view types to ConcatAdapter-internal IDs, two week sections would
     * each pick the same internal IDs (e.g. both Week 0's HeaderAdapter
     * and Week 1's VideosAdapter end up reporting view-type 0 to the
     * shared pool). RecyclerView then hands a VideoVH to HeaderAdapter
     * and crashes during onBindViewHolder cast. We rely on
     * WEEK_HEADER_VIEW_TYPE / WEEK_SHORTS_VIEW_TYPE / WEEK_VIDEO_VIEW_TYPE
     * being globally unique constants so disabling isolation here is safe.
     */
    val sectionAdapter: ConcatAdapter = ConcatAdapter(
        ConcatAdapter.Config.Builder().setIsolateViewTypes(false).build(),
        headerAdapter,
        shortsAdapter,
        videosAdapter,
    )

    init {
        submit(initial)
    }

    /** Replace the rendered content for this week. Used when the cache
     *  changes (new ATOM refresh, deep-page fill). */
    fun submit(content: WeekContent) {
        headerAdapter.submit(content.weekIndex)
        shortsAdapter.submit(content.shorts)
        videosAdapter.submitList(content.videos)
    }

    // ---- Header ----

    private class HeaderAdapter : RecyclerView.Adapter<HeaderVH>() {
        private var weekIndex: Int = -1

        fun submit(weekIndex: Int) {
            if (this.weekIndex == weekIndex) return
            val hadItem = this.weekIndex >= 0
            this.weekIndex = weekIndex
            if (!hadItem) notifyItemInserted(0) else notifyItemChanged(0)
        }

        override fun getItemCount(): Int = if (weekIndex >= 0) 1 else 0
        override fun getItemViewType(position: Int) = WEEK_HEADER_VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderVH {
            val binding = ViewMeWeekHeaderBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return HeaderVH(binding)
        }

        override fun onBindViewHolder(holder: HeaderVH, position: Int) {
            val ctx = holder.binding.root.context
            val resId = WeekBucket.headerLabel(weekIndex)
            holder.binding.meWeekHeader.text = if (resId == R.string.me_week_n_ago) {
                ctx.getString(resId, weekIndex)
            } else {
                ctx.getString(resId)
            }
        }
    }

    private class HeaderVH(val binding: ViewMeWeekHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    // ---- Shorts row ----

    /**
     * Section adapter that toggles 0/1 visibility based on whether the
     * week has any shorts. The single rendered ViewHolder hosts an inner
     * horizontal RecyclerView populated by [InnerShortsAdapter]. Mirrors
     * the [MeShortsAdapter] pattern used by the legacy "newest shorts"
     * row above the feed.
     */
    private class ShortsRowAdapter(
        private val onClick: (MeFeedVideo) -> Unit,
        private val onItemBind: (videoId: String) -> Unit,
    ) : RecyclerView.Adapter<ShortsRowVH>() {
        private val inner = InnerShortsAdapter(onClick, onItemBind)

        fun submit(items: List<MeFeedVideo>) {
            val wasEmpty = inner.itemCount == 0
            inner.submitList(items) {
                val isEmpty = inner.itemCount == 0
                when {
                    wasEmpty && !isEmpty -> notifyItemInserted(0)
                    !wasEmpty && isEmpty -> notifyItemRemoved(0)
                }
            }
        }

        override fun getItemCount(): Int = if (inner.itemCount == 0) 0 else 1
        override fun getItemViewType(position: Int) = WEEK_SHORTS_VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortsRowVH {
            val binding = ItemMeShortsSectionBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            binding.shortsRecycler.layoutManager = LinearLayoutManager(
                parent.context, LinearLayoutManager.HORIZONTAL, false
            )
            return ShortsRowVH(binding)
        }

        override fun onBindViewHolder(holder: ShortsRowVH, position: Int) {
            // Bind-time attach to handle ViewHolder recycling. See
            // MeShortsAdapter for the long-form rationale.
            if (holder.binding.shortsRecycler.adapter !== inner) {
                holder.binding.shortsRecycler.adapter = inner
            }
        }
    }

    private class ShortsRowVH(val binding: ItemMeShortsSectionBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class InnerShortsAdapter(
        private val onClick: (MeFeedVideo) -> Unit,
        private val onItemBind: (videoId: String) -> Unit,
    ) : ListAdapter<MeFeedVideo, ShortVH>(SHORT_DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortVH {
            val binding = ItemMeShortBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ShortVH(binding, onClick)
        }

        override fun onBindViewHolder(holder: ShortVH, position: Int) {
            val item = getItem(position)
            holder.bind(item)
            // Warm the prefetch cache for this short so the player has the
            // resolved streams ready when the user taps it. No-op fallback
            // is supplied by the constructor default in [MeWeekSectionAdapter].
            if (item.videoId.isNotBlank()) onItemBind(item.videoId)
        }
    }

    private class ShortVH(
        private val binding: ItemMeShortBinding,
        private val onClick: (MeFeedVideo) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MeFeedVideo) {
            binding.shortTitle.text = item.title
            // ANDROID-PERSONAL-03 round 8 [field-bug]: cached URL is
            // hqdefault.jpg (480x360) which looks pixelated on high-DPI
            // displays. Pass primaryUrl=null so the helper builds the full
            // YouTube fallback chain (maxresdefault → sddefault → hqdefault →
            // mqdefault → default), starting from the highest quality.
            binding.shortThumbnail.loadYouTubeThumbnail(
                primaryUrl = null,
                videoId = item.videoId,
                isShort = true,
                placeholder = R.drawable.thumbnail_placeholder,
            )
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    // ---- Videos grid ----

    private class VideosAdapter(
        private val onClick: (MeFeedVideo) -> Unit,
        private val getChannelAvatar: (channelId: String) -> String?,
        private val onChannelClick: (channelId: String) -> Unit,
        private val onItemBind: (videoId: String) -> Unit,
    ) : ListAdapter<MeFeedVideo, VideoVH>(VIDEO_DIFF) {

        override fun getItemViewType(position: Int): Int = WEEK_VIDEO_VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoVH {
            val binding = ItemMeVideoBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VideoVH(binding, onClick, getChannelAvatar, onChannelClick)
        }

        override fun onBindViewHolder(holder: VideoVH, position: Int) {
            val item = getItem(position)
            holder.bind(item)
            // Same prefetch warm-up as InnerShortsAdapter — see comment there.
            if (item.videoId.isNotBlank()) onItemBind(item.videoId)
        }
    }

    class VideoVH(
        private val binding: ItemMeVideoBinding,
        private val onClick: (MeFeedVideo) -> Unit,
        private val getChannelAvatar: (channelId: String) -> String?,
        private val onChannelClick: (channelId: String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MeFeedVideo) {
            binding.videoTitle.text = item.title
            binding.videoMeta.text = buildMeta(item)

            // ANDROID-PERSONAL-03 round 8 [field-bug]: cached URL is
            // hqdefault.jpg (480x360) which looks pixelated on high-DPI
            // displays. Pass primaryUrl=null so the helper builds the full
            // YouTube fallback chain (maxresdefault → sddefault → hqdefault →
            // mqdefault → default), starting from the highest quality.
            binding.videoThumbnail.loadYouTubeThumbnail(
                primaryUrl = null,
                videoId = item.videoId,
                isShort = false,
                placeholder = R.drawable.thumbnail_placeholder,
            )

            val avatarUrl = getChannelAvatar(item.channelId)
            if (!avatarUrl.isNullOrBlank()) {
                binding.videoChannelAvatar.load(avatarUrl) {
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                    transformations(CircleCropTransformation())
                }
            } else {
                binding.videoChannelAvatar.load(R.drawable.thumbnail_placeholder) {
                    transformations(CircleCropTransformation())
                }
            }
            binding.videoChannelAvatar.setOnClickListener { onChannelClick(item.channelId) }
            binding.root.setOnClickListener { onClick(item) }
        }

        /**
         * Channel name + view count (when available) + relative upload date.
         *
         * View count is OPTIONAL because two upstream sources populate the
         * cache:
         *   - ATOM RSS (15 most-recent per channel) — does NOT expose
         *     viewCount; rows from this path leave the field null.
         *   - NewPipe deep-paging (Videos tab) — DOES expose viewCount;
         *     rows from this path carry the count.
         * Showing "— views" when the source didn't return a count would
         * be misleading, so we conditionally include the views chip only
         * when a real number is present. ATOM-only items render the
         * original "Channel • date" pair unchanged.
         */
        private fun buildMeta(item: MeFeedVideo): CharSequence {
            val ctx = binding.root.context
            val locale: Locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()
            val views: String? = item.viewCount
                ?.takeIf { it >= 0L }
                ?.let { NumberFormat.getNumberInstance(locale).format(it) }
                ?.let { ctx.getString(R.string.video_views_format, it) }
            val relative = if (item.uploadedAt > 0L) {
                DateUtils.getRelativeTimeSpanString(
                    item.uploadedAt,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE,
                ).toString()
            } else ""
            // ANDROID-PERSONAL-03 round 8 [field-bug]: long channel names
            // (e.g. "Dr. Othman Alkamees - الشيخ الدكتور عثمان الخميس",
            // ~50 chars) consumed all of the meta line's width on a
            // single phone, ellipsizing the much-more-useful views and
            // relative-date trailers off the right edge. Truncate the
            // channel name so the views + date are always visible.
            // CHANNEL_NAME_META_MAX_CHARS is generous enough that
            // typical channel names ("Mufti Menk", "Alafasy") survive
            // intact; only verbose Arabic-bilingual names get clipped.
            val channelName = if (item.channelName.length > CHANNEL_NAME_META_MAX_CHARS) {
                item.channelName.substring(0, CHANNEL_NAME_META_MAX_CHARS - 1).trimEnd() + "…"
            } else {
                item.channelName
            }
            return buildString {
                append(channelName)
                if (!views.isNullOrEmpty()) append(" • ").append(views)
                if (relative.isNotEmpty()) append(" • ").append(relative)
            }
        }
    }

    companion object {
        /**
         * Public view-type constants so [MeFragment] can recognise them
         * in the spanSizeLookup. Headers + shorts rows are always full
         * width; only video tiles span 1 column.
         *
         * Values are in the 5xx range to avoid colliding with chips
         * (101), favorites (401-403), and the legacy MeShortsAdapter /
         * MeVideosPagingAdapter constants (201, 301-302). Required
         * because the outer ConcatAdapter is constructed with isolation
         * disabled so the spanSizeLookup can compare raw inner view
         * types — see [MeFragment].
         */
        const val WEEK_HEADER_VIEW_TYPE = 501
        const val WEEK_SHORTS_VIEW_TYPE = 502
        const val WEEK_VIDEO_VIEW_TYPE = 503

        /**
         * Cap on channel-name length in [VideoVH.buildMeta]. Past this we
         * suffix an ellipsis so views + date stay visible. 24 chars fits
         * "Mufti Menk", "MercifulServant", "Alafasy", and similar names
         * intact and clips only the long bilingual Arabic/English names
         * that were eating the whole meta line on a phone.
         */
        private const val CHANNEL_NAME_META_MAX_CHARS = 24

        private val SHORT_DIFF = object : DiffUtil.ItemCallback<MeFeedVideo>() {
            override fun areItemsTheSame(old: MeFeedVideo, new: MeFeedVideo) =
                old.videoId == new.videoId
            override fun areContentsTheSame(old: MeFeedVideo, new: MeFeedVideo) = old == new
        }

        private val VIDEO_DIFF = object : DiffUtil.ItemCallback<MeFeedVideo>() {
            override fun areItemsTheSame(old: MeFeedVideo, new: MeFeedVideo) =
                old.videoId == new.videoId
            override fun areContentsTheSame(old: MeFeedVideo, new: MeFeedVideo) = old == new
        }
    }
}
