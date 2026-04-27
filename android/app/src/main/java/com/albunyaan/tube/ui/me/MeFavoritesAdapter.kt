package com.albunyaan.tube.ui.me

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.databinding.ItemMeFavoriteVideoBinding
import com.albunyaan.tube.databinding.ItemMeFavoritesRowBinding
import com.albunyaan.tube.databinding.ItemMeFavoritesSeeAllBinding

/**
 * Horizontal "Favorites" row for the Me tab. Renders the most recent
 * [MAX_TILES] favorites + a trailing "See all" tile. Hidden when the user
 * has 0 favorites.
 *
 * Pattern mirrors [MeShortsAdapter]: a single-item section adapter holds the
 * row container; a nested ConcatAdapter inside the row's RecyclerView holds
 * the tiles + see-all. Visibility of the parent row is toggled via the
 * same notifyItemInserted/notifyItemRemoved bridge used by chips/shorts.
 */
class MeFavoritesAdapter(
    private val onClick: (FavoriteVideo) -> Unit,
    private val onLongPress: (FavoriteVideo) -> Unit,
    private val onSeeAll: () -> Unit,
) {
    private val tilesAdapter = TilesAdapter(onClick, onLongPress)
    private val seeAllAdapter = SeeAllAdapter(onSeeAll)

    /**
     * Whether the row is currently visible. Driven by [submit] — true when
     * the most recent submission contained at least one favorite, false
     * otherwise. Used by [sectionAdapter.getItemCount].
     */
    private var hasItems: Boolean = false

    fun submit(items: List<FavoriteVideo>) {
        // T10: cap newest 20 at the adapter level. The repository may return
        // many more — the row only renders the freshest slice.
        val capped = items.take(MAX_TILES)
        val wasEmpty = !hasItems
        tilesAdapter.submitList(capped) {
            // Mirrors MeChipsAdapter / MeShortsAdapter F-CR1: ListAdapter's
            // commitCallback fires after the diff is applied; only at this
            // point do we know the inner item count is settled, so we can
            // toggle the section row's count and notify the outer adapter.
            val nowEmpty = capped.isEmpty()
            hasItems = !nowEmpty
            seeAllAdapter.show = !nowEmpty
            when {
                wasEmpty && !nowEmpty -> sectionAdapter.notifyItemInserted(0)
                !wasEmpty && nowEmpty -> sectionAdapter.notifyItemRemoved(0)
            }
        }
    }

    val sectionAdapter: RecyclerView.Adapter<*> = object : RecyclerView.Adapter<RowVH>() {
        override fun getItemCount(): Int = if (hasItems) 1 else 0
        override fun getItemViewType(position: Int) = FAVORITES_ROW_VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
            val binding = ItemMeFavoritesRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            binding.favoritesRecycler.layoutManager = LinearLayoutManager(
                parent.context, LinearLayoutManager.HORIZONTAL, false
            )
            return RowVH(binding)
        }

        override fun onBindViewHolder(holder: RowVH, position: Int) {
            // Mirrors MeShortsAdapter F-CR: attach the inner ConcatAdapter on
            // bind, not on create. ViewHolder recycling can hand a fresh
            // RecyclerView the same inner adapter reference; bind-time
            // assign keeps the attachment fresh per recycle, with an
            // identity check so we don't tear data observers on every bind.
            val current = holder.binding.favoritesRecycler.adapter
            if (current !is ConcatAdapter ||
                current.adapters.size != 2 ||
                current.adapters.firstOrNull() !== tilesAdapter
            ) {
                holder.binding.favoritesRecycler.adapter =
                    ConcatAdapter(tilesAdapter, seeAllAdapter)
            }
        }
    }

    class RowVH(val binding: ItemMeFavoritesRowBinding) : RecyclerView.ViewHolder(binding.root)

    private class TilesAdapter(
        private val onClick: (FavoriteVideo) -> Unit,
        private val onLongPress: (FavoriteVideo) -> Unit,
    ) : ListAdapter<FavoriteVideo, TileVH>(DIFF) {

        override fun getItemViewType(position: Int) = FAVORITES_TILE_VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileVH {
            val binding = ItemMeFavoriteVideoBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return TileVH(binding, onClick, onLongPress)
        }

        override fun onBindViewHolder(holder: TileVH, position: Int) {
            holder.bind(getItem(position))
        }
    }

    class TileVH(
        private val binding: ItemMeFavoriteVideoBinding,
        private val onClick: (FavoriteVideo) -> Unit,
        private val onLongPress: (FavoriteVideo) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FavoriteVideo) {
            binding.favoriteTitle.text = item.title
            binding.favoriteChannel.text = item.channelName

            val url = item.thumbnailUrl
            if (!url.isNullOrBlank()) {
                binding.favoriteThumbnail.load(url) {
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                }
            } else {
                // F-CR10 (CodeRabbit, mirrored from sibling adapters): cancel
                // any pending Coil request on a recycled ImageView by going
                // through Coil's load() instead of setImageResource().
                binding.favoriteThumbnail.load(R.drawable.thumbnail_placeholder)
            }

            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongPress(item)
                true
            }
        }
    }

    private class SeeAllAdapter(
        private val onSeeAll: () -> Unit,
    ) : RecyclerView.Adapter<SeeAllVH>() {
        var show: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                if (value) notifyItemInserted(0) else notifyItemRemoved(0)
            }

        override fun getItemCount(): Int = if (show) 1 else 0
        override fun getItemViewType(position: Int) = FAVORITES_SEE_ALL_VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeeAllVH {
            val binding = ItemMeFavoritesSeeAllBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return SeeAllVH(binding, onSeeAll)
        }

        override fun onBindViewHolder(holder: SeeAllVH, position: Int) = holder.bind()
    }

    class SeeAllVH(
        private val binding: ItemMeFavoritesSeeAllBinding,
        private val onSeeAll: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener { onSeeAll() }
        }
    }

    companion object {
        const val MAX_TILES = 20

        // View types — keep in 4xx range so they don't collide with chips
        // (1xx), shorts (2xx), or videos (3xx). Verified by grep at
        // implementation time.
        const val FAVORITES_ROW_VIEW_TYPE = 401
        const val FAVORITES_TILE_VIEW_TYPE = 403
        const val FAVORITES_SEE_ALL_VIEW_TYPE = 402

        private val DIFF = object : DiffUtil.ItemCallback<FavoriteVideo>() {
            override fun areItemsTheSame(old: FavoriteVideo, new: FavoriteVideo): Boolean =
                old.videoId == new.videoId

            override fun areContentsTheSame(old: FavoriteVideo, new: FavoriteVideo): Boolean =
                old == new
        }
    }
}
