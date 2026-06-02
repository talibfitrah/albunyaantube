package com.albunyaan.tube.ui.me.importflow

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albunyaan.tube.R
import com.albunyaan.tube.data.youtube.CandidateType
import com.albunyaan.tube.data.youtube.ImportCandidate
import com.albunyaan.tube.databinding.ItemImportCandidateBinding
import com.albunyaan.tube.databinding.ItemImportGroupHeaderBinding

/**
 * B11: Grouped adapter for the import review RecyclerView.
 *
 * Items are presented in 3 fixed sections — CHANNEL, PLAYLIST, VIDEO — each
 * preceded by a group header that shows a "select all" checkbox and a count.
 * Sections with zero candidates are omitted.
 *
 * View types:
 *  VIEW_TYPE_HEADER    (100) — group header row
 *  VIEW_TYPE_CANDIDATE (101) — individual import candidate row
 */
class ImportReviewAdapter(
    private val onToggleItem: (youtubeId: String) -> Unit,
    private val onGroupSelectAll: (type: CandidateType, selected: Boolean) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // The flat list of display items assembled by [submitReview].
    private val items: MutableList<DisplayItem> = mutableListOf()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Rebuilds the adapter's flat list from [candidates] and [selected], then
     * notifies with a DiffUtil pass so animations work.
     */
    fun submitReview(candidates: List<ImportCandidate>, selected: Set<String>) {
        val newItems = buildDisplayList(candidates, selected)
        val diff = DiffUtil.calculateDiff(DisplayDiff(items, newItems))
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    // ── RecyclerView.Adapter ──────────────────────────────────────────────────

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is DisplayItem.Header    -> VIEW_TYPE_HEADER
        is DisplayItem.Candidate -> VIEW_TYPE_CANDIDATE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val b = ItemImportGroupHeaderBinding.inflate(inflater, parent, false)
                HeaderVH(b, onGroupSelectAll)
            }
            else -> {
                val b = ItemImportCandidateBinding.inflate(inflater, parent, false)
                CandidateVH(b, onToggleItem)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DisplayItem.Header    -> (holder as HeaderVH).bind(item)
            is DisplayItem.Candidate -> (holder as CandidateVH).bind(item)
        }
    }

    // ── ViewHolders ───────────────────────────────────────────────────────────

    class HeaderVH(
        private val binding: ItemImportGroupHeaderBinding,
        private val onGroupSelectAll: (CandidateType, Boolean) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DisplayItem.Header) {
            val ctx = binding.root.context
            val titleRes = when (item.type) {
                CandidateType.CHANNEL  -> R.string.import_youtube_group_channels
                CandidateType.PLAYLIST -> R.string.import_youtube_group_playlists
                CandidateType.VIDEO    -> R.string.import_youtube_group_videos
            }
            binding.groupTitle.text = ctx.getString(titleRes, item.count)

            // Avoid re-triggering the listener while we programmatically set state.
            binding.selectAllCheckbox.setOnCheckedChangeListener(null)
            binding.selectAllCheckbox.isChecked = item.allSelected
            binding.selectAllCheckbox.setOnCheckedChangeListener { _, isChecked ->
                onGroupSelectAll(item.type, isChecked)
            }
        }
    }

    class CandidateVH(
        private val binding: ItemImportCandidateBinding,
        private val onToggleItem: (String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DisplayItem.Candidate) {
            binding.candidateTitle.text = item.candidate.title

            val url = item.candidate.thumbnailUrl
            if (!url.isNullOrBlank()) {
                binding.thumbnail.load(url) {
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                }
            } else {
                binding.thumbnail.load(R.drawable.thumbnail_placeholder)
            }

            // Disable listener before setting state to avoid spurious ViewModel calls.
            binding.candidateCheckbox.setOnCheckedChangeListener(null)
            binding.candidateCheckbox.isChecked = item.isSelected

            val toggle = { onToggleItem(item.candidate.youtubeId) }
            binding.candidateCheckbox.setOnCheckedChangeListener { _, _ -> toggle() }
            binding.root.setOnClickListener { toggle() }
        }
    }

    // ── Display model ─────────────────────────────────────────────────────────

    sealed interface DisplayItem {
        data class Header(
            val type: CandidateType,
            val count: Int,
            val allSelected: Boolean,
        ) : DisplayItem

        data class Candidate(
            val candidate: ImportCandidate,
            val isSelected: Boolean,
        ) : DisplayItem
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildDisplayList(
        candidates: List<ImportCandidate>,
        selected: Set<String>,
    ): List<DisplayItem> {
        val result = mutableListOf<DisplayItem>()
        // Sections in declaration order: CHANNEL, PLAYLIST, VIDEO.
        for (type in CandidateType.entries) {
            val group = candidates.filter { it.type == type }
            if (group.isEmpty()) continue
            val groupSelected = group.filter { it.youtubeId in selected }
            result += DisplayItem.Header(
                type = type,
                count = group.size,
                allSelected = groupSelected.size == group.size,
            )
            group.forEach { candidate ->
                result += DisplayItem.Candidate(
                    candidate = candidate,
                    isSelected = candidate.youtubeId in selected,
                )
            }
        }
        return result
    }

    private class DisplayDiff(
        private val old: List<DisplayItem>,
        private val new: List<DisplayItem>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = new.size
        override fun areItemsTheSame(op: Int, np: Int): Boolean {
            val o = old[op]; val n = new[np]
            return when {
                o is DisplayItem.Header    && n is DisplayItem.Header    -> o.type == n.type
                o is DisplayItem.Candidate && n is DisplayItem.Candidate ->
                    o.candidate.youtubeId == n.candidate.youtubeId
                else -> false
            }
        }
        override fun areContentsTheSame(op: Int, np: Int): Boolean = old[op] == new[np]
    }

    companion object {
        const val VIEW_TYPE_HEADER    = 100
        const val VIEW_TYPE_CANDIDATE = 101
    }
}
