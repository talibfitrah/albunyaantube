package com.albunyaan.tube.ui.utils

import android.content.Context

/**
 * Extension functions for views and responsive layout calculations.
 * Supports adaptive UI for phones, tablets, and TV boxes.
 */

/**
 * Calculate optimal grid span count based on screen width.
 *
 * This function dynamically determines how many columns should be shown in a grid
 * based on the available screen width and a minimum item width. This ensures
 * content displays optimally across different device sizes:
 * - Phone (360dp width): 2 columns
 * - 7" Tablet (600dp+ width): 3-4 columns
 * - 10" Tablet/TV (720dp+ width): 4-6 columns
 *
 * @param itemMinWidthDp Minimum width for each grid item in dp (default 160dp)
 * @return Span count (minimum 2, maximum determined by screen width)
 *
 * Example usage:
 * ```kotlin
 * val spanCount = requireContext().calculateGridSpanCount(itemMinWidthDp = 180)
 * layoutManager = GridLayoutManager(requireContext(), spanCount)
 * ```
 */
fun Context.calculateGridSpanCount(itemMinWidthDp: Int = 160): Int {
    val displayMetrics = resources.displayMetrics
    val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
    val calculatedSpan = (screenWidthDp / itemMinWidthDp).toInt()

    // Ensure minimum of 2 columns, maximum of 8 columns
    return calculatedSpan.coerceIn(2, 8)
}

/**
 * Check if the device is a tablet (screen width >= 600dp).
 *
 * @return true if device is a tablet, false if phone
 */
fun Context.isTablet(): Boolean {
    return resources.getBoolean(com.albunyaan.tube.R.bool.is_tablet)
}

/**
 * Check if the device is a large screen (screen width >= 720dp).
 * Includes large tablets and TV boxes.
 *
 * @return true if device is a large screen, false otherwise
 */
fun Context.isLargeScreen(): Boolean {
    return resources.getBoolean(com.albunyaan.tube.R.bool.is_large_screen)
}

/**
 * Check if two-pane layouts should be used.
 * Two-pane layouts show master-detail views side by side on tablets.
 *
 * @return true if two-pane layout should be used, false otherwise
 */
fun Context.useTwoPaneLayout(): Boolean {
    return resources.getBoolean(com.albunyaan.tube.R.bool.use_two_pane_layout)
}

