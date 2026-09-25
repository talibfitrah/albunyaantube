package com.albunyaan.tube.util

import android.content.res.Resources
import com.albunyaan.tube.R

/** Video upload age for list rows. */
object UploadAge {

    /**
     * "Today" / "3 days ago" / "2 weeks ago" / ... — or null when the upload
     * date is unknown (the backend omits uploadedDaysAgo for stream-index search
     * hits). Unknown must render nothing, never "Today".
     */
    fun format(res: Resources, daysAgo: Int?): String? = when {
        daysAgo == null -> null
        daysAgo <= 0 -> res.getString(R.string.video_uploaded_today)
        daysAgo < 7 -> res.getQuantityString(R.plurals.video_uploaded_days_ago, daysAgo, daysAgo)
        daysAgo < 30 -> (daysAgo / 7).let { res.getQuantityString(R.plurals.time_ago_weeks, it, it) }
        daysAgo < 365 -> (daysAgo / 30).let { res.getQuantityString(R.plurals.time_ago_months, it, it) }
        else -> (daysAgo / 365).let { res.getQuantityString(R.plurals.time_ago_years, it, it) }
    }

    /** Joins the non-empty parts with " • ", so a missing part leaves no stray separator. */
    fun joinMeta(vararg parts: String?): String =
        parts.filterNot { it.isNullOrEmpty() }.joinToString(" • ")

    private val ENGLISH_AGE = Regex("""([0-9]+) (second|minute|hour|day|week|month|year)s? ago""")

    /**
     * YouTube's English age text (NewPipe runs Locale.US) as minutes: "2 hours ago" -> 120,
     * "Streamed 3 days ago" -> 4320, "30 seconds ago" -> 0. Months/years are 30/365 days —
     * YouTube's text is already that coarse. Null when the text holds no age.
     * Same rule as iOS InnerTubeKit EnglishCounts.minutesAgo.
     */
    fun minutesAgo(text: String?): Int? {
        val match = ENGLISH_AGE.find(text ?: return null) ?: return null
        val n = match.groupValues[1].toIntOrNull() ?: return null
        val unit = when (match.groupValues[2]) {
            "minute" -> 1; "hour" -> 60; "day" -> 1440; "week" -> 7 * 1440
            "month" -> 30 * 1440; "year" -> 365 * 1440; else -> 0
        }
        return n * unit
    }

    /** Minutes, then hours below a day, then the day ladder of [format]. "1 minute ago", never 0. */
    fun formatMinutes(res: Resources, minutes: Int?): String? = when {
        minutes == null -> null
        minutes < 60 -> maxOf(1, minutes).let { res.getQuantityString(R.plurals.time_ago_minutes, it, it) }
        minutes < 1440 -> (minutes / 60).let { res.getQuantityString(R.plurals.time_ago_hours, it, it) }
        else -> format(res, minutes / 1440)
    }

    /** The age in the app's locale; text with no parseable age is shown as-is, never invented. */
    fun fromEnglish(res: Resources, text: String?): String? =
        formatMinutes(res, minutesAgo(text)) ?: text?.takeIf { it.isNotBlank() }
}
