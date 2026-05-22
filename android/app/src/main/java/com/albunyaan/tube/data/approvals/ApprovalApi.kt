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
    suspend fun submitChannel(@Body body: SubmitChannelRequest): Response<Void>

    @POST("api/admin/registry/playlists")
    suspend fun submitPlaylist(@Body body: SubmitPlaylistRequest): Response<Void>

    @POST("api/admin/registry/videos")
    suspend fun submitVideo(@Body body: SubmitVideoRequest): Response<Void>

    @PATCH("api/admin/registry/channels/{id}/submitter-note")
    suspend fun editChannelSubmitterNote(
        @Path("id") id: String,
        @Body body: SubmitterNoteUpdateRequest,
    ): Response<Void>

    @PATCH("api/admin/registry/playlists/{id}/submitter-note")
    suspend fun editPlaylistSubmitterNote(
        @Path("id") id: String,
        @Body body: SubmitterNoteUpdateRequest,
    ): Response<Void>

    @PATCH("api/admin/registry/videos/{id}/submitter-note")
    suspend fun editVideoSubmitterNote(
        @Path("id") id: String,
        @Body body: SubmitterNoteUpdateRequest,
    ): Response<Void>

    @DELETE("api/admin/registry/channels/{id}/submission")
    suspend fun deleteChannelSubmission(@Path("id") id: String): Response<Void>

    @DELETE("api/admin/registry/playlists/{id}/submission")
    suspend fun deletePlaylistSubmission(@Path("id") id: String): Response<Void>

    @DELETE("api/admin/registry/videos/{id}/submission")
    suspend fun deleteVideoSubmission(@Path("id") id: String): Response<Void>
}
