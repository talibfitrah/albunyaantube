package com.albunyaan.tube.ui.categories

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.databinding.ItemCategoryBinding

class CategoryAdapter(
    private val onCategoryClick: (Category) -> Unit
) : ListAdapter<Category, CategoryAdapter.CategoryViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CategoryViewHolder(binding, onCategoryClick)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class CategoryViewHolder(
        private val binding: ItemCategoryBinding,
        private val onCategoryClick: (Category) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(category: Category) {
            binding.categoryName.text = category.name

            // Show icon if available
            if (!category.icon.isNullOrBlank()) {
                binding.categoryIcon.text = category.icon
                binding.categoryIcon.visibility = View.VISIBLE
            } else {
                binding.categoryIcon.visibility = View.GONE
            }

            // Show or hide chevron based on whether category has subcategories
            binding.chevron.visibility = if (category.hasSubcategories) {
                View.VISIBLE
            } else {
                View.GONE
            }

            binding.root.setOnClickListener {
                android.util.Log.d("CategoryAdapter", "Category clicked: ${category.name}")
                onCategoryClick(category)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Category>() {
            override fun areItemsTheSame(oldItem: Category, newItem: Category): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Category, newItem: Category): Boolean =
                oldItem == newItem
        }
    }
}
