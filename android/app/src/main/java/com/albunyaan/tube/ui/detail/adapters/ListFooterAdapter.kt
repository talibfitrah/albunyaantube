package com.albunyaan.tube.ui.detail.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.albunyaan.tube.databinding.ItemListFooterBinding

/**
 * Adapter for displaying a footer item in paginated lists.
 * Shows one of four states:
 * - Hidden: when no footer should be displayed (item count = 0)
 * - Load More button: when autofill is capped but more pages exist
 * - Loading spinner: when appending new items
 * - Error with Retry: when append failed
 *
 * Usage with ConcatAdapter:
 * ```
 * val contentAdapter = ChannelVideoAdapter { ... }
 * val footerAdapter = ListFooterAdapter(
 *     onLoadMoreClick = { viewModel.loadNextPage(tab) },
 *     onRetryClick = { viewModel.retryAppend(tab) }
 * )
 * recyclerView.adapter = ConcatAdapter(contentAdapter, footerAdapter)
 *
 * // Update footer state based on PaginatedState
 * footerAdapter.setState(FooterState.LoadMore)
 * ```
 */
class ListFooterAdapter(
    private val onLoadMoreClick: () -> Unit,
    private val onRetryClick: () -> Unit
) : RecyclerView.Adapter<ListFooterAdapter.FooterViewHolder>() {

    private var state: FooterState = FooterState.Hidden

    /**
     * Footer display states.
     */
    sealed class FooterState {
        /** Footer is hidden (no items displayed) */
        data object Hidden : FooterState()
        /** Show "Load More" button */
        data object LoadMore : FooterState()
        /** Show loading spinner */
        data object Loading : FooterState()
        /** Show error with retry button */
        data class Error(val message: String? = null) : FooterState()
    }

    /**
     * Update the footer state.
     * Call this when paginated state changes.
     * Short-circuits if the new state equals the current state to avoid redundant binds.
     */
    fun setState(newState: FooterState) {
        // Short-circuit if state hasn't changed
        if (newState == state) return

        val wasVisible = state != FooterState.Hidden
        val willBeVisible = newState != FooterState.Hidden

        state = newState

        when {
            wasVisible && !willBeVisible -> notifyItemRemoved(0)
            !wasVisible && willBeVisible -> notifyItemInserted(0)
            wasVisible && willBeVisible -> notifyItemChanged(0)
        }
    }

    override fun getItemCount(): Int = if (state != FooterState.Hidden) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FooterViewHolder {
        val binding = ItemListFooterBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FooterViewHolder(binding, onLoadMoreClick, onRetryClick)
    }

    override fun onBindViewHolder(holder: FooterViewHolder, position: Int) {
        holder.bind(state)
    }

    class FooterViewHolder(
        private val binding: ItemListFooterBinding,
        private val onLoadMoreClick: () -> Unit,
        private val onRetryClick: () -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.loadMoreButton.setOnClickListener { onLoadMoreClick() }
            binding.retryButton.setOnClickListener { onRetryClick() }
        }

        fun bind(state: FooterState) {
            when (state) {
                FooterState.Hidden -> {
                    // Should not be called, but handle for safety
                    binding.loadMoreButton.isVisible = false
                    binding.loadingIndicator.isVisible = false
                    binding.errorContainer.isVisible = false
                }
                FooterState.LoadMore -> {
                    binding.loadMoreButton.isVisible = true
                    binding.loadingIndicator.isVisible = false
                    binding.errorContainer.isVisible = false
                }
                FooterState.Loading -> {
                    binding.loadMoreButton.isVisible = false
                    binding.loadingIndicator.isVisible = true
                    binding.errorContainer.isVisible = false
                }
                is FooterState.Error -> {
                    binding.loadMoreButton.isVisible = false
                    binding.loadingIndicator.isVisible = false
                    binding.errorContainer.isVisible = true
                    // Display custom message if provided, otherwise use default
                    if (!state.message.isNullOrBlank()) {
                        binding.errorText.text = state.message
                    } else {
                        binding.errorText.setText(com.albunyaan.tube.R.string.load_more_error)
                    }
                }
            }
        }
    }
}
