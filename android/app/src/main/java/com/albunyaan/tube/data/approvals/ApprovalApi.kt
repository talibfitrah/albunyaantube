package com.albunyaan.tube.data.approvals

import com.albunyaan.tube.data.approvals.dto.*
import retrofit2.Response
import retrofit2.http.*

interface ApprovalApi {

    @GET("api/admin/approvals/my-submissions")
    suspend fun mySubmissions(
        @Query("status") status: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
    ): Response<CursorPageDto<PendingApprovalDto>>

    @POST("api/admin/registry/channels")
    suspend fun submitChannel(@Body body: SubmitChannelRequest): Response<PendingApprovalDto>

    @POST("api/admin/registry/playlists")
    suspend fun submitPlaylist(@Body body: SubmitPlaylistRequest): Response<PendingApprovalDto>

    @POST("api/admin/registry/videos")
    suspend fun submitVideo(@Body body: SubmitVideoRequest): Response<PendingApprovalDto>
}
