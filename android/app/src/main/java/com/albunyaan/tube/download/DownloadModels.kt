package com.albunyaan.tube.download

data class DownloadRequest(
    val id: String,
    val title: String,
    val videoId: String,
    val audioOnly: Boolean = true
)

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

data class DownloadEntry(
    val request: DownloadRequest,
    val status: DownloadStatus,
    val progress: Int = 0,
    val message: String? = null,
    val filePath: String? = null
)
