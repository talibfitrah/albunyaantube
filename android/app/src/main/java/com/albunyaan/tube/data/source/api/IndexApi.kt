package com.albunyaan.tube.data.source.api

import com.albunyaan.tube.data.index.IndexStreamsRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface IndexApi {
    @POST("api/v1/index/streams")
    suspend fun indexStreams(@Body request: IndexStreamsRequest): Response<Void>
}
