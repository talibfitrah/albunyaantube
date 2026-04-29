package com.albunyaan.tube.data.source.api

import com.albunyaan.tube.data.report.ReportRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ReportApi {

    @POST("api/v1/reports")
    suspend fun submitReport(@Body request: ReportRequest): Response<Void>
}
