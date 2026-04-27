package com.albunyaan.tube.data.me

import com.albunyaan.tube.R

/**
 * ANDROID-PERSONAL-03 / T3: rolling 7-day bucket math for the Me-tab feed.
 *
 * The Me-tab now renders content grouped by week. Buckets are NOT ISO weeks
 * (Mon-Sun) — they are rolling 7-day windows from `now`:
 *  - weekIndex=0: now-7d to now ("This week")
 *  - weekIndex=1: now-14d to now-7d ("Last week")
 *  - weekIndex=N: now-(N+1)*7d to now-N*7d ("N weeks ago")
 *
 * The `now` argument is the test seam — production code passes
 * [System.currentTimeMillis]. Rolling boundaries mean "today is Sunday" is
 * NOT a special case: the only thing that matters is the timestamp at which
 * `now` was computed, which is per-call.
 *
 * Empty weeks (no shorts AND no videos) are skipped at the ViewModel layer;
 * this class does not concern itself with content.
 */
data class WeekBucket(
    val weekIndex: Int,
    val startMs: Long,
    val endMs: Long,
) {
    companion object {
        /** 7 days in milliseconds. */
        const val WEEK_MS: Long = 7L * 24L * 60L * 60L * 1_000L

        /** Maximum index supported by the UI (1 year of history). */
        const val MAX_WEEKS_BACK: Int = 52

        /**
         * Build a bucket for `weekIndex` relative to `now`. The window is
         * half-open: `[startMs, endMs)`.
         */
        fun forIndex(weekIndex: Int, now: Long): WeekBucket {
            require(weekIndex >= 0) { "weekIndex must be non-negative, got $weekIndex" }
            return WeekBucket(
                weekIndex = weekIndex,
                startMs = now - (weekIndex + 1).toLong() * WEEK_MS,
                endMs = now - weekIndex.toLong() * WEEK_MS,
            )
        }

        /**
         * String resource id for the week header.
         *
         *  - weekIndex=0 → "This week"
         *  - weekIndex=1 → "Last week"
         *  - weekIndex>=2 → "%1$d weeks ago" (caller must format with the
         *    integer arg; we don't know it from a static fn)
         */
        fun headerLabel(weekIndex: Int): Int = when (weekIndex) {
            0 -> R.string.me_week_this
            1 -> R.string.me_week_last
            else -> R.string.me_week_n_ago
        }
    }
}

/**
 * ANDROID-PERSONAL-03 / T3: a single non-empty week's content for the Me-tab.
 *
 * The repository emits one [WeekContent] per non-empty week; the ViewModel
 * appends these to its `weeks` flow as the user scrolls. Items split into:
 *  - [shorts]: `isShort = true`, newest-first
 *  - [videos]: `isShort = false`, newest-first
 *
 * Items with a null `uploadedAt` are excluded by the repository at query
 * time (the DAO's `uploadedAt IS NOT NULL` predicate handles this).
 */
data class WeekContent(
    val weekIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val shorts: List<MeFeedVideo>,
    val videos: List<MeFeedVideo>,
) {
    val isEmpty: Boolean get() = shorts.isEmpty() && videos.isEmpty()
}
