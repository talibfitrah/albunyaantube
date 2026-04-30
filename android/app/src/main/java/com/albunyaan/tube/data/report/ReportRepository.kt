package com.albunyaan.tube.data.report

import com.albunyaan.tube.data.source.api.ReportApi
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

interface ReportRepository {
    suspend fun submitReport(
        targetType: ReportTargetType,
        targetId: String,
        reasons: List<ReportReason>,
        otherDescription: String?
    ): Result<Unit>
}

class RetrofitReportRepository @Inject constructor(
    private val api: ReportApi
) : ReportRepository {

    override suspend fun submitReport(
        targetType: ReportTargetType,
        targetId: String,
        reasons: List<ReportReason>,
        otherDescription: String?
    ): Result<Unit> {
        return try {
            val response = api.submitReport(
                ReportRequest(
                    targetType = targetType.name,
                    targetId = targetId,
                    reasons = reasons.map { it.name },
                    otherDescription = otherDescription
                )
            )
            when {
                response.isSuccessful -> Result.success(Unit)
                response.code() == 429 -> Result.failure(RateLimitException())
                else -> Result.failure(IOException("HTTP ${response.code()}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    class RateLimitException : Exception("Report rate limit exceeded. Please try again later.")
}
