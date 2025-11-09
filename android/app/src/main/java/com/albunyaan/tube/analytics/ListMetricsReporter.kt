package com.albunyaan.tube.analytics

import android.util.Log
import com.albunyaan.tube.data.model.ContentType

/**
 * Lightweight metrics surface for list reliability. Logging is a stand-in for structured telemetry
 * until analytics plumbing is available.
 */
interface ListMetricsReporter {
    fun onLoadSuccess(contentType: ContentType, itemCount: Int)
    fun onLoadEmpty(contentType: ContentType)
    fun onLoadError(contentType: ContentType, category: ErrorCategory)
    fun onRetry(contentType: ContentType)
    fun onClearFilters(contentType: ContentType)
}

enum class ErrorCategory { OFFLINE, SERVER, UNKNOWN }

class LogListMetricsReporter : ListMetricsReporter {

    override fun onLoadSuccess(contentType: ContentType, itemCount: Int) {
        Log.d(TAG, "load_success type=${contentType.name} count=$itemCount")
    }

    override fun onLoadEmpty(contentType: ContentType) {
        Log.d(TAG, "load_empty type=${contentType.name}")
    }

    override fun onLoadError(contentType: ContentType, category: ErrorCategory) {
        Log.w(TAG, "load_error type=${contentType.name} category=$category")
    }

    override fun onRetry(contentType: ContentType) {
        Log.d(TAG, "retry type=${contentType.name}")
    }

    override fun onClearFilters(contentType: ContentType) {
        Log.d(TAG, "clear_filters type=${contentType.name}")
    }

    private companion object {
        const val TAG = "ListMetrics"
    }
}

