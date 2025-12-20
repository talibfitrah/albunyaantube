package com.albunyaan.tube.data.model.mappers

import com.albunyaan.tube.data.model.Category as DomainCategory
import com.albunyaan.tube.data.model.ContentItem as DomainContentItem
import com.albunyaan.tube.data.model.api.models.Category as ApiCategory
import com.albunyaan.tube.data.model.api.models.ContentItem as ApiContentItem
import com.albunyaan.tube.data.model.api.models.ContentItemDto as ApiContentItemDto

/**
 * Mapper functions to convert API DTOs (generated from OpenAPI spec)
 * to domain models (optimized for UI/business logic).
 *
 * Pattern: API DTOs are transport types only. Domain models are
 * UI-specific and may have computed properties, sealed classes, etc.
 */

/**
 * Map API Category DTO to domain Category model
 */
fun ApiCategory.toDomain(): DomainCategory {
    return DomainCategory(
        id = this.id,
        name = this.name,
        slug = this.slug,
        parentId = this.parentCategoryId,
        hasSubcategories = false, // Will be computed by repository
        icon = this.icon
    )
}

/**
 * Map API ContentItem DTO to domain ContentItem sealed class
 */
fun ApiContentItem.toDomain(primaryCategory: String = "General"): DomainContentItem? {
    // Map based on content type
    return when (this.type) {
        ApiContentItem.Type.VIDEO -> DomainContentItem.Video(
            id = this.id ?: return null,
            title = this.title ?: "",
            category = primaryCategory,
            durationSeconds = 0, // Not available in ContentItem DTO
            uploadedDaysAgo = computeDaysAgo(this.createdAt),
            description = this.description ?: "",
            thumbnailUrl = this.thumbnailUrl,
            viewCount = this.count
        )
        ApiContentItem.Type.CHANNEL -> DomainContentItem.Channel(
            id = this.id ?: return null,
            name = this.title ?: "",
            category = primaryCategory,
            subscribers = this.count?.toInt() ?: 0,
            description = this.description,
            thumbnailUrl = this.thumbnailUrl,
            videoCount = null, // Not available in ContentItem DTO
            categories = this.categoryIds
        )
        ApiContentItem.Type.PLAYLIST -> DomainContentItem.Playlist(
            id = this.id ?: return null,
            title = this.title ?: "",
            category = primaryCategory,
            itemCount = this.count?.toInt() ?: 0,
            description = this.description,
            thumbnailUrl = this.thumbnailUrl
        )
        else -> null
    }
}

/**
 * Map API ContentItemDto to domain ContentItem sealed class
 */
fun ApiContentItemDto.toDomain(): DomainContentItem {
    // ContentItemDto uses different fields for different content types
    return when (this.type) {
        ApiContentItemDto.Type.VIDEO -> DomainContentItem.Video(
            id = this.id,
            title = this.title ?: "",
            category = this.category ?: "General",
            durationSeconds = this.durationSeconds ?: 0,
            uploadedDaysAgo = this.uploadedDaysAgo ?: 0,
            description = this.description ?: "",
            thumbnailUrl = this.thumbnailUrl,
            viewCount = this.viewCount
        )
        ApiContentItemDto.Type.CHANNEL -> DomainContentItem.Channel(
            id = this.id,
            name = this.name ?: this.title ?: "",
            category = this.category ?: "General",
            subscribers = this.subscribers?.toInt() ?: 0,
            description = this.description,
            thumbnailUrl = this.thumbnailUrl,
            videoCount = this.videoCount,
            categories = null // ContentItemDto doesn't have categoryIds, only single category
        )
        ApiContentItemDto.Type.PLAYLIST -> DomainContentItem.Playlist(
            id = this.id,
            title = this.title ?: "",
            category = this.category ?: "General",
            itemCount = this.itemCount ?: 0,
            description = this.description,
            thumbnailUrl = this.thumbnailUrl
        )
    }
}

/**
 * Compute days ago from OffsetDateTime (nullable)
 */
private fun computeDaysAgo(createdAt: java.time.OffsetDateTime?): Int {
    if (createdAt == null) return 0
    val now = java.time.OffsetDateTime.now()
    return java.time.temporal.ChronoUnit.DAYS.between(createdAt, now).toInt()
}

/**
 * Extension to map list of API DTOs to domain models
 */
fun List<ApiCategory>.toDomain(): List<DomainCategory> {
    return this.map { it.toDomain() }
}

fun List<ApiContentItem>.toDomainContentItems(defaultCategory: String = "General"): List<DomainContentItem> {
    return this.mapNotNull { it.toDomain(defaultCategory) }
}

fun List<ApiContentItemDto>.toDomainContentItems(): List<DomainContentItem> {
    return this.map { it.toDomain() }
}
