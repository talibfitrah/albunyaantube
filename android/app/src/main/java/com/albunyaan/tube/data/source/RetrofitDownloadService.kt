package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.model.api.models.DownloadCompletedEvent
import com.albunyaan.tube.data.model.api.models.DownloadFailedEvent
import com.albunyaan.tube.data.model.api.models.DownloadManifestDto
import com.albunyaan.tube.data.model.api.models.DownloadPolicyDto
import com.albunyaan.tube.data.model.api.models.DownloadStartedEvent
import com.albunyaan.tube.data.model.api.models.DownloadTokenDto
import com.albunyaan.tube.data.model.api.models.DownloadTokenRequest
import com.albunyaan.tube.data.source.api.DownloadApi
import com.albunyaan.tube.download.DownloadManifest
import com.albunyaan.tube.download.DownloadPolicyResult

/**
 * Retrofit-based implementation of download service using generated OpenAPI DTOs
 *
 * Replaces MockDownloadService with real backend API calls.
 * Follows Transport Types vs Domain Models pattern with explicit DTO → domain mappers.
 */
class RetrofitDownloadService(
    private val api: DownloadApi
) {

    /**
     * Check download policy for a video
     *
     * @param videoId The video ID to check
     * @return Download policy result with allowed status
     */
    suspend fun checkDownloadPolicy(videoId: String): DownloadPolicyResult {
        val dto = api.getDownloadPolicy(videoId)
        return dto.toDomainPolicy()
    }

    /**
     * Generate download authorization token
     *
     * @param videoId The video ID to generate token for
     * @param eulaAccepted Whether user has accepted EULA (must be explicitly provided by caller)
     * @return Download token with JWT and expiration
     */
    suspend fun generateDownloadToken(videoId: String, eulaAccepted: Boolean): DownloadToken {
        val request = DownloadTokenRequest(eulaAccepted = eulaAccepted)
        val dto = api.generateDownloadToken(videoId, request)
        return dto.toDomainToken()
    }

    /**
     * Get download manifest with stream URLs
     *
     * @param videoId The video ID to get manifest for
     * @param token Download authorization token (required by backend)
     * @return Download manifest with video/audio streams
     */
    suspend fun getDownloadManifest(videoId: String, token: String): DownloadManifest {
        val dto = api.getDownloadManifest(videoId, token)
        return dto.toDomainManifest()
    }

    /**
     * Track download started event
     *
     * @param videoId The video ID
     * @param quality Quality selected (e.g., "720p", "128kbps")
     * @param deviceType Device type (default: "android")
     */
    suspend fun trackDownloadStarted(
        videoId: String,
        quality: String,
        deviceType: String = "android"
    ) {
        val event = DownloadStartedEvent(
            videoId = videoId,
            quality = quality,
            deviceType = deviceType
        )
        api.trackDownloadStarted(event)
    }

    /**
     * Track download completed event
     *
     * @param videoId The video ID
     * @param quality Quality downloaded
     * @param fileSize File size in bytes (optional)
     * @param deviceType Device type (default: "android")
     */
    suspend fun trackDownloadCompleted(
        videoId: String,
        quality: String,
        fileSize: Long? = null,
        deviceType: String = "android"
    ) {
        val event = DownloadCompletedEvent(
            videoId = videoId,
            quality = quality,
            fileSize = fileSize,
            deviceType = deviceType
        )
        api.trackDownloadCompleted(event)
    }

    /**
     * Track download failed event
     *
     * @param videoId The video ID
     * @param errorReason Error reason/message
     * @param deviceType Device type (default: "android")
     */
    suspend fun trackDownloadFailed(
        videoId: String,
        errorReason: String,
        deviceType: String = "android"
    ) {
        val event = DownloadFailedEvent(
            videoId = videoId,
            errorReason = errorReason,
            deviceType = deviceType
        )
        api.trackDownloadFailed(event)
    }
}

// ============================================================================
// Mapper Functions: DTO → Domain Models
// ============================================================================

/**
 * Map DownloadPolicyDto to domain DownloadPolicyResult
 */
private fun DownloadPolicyDto.toDomainPolicy(): DownloadPolicyResult {
    return DownloadPolicyResult(
        allowed = this.allowed ?: false,
        reason = this.reason ?: "Unknown",
        requiresEula = this.requiresEula ?: false
    )
}

/**
 * Map DownloadTokenDto to domain DownloadToken
 */
private fun DownloadTokenDto.toDomainToken(): DownloadToken {
    return DownloadToken(
        token = this.token ?: "",
        expiresAt = this.expiresAt ?: 0L,
        videoId = this.videoId ?: ""
    )
}

/**
 * Map DownloadManifestDto to domain DownloadManifest
 *
 * Note: Backend provides multiple stream options per quality.
 * We select the best option (highest bitrate) for audio and video.
 */
private fun DownloadManifestDto.toDomainManifest(): DownloadManifest {
    val bestVideo = this.videoStreams?.maxByOrNull { it.bitrate ?: 0 }
    val bestAudio = this.audioStreams?.maxByOrNull { it.bitrate ?: 0 }

    return DownloadManifest(
        videoId = this.videoId ?: "",
        audioUrl = bestAudio?.url ?: "",
        videoUrl = bestVideo?.url ?: "",
        audioSize = bestAudio?.fileSize ?: 0L,
        videoSize = bestVideo?.fileSize ?: 0L,
        expiresAt = this.expiresAt ?: 0L
    )
}

// ============================================================================
// Domain Models
// ============================================================================

/**
 * Download authorization token (domain model)
 */
data class DownloadToken(
    val token: String,
    val expiresAt: Long,
    val videoId: String
)
