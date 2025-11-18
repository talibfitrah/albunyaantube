package com.albunyaan.tube.data.source.api

import com.albunyaan.tube.data.model.api.models.DownloadCompletedEvent
import com.albunyaan.tube.data.model.api.models.DownloadFailedEvent
import com.albunyaan.tube.data.model.api.models.DownloadManifestDto
import com.albunyaan.tube.data.model.api.models.DownloadPolicyDto
import com.albunyaan.tube.data.model.api.models.DownloadStartedEvent
import com.albunyaan.tube.data.model.api.models.DownloadTokenDto
import com.albunyaan.tube.data.model.api.models.DownloadTokenRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit API interface for download management using generated OpenAPI DTOs
 *
 * Maps to backend endpoints:
 * - GET /downloads/policy/{videoId} - Check download policy
 * - POST /downloads/token/{videoId} - Generate download token
 * - GET /downloads/manifest/{videoId} - Get download manifest with stream URLs
 * - POST /downloads/analytics/download-started - Track download started
 * - POST /downloads/analytics/download-completed - Track download completed
 * - POST /downloads/analytics/download-failed - Track download failed
 */
interface DownloadApi {

    /**
     * Check download policy for a video
     *
     * @param videoId The video ID to check
     * @return Download policy with allowed status and reason
     */
    @GET("downloads/policy/{videoId}")
    suspend fun getDownloadPolicy(
        @Path("videoId") videoId: String
    ): DownloadPolicyDto

    /**
     * Generate download authorization token
     *
     * @param videoId The video ID to generate token for
     * @param request Token request with device info
     * @return Download token with JWT and expiration
     */
    @POST("downloads/token/{videoId}")
    suspend fun generateDownloadToken(
        @Path("videoId") videoId: String,
        @Body request: DownloadTokenRequest
    ): DownloadTokenDto

    /**
     * Get download manifest with stream URLs
     *
     * @param videoId The video ID to get manifest for
     * @param token Download authorization token (required)
     * @param supportsMerging Whether client supports FFmpeg merging (for split streams)
     * @return Download manifest with video/audio streams
     */
    @GET("downloads/manifest/{videoId}")
    suspend fun getDownloadManifest(
        @Path("videoId") videoId: String,
        @retrofit2.http.Query("token") token: String,
        @retrofit2.http.Query("supportsMerging") supportsMerging: Boolean = false
    ): DownloadManifestDto

    /**
     * Track download started event for analytics
     *
     * @param event Download started event data
     */
    @POST("downloads/analytics/download-started")
    suspend fun trackDownloadStarted(
        @Body event: DownloadStartedEvent
    )

    /**
     * Track download completed event for analytics
     *
     * @param event Download completed event data
     */
    @POST("downloads/analytics/download-completed")
    suspend fun trackDownloadCompleted(
        @Body event: DownloadCompletedEvent
    )

    /**
     * Track download failed event for analytics
     *
     * @param event Download failed event data
     */
    @POST("downloads/analytics/download-failed")
    suspend fun trackDownloadFailed(
        @Body event: DownloadFailedEvent
    )
}
