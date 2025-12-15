package com.albunyaan.tube.service;

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
}
