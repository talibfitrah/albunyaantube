package com.albunyaan.tube.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Factory that creates instrumented HTTP data sources for stream request telemetry.
 *
 * Wraps DefaultHttpDataSource to intercept request/response details for 403 diagnostics.
 * Captures:
 * - Request URL and headers
 * - Response code and headers
 * - Response body (for error responses)
 *
 * Used to diagnose mid-playback 403 errors caused by:
 * - Expired stream URLs
 * - Geo-restrictions
 * - Rate limiting
 * - User-Agent mismatches
 */
@OptIn(UnstableApi::class)
class InstrumentedHttpDataSourceFactory(
    private val userAgent: String,
    private val telemetry: StreamRequestTelemetry,
    private val streamType: String = "UNKNOWN",
    private val videoIdProvider: () -> String? = { null },
    private val playbackPositionProvider: () -> Long? = { null },
    connectTimeoutMs: Int = 15000,
    readTimeoutMs: Int = 20000,
    allowCrossProtocolRedirects: Boolean = true
) : HttpDataSource.Factory {

    companion object {
        private const val TAG = "InstrumentedHttp"
    }

    private val baseFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(userAgent)
        .setConnectTimeoutMs(connectTimeoutMs)
        .setReadTimeoutMs(readTimeoutMs)
        .setAllowCrossProtocolRedirects(allowCrossProtocolRedirects)

    private var defaultRequestProperties: HttpDataSource.RequestProperties? = null
    private var transferListener: TransferListener? = null

    override fun setDefaultRequestProperties(
        defaultRequestProperties: MutableMap<String, String>
    ): HttpDataSource.Factory {
        this.defaultRequestProperties = HttpDataSource.RequestProperties().apply {
            set(defaultRequestProperties)
        }
        baseFactory.setDefaultRequestProperties(defaultRequestProperties)
        return this
    }

    override fun createDataSource(): HttpDataSource {
        val baseSource = baseFactory.createDataSource()
        return InstrumentedHttpDataSource(
            baseSource,
            telemetry,
            streamType,
            userAgent,
            videoIdProvider,
            playbackPositionProvider
        )
    }

    /**
     * Instrumented HTTP data source that captures request/response telemetry.
     */
    private class InstrumentedHttpDataSource(
        private val delegate: HttpDataSource,
        private val telemetry: StreamRequestTelemetry,
        private val streamType: String,
        private val userAgent: String,
        private val videoIdProvider: () -> String?,
        private val playbackPositionProvider: () -> Long?
    ) : HttpDataSource {

        private var currentDataSpec: DataSpec? = null
        private var lastFailureType: StreamRequestTelemetry.FailureType? = null

        override fun open(dataSpec: DataSpec): Long {
            currentDataSpec = dataSpec
            try {
                return delegate.open(dataSpec)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                // Capture telemetry for HTTP errors
                handleHttpError(dataSpec, e)
                throw e
            } catch (e: HttpDataSource.HttpDataSourceException) {
                // Capture telemetry for other HTTP errors
                if (e.cause is HttpDataSource.InvalidResponseCodeException) {
                    handleHttpError(dataSpec, e.cause as HttpDataSource.InvalidResponseCodeException)
                }
                throw e
            }
        }

        private fun handleHttpError(dataSpec: DataSpec, e: HttpDataSource.InvalidResponseCodeException) {
            val requestHeaders = mutableMapOf<String, String>()
            requestHeaders["User-Agent"] = userAgent

            // Capture request headers from dataSpec
            dataSpec.httpRequestHeaders.forEach { (key, value) ->
                requestHeaders[key] = value
            }

            // Capture response headers (headerFields is always non-null per API contract)
            val responseHeaders = e.headerFields

            // Get response body (responseBody is always non-null per API contract)
            val responseBody = e.responseBody.decodeToString()

            val failureType = telemetry.recordFailure(
                videoId = videoIdProvider(),
                streamType = streamType,
                requestUrl = dataSpec.uri.toString(),
                requestHeaders = requestHeaders,
                responseCode = e.responseCode,
                responseHeaders = responseHeaders,
                responseBody = responseBody,
                playbackPositionMs = playbackPositionProvider()
            )

            lastFailureType = failureType

            // Log specific recovery hints
            when (failureType) {
                StreamRequestTelemetry.FailureType.URL_EXPIRED -> {
                    Log.w(TAG, "Stream URL expired - recommend forced refresh")
                }
                StreamRequestTelemetry.FailureType.GEO_RESTRICTED -> {
                    Log.w(TAG, "Content geo-restricted - no recovery possible")
                }
                StreamRequestTelemetry.FailureType.RATE_LIMITED -> {
                    val retryAfter = responseHeaders.entries
                        .firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
                        ?.value?.firstOrNull()
                    Log.w(TAG, "Rate limited - retry after: ${retryAfter ?: "unknown"}")
                }
                else -> {
                    Log.w(TAG, "HTTP ${e.responseCode} error on stream request")
                }
            }
        }

        /**
         * Get the last failure type if an error occurred.
         */
        fun getLastFailureType(): StreamRequestTelemetry.FailureType? = lastFailureType
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            try {
                return delegate.read(buffer, offset, length)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                currentDataSpec?.let { handleHttpError(it, e) }
                throw e
            } catch (e: HttpDataSource.HttpDataSourceException) {
                if (e.cause is HttpDataSource.InvalidResponseCodeException) {
                    currentDataSpec?.let {
                        handleHttpError(it, e.cause as HttpDataSource.InvalidResponseCodeException)
                    }
                }
                throw e
            }
        }

        override fun close() {
            delegate.close()
            currentDataSpec = null
        }

        override fun addTransferListener(transferListener: TransferListener) {
            delegate.addTransferListener(transferListener)
        }

        override fun getUri() = delegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun setRequestProperty(name: String, value: String) {
            delegate.setRequestProperty(name, value)
        }

        override fun clearRequestProperty(name: String) {
            delegate.clearRequestProperty(name)
        }

        override fun clearAllRequestProperties() {
            delegate.clearAllRequestProperties()
        }

        override fun getResponseCode(): Int = delegate.responseCode
    }
}

/**
 * Extension to create an instrumented data source factory with telemetry.
 */
@OptIn(UnstableApi::class)
fun createInstrumentedDataSourceFactory(
    userAgent: String,
    telemetry: StreamRequestTelemetry,
    streamType: String,
    videoIdProvider: () -> String?,
    playbackPositionProvider: () -> Long?
): HttpDataSource.Factory {
    return InstrumentedHttpDataSourceFactory(
        userAgent = userAgent,
        telemetry = telemetry,
        streamType = streamType,
        videoIdProvider = videoIdProvider,
        playbackPositionProvider = playbackPositionProvider
    )
}
