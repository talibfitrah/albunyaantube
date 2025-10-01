package com.albunyaan.tube.data.filters

/**
 * Represents the global filter state shared across paged lists (home, channels, playlists, videos).
 * State is persisted via DataStore so selections survive process restarts and tab switches.
 */
data class FilterState(
    val category: String? = null,
    val videoLength: VideoLength = VideoLength.ANY,
    val publishedDate: PublishedDate = PublishedDate.ANY,
    val sortOption: SortOption = SortOption.DEFAULT
) {
    val hasActiveFilters: Boolean
        get() = category != null || videoLength != VideoLength.ANY || publishedDate != PublishedDate.ANY || sortOption != SortOption.DEFAULT
}

enum class VideoLength { ANY, UNDER_FOUR_MIN, FOUR_TO_TWENTY_MIN, OVER_TWENTY_MIN }

enum class PublishedDate { ANY, LAST_24_HOURS, LAST_7_DAYS, LAST_30_DAYS }

enum class SortOption { DEFAULT, MOST_POPULAR, NEWEST }
