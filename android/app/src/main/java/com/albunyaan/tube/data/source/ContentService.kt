package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.Category
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.model.HomeFeedResult

enum class AvailabilityCheckType { CHANNEL, PLAYLIST, VIDEO }

interface ContentService {
    suspend fun fetchContent(
        type: ContentType,
        cursor: String?,
        pageSize: Int,
        filters: FilterState,
        query: String? = null
    ): CursorResponse

    suspend fun fetchHomeFeed(
        cursor: String?,
        categoryLimit: Int,
        contentLimit: Int,
        category: String? = null
    ): HomeFeedResult

    suspend fun search(query: String, type: String? = null, limit: Int = 20): List<ContentItem>

    suspend fun fetchCategories(): List<Category>

    suspend fun fetchSubcategories(parentId: String): List<Category>

    /**
     * Returns true if the content is currently available (not archived, rejected,
     * or otherwise hidden). Returns false on a 404 from the backend (the public
     * detail endpoints use 404 to signal archive/unavailable state).
     * Throws on transport errors so the caller can decide whether to fail-open
     * (e.g., offline scenarios) or fail-closed.
     */
    suspend fun verifyAvailable(type: AvailabilityCheckType, youtubeId: String): Boolean

    /**
     * Returns true ONLY when the id is registered AND APPROVED in our backend
     * (HTTP 2xx). Returns false on 404 (not in registry, e.g. parent channel of
     * a standalone playlist), 410 (admin-blocked), or any other status. Returns
     * false on transport errors — callers that use this to gate exposing
     * uncurated content (e.g., the parent-channel link on a standalone playlist)
     * should fail-closed to honour the curation intent.
     *
     * Distinct from [verifyAvailable], which fails-open on 404 so unregistered
     * downstream content (a video whose parent isn't separately registered) can
     * still resolve via NewPipe. This method's semantic is the inverse: prove
     * approval before exposing a lateral discovery surface.
     */
    suspend fun isInApprovedRegistry(type: AvailabilityCheckType, youtubeId: String): Boolean
}
