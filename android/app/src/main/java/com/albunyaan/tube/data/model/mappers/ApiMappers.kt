package com.albunyaan.tube.data.model.mappers

import com.albunyaan.tube.data.model.Category as DomainCategory
import com.albunyaan.tube.data.model.ContentItem as DomainContentItem
import com.albunyaan.tube.data.model.HomeSection
import com.albunyaan.tube.data.model.api.models.ContentItemDto as ApiContentItemDto
import com.albunyaan.tube.data.source.api.CategoryResponse
import com.albunyaan.tube.data.source.api.HomeCategorySection

/**
 * Mapper functions to convert API DTOs (generated from OpenAPI spec)
 * to domain models (optimized for UI/business logic).
 *
 * Pattern: API DTOs are transport types only. Domain models are
 * UI-specific and may have computed properties, sealed classes, etc.
 */

/**
 * Map public API CategoryResponse to domain Category model
 */
fun CategoryResponse.toDomain(): DomainCategory {
    return DomainCategory(
        id = this.id,
        name = this.name,
        slug = this.slug,
        parentId = this.parentId,
        hasSubcategories = false, // Computed by RetrofitContentService
        icon = this.icon,
        displayOrder = this.displayOrder,
        localizedNames = this.localizedNames
    )
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
            viewCount = this.viewCount,
            channelName = this.channelTitle
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
 * Extension to map list of API DTOs to domain models
 */
fun List<ApiContentItemDto>.toDomainContentItems(): List<DomainContentItem> {
    return this.map { it.toDomain() }
}

/**
 * Map a home feed category section to domain HomeSection
 */
fun HomeCategorySection.toDomain(): HomeSection {
    return HomeSection(
        categoryId = this.id,
        categoryName = this.name,
        categorySlug = this.slug,
        localizedNames = this.localizedNames,
        icon = this.icon,
        items = this.items.toDomainContentItems(),
        totalItemCount = this.totalContentCount
    )
}
