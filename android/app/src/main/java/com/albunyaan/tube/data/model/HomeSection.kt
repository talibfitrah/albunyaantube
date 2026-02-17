package com.albunyaan.tube.data.model

/**
 * Domain model for a category section on the home screen.
 * Each section contains a category header and a list of content items.
 */
data class HomeSection(
    val categoryId: String,
    val categoryName: String,
    val categorySlug: String? = null,
    val localizedNames: Map<String, String>? = null,
    val icon: String? = null,
    val items: List<ContentItem>,
    val totalItemCount: Int
)
