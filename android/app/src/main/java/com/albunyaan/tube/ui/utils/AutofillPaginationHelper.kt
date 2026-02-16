package com.albunyaan.tube.ui.utils

import android.util.Log
import androidx.recyclerview.widget.RecyclerView

/**
 * Reusable helper for auto-loading more items on large screens where content
 * may fit without scrolling (so the scroll listener never fires).
 *
 * Guards:
 * - Large-screen only (smallestScreenWidthDp >= 600)
 * - Bounded attempts (capped at [maxAttempts])
 * - Pagination-error gating (skips after error to prevent retry storm)
 * - Progress invariant (item count must grow between attempts)
 * - Lifecycle safety (caller verifies view is active)
 */
class AutofillPaginationHelper(
    private val tag: String,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
) {
    private var attempts = 0
    private var lastItemCount = 0

    fun reset() {
        attempts = 0
        lastItemCount = 0
    }

    /**
     * Check if auto-loading should be triggered after a list update.
     *
     * @param itemCount Current number of items in the list
     * @param hasMoreData Whether more data is available from the server
     * @param hasPaginationError Whether the last pagination attempt failed
     * @param smallestScreenWidthDp The device's smallest screen width in dp
     * @param recyclerView The RecyclerView to check scrollability on
     * @param isViewActive Whether the fragment view is still active
     * @param canLoadMore Whether the ViewModel allows loading more
     * @param loadMore Callback to trigger loading more items
     */
    fun check(
        itemCount: Int,
        hasMoreData: Boolean,
        hasPaginationError: Boolean,
        smallestScreenWidthDp: Int,
        recyclerView: RecyclerView?,
        isViewActive: () -> Boolean,
        canLoadMore: () -> Boolean,
        loadMore: () -> Unit
    ) {
        // Only run on large screens where items-fit-without-scrolling is a real scenario
        if (smallestScreenWidthDp < LARGE_SCREEN_THRESHOLD_DP) return

        if (!hasMoreData) {
            reset()
            return
        }

        // Don't retry autofill after a pagination error (prevents retry storm)
        if (hasPaginationError) {
            Log.d(tag, "Autofill skipped: pagination error present")
            return
        }

        // Bounded: cap attempts to prevent excessive fetches
        if (attempts >= maxAttempts) {
            Log.d(tag, "Autofill capped at $maxAttempts attempts ($itemCount items)")
            return
        }

        // Progress invariant: stop if no new items since last attempt
        if (attempts > 0 && itemCount <= lastItemCount) {
            Log.d(tag, "Autofill stopped: no progress ($itemCount items, was $lastItemCount)")
            return
        }

        val rv = recyclerView ?: return
        rv.post {
            if (!isViewActive()) return@post
            if (!rv.canScrollVertically(1) && canLoadMore()) {
                lastItemCount = itemCount
                attempts++
                Log.d(tag, "Autofill triggered (attempt $attempts), $itemCount items")
                loadMore()
            } else {
                // Either the list became scrollable or canLoadMore() returned false
                // (e.g., already loading); reset counter in both cases
                reset()
            }
        }
    }

    companion object {
        private const val DEFAULT_MAX_ATTEMPTS = 5
        private const val LARGE_SCREEN_THRESHOLD_DP = 600
    }
}
