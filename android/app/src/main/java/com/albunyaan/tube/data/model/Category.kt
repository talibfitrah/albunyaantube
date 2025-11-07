package com.albunyaan.tube.data.model

data class Category(
    val id: String,
    val name: String,
    val slug: String = "",
    val parentId: String? = null,
    val hasSubcategories: Boolean = false,
    val icon: String? = null // Emoji or icon identifier
)
