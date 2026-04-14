package com.albunyaan.tube.ui.me

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.albunyaan.tube.R
import com.albunyaan.tube.data.me.ChipItem
import com.albunyaan.tube.databinding.ItemMeChipsRowBinding
import com.albunyaan.tube.databinding.ItemMeChipBinding

/**
 * Renders the horizontal row of subscribed-channel / saved-playlist chips
 * at the top of the Me feed.
 *
 * Exposed as a single-item ConcatAdapter row via [rowAdapter] so the whole
 * strip scrolls horizontally inside a vertical RecyclerView.
 */
class MeChipsAdapter(
    private val onClick: (ChipItem) -> Unit,
) {
    private val inner = InnerAdapter(onClick = { chip ->
        onClick(chip)
    })

    var selectedId: String?
        get() = inner.selectedId
        set(value) {
            val previous = inner.selectedId
            if (previous == value) return
            inner.selectedId = value
            // F7: only notify the two chips whose selection state actually
            // changed. `notifyDataSetChanged()` caused Coil to re-fetch every
            // avatar on any selection change — wasteful + visible flicker.
            val currentList = inner.currentList
            if (previous != null) {
                val oldIndex = currentList.indexOfFirst { it.id == previous }
                if (oldIndex >= 0) inner.notifyItemChanged(oldIndex, SELECTION_PAYLOAD)
            }
            if (value != null) {
                val newIndex = currentList.indexOfFirst { it.id == value }
                if (newIndex >= 0) inner.notifyItemChanged(newIndex, SELECTION_PAYLOAD)
            }
        }

    fun submit(items: List<ChipItem>) {
        inner.submitList(items)
    }

    val rowAdapter: RecyclerView.Adapter<*> = object : RecyclerView.Adapter<RowVH>() {
        override fun getItemCount() = if (inner.itemCount == 0) 0 else 1
        override fun getItemViewType(position: Int) = ROW_VIEW_TYPE
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
            val binding = ItemMeChipsRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            binding.chipsRecycler.layoutManager = LinearLayoutManager(
                parent.context, LinearLayoutManager.HORIZONTAL, false
            )
            binding.chipsRecycler.adapter = inner
            return RowVH(binding)
        }

        override fun onBindViewHolder(holder: RowVH, position: Int) {
            // no-op — inner adapter holds the data
        }
    }

    class RowVH(val binding: ItemMeChipsRowBinding) : RecyclerView.ViewHolder(binding.root)

    private class InnerAdapter(
        private val onClick: (ChipItem) -> Unit,
    ) : ListAdapter<ChipItem, ChipVH>(DIFF) {
        var selectedId: String? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipVH {
            val binding = ItemMeChipBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ChipVH(binding, onClick)
        }

        override fun onBindViewHolder(holder: ChipVH, position: Int) {
            holder.bind(getItem(position), isSelected = getItem(position).id == selectedId)
        }

        override fun onBindViewHolder(holder: ChipVH, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(SELECTION_PAYLOAD)) {
                holder.bindSelection(isSelected = getItem(position).id == selectedId)
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }
    }

    class ChipVH(
        private val binding: ItemMeChipBinding,
        private val onClick: (ChipItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChipItem, isSelected: Boolean) {
            binding.chipLabel.text = item.label
            val url = item.imageUrl
            if (!url.isNullOrBlank()) {
                binding.chipAvatar.load(url) {
                    placeholder(R.drawable.thumbnail_placeholder)
                    error(R.drawable.thumbnail_placeholder)
                    transformations(CircleCropTransformation())
                }
            } else {
                binding.chipAvatar.setImageResource(R.drawable.thumbnail_placeholder)
            }
            binding.chipRoot.isCheckable = true
            binding.chipRoot.isChecked = isSelected
            binding.chipRoot.setOnClickListener { onClick(item) }
        }

        /** Payload-path rebind: flip selection without re-loading the avatar. */
        fun bindSelection(isSelected: Boolean) {
            binding.chipRoot.isCheckable = true
            binding.chipRoot.isChecked = isSelected
        }
    }

    companion object {
        const val ROW_VIEW_TYPE = 101
        private val SELECTION_PAYLOAD = Any()

        private val DIFF = object : DiffUtil.ItemCallback<ChipItem>() {
            override fun areItemsTheSame(old: ChipItem, new: ChipItem): Boolean =
                old.id == new.id && old.javaClass == new.javaClass
            override fun areContentsTheSame(old: ChipItem, new: ChipItem): Boolean = old == new
        }
    }
}
