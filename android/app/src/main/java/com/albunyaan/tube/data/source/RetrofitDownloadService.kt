package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.model.api.models.DownloadCompletedEvent
import com.albunyaan.tube.data.model.api.models.DownloadFailedEvent
import com.albunyaan.tube.data.model.api.models.DownloadManifestDto
import com.albunyaan.tube.data.model.api.models.DownloadPolicyDto
import com.albunyaan.tube.data.model.api.models.DownloadStartedEvent
import com.albunyaan.tube.data.model.api.models.DownloadTokenDto
import com.albunyaan.tube.data.model.api.models.DownloadTokenRequest
import com.albunyaan.tube.data.model.api.models.StreamOption
import com.albunyaan.tube.data.source.api.DownloadApi
import com.albunyaan.tube.download.DownloadManifest
import com.albunyaan.tube.download.DownloadPolicyResult
import com.albunyaan.tube.download.SelectedStream

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
     * @param supportsMerging Whether client supports FFmpeg merging (for split streams)
     * @param audioOnly Whether to select audio-only stream
     * @param targetHeight Target video height for quality selection (null = best available)
     * @return Download manifest with video/audio streams
     */
    suspend fun getDownloadManifest(
        videoId: String,
        token: String,
        supportsMerging: Boolean = false,
        audioOnly: Boolean = false,
        targetHeight: Int? = null
    ): DownloadManifest {
        val dto = api.getDownloadManifest(videoId, token, supportsMerging)
        return dto.toDomainManifest(audioOnly, targetHeight)
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
        expiresAtMillis = this.expiresAtMillis ?: 0L,
        videoId = this.videoId ?: ""
    )
}

/**
 * Map DownloadManifestDto to domain DownloadManifest
 *
 * Uses DownloadStreamSelector to select stream based on audioOnly flag and targetHeight preference.
 *
 * @param audioOnly Whether to select audio-only stream
 * @param targetHeight Target video height for quality selection (null = best available)
 */
private fun DownloadManifestDto.toDomainManifest(
    audioOnly: Boolean = false,
    targetHeight: Int? = null
): DownloadManifest {
    val selectedStream = DownloadStreamSelector.selectStream(this, audioOnly, targetHeight)

    return DownloadManifest(
        videoId = this.videoId ?: "",
        selectedStream = selectedStream.toDomainStream(),
        expiresAtMillis = this.expiresAtMillis ?: 0L
    )
}

/**
 * Map StreamOption DTO to domain SelectedStream
 */
private fun StreamOption.toDomainStream(): SelectedStream {
    return SelectedStream(
        id = this.id ?: "",
        qualityLabel = this.qualityLabel ?: "",
        mimeType = this.mimeType ?: "",
        requiresMerging = this.requiresMerging ?: false,
        progressiveUrl = this.progressiveUrl,
        videoUrl = this.videoUrl,
        audioUrl = this.audioUrl,
        fileSize = this.fileSize ?: 0L,
        bitrate = this.bitrate ?: 0
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
    /** Token expiration time in milliseconds since epoch (UTC) */
    val expiresAtMillis: Long,
    val videoId: String
)
