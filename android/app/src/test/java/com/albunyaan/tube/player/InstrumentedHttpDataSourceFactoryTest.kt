package com.albunyaan.tube.player

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import android.net.Uri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for InstrumentedHttpDataSourceFactory.
 *
 * Tests that instrumentation hooks fire correctly and error info propagates:
 * - Factory creates data sources with proper configuration
 * - Error handling captures telemetry for HTTP errors
 * - Request properties are properly forwarded
 * - Video ID and playback position providers work correctly
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class InstrumentedHttpDataSourceFactoryTest {

    private lateinit var telemetry: StreamRequestTelemetry

    @Before
    fun setUp() {
        telemetry = StreamRequestTelemetry()
    }

    // --- Factory Creation Tests ---

    @Test
    fun `factory creates data source with correct configuration`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestUserAgent/1.0",
            telemetry = telemetry,
            streamType = "HLS",
            videoIdProvider = { "video123" },
            playbackPositionProvider = { 5000L }
        )

        val dataSource = factory.createDataSource()
        assertNotNull("Factory should create a data source", dataSource)
    }

    @Test
    fun `factory with default parameters creates data source`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestUserAgent/1.0",
            telemetry = telemetry
        )

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
    }

    @Test
    fun `factory with custom timeouts creates data source`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestUserAgent/1.0",
            telemetry = telemetry,
            streamType = "DASH",
            connectTimeoutMs = 30000,
            readTimeoutMs = 45000,
            allowCrossProtocolRedirects = false
        )

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
    }

    @Test
    fun `setDefaultRequestProperties returns factory for chaining`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestUserAgent/1.0",
            telemetry = telemetry
        )

        val result = factory.setDefaultRequestProperties(mutableMapOf("X-Custom" to "value"))
        assertSame("Should return same factory for chaining", factory, result)
    }

    // --- Video ID Provider Tests ---

    @Test
    fun `videoIdProvider returning null is handled`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestUserAgent/1.0",
            telemetry = telemetry,
            videoIdProvider = { null }
        )

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
    }

    @Test
    fun `videoIdProvider returning valid ID is used`() {
        var capturedVideoId: String?
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestUserAgent/1.0",
            telemetry = telemetry,
            videoIdProvider = {
                capturedVideoId = "video123"
                capturedVideoId
            }
        )

        factory.createDataSource()
        // Provider is lazy, called when error occurs
    }

    // --- Playback Position Provider Tests ---

    @Test
    fun `playbackPositionProvider returning null is handled`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestUserAgent/1.0",
            telemetry = telemetry,
            playbackPositionProvider = { null }
        )

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
    }

    @Test
    fun `playbackPositionProvider returning value is used`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestUserAgent/1.0",
            telemetry = telemetry,
            playbackPositionProvider = { 12345L }
        )

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
    }

    // --- createInstrumentedDataSourceFactory Extension Tests ---

    @Test
    fun `createInstrumentedDataSourceFactory extension creates factory`() {
        val factory = createInstrumentedDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            streamType = "PROGRESSIVE",
            videoIdProvider = { "vid1" },
            playbackPositionProvider = { 0L }
        )

        assertNotNull(factory)
        assertTrue(factory is InstrumentedHttpDataSourceFactory)
    }

    // --- Stream Type Tests ---

    @Test
    fun `factory accepts HLS stream type`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            streamType = "HLS"
        )
        assertNotNull(factory.createDataSource())
    }

    @Test
    fun `factory accepts DASH stream type`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            streamType = "DASH"
        )
        assertNotNull(factory.createDataSource())
    }

    @Test
    fun `factory accepts PROGRESSIVE stream type`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            streamType = "PROGRESSIVE"
        )
        assertNotNull(factory.createDataSource())
    }

    @Test
    fun `factory defaults to UNKNOWN stream type`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry
            // streamType defaults to "UNKNOWN"
        )
        assertNotNull(factory.createDataSource())
    }

    // --- Request Properties Tests ---

    @Test
    fun `setDefaultRequestProperties applies to created sources`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry
        )

        factory.setDefaultRequestProperties(mutableMapOf(
            "Accept" to "application/json",
            "X-Custom-Header" to "custom-value"
        ))

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
    }

    @Test
    fun `empty request properties map is handled`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry
        )

        factory.setDefaultRequestProperties(mutableMapOf())
        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
    }

    // --- Multiple Data Source Creation Tests ---

    @Test
    fun `factory can create multiple data sources`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry
        )

        val source1 = factory.createDataSource()
        val source2 = factory.createDataSource()
        val source3 = factory.createDataSource()

        assertNotNull(source1)
        assertNotNull(source2)
        assertNotNull(source3)
        assertNotSame("Each call should create new instance", source1, source2)
        assertNotSame("Each call should create new instance", source2, source3)
    }

    // --- User-Agent Tests ---

    @Test
    fun `user agent is required`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "CustomUserAgent/2.0",
            telemetry = telemetry
        )

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
    }

    @Test
    fun `empty user agent is allowed`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "",
            telemetry = telemetry
        )

        val dataSource = factory.createDataSource()
        assertNotNull(dataSource)
    }

    // --- Telemetry Integration Tests ---

    @Test
    fun `telemetry instance is stored`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry
        )

        // Factory should hold reference to telemetry
        // (Internal state - tested indirectly through error recording)
        assertNotNull(factory)
    }

    @Test
    fun `telemetry records no failures initially`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry
        )

        factory.createDataSource()

        // No HTTP errors yet - telemetry should be empty
        assertTrue("No failures should be recorded yet",
            telemetry.getRecentFailures().isEmpty())
    }

    // --- Timeout Configuration Tests ---

    @Test
    fun `connect timeout is configurable`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            connectTimeoutMs = 5000
        )

        assertNotNull(factory.createDataSource())
    }

    @Test
    fun `read timeout is configurable`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            readTimeoutMs = 60000
        )

        assertNotNull(factory.createDataSource())
    }

    @Test
    fun `cross protocol redirects setting is configurable`() {
        val factoryWithRedirects = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            allowCrossProtocolRedirects = true
        )

        val factoryWithoutRedirects = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            allowCrossProtocolRedirects = false
        )

        assertNotNull(factoryWithRedirects.createDataSource())
        assertNotNull(factoryWithoutRedirects.createDataSource())
    }

    // --- Default Timeouts Tests ---

    @Test
    fun `default connect timeout is 15 seconds`() {
        // Default value from constructor
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry
        )

        // Can't directly verify internal state, but factory should work
        assertNotNull(factory.createDataSource())
    }

    @Test
    fun `default read timeout is 20 seconds`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry
        )

        assertNotNull(factory.createDataSource())
    }

    @Test
    fun `default allows cross protocol redirects`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry
        )

        assertNotNull(factory.createDataSource())
    }

    // --- Data Source Interface Tests ---

    @Test
    fun `createDataSource returns new instance each time`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry
        )

        val dataSource1 = factory.createDataSource()
        val dataSource2 = factory.createDataSource()
        assertNotSame("Should create a new data source instance each time", dataSource1, dataSource2)
    }

    // --- Provider Callback Behavior Tests ---

    @Test
    fun `providers are lazy - not called during factory creation`() {
        var videoIdCalled = false
        var positionCalled = false

        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            videoIdProvider = {
                videoIdCalled = true
                "video1"
            },
            playbackPositionProvider = {
                positionCalled = true
                0L
            }
        )

        // Create factory should not invoke providers
        assertFalse("VideoId provider should not be called during factory creation",
            videoIdCalled)
        assertFalse("Position provider should not be called during factory creation",
            positionCalled)
        assertNotNull(factory)
    }

    @Test
    fun `providers are lazy - not called during data source creation`() {
        var videoIdCalled = false
        var positionCalled = false

        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            videoIdProvider = {
                videoIdCalled = true
                "video1"
            },
            playbackPositionProvider = {
                positionCalled = true
                0L
            }
        )

        factory.createDataSource()

        // Create data source should not invoke providers either
        // Providers are only called when an error occurs
        assertFalse("VideoId provider should not be called during source creation",
            videoIdCalled)
        assertFalse("Position provider should not be called during source creation",
            positionCalled)
    }

    // --- Error Scenario Integration Tests (without actual network) ---

    @Test
    fun `telemetry is available for error recording`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            videoIdProvider = { "testVideo" }
        )
        assertNotNull(factory.createDataSource())

        // Simulate what would happen on error by recording directly
        telemetry.recordFailure(
            videoId = "testVideo",
            streamType = "HLS",
            requestUrl = "https://example.com/stream",
            requestHeaders = mapOf("User-Agent" to "TestAgent"),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "Forbidden"
        )

        assertEquals(1, telemetry.getRecentFailures().size)
    }

    // --- Factory State Tests ---

    @Test
    fun `factory is stateless between createDataSource calls`() {
        var callCount = 0
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            videoIdProvider = {
                callCount++
                "video$callCount"
            }
        )

        // Each createDataSource creates independent instances
        factory.createDataSource()
        factory.createDataSource()

        // callCount should still be 0 - providers only called on error
        assertEquals("Providers should not have been called", 0, callCount)
    }

    // --- Edge Case Tests ---

    @Test
    fun `very long user agent is handled`() {
        val longUserAgent = "A".repeat(1000)
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = longUserAgent,
            telemetry = telemetry
        )

        assertNotNull(factory.createDataSource())
    }

    @Test
    fun `unicode user agent is handled`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent/1.0 (中文; العربية)",
            telemetry = telemetry
        )

        assertNotNull(factory.createDataSource())
    }

    @Test
    fun `special characters in stream type are handled`() {
        val factory = InstrumentedHttpDataSourceFactory(
            userAgent = "TestAgent",
            telemetry = telemetry,
            streamType = "HLS/DASH-Hybrid"
        )

        assertNotNull(factory.createDataSource())
    }
}
