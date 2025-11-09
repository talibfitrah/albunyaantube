package com.albunyaan.tube.telemetry

import android.util.Log
import org.json.JSONObject

/**
 * Minimal telemetry pipeline for structured event emission. Downstream implementations can forward
 * events to analytics backends while this default logs JSON payloads for local debugging.
 */
interface TelemetryClient {
    fun send(event: TelemetryEvent)
}

sealed class TelemetryEvent(val name: String) {

    data class DownloadStarted(
        val downloadId: String,
        val videoId: String
    ) : TelemetryEvent("download.started")

    data class DownloadProgress(
        val downloadId: String,
        val videoId: String,
        val progress: Int
    ) : TelemetryEvent("download.progress")

    data class DownloadCompleted(
        val downloadId: String,
        val videoId: String,
        val filePath: String,
        val sizeBytes: Long?
    ) : TelemetryEvent("download.completed")

    data class DownloadFailed(
        val downloadId: String,
        val videoId: String,
        val reason: String
    ) : TelemetryEvent("download.failed")

    fun toJson(): String {
        val json = JSONObject()
        json.put("event", name)
        when (this) {
            is DownloadStarted -> {
                json.put("downloadId", downloadId)
                json.put("videoId", videoId)
            }
            is DownloadProgress -> {
                json.put("downloadId", downloadId)
                json.put("videoId", videoId)
                json.put("progress", progress)
            }
            is DownloadCompleted -> {
                json.put("downloadId", downloadId)
                json.put("videoId", videoId)
                json.put("filePath", filePath)
                sizeBytes?.let { json.put("sizeBytes", it) }
            }
            is DownloadFailed -> {
                json.put("downloadId", downloadId)
                json.put("videoId", videoId)
                json.put("reason", reason)
            }
        }
        return json.toString()
    }
}

class LogTelemetryClient : TelemetryClient {
    override fun send(event: TelemetryEvent) {
        Log.d(TAG, event.toJson())
    }

    private companion object {
        const val TAG = "Telemetry"
    }
}

