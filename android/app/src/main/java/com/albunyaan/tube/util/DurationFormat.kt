package com.albunyaan.tube.util

import java.util.Locale

object DurationFormat {
    /** `m:ss` / `h:mm:ss`, or null when the duration is unknown (null, or a legacy 0). Never "0:00". */
    fun badge(seconds: Int?): String? {
        if (seconds == null || seconds <= 0) return null
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        else String.format(Locale.US, "%d:%02d", mins, secs)
    }
}
