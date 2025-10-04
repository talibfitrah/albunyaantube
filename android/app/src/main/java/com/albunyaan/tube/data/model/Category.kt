package com.albunyaan.tube.data.model

data class Category(
    val id: String,
    val name: String,
    val slug: String,
    val parentId: String? = null
)
