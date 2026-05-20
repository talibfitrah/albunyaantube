package com.albunyaan.tube.data.me

import com.albunyaan.tube.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * ANDROID-PERSONAL-03 / T3: ISO-week bucket math for the Me-tab feed.
 *
 * Cubic R-final7 / weekly grouping design — boundaries are ISO weeks
 * (Monday → Sunday), per the design answer from the 2026-05-16 session:
 *  - weekIndex=0: Monday of the current ISO week → next Monday ("This week")
 *  - weekIndex=1: Monday of the previous ISO week → previous Monday's next ("Last week")
 *  - weekIndex=N: N ISO weeks back from this Monday
 *
 * Prior implementation used rolling 7-day windows from `now`. ISO weeks
 * give predictable calendar alignment — a user's "this week" matches their
 * calendar app — at the cost of week 0 sometimes being only a single day
 * (when the user opens the app on a Monday morning, week 0 is just today).
 *
 * The `now` argument is the test seam — production code passes
 * [System.currentTimeMillis].
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
        /** 7 days in milliseconds. Retained for legacy callers; new code should
         *  not rely on a fixed 7-day window because DST transitions shift the
         *  ISO week boundary by ±1 hour. */
        const val WEEK_MS: Long = 7L * 24L * 60L * 60L * 1_000L

        /**
         * Hard sanity cap on weekIndex pagination. Set high enough that
         * the actual stop signal is NewPipe's
         * [com.albunyaan.tube.data.me.ChannelDeepPaginator.DeepPageResult.EndOfChannel]
         * (persisted as `deepPageUrl = DEEP_PAGE_EOF_SENTINEL` in the
         * refresh state), not this cap.
         *
         * 5000 weeks ≈ 96 years — safely beyond any real YouTube channel.
         */
        const val MAX_WEEKS_BACK: Int = 5_000

        /**
         * Build a bucket for `weekIndex` relative to `now`. The window is
         * half-open: `[startMs, endMs)`.
         *
         * Cubic R-final7 — ISO-week boundaries. The Monday of the ISO week
         * containing `now` is the start of weekIndex=0. Subsequent weeks
         * step back one ISO week at a time. Uses the system default zone
         * for the day-boundary calculation so users see weeks aligned to
         * their local calendar.
         */
        fun forIndex(weekIndex: Int, now: Long): WeekBucket {
            require(weekIndex >= 0) { "weekIndex must be non-negative, got $weekIndex" }
            val zone = ZoneId.systemDefault()
            val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val thisMonday = nowDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val targetMonday = thisMonday.minusWeeks(weekIndex.toLong())
            val nextMonday = targetMonday.plusWeeks(1)
            return WeekBucket(
                weekIndex = weekIndex,
                startMs = targetMonday.atStartOfDay(zone).toInstant().toEpochMilli(),
                endMs = nextMonday.atStartOfDay(zone).toInstant().toEpochMilli(),
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

        /**
         * Returns the ISO week index of [uploadedAt] relative to [now].
         * Uses the same Monday-boundary logic as [forIndex] so callers that
         * classify an item's week and callers that query by week index are
         * always consistent.
         */
        fun weekIndexOf(uploadedAt: Long, now: Long): Int {
            val zone = ZoneId.systemDefault()
            val uploadedDate = Instant.ofEpochMilli(uploadedAt).atZone(zone).toLocalDate()
            val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val thisMonday = nowDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val uploadedMonday = uploadedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return ((thisMonday.toEpochDay() - uploadedMonday.toEpochDay()) / 7L).toInt()
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
