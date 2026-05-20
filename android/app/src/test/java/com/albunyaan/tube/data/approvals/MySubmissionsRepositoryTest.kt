package com.albunyaan.tube.data.approvals

import com.albunyaan.tube.data.approvals.dto.*
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class MySubmissionsRepositoryTest {

    @Test
    fun rateLimitedSubmitReturnsRateLimitError() = runTest {
        val api = object : ApprovalApi {
            override suspend fun mySubmissions(status: String?, cursor: String?, limit: Int) =
                Response.success(CursorPageDto<PendingApprovalDto>(emptyList(), null))

            override suspend fun submitChannel(body: SubmitChannelRequest): Response<Void> {
                val rb = "".toResponseBody("application/json".toMediaType())
                return Response.error(
                    rb,
                    okhttp3.Response.Builder()
                        .request(okhttp3.Request.Builder().url("http://test/").build())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .headers(Headers.headersOf("Retry-After", "3600"))
                        .build()
                )
            }

            override suspend fun submitPlaylist(body: SubmitPlaylistRequest) = error("n/a")
            override suspend fun submitVideo(body: SubmitVideoRequest) = error("n/a")
        }
        val repo = MySubmissionsRepository(api)

        val result = repo.submitChannel("UC1", listOf("cat-1"))

        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is RateLimitError)
        assertEquals(3600L, (err as RateLimitError).retryAfterSeconds)
    }

    @Test
    fun successfulSubmitReturnsUnit() = runTest {
        val api = object : ApprovalApi {
            override suspend fun mySubmissions(status: String?, cursor: String?, limit: Int) =
                Response.success(CursorPageDto<PendingApprovalDto>(emptyList(), null))

            override suspend fun submitChannel(body: SubmitChannelRequest): Response<Void> =
                Response.success(null)

            override suspend fun submitPlaylist(body: SubmitPlaylistRequest) = error("n/a")
            override suspend fun submitVideo(body: SubmitVideoRequest) = error("n/a")
        }
        val repo = MySubmissionsRepository(api)

        val result = repo.submitChannel("UC1", listOf("cat-1"))

        assertTrue(result.isSuccess)
    }

    @Test
    fun fetchMySubmissionsReturnsItems() = runTest {
        val dto = PendingApprovalDto(
            id = "s1",
            type = "channel",
            entityId = "UC1",
            title = "Test Channel",
            category = "Education",
            submittedAt = 1000L,
            submittedBy = "uid-1",
            submittedByDisplayName = "User One",
            submittedByEmail = "user@example.com",
            status = "PENDING"
        )
        val api = object : ApprovalApi {
            override suspend fun mySubmissions(status: String?, cursor: String?, limit: Int) =
                Response.success(CursorPageDto(listOf(dto), null))

            override suspend fun submitChannel(body: SubmitChannelRequest) = error("n/a")
            override suspend fun submitPlaylist(body: SubmitPlaylistRequest) = error("n/a")
            override suspend fun submitVideo(body: SubmitVideoRequest) = error("n/a")
        }
        val repo = MySubmissionsRepository(api)

        val result = repo.fetchMySubmissions()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertEquals("UC1", result.getOrNull()?.get(0)?.entityId)
    }

    @Test
    fun conflictSubmitReturnsError() = runTest {
        val api = object : ApprovalApi {
            override suspend fun mySubmissions(status: String?, cursor: String?, limit: Int) =
                Response.success(CursorPageDto<PendingApprovalDto>(emptyList(), null))

            override suspend fun submitChannel(body: SubmitChannelRequest): Response<Void> {
                val rb = "".toResponseBody("application/json".toMediaType())
                return Response.error(
                    rb,
                    okhttp3.Response.Builder()
                        .request(okhttp3.Request.Builder().url("http://test/").build())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(409)
                        .message("Conflict")
                        .build()
                )
            }

            override suspend fun submitPlaylist(body: SubmitPlaylistRequest) = error("n/a")
            override suspend fun submitVideo(body: SubmitVideoRequest) = error("n/a")
        }
        val repo = MySubmissionsRepository(api)

        val result = repo.submitChannel("UC1", listOf("cat-1"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Already exists") == true)
    }
}
