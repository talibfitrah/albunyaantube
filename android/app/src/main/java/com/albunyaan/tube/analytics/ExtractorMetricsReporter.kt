package com.albunyaan.tube.analytics

import android.util.Log
import com.albunyaan.tube.data.model.ContentType

interface ExtractorMetricsReporter {
    fun onCacheHit(type: ContentType, hitCount: Int)
    fun onCacheMiss(type: ContentType, missCount: Int)
    fun onFetchSuccess(type: ContentType, fetchedCount: Int, durationMillis: Long)
    fun onFetchFailure(type: ContentType, ids: List<String>, throwable: Throwable)

    fun onStreamResolveSuccess(videoId: String, durationMillis: Long) {}
    fun onStreamResolveFailure(videoId: String, throwable: Throwable) {}
}

class LogExtractorMetricsReporter : ExtractorMetricsReporter {

    override fun onCacheHit(type: ContentType, hitCount: Int) {
        Log.d(TAG, "extractor_cache_hit type=${type.name} hits=$hitCount")
    }

    override fun onCacheMiss(type: ContentType, missCount: Int) {
        Log.d(TAG, "extractor_cache_miss type=${type.name} misses=$missCount")
    }

    override fun onFetchSuccess(type: ContentType, fetchedCount: Int, durationMillis: Long) {
        Log.d(TAG, "extractor_fetch_success type=${type.name} fetched=$fetchedCount durationMs=$durationMillis")
    }

    override fun onFetchFailure(type: ContentType, ids: List<String>, throwable: Throwable) {
        Log.w(TAG, "extractor_fetch_failure type=${type.name} ids=$ids", throwable)
    }

    override fun onStreamResolveSuccess(videoId: String, durationMillis: Long) {
        Log.d(TAG, "stream_resolve_success videoId=$videoId durationMs=$durationMillis")
    }

    override fun onStreamResolveFailure(videoId: String, throwable: Throwable) {
        Log.w(TAG, "stream_resolve_failure videoId=$videoId", throwable)
    }

    private companion object {
        const val TAG = "ExtractorMetrics"
    }
}
