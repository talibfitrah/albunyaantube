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
import kotlinx.coroutines.flow.combine
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
    private val callbacks: Callbacks
) : ListAdapter<ShortsItem, ShortsPageViewHolder>(DIFF) {

    /**
     * Indices passed to the callbacks are the adapter position of the bound
     * item at click time; the fragment translates these into [ShortsItem]s via
     * [ShortsPlayerViewModel.items]. [onLikedFlow] / [onFollowedFlow] must
     * emit continuously as the underlying repositories change — typically
     * backed by Room `Flow<Boolean>` queries.
     */
    data class Callbacks(
        val onLike: (Int) -> Unit,
        val onShare: (Int) -> Unit,
        val onSubscribe: (Int) -> Unit,
        val onChannelTap: (Int) -> Unit,
        val onTapVideo: (Int) -> Unit,
        val onLikedFlow: (videoId: String) -> Flow<Boolean>,
        val onFollowedFlow: (channelId: String) -> Flow<Boolean>
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
        holder.bindItem(item, isLiked = false, isFollowed = false)

        val lifecycleOwner = holder.itemView.findViewTreeLifecycleOwner() ?: return
        val job = lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val likedFlow = callbacks.onLikedFlow(item.id)
                // Empty channelId means feed-mode without hydrated header; treat
                // as "not followed" without touching the follow repo.
                val followedFlow = if (item.channelId.isNotBlank()) {
                    callbacks.onFollowedFlow(item.channelId)
                } else {
                    kotlinx.coroutines.flow.flowOf(false)
                }
                combine(likedFlow, followedFlow) { liked, followed -> liked to followed }
                    .collect { (liked, followed) ->
                        val currentPos = holder.bindingAdapterPosition
                        if (currentPos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                            return@collect
                        }
                        holder.bindItem(item, liked, followed)
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
        isFollowed: Boolean
    ) {
        bind(
            item = item,
            isLiked = isLiked,
            isFollowed = isFollowed,
            onLike = {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) callbacks.onLike(pos)
            },
            onShare = {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) callbacks.onShare(pos)
            },
            onSubscribe = {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) callbacks.onSubscribe(pos)
            },
            onChannelTap = {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) callbacks.onChannelTap(pos)
            },
            onTapVideo = {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) callbacks.onTapVideo(pos)
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
