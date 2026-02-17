package com.albunyaan.tube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.R
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.HomeSection
import com.albunyaan.tube.databinding.ItemHomeSectionBinding
import com.albunyaan.tube.locale.LocaleManager

/**
 * Vertical adapter for the home screen. Each item is a category section
 * containing a header (category name + "See All") and a nested horizontal
 * RecyclerView of mixed content items (channels, playlists, videos).
 */
class HomeSectionAdapter(
    private val onItemClick: (ContentItem) -> Unit,
    private val onSeeAllClick: (HomeSection) -> Unit
) : ListAdapter<HomeSection, HomeSectionAdapter.SectionViewHolder>(DIFF_CALLBACK) {

    var videoCardWidth: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }
    var channelCardWidth: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
        val binding = ItemHomeSectionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SectionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SectionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SectionViewHolder(
        private val binding: ItemHomeSectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val contentAdapter = HomeFeaturedAdapter { item -> onItemClick(item) }

        init {
            binding.sectionRecyclerView.apply {
                adapter = contentAdapter
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                setHasFixedSize(true)
            }
        }

        fun bind(section: HomeSection) {
            // Use localized name if available for current locale
            val context = binding.root.context
            val currentLocale = LocaleManager.getCurrentLocale(context).language
            val displayName = section.localizedNames?.get(currentLocale)
                ?: section.categoryName
            binding.sectionTitle.text = displayName

            // Apply card widths to the nested adapter
            contentAdapter.cardWidth = videoCardWidth
            contentAdapter.channelCardWidth = channelCardWidth
            contentAdapter.submitList(section.items)

            binding.sectionSeeAll.setOnClickListener {
                onSeeAllClick(section)
            }

            // Accessibility
            binding.sectionSeeAll.contentDescription = context.getString(
                R.string.home_see_all_category, displayName
            )
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<HomeSection>() {
            override fun areItemsTheSame(
                oldItem: HomeSection,
                newItem: HomeSection
            ): Boolean = oldItem.categoryId == newItem.categoryId

            override fun areContentsTheSame(
                oldItem: HomeSection,
                newItem: HomeSection
            ): Boolean = oldItem == newItem
        }
    }
}
