package com.albunyaan.tube.data.search

import com.albunyaan.tube.data.search.dto.YouTubeContentTypeDto
import com.albunyaan.tube.data.search.dto.YouTubeSearchResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface YouTubeSearchApi {
    @GET("api/admin/youtube/search")
    suspend fun search(
        @Query("q")         q: String,
        @Query("type")      type: YouTubeContentTypeDto,
        @Query("pageToken") pageToken: String? = null
    ): Response<YouTubeSearchResponseDto>
}
