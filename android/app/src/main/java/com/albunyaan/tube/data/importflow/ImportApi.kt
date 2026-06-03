package com.albunyaan.tube.data.importflow

import com.albunyaan.tube.data.importflow.dto.ImportResolveRequestDto
import com.albunyaan.tube.data.importflow.dto.ImportResolveResponseDto
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * B8: Retrofit interface for the FitrahTube backend import-resolve endpoint.
 *
 * The authenticated Retrofit instance (base URL + Firebase auth interceptor)
 * is wired in B15; this is the interface + DTOs only.
 */
interface ImportApi {

    /**
     * Resolve a batch of YouTube import candidates against the content registry.
     *
     * POST /api/account/import/resolve
     *
     * Backend checks each item's youtubeId against approved/pending/rejected
     * content and returns a disposition for each. APPROVED items include the
     * full [com.albunyaan.tube.data.model.api.models.ContentItemDto] so they
     * can flow directly into the Me list.
     */
    @POST("api/account/import/resolve")
    suspend fun resolve(@Body request: ImportResolveRequestDto): ImportResolveResponseDto
}
