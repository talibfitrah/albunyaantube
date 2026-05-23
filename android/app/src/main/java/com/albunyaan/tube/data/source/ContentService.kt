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
     * Playback availability gate. Returns:
     *  - true on HTTP 2xx (id is registered AND APPROVED).
     *  - true on HTTP 404 (NOT in registry — fail-open so NewPipe can resolve
     *    downstream content like a channel-sourced video whose parent isn't
     *    separately registered, or a PENDING channel whose review hasn't
     *    landed yet; the admin curation gate runs separately via
     *    [isInApprovedRegistry] for surfaces that need fail-closed semantics).
     *  - false on HTTP 410 (admin-explicitly-blocked — hard stop, never
     *    fail-open; backend uses 410 for REJECTED status and for
     *    validationStatus = ARCHIVED/UNAVAILABLE).
     *  - false on any other non-2xx (defence-in-depth).
     *  - false on transport errors (treated as "can't verify → fail-closed
     *    so we don't accidentally play admin-blocked content during a brief
     *    network blip"). For surfaces that should fail-open on transport
     *    errors instead (e.g. fully-offline playback of cached content), the
     *    caller is responsible for its own error handling above this method.
     *
     * Pairs with [isInApprovedRegistry] (inverse semantics on 404):
     * verifyAvailable gates PLAYBACK (must not play admin-blocked content);
     * isInApprovedRegistry gates DISCOVERY-LINKS (must not surface
     * uncurated lateral navigation).
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
