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
)
