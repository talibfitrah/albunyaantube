package com.albunyaan.tube.data.model

data class Category(
    val id: String,
    val name: String,
    val slug: String? = null,
    val parentId: String? = null,
    val hasSubcategories: Boolean = false,
    val icon: String? = null,
    val displayOrder: Int? = null,
    val localizedNames: Map<String, String>? = null
)
