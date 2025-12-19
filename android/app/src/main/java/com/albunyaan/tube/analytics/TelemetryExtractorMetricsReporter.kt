package com.albunyaan.tube.analytics

import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.telemetry.TelemetryClient
import com.albunyaan.tube.telemetry.TelemetryEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges the existing extractor metrics surface with the structured telemetry pipeline while
 * preserving legacy logging behavior through delegation.
 */
class TelemetryExtractorMetricsReporter(
    private val delegate: ExtractorMetricsReporter,
    private val telemetryClient: TelemetryClient
) : ExtractorMetricsReporter {

    private val downloadVideoIds = ConcurrentHashMap<String, String>()
    private val downloadSizes = ConcurrentHashMap<String, Long>()

    override fun onCacheHit(type: ContentType, hitCount: Int) {
        delegate.onCacheHit(type, hitCount)
    }

    override fun onCacheMiss(type: ContentType, missCount: Int) {
        delegate.onCacheMiss(type, missCount)
    }

    override fun onFetchSuccess(type: ContentType, fetchedCount: Int, durationMillis: Long) {
        delegate.onFetchSuccess(type, fetchedCount, durationMillis)
    }

    override fun onFetchFailure(type: ContentType, ids: List<String>, throwable: Throwable) {
        delegate.onFetchFailure(type, ids, throwable)
    }

    override fun onStreamResolveSuccess(videoId: String, durationMillis: Long) {
        delegate.onStreamResolveSuccess(videoId, durationMillis)
    }

    override fun onStreamResolveFailure(videoId: String, throwable: Throwable) {
        delegate.onStreamResolveFailure(videoId, throwable)
    }

    override fun onDownloadStarted(downloadId: String, videoId: String) {
        downloadVideoIds[downloadId] = videoId
        telemetryClient.send(TelemetryEvent.DownloadStarted(downloadId, videoId))
        delegate.onDownloadStarted(downloadId, videoId)
    }

    override fun onDownloadProgress(downloadId: String, progress: Int) {
        val videoId = downloadVideoIds[downloadId]
        if (videoId != null) {
            telemetryClient.send(TelemetryEvent.DownloadProgress(downloadId, videoId, progress))
        }
        delegate.onDownloadProgress(downloadId, progress)
    }

    override fun onDownloadSizeKnown(downloadId: String, sizeBytes: Long) {
        downloadSizes[downloadId] = sizeBytes
        delegate.onDownloadSizeKnown(downloadId, sizeBytes)
    }

    override fun onDownloadCompleted(downloadId: String, filePath: String) {
        val videoId = downloadVideoIds[downloadId]
        if (videoId != null) {
            telemetryClient.send(
                TelemetryEvent.DownloadCompleted(
                    downloadId = downloadId,
                    videoId = videoId,
                    filePath = filePath,
                    sizeBytes = downloadSizes[downloadId]
                )
            )
        }
        downloadVideoIds.remove(downloadId)
        downloadSizes.remove(downloadId)
        delegate.onDownloadCompleted(downloadId, filePath)
    }

    override fun onDownloadFailed(downloadId: String, throwable: Throwable) {
        val videoId = downloadVideoIds[downloadId]
        if (videoId != null) {
            telemetryClient.send(
                TelemetryEvent.DownloadFailed(
                    downloadId = downloadId,
                    videoId = videoId,
                    reason = throwable.message ?: throwable::class.java.simpleName
                )
            )
        }
        downloadVideoIds.remove(downloadId)
        downloadSizes.remove(downloadId)
        delegate.onDownloadFailed(downloadId, throwable)
    }

    override fun onFavoriteToggleFailed(videoId: String, throwable: Throwable) {
        telemetryClient.send(
            TelemetryEvent.FavoriteToggleFailed(
                videoId = videoId,
                reason = throwable.message ?: throwable::class.java.simpleName
            )
        )
        delegate.onFavoriteToggleFailed(videoId, throwable)
    }
}
