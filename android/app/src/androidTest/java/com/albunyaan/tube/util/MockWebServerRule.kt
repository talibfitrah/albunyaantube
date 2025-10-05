package com.albunyaan.tube.util

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.IOException

/**
 * JUnit rule for managing MockWebServer lifecycle in instrumentation tests.
 *
 * Usage:
 * ```
 * @get:Rule
 * val mockWebServerRule = MockWebServerRule()
 *
 * @Test
 * fun testApiCall() {
 *     mockWebServerRule.enqueue(MockResponse().setBody("..."))
 *     // Your test code
 * }
 * ```
 */
class MockWebServerRule : TestWatcher() {
    private val mockWebServer = MockWebServer()

    val baseUrl: String
        get() = mockWebServer.url("/").toString()

    override fun starting(description: Description) {
        super.starting(description)
        try {
            mockWebServer.start()
        } catch (e: IOException) {
            throw RuntimeException("Failed to start MockWebServer", e)
        }
    }

    override fun finished(description: Description) {
        super.finished(description)
        try {
            mockWebServer.shutdown()
        } catch (e: IOException) {
            throw RuntimeException("Failed to shutdown MockWebServer", e)
        }
    }

    /**
     * Enqueue a mock response for the next request.
     */
    fun enqueue(response: MockResponse) {
        mockWebServer.enqueue(response)
    }

    /**
     * Convenience method to enqueue a successful JSON response.
     */
    fun enqueueJson(json: String, code: Int = 200) {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(json)
        )
    }

    /**
     * Convenience method to enqueue an error response.
     */
    fun enqueueError(code: Int = 500, message: String = "Internal Server Error") {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setBody(message)
        )
    }

    /**
     * Get the number of requests received.
     */
    fun getRequestCount(): Int = mockWebServer.requestCount

    /**
     * Take a recorded request (for verification in tests).
     */
    fun takeRequest() = mockWebServer.takeRequest()
}
