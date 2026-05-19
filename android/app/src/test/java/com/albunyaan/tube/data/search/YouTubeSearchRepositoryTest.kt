package com.albunyaan.tube.data.search

import com.albunyaan.tube.data.search.dto.SearchHitDto
import com.albunyaan.tube.data.search.dto.YouTubeContentTypeDto
import com.albunyaan.tube.data.search.dto.YouTubeSearchResponseDto
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class YouTubeSearchRepositoryTest {

    @Test
    fun search_200_returnsSuccessWithItems() = runTest {
        val api = object : YouTubeSearchApi {
            override suspend fun search(q: String, type: YouTubeContentTypeDto, pageToken: String?) =
                Response.success(
                    YouTubeSearchResponseDto(
                        items = listOf(SearchHitDto("UC1", "Ch", "https://yt/c/UC1")),
                        nextPageToken = null
                    )
                )
        }
        val result = YouTubeSearchRepository(api).search("k", YouTubeContentTypeDto.CHANNEL, null)
        assertTrue(result is SearchResult.Success)
        assertEquals(1, (result as SearchResult.Success).page.items.size)
    }

    @Test
    fun search_403_returnsForbidden() = runTest {
        val errorBody = "".toResponseBody("application/json".toMediaType())
        val resp: Response<YouTubeSearchResponseDto> = Response.error(403, errorBody)
        val api = object : YouTubeSearchApi {
            override suspend fun search(q: String, type: YouTubeContentTypeDto, pageToken: String?) = resp
        }
        val result = YouTubeSearchRepository(api).search("k", YouTubeContentTypeDto.CHANNEL, null)
        assertTrue(result is SearchResult.Forbidden)
    }

    @Test
    fun search_429_returnsRateLimitedFromRetryAfterHeader() = runTest {
        val errorBody = """{"code":"RATE_LIMIT"}""".toResponseBody("application/json".toMediaType())
        val raw = okhttp3.Response.Builder()
            .code(429)
            .request(okhttp3.Request.Builder().url("http://t/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .message("Too Many")
            .addHeader("Retry-After", "120")
            .body(errorBody)
            .build()
        val resp: Response<YouTubeSearchResponseDto> = Response.error(errorBody, raw)
        val api = object : YouTubeSearchApi {
            override suspend fun search(q: String, type: YouTubeContentTypeDto, pageToken: String?) = resp
        }
        val result = YouTubeSearchRepository(api).search("k", YouTubeContentTypeDto.CHANNEL, null)
        assertTrue(result is SearchResult.RateLimited)
        assertEquals(120L, (result as SearchResult.RateLimited).retryAfterSec)
    }

    @Test
    fun search_500_returnsUnknown() = runTest {
        val errorBody = "".toResponseBody("application/json".toMediaType())
        val resp: Response<YouTubeSearchResponseDto> = Response.error(500, errorBody)
        val api = object : YouTubeSearchApi {
            override suspend fun search(q: String, type: YouTubeContentTypeDto, pageToken: String?) = resp
        }
        val result = YouTubeSearchRepository(api).search("k", YouTubeContentTypeDto.VIDEO, null)
        assertTrue(result is SearchResult.Unknown)
        assertEquals(500, (result as SearchResult.Unknown).code)
    }

    @Test
    fun search_ioException_returnsNetworkError() = runTest {
        val api = object : YouTubeSearchApi {
            override suspend fun search(q: String, type: YouTubeContentTypeDto, pageToken: String?): Response<YouTubeSearchResponseDto> {
                throw java.io.IOException("no connection")
            }
        }
        val result = YouTubeSearchRepository(api).search("k", YouTubeContentTypeDto.PLAYLIST, null)
        assertTrue(result is SearchResult.NetworkError)
    }

    @Test
    fun search_429_defaultsTo60WhenNoRetryAfterHeader() = runTest {
        val errorBody = "".toResponseBody("application/json".toMediaType())
        val raw = okhttp3.Response.Builder()
            .code(429)
            .request(okhttp3.Request.Builder().url("http://t/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .message("Too Many")
            .body(errorBody)
            .build()
        val resp: Response<YouTubeSearchResponseDto> = Response.error(errorBody, raw)
        val api = object : YouTubeSearchApi {
            override suspend fun search(q: String, type: YouTubeContentTypeDto, pageToken: String?) = resp
        }
        val result = YouTubeSearchRepository(api).search("k", YouTubeContentTypeDto.CHANNEL, null)
        assertTrue(result is SearchResult.RateLimited)
        assertEquals(60L, (result as SearchResult.RateLimited).retryAfterSec)
    }
}
