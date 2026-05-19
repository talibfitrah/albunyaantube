package com.albunyaan.tube.data.search

import com.albunyaan.tube.data.search.dto.YouTubeContentTypeDto
import com.albunyaan.tube.data.search.dto.YouTubeSearchResponseDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed class SearchResult {
    data class Success(val page: YouTubeSearchResponseDto) : SearchResult()
    object Forbidden : SearchResult()
    data class RateLimited(val retryAfterSec: Long) : SearchResult()
    object NetworkError : SearchResult()
    data class Unknown(val code: Int) : SearchResult()
}

@Singleton
class YouTubeSearchRepository @Inject constructor(
    private val api: YouTubeSearchApi
) {
    suspend fun search(
        q: String,
        type: YouTubeContentTypeDto,
        pageToken: String?
    ): SearchResult = try {
        val resp = api.search(q, type, pageToken)
        when {
            resp.isSuccessful && resp.body() != null ->
                SearchResult.Success(resp.body()!!)
            resp.code() == 403 ->
                SearchResult.Forbidden
            resp.code() == 429 ->
                SearchResult.RateLimited(
                    resp.headers()["Retry-After"]?.toLongOrNull() ?: 60L
                )
            else ->
                SearchResult.Unknown(resp.code())
        }
    } catch (e: IOException) {
        SearchResult.NetworkError
    } catch (e: Exception) {
        // Plan G cubic R1 P1: catch JsonDataException, HttpException and
        // any other Retrofit/Moshi failure during body deserialization so a
        // malformed response body doesn't crash the calling coroutine.
        SearchResult.Unknown(0)
    }
}
