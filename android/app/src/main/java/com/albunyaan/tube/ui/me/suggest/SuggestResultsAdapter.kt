package com.albunyaan.tube.ui.me.suggest

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.search.dto.SearchHitDto
import com.albunyaan.tube.databinding.ItemSuggestResultBinding
import com.albunyaan.tube.util.ImageLoading.loadThumbnailUrl

class SuggestResultsAdapter(
    private val onClick: (SearchHitDto) -> Unit
) : ListAdapter<SearchHitDto, SuggestResultsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSuggestResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemSuggestResultBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(hit: SearchHitDto) {
            b.name.text = hit.name
            b.secondary.text = hit.secondary.orEmpty()

            b.thumb.loadThumbnailUrl(hit.thumbnailUrl)

            val badgeTextRes: Int? = when {
                hit.alreadyKnown && hit.knownStatus == "PENDING"  -> R.string.suggest_already_pending
                hit.alreadyKnown && hit.knownStatus == "REJECTED" -> R.string.suggest_already_rejected
                hit.alreadyKnown                                   -> R.string.suggest_already_in_registry
                else                                               -> null
            }

            if (badgeTextRes != null) {
                b.badge.visibility = View.VISIBLE
                b.badge.setText(badgeTextRes)
            } else {
                b.badge.visibility = View.GONE
            }

            b.root.setOnClickListener { onClick(hit) }
        }
    }

    private companion object DIFF : DiffUtil.ItemCallback<SearchHitDto>() {
        override fun areItemsTheSame(oldItem: SearchHitDto, newItem: SearchHitDto) =
            oldItem.youtubeId == newItem.youtubeId

        override fun areContentsTheSame(oldItem: SearchHitDto, newItem: SearchHitDto) =
            oldItem == newItem
    }
}
