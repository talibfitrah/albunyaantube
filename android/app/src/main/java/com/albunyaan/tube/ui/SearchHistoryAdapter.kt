package com.albunyaan.tube.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.databinding.ItemSearchHistoryBinding

class SearchHistoryAdapter(
    private val onItemClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : ListAdapter<String, SearchHistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onItemClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemSearchHistoryBinding,
        private val onItemClick: (String) -> Unit,
        private val onDeleteClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(query: String) {
            binding.searchText.text = query

            binding.root.setOnClickListener {
                onItemClick(query)
            }

            binding.deleteButton.setOnClickListener {
                onDeleteClick(query)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean =
                oldItem == newItem

            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean =
                oldItem == newItem
        }
    }
}
