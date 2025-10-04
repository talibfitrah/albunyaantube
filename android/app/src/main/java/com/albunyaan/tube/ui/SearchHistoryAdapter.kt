package com.albunyaan.tube.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.databinding.ItemSearchHistoryBinding

class SearchHistoryAdapter(
    private val items: List<String>,
    private val onItemClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.ViewHolder>() {

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onItemClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size
}
