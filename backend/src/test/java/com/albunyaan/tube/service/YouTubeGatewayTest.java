package com.albunyaan.tube.service;

import com.albunyaan.tube.config.ValidationProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for YouTubeGateway
 *
 * Tests verify:
 * - Page token encoding/decoding
 * - Executor service management
 * - NewPipe service initialization
 */
@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class YouTubeGatewayTest {

    private YouTubeGateway gateway;

    @BeforeAll
    static void initializeNewPipe() {
        // Initialize NewPipe once for the entire test class.
        // NewPipe.init() can only be called once; subsequent calls throw IllegalStateException.
        // We catch only IllegalStateException since NewPipe may already be initialized
        // by another test class in the same test run. Other exceptions should propagate.
        try {
            NewPipe.init(new org.schabi.newpipe.extractor.downloader.Downloader() {
                @Override
                public org.schabi.newpipe.extractor.downloader.Response execute(
                        org.schabi.newpipe.extractor.downloader.Request request) {
                    // Return null for stub downloader. Current tests only verify URL utilities
                    // and page token encoding which don't require actual HTTP responses.
                    // If tests are added that perform extraction, this should return a proper
                    // stubbed Response to avoid NPEs.
                    return null;
                }
            });
        } catch (IllegalStateException ignored) {
            // NewPipe is already initialized - this is expected and safe to ignore
        }
    }

    @BeforeEach
    void setUp() {
        StreamingService youtube = ServiceList.YouTube;
        gateway = new YouTubeGateway(youtube, 3, null, null); // Use default pool size of 3
    }

    @Nested
    @DisplayName("Page Token Encoding/Decoding")
    class PageTokenTests {

        @Test
        @DisplayName("Should encode null page to null token")
        void encodeNullPage_returnsNull() {
            String token = gateway.encodePageToken(null);
            assertNull(token);
        }

        @Test
        @DisplayName("Should decode null token to null page")
        void decodeNullToken_returnsNull() {
            Page page = gateway.decodePageToken(null);
            assertNull(page);
        }

        @Test
        @DisplayName("Should decode empty token to null page")
        void decodeEmptyToken_returnsNull() {
            Page page = gateway.decodePageToken("");
            assertNull(page);
        }

        @Test
        @DisplayName("Should round-trip page URL through encode/decode")
        void encodeDecodeCycle_preservesUrl() {
            String testUrl = "https://www.youtube.com/page?token=abc123";
            Page originalPage = new Page(testUrl);

            String encoded = gateway.encodePageToken(originalPage);
            assertNotNull(encoded);

            Page decoded = gateway.decodePageToken(encoded);
            assertNotNull(decoded);
            assertEquals(testUrl, decoded.getUrl());
        }
    }

    @Nested
    @DisplayName("Executor Service")
    class ExecutorServiceTests {

        @Test
        @DisplayName("Should provide executor service for async operations")
        void getExecutorService_notNull() {
            ExecutorService executor = gateway.getExecutorService();
            assertNotNull(executor);
            // Note: Can't check isShutdown() because static executor may be affected by other tests
        }
    }

    @Nested
    @DisplayName("URL Utilities")
    class UrlUtilityTests {

        @Test
        @DisplayName("Should build channel URL with /channel/ format for UC IDs")
        void getChannelUrl_channelId_usesChannelFormat() throws ExtractionException {
            // UC prefix identifies official YouTube channel IDs
            String channelId = "UCXuqSBlHAE6Xw-yeJA0Tunw";  // LinusTechTips
            String url = gateway.getChannelUrl(channelId);

            assertNotNull(url);
            assertEquals("https://www.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw", url,
                    "Channel IDs (UC*) must use /channel/ format, not /c/ format");
        }

        @Test
        @DisplayName("Should handle short UC channel IDs")
        void getChannelUrl_shortChannelId() throws ExtractionException {
            String channelId = "UCTest";  // Minimum valid UC ID
            String url = gateway.getChannelUrl(channelId);

            assertNotNull(url);
            assertEquals("https://www.youtube.com/channel/UCTest", url);
        }

        @Test
        @DisplayName("Should handle null channel ID gracefully")
        void getChannelUrl_null_doesNotThrow() throws ExtractionException {
            // Null goes through factory fallback, which catches exceptions
            // and returns a /channel/ URL with "null" appended
            String url = gateway.getChannelUrl(null);

            assertNotNull(url, "Should not throw, should return a URL");
            assertTrue(url.contains("youtube.com"),
                    "Should return a valid YouTube URL even for null input");
        }

        @Test
        @DisplayName("Should handle non-UC ID (custom handle) via factory")
        void getChannelUrl_customHandle() throws ExtractionException {
            // IDs not starting with UC might be handles or custom URLs
            String customId = "LinusTechTips";  // A handle
            String url = gateway.getChannelUrl(customId);

            assertNotNull(url);
            // Factory may return different formats; just verify it's not null
            assertTrue(url.contains("youtube.com"),
                    "URL should be a valid YouTube URL");
        }

        @Test
        @DisplayName("Should handle empty string channel ID")
        void getChannelUrl_empty_fallsBackToChannelFormat() throws ExtractionException {
            String url = gateway.getChannelUrl("");

            assertNotNull(url);
            // Empty doesn't start with UC, so falls back to factory, then to /channel/
            assertTrue(url.contains("youtube.com"),
                    "URL should be a valid YouTube URL");
        }

        @Test
        @DisplayName("Should get playlist URL from playlist ID")
        void getPlaylistUrl_validId() throws ExtractionException {
            String playlistId = "PLTest123";
            String url = gateway.getPlaylistUrl(playlistId);

            assertNotNull(url);
            assertTrue(url.contains("youtube.com") || url.contains("playlist") || url.contains(playlistId),
                    "URL should be a playlist URL");
        }

        @Test
        @DisplayName("Should get video URL from video ID")
        void getVideoUrl_validId() throws ExtractionException {
            String videoId = "EnfgPg0Ey3I";
            String url = gateway.getVideoUrl(videoId);

            assertNotNull(url);
            assertTrue(url.contains("youtube.com") || url.contains(videoId),
                    "URL should contain video identifier");
        }
    }

    @Nested
    @DisplayName("Search Operations")
    class SearchOperationTests {

        @Test
        @DisplayName("Should create search extractor for query")
        void createSearchExtractor_notNull() throws ExtractionException {
            SearchExtractor extractor = gateway.createSearchExtractor("test query");
            assertNotNull(extractor);
        }
    }

    @Nested
    @DisplayName("Shutdown Behavior")
    class ShutdownTests {

        @Test
        @DisplayName("Should shutdown executor service gracefully")
        void shutdown_completesWithoutError() {
            // Create a separate gateway instance for shutdown test
            StreamingService youtube = ServiceList.YouTube;
            YouTubeGateway testGateway = new YouTubeGateway(youtube, 3, null, null);

            // Should not throw
            assertDoesNotThrow(() -> testGateway.shutdown());
        }
    }

    @Nested
    @DisplayName("Circuit Breaker Error Messages")
    class CircuitBreakerErrorMessageTests {

        private ValidationProperties createTestProperties() {
            ValidationProperties props = new ValidationProperties();
            props.getYoutube().getCircuitBreaker().setEnabled(true);
            props.getYoutube().getCircuitBreaker().setCooldownBaseMinutes(60);
            props.getYoutube().getCircuitBreaker().setCooldownMaxMinutes(600);
            props.getYoutube().getCircuitBreaker().getRollingWindow().setErrorThreshold(1);
            props.getYoutube().getCircuitBreaker().getRollingWindow().setWindowMinutes(10);
            return props;
        }

        @Test
        @DisplayName("HALF_OPEN with probe in progress should throw specific error message")
        void halfOpenWithProbeInProgress_throwsSpecificMessage() {
            // Arrange: Create circuit breaker and gateway
            ValidationProperties props = createTestProperties();
            YouTubeCircuitBreaker circuitBreaker = new YouTubeCircuitBreaker(props, null);
            StreamingService youtube = ServiceList.YouTube;
            YouTubeGateway testGateway = new YouTubeGateway(youtube, 3, null, circuitBreaker);

            // Open the circuit
            circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
            assertEquals(YouTubeCircuitBreaker.State.OPEN, circuitBreaker.getStatus().getState());

            // Transition to HALF_OPEN (simulating cooldown expiry)
            circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);

            // First request: should proceed (acquires probe permit)
            // We need to call a public method that triggers checkCircuitBreaker()
            // fetchChannelInfo will fail due to stub downloader returning null (causes NPE)

            // Acquire the probe permit by making a request that will fail on extraction but pass circuit check
            try {
                testGateway.fetchChannelInfo("UCTest");
                fail("Expected exception from extraction");
            } catch (IOException e) {
                // If it's a circuit breaker message, fail the test
                if (e.getMessage() != null && e.getMessage().contains("circuit breaker")) {
                    fail("First request should not be blocked by circuit breaker: " + e.getMessage());
                }
                // Otherwise, it's an extraction error which is expected (stub downloader)
            } catch (ExtractionException e) {
                // Expected - extraction fails because of stub downloader
            } catch (NullPointerException e) {
                // Expected - stub downloader returns null, causing NPE in NewPipe
                // This is fine - the circuit breaker check passed and the probe permit was acquired
            }

            // Verify probe permit was acquired (circuit breaker check passed for first request)
            assertTrue(circuitBreaker.isProbeRequest(),
                    "First request should have acquired probe permit");

            // Now probe is in progress - second request should get specific message
            IOException thrown = assertThrows(IOException.class, () -> {
                testGateway.fetchChannelInfo("UCTest2");
            });

            assertTrue(thrown.getMessage().contains("HALF_OPEN"),
                    "Error message should mention HALF_OPEN state. Got: " + thrown.getMessage());
            assertTrue(thrown.getMessage().contains("probe already in progress"),
                    "Error message should mention probe in progress. Got: " + thrown.getMessage());

            // Clean up
            testGateway.shutdown();
        }

        @Test
        @DisplayName("OPEN state with cooldown should throw generic circuit open message")
        void openWithCooldown_throwsGenericMessage() {
            // Arrange: Create circuit breaker and gateway
            ValidationProperties props = createTestProperties();
            YouTubeCircuitBreaker circuitBreaker = new YouTubeCircuitBreaker(props, null);
            StreamingService youtube = ServiceList.YouTube;
            YouTubeGateway testGateway = new YouTubeGateway(youtube, 3, null, circuitBreaker);

            // Open the circuit (it will have cooldown remaining)
            circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
            assertEquals(YouTubeCircuitBreaker.State.OPEN, circuitBreaker.getStatus().getState());
            assertTrue(circuitBreaker.getRemainingCooldownMs() > 0, "Should have cooldown remaining");

            // Request should get generic "circuit is open" message
            IOException thrown = assertThrows(IOException.class, () -> {
                testGateway.fetchChannelInfo("UCTest");
            });

            assertTrue(thrown.getMessage().contains("circuit breaker is open"),
                    "Error message should say circuit is open. Got: " + thrown.getMessage());
            assertTrue(thrown.getMessage().contains("Remaining cooldown"),
                    "Error message should include remaining cooldown. Got: " + thrown.getMessage());
            assertFalse(thrown.getMessage().contains("HALF_OPEN"),
                    "OPEN state should NOT mention HALF_OPEN. Got: " + thrown.getMessage());

            // Clean up
            testGateway.shutdown();
        }

        @Test
        @DisplayName("CLOSED state should not block requests")
        void closedState_doesNotBlockRequests() {
            // Arrange: Create circuit breaker and gateway
            ValidationProperties props = createTestProperties();
            YouTubeCircuitBreaker circuitBreaker = new YouTubeCircuitBreaker(props, null);
            StreamingService youtube = ServiceList.YouTube;
            YouTubeGateway testGateway = new YouTubeGateway(youtube, 3, null, circuitBreaker);

            // Circuit starts in CLOSED state
            assertEquals(YouTubeCircuitBreaker.State.CLOSED, circuitBreaker.getStatus().getState());

            // Request should NOT be blocked by circuit breaker
            // It will fail on extraction due to stub downloader returning null (causes NPE)
            try {
                testGateway.fetchChannelInfo("UCTest");
                fail("Expected exception from extraction");
            } catch (IOException e) {
                // Should NOT be a circuit breaker error
                if (e.getMessage() != null) {
                    assertFalse(e.getMessage().contains("circuit breaker"),
                            "CLOSED state should not throw circuit breaker error. Got: " + e.getMessage());
                }
                // If message is null, that's fine - it's not a circuit breaker error
            } catch (ExtractionException e) {
                // Expected - extraction fails because of stub downloader
            } catch (NullPointerException e) {
                // Expected - stub downloader returns null, causing NPE in NewPipe
                // This means the circuit breaker check passed (CLOSED state allows request)
            }

            // Clean up
            testGateway.shutdown();
        }
    }
}
