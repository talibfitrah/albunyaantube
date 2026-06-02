package com.albunyaan.tube.data.`import`

/**
 * B9: Live progress snapshot emitted by [YouTubeImportRepository.progress]
 * during an [YouTubeImportRepository.import] call.
 *
 * [processed] counts items that have been resolved and written (or skipped)
 * so far. [total] is the number of fresh (non-duplicate) items being resolved.
 * [Phase.RESOLVING] → waiting for the backend resolve response for a chunk.
 * [Phase.WRITING]   → writing resolved items to Room.
 * [Phase.DONE]      → import finished; [ImportSummary] is ready.
 */
data class ImportProgress(
    val phase: Phase,
    val processed: Int,
    val total: Int,
) {
    enum class Phase { RESOLVING, WRITING, DONE }
}
