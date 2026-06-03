package com.albunyaan.tube.ui.me

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.me.AwaitingImports
import com.albunyaan.tube.databinding.ItemMeAwaitingHeaderBinding
import com.albunyaan.tube.databinding.ItemMeAwaitingItemBinding

/**
 * B14: "Imported — awaiting review" section for the Me tab ConcatAdapter.
 *
 * Exposes [sectionAdapter] — an outer [RecyclerView.Adapter] whose item count
 * toggles between 0 (hidden) and N+1 (header + N awaiting rows) reactively as
 * [submit] is called. This mirrors [MeFavoritesAdapter]'s pattern so the
 * ConcatAdapter (isolation OFF) always sees a stable range of positions.
 *
 * Items are NOT clickable — they are pending admin approval and must not
 * navigate to a player or channel detail screen.
 *
 * View-type allocation (unique within the shared ConcatAdapter):
 *   AWAITING_HEADER_VIEW_TYPE = 601
 *   AWAITING_ITEM_VIEW_TYPE   = 602
 *
 * Ordering: channels first, then playlists, then videos — mirrors the import
 * review screen grouping (B11).
 */
class AwaitingImportsAdapter {

    /** Flat display list: one header + one row per awaiting item. */
    private sealed interface DisplayRow {
        data class Header(val total: Int) : DisplayRow
        data class ChannelRow(val channel: SubscribedChannel) : DisplayRow
        data class PlaylistRow(val playlist: SavedPlaylist) : DisplayRow
        data class VideoRow(val video: FavoriteVideo) : DisplayRow
    }

    private var rows: List<DisplayRow> = emptyList()

    /**
     * Public surface: the single RecyclerView.Adapter to pass to ConcatAdapter.
     * Returns 0 items when [AwaitingImports] is empty so the section disappears.
     */
    val sectionAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder> = SectionAdapter()

    /**
     * Push a new [AwaitingImports] snapshot. When all three lists are empty the
     * section collapses (item count → 0). Otherwise rebuilds header + item rows
     * and notifies the adapter.
     */
    fun submit(awaiting: AwaitingImports) {
        val newRows = buildRows(awaiting)
        val old = rows
        rows = newRows
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val o = old[oldPos]; val n = newRows[newPos]
                return when {
                    o is DisplayRow.Header && n is DisplayRow.Header -> true
                    o is DisplayRow.ChannelRow && n is DisplayRow.ChannelRow ->
                        o.channel.channelId == n.channel.channelId
                    o is DisplayRow.PlaylistRow && n is DisplayRow.PlaylistRow ->
                        o.playlist.playlistId == n.playlist.playlistId
                    o is DisplayRow.VideoRow && n is DisplayRow.VideoRow ->
                        o.video.videoId == n.video.videoId
                    else -> false
                }
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == newRows[newPos]
        }).dispatchUpdatesTo(sectionAdapter)
    }

    private fun buildRows(awaiting: AwaitingImports): List<DisplayRow> {
        val total = awaiting.channels.size + awaiting.playlists.size + awaiting.videos.size
        if (total == 0) return emptyList()
        val result = mutableListOf<DisplayRow>(DisplayRow.Header(total))
        awaiting.channels.mapTo(result) { DisplayRow.ChannelRow(it) }
        awaiting.playlists.mapTo(result) { DisplayRow.PlaylistRow(it) }
        awaiting.videos.mapTo(result) { DisplayRow.VideoRow(it) }
        return result
    }

    // ── Inner adapter ──────────────────────────────────────────────────────

    private inner class SectionAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is DisplayRow.Header -> AWAITING_HEADER_VIEW_TYPE
            else -> AWAITING_ITEM_VIEW_TYPE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                AWAITING_HEADER_VIEW_TYPE -> HeaderVH(
                    ItemMeAwaitingHeaderBinding.inflate(inflater, parent, false)
                )
                else -> ItemVH(
                    ItemMeAwaitingItemBinding.inflate(inflater, parent, false)
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is DisplayRow.Header -> (holder as HeaderVH).bindTotal(row.total)
                is DisplayRow.ChannelRow -> (holder as ItemVH).bindContent(
                    title = row.channel.name,
                    thumbnailUrl = row.channel.avatarUrl,
                )
                is DisplayRow.PlaylistRow -> (holder as ItemVH).bindContent(
                    title = row.playlist.name,
                    thumbnailUrl = row.playlist.thumbnailUrl,
                )
                is DisplayRow.VideoRow -> (holder as ItemVH).bindContent(
                    title = row.video.title,
                    thumbnailUrl = row.video.thumbnailUrl,
                )
            }
        }
    }

    // ── ViewHolders — package-private so the inner SectionAdapter can call them ──

    inner class HeaderVH(private val binding: ItemMeAwaitingHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bindTotal(total: Int) {
            binding.awaitingHeaderTitle.setText(R.string.me_awaiting_section_title)
            binding.awaitingHeaderCount.text =
                binding.root.context.getString(R.string.me_awaiting_count, total)
        }
    }

    inner class ItemVH(private val binding: ItemMeAwaitingItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bindContent(title: String, thumbnailUrl: String?) {
            binding.awaitingItemTitle.text = title
            binding.awaitingItemChip.setText(R.string.me_awaiting_pending_label)
            if (thumbnailUrl != null) {
                binding.awaitingItemThumbnail.load(thumbnailUrl) {
                    crossfade(true)
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                }
            } else {
                binding.awaitingItemThumbnail.setImageResource(R.drawable.thumbnail_placeholder)
            }
        }
    }

    companion object {
        /** Unique view-type constants within the Me-tab ConcatAdapter (isolation OFF).
         *  chips=101, favorites=401-403, weeks=501-503 — awaiting uses 601-602. */
        const val AWAITING_HEADER_VIEW_TYPE = 601
        const val AWAITING_ITEM_VIEW_TYPE   = 602
    }
}
