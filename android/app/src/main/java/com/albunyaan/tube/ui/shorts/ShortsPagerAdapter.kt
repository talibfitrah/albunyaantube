package com.albunyaan.tube.ui.shorts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.albunyaan.tube.data.shorts.ShortsItem
import com.albunyaan.tube.databinding.ItemShortsPageBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * ListAdapter backing the vertical ViewPager2 in [ShortsPlayerFragment].
 *
 * All interactive callbacks and like/follow state flows are supplied via
 * [Callbacks], which the fragment constructs — the adapter itself owns no
 * state. Per-item like/follow collection is scoped to the ViewHolder's own
 * view-tree LifecycleOwner so state updates stop the moment the page is
 * recycled or detached.
 */
class ShortsPagerAdapter(
    /**
     * Owner whose lifecycle scopes every per-page flow collection. Must be
     * the hosting fragment's viewLifecycleOwner — using
     * `itemView.findViewTreeLifecycleOwner()` here returns null because
     * RecyclerView items aren't attached to window at bind time, which
     * silently disabled every flow-driven UI update (likes, audio-language
     * button visibility, etc.).
     */
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    private val callbacks: Callbacks
) : ListAdapter<ShortsItem, ShortsPageViewHolder>(DIFF) {

    /**
     * Indices passed to the callbacks are the adapter position of the bound
     * item at click time; the fragment translates these into [ShortsItem]s via
     * [ShortsPlayerViewModel.items]. [onLikedFlow] must emit continuously as
     * the favorites repository changes — typically backed by a Room
     * `Flow<Boolean>` query. Subscribe is intentionally absent — that UX
     * lives on the channel detail screen.
     */
    data class Callbacks(
        val onLike: (Int) -> Unit,
        val onShare: (Int) -> Unit,
        val onDownload: (Int) -> Unit,
        val onChannelTap: (Int) -> Unit,
        val onTapVideo: (Int) -> Unit,
        val onAudioTrackTap: (Int) -> Unit,
        val onSubtitleTap: (Int) -> Unit,
        val onLikedFlow: (videoId: String) -> Flow<Boolean>,
        /**
         * Emits the number of selectable audio-language options for the
         * given video id. The adapter flips the rail button visible when
         * the count is ≥ 2. MUST NOT block: expected to be backed by a
         * MutableStateFlow the fragment populates on PlayerBinder.resolvedEvents.
         */
        val onAudioLanguageCountFlow: (videoId: String) -> Flow<Int>,
        /**
         * Emits the number of subtitle tracks available for the given video id.
         * The adapter flips the CC button visible when the count is > 0. MUST
         * NOT block: backed by a MutableStateFlow populated on resolvedEvents.
         */
        val onSubtitleCountFlow: (videoId: String) -> Flow<Int>
    )

    // Tracks the active Flow-collection job per ViewHolder so it can be cancelled
    // when the holder is recycled / rebound to a new item.
    private val collectJobs = mutableMapOf<ShortsPageViewHolder, Job>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortsPageViewHolder {
        val binding = ItemShortsPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ShortsPageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ShortsPageViewHolder, position: Int) {
        val item = getItem(position)

        // Cancel any previous collection for this recycled holder before we
        // rebind with the new item's flows.
        collectJobs.remove(holder)?.cancel()

        // Bind once synchronously with default state so the page is visible
        // even before the first flow emission arrives.
        holder.bindItem(item, isLiked = false, hasMultipleAudioTracks = false)

        val job = lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Collect liked + audio-language-count flows in parallel and
                // rebind the affected UI pieces incrementally — we don't want
                // a tiny language-count change to re-run avatar loads.
                launch {
                    callbacks.onLikedFlow(item.id).collect { liked ->
                        val currentPos = holder.bindingAdapterPosition
                        if (currentPos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                            return@collect
                        }
                        // Re-bind keeping the current audio-track button state — the
                        // counter flow below drives its visibility independently.
                        holder.bindItem(item, liked, hasMultipleAudioTracks = false)
                    }
                }
                launch {
                    callbacks.onAudioLanguageCountFlow(item.id).collect { count ->
                        val currentPos = holder.bindingAdapterPosition
                        if (currentPos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                            return@collect
                        }
                        holder.setAudioTrackButtonVisible(count >= 2)
                    }
                }
                launch {
                    callbacks.onSubtitleCountFlow(item.id).collect { count ->
                        val currentPos = holder.bindingAdapterPosition
                        if (currentPos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                            return@collect
                        }
                        holder.setSubtitleButtonVisible(count > 0)
                    }
                }
            }
        }
        collectJobs[holder] = job
    }

    override fun onViewRecycled(holder: ShortsPageViewHolder) {
        super.onViewRecycled(holder)
        collectJobs.remove(holder)?.cancel()
    }

    private fun ShortsPageViewHolder.bindItem(
        item: ShortsItem,
        isLiked: Boolean,
        hasMultipleAudioTracks: Boolean
    ) {
        bind(
            item = item,
            isLiked = isLiked,
            hasMultipleAudioTracks = hasMultipleAudioTracks,
            onLike = {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) callbacks.onLike(pos)
            },
            onShare = {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) callbacks.onShare(pos)
            },
            onDownload = {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) callbacks.onDownload(pos)
            },
            onChannelTap = {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) callbacks.onChannelTap(pos)
            },
            onTapVideo = {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) callbacks.onTapVideo(pos)
            },
            onAudioTrackTap = {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) callbacks.onAudioTrackTap(pos)
            },
            onSubtitleTap = {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) callbacks.onSubtitleTap(pos)
            }
        )
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ShortsItem>() {
            override fun areItemsTheSame(oldItem: ShortsItem, newItem: ShortsItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ShortsItem, newItem: ShortsItem): Boolean =
                oldItem == newItem
        }
    }
}
