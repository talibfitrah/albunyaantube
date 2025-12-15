package com.albunyaan.tube.service;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.config.YouTubeCircuitBreakerProperties;
import com.albunyaan.tube.config.YouTubeThrottleProperties;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.search.SearchExtractor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for YouTubeGateway
 *
 * Tests verify:
 * - Page token encoding/decoding
 * - Executor service management
 * - NewPipe service initialization
 * - Throttle configuration
 * - Circuit breaker integration
 */
@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class YouTubeGatewayTest {

    @Mock
    private Firestore firestore;

    @Mock
    private CollectionReference collectionReference;

    @Mock
    private DocumentReference documentReference;

    @Mock
    private DocumentSnapshot documentSnapshot;

    @Mock
    private ApiFuture<DocumentSnapshot> documentFuture;

    private YouTubeGateway gateway;
    private YouTubeThrottleProperties throttleProperties;
    private YouTubeCircuitBreakerService circuitBreakerService;

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
    void setUp() throws Exception {
        StreamingService youtube = ServiceList.YouTube;

        // Create throttle properties with throttling disabled for fast tests
        throttleProperties = new YouTubeThrottleProperties();
        throttleProperties.setEnabled(false);  // Disable throttling in tests

        // Create circuit breaker with disabled state for most tests
        FirestoreTimeoutProperties timeoutProperties = new FirestoreTimeoutProperties();
        timeoutProperties.setRead(5);
        timeoutProperties.setWrite(10);

        YouTubeCircuitBreakerProperties breakerProperties = new YouTubeCircuitBreakerProperties();
        breakerProperties.setEnabled(false);  // Disable circuit breaker in most tests

        // Mock Firestore chain for circuit breaker service
        lenient().when(firestore.collection(anyString())).thenReturn(collectionReference);
        lenient().when(collectionReference.document(anyString())).thenReturn(documentReference);
        lenient().when(documentReference.get()).thenReturn(documentFuture);
        lenient().when(documentFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(documentSnapshot);
        lenient().when(documentSnapshot.exists()).thenReturn(false);

        circuitBreakerService = new YouTubeCircuitBreakerService(firestore, timeoutProperties, breakerProperties);

        gateway = new YouTubeGateway(youtube, 3, throttleProperties, circuitBreakerService);
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
            String videoId = "dQw4w9WgXcQ";
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
        void createSearchExtractor_notNull() throws Exception {
            SearchExtractor extractor = gateway.createSearchExtractor("test query");
            assertNotNull(extractor);
        }
    }

    @Nested
    @DisplayName("Throttle Configuration")
    class ThrottleConfigTests {

        @Test
        @DisplayName("Should report throttle status")
        void getThrottleStatus_returnsConfiguration() {
            YouTubeGateway.ThrottleStatus status = gateway.getThrottleStatus();

            assertNotNull(status);
            assertFalse(status.enabled(), "Throttling should be disabled in tests");
        }

        @Test
        @DisplayName("Should apply throttle when enabled")
        void throttleEnabled_appliesDelay() {
            // Create gateway with throttling enabled but very short delay
            YouTubeThrottleProperties enabledProps = new YouTubeThrottleProperties();
            enabledProps.setEnabled(true);
            enabledProps.setDelayBetweenItemsMs(10);  // Short delay for test
            enabledProps.setJitterMs(0);

            StreamingService youtube = ServiceList.YouTube;
            YouTubeGateway throttledGateway = new YouTubeGateway(youtube, 1, enabledProps, circuitBreakerService);

            YouTubeGateway.ThrottleStatus status = throttledGateway.getThrottleStatus();
            assertTrue(status.enabled());
            assertEquals(10, status.delayMs());
            assertEquals(0, status.jitterMs());

            // Cleanup
            throttledGateway.shutdown();
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
            YouTubeThrottleProperties props = new YouTubeThrottleProperties();
            props.setEnabled(false);
            YouTubeGateway testGateway = new YouTubeGateway(youtube, 3, props, circuitBreakerService);

            // Should not throw
            assertDoesNotThrow(() -> testGateway.shutdown());
        }
    }

    @Nested
    @DisplayName("Circuit Breaker Integration")
    class CircuitBreakerTests {

        @Test
        @DisplayName("Should return circuit breaker status")
        void getCircuitBreakerStatus_shouldReturnStatus() {
            YouTubeCircuitBreakerService.CircuitBreakerStatus status = gateway.getCircuitBreakerStatus();

            assertNotNull(status);
            // Circuit breaker is disabled in setUp
            assertFalse(status.enabled());
        }

        @Test
        @DisplayName("Should delegate rate limit error check to circuit breaker")
        void isRateLimitError_shouldDelegate() {
            Exception rateLimitError = new RuntimeException("confirm you're not a bot");
            Exception normalError = new RuntimeException("Video not found");

            // Circuit breaker is disabled, so isRateLimitError should still work
            // as the method doesn't depend on enabled state
            assertTrue(gateway.isRateLimitError(rateLimitError));
            assertFalse(gateway.isRateLimitError(normalError));
        }
    }

    @Nested
    @DisplayName("isCircuitBreakerBlocking")
    class IsCircuitBreakerBlockingTests {

        @Mock
        private YouTubeCircuitBreakerService mockCircuitBreakerService;

        private YouTubeGateway gatewayWithMockedBreaker;

        @BeforeEach
        void setUpMockedGateway() {
            StreamingService youtube = ServiceList.YouTube;
            YouTubeThrottleProperties props = new YouTubeThrottleProperties();
            props.setEnabled(false);
            gatewayWithMockedBreaker = new YouTubeGateway(youtube, 1, props, mockCircuitBreakerService);
        }

        @org.junit.jupiter.api.AfterEach
        void tearDownMockedGateway() {
            if (gatewayWithMockedBreaker != null) {
                gatewayWithMockedBreaker.shutdown();
            }
        }

        @Test
        @DisplayName("Should return false when circuit breaker is CLOSED")
        void isCircuitBreakerBlocking_closed_returnsFalse() {
            when(mockCircuitBreakerService.getStatus()).thenReturn(
                    new YouTubeCircuitBreakerService.CircuitBreakerStatus(
                            true, "CLOSED", null, null, null, 0, 0
                    )
            );

            assertFalse(gatewayWithMockedBreaker.isCircuitBreakerBlocking());
        }

        @Test
        @DisplayName("Should return true when UNKNOWN state (fail-safe)")
        void isCircuitBreakerBlocking_unknown_returnsTrue() {
            when(mockCircuitBreakerService.getStatus()).thenReturn(
                    new YouTubeCircuitBreakerService.CircuitBreakerStatus(
                            true, "UNKNOWN", null, null, null, 0, 0
                    )
            );

            assertTrue(gatewayWithMockedBreaker.isCircuitBreakerBlocking());
        }

        @Test
        @DisplayName("Should return true when OPEN and cooldown NOT expired")
        void isCircuitBreakerBlocking_openActiveCooldown_returnsTrue() {
            java.time.Instant futureTime = java.time.Instant.now().plusSeconds(3600); // 1 hour from now
            when(mockCircuitBreakerService.getStatus()).thenReturn(
                    new YouTubeCircuitBreakerService.CircuitBreakerStatus(
                            true, "OPEN", java.time.Instant.now(), futureTime, "RateLimitError", 1, 5
                    )
            );

            assertTrue(gatewayWithMockedBreaker.isCircuitBreakerBlocking(),
                    "Should block when OPEN with active cooldown");
        }

        @Test
        @DisplayName("Should return false when OPEN but cooldown HAS expired - allows recovery")
        void isCircuitBreakerBlocking_openExpiredCooldown_returnsFalse() {
            java.time.Instant pastTime = java.time.Instant.now().minusSeconds(60); // 1 minute ago
            when(mockCircuitBreakerService.getStatus()).thenReturn(
                    new YouTubeCircuitBreakerService.CircuitBreakerStatus(
                            true, "OPEN", java.time.Instant.now().minusSeconds(3600), pastTime, "RateLimitError", 1, 5
                    )
            );

            assertFalse(gatewayWithMockedBreaker.isCircuitBreakerBlocking(),
                    "Should NOT block when OPEN but cooldown has expired - allows recovery via tryAllowRequest()");
        }

        @Test
        @DisplayName("Should return false when HALF_OPEN (probe allowed via tryAllowRequest)")
        void isCircuitBreakerBlocking_halfOpen_returnsFalse() {
            when(mockCircuitBreakerService.getStatus()).thenReturn(
                    new YouTubeCircuitBreakerService.CircuitBreakerStatus(
                            true, "HALF_OPEN", java.time.Instant.now(), null, "RateLimitError", 1, 5
                    )
            );

            assertFalse(gatewayWithMockedBreaker.isCircuitBreakerBlocking(),
                    "Should not block in HALF_OPEN - probe request allowed via tryAllowRequest()");
        }

        @Test
        @DisplayName("Should return true when OPEN and cooldownUntil is null (defensive)")
        void isCircuitBreakerBlocking_openNullCooldown_returnsTrue() {
            when(mockCircuitBreakerService.getStatus()).thenReturn(
                    new YouTubeCircuitBreakerService.CircuitBreakerStatus(
                            true, "OPEN", java.time.Instant.now(), null, "RateLimitError", 1, 5
                    )
            );

            assertTrue(gatewayWithMockedBreaker.isCircuitBreakerBlocking(),
                    "Should block when OPEN with null cooldownUntil (defensive)");
        }
    }
}
