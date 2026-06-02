package com.albunyaan.tube.data.importflow

/**
 * B9: Result returned by [YouTubeImportRepository.import] once all candidates
 * have been resolved and written.
 *
 * @param added          Items written with [approvalStatus = "APPROVED"].
 * @param sentForReview  Items written with [approvalStatus = "AWAITING"] (backend
 *                       returned PENDING — admin review required before they
 *                       appear in the Me feed).
 * @param skipped        Items NOT written: either already present in Room (any
 *                       approval_status) or backend returned REJECTED / ERROR.
 * @param alreadyPresent Subset of [skipped]: candidates whose youtubeId was
 *                       already found in the local Room table before resolve.
 */
data class ImportSummary(
    val added: Int,
    val sentForReview: Int,
    val skipped: Int,
    val alreadyPresent: Int,
    /**
     * F10 — true when the per-user daily import budget (HTTP 429) cut the run short.
     * The counts above reflect only the chunks that completed before the cap; the
     * remaining items were not sent and can be re-imported later (dedup skips the
     * ones already written).
     */
    val rateLimited: Boolean = false,
)
