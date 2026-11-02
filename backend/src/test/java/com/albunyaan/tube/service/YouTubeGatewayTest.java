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
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

        // ── SSRF rejection tests for the pageToken decoder ──
        // The decoder is the user-supplied URL surface that dispatches
        // outbound HTTP via NewPipe — without these tests a maintainer
        // could weaken the whitelist/denylist and ship the regression.

        @Test
        @DisplayName("Rejects pageToken with http scheme (only https allowed)")
        void rejectsHttpScheme() {
            assertNull(gateway.decodePageToken("http://www.youtube.com/results?q=cats"));
        }

        @Test
        @DisplayName("Rejects pageToken on non-YouTube host")
        void rejectsNonYouTubeHost() {
            assertNull(gateway.decodePageToken("https://attacker.example.com/results"));
            assertNull(gateway.decodePageToken("https://internal/admin"));
            // userinfo trick should resolve via getHost() to the actual host
            assertNull(gateway.decodePageToken("https://www.youtube.com@attacker.com/x"));
        }

        @Test
        @DisplayName("Rejects pageToken on /redirect helper path (open-redirect SSRF surface)")
        void rejectsRedirectHelperPath() {
            assertNull(gateway.decodePageToken("https://www.youtube.com/redirect?q=http://internal/"));
            assertNull(gateway.decodePageToken("https://youtube.com/redirect"));
        }

        @Test
        @DisplayName("Rejects pageToken on /url helper path")
        void rejectsUrlHelperPath() {
            assertNull(gateway.decodePageToken("https://www.youtube.com/url?q=http://internal/"));
        }

        @Test
        @DisplayName("Rejects pageToken on /oembed helper path")
        void rejectsOembedPath() {
            assertNull(gateway.decodePageToken("https://www.youtube.com/oembed?url=http://internal/"));
        }

        @Test
        @DisplayName("Rejects pageToken on /attribution_link (codex round-3 redirect-chain)")
        void rejectsAttributionLinkPath() {
            assertNull(gateway.decodePageToken(
                "https://www.youtube.com/attribution_link?u=%2Fredirect%3Fq%3Dhttp%3A%2F%2F169.254.169.254%2F"));
        }

        @Test
        @DisplayName("Rejects pageToken with non-http(s) scheme")
        void rejectsExoticSchemes() {
            assertNull(gateway.decodePageToken("file:///etc/passwd"));
            assertNull(gateway.decodePageToken("javascript:alert(1)"));
            assertNull(gateway.decodePageToken("gopher://internal/"));
            assertNull(gateway.decodePageToken("data:text/plain,hello"));
        }

        @Test
        @DisplayName("Rejects googlevideo.com (no longer in pagination allowlist)")
        void rejectsGooglevideoHost() {
            assertNull(gateway.decodePageToken("https://r1---sn-x.googlevideo.com/videoplayback?..."));
        }

        @Test
        @DisplayName("Accepts legitimate YouTube InnerTube pagination URL")
        void acceptsInnerTubePaginationUrl() {
            Page decoded = gateway.decodePageToken(
                "https://www.youtube.com/youtubei/v1/search?prettyPrint=false&key=ABC");
            assertNotNull(decoded);
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
        @DisplayName("extractChannelId with NewPipe's real factory: /channel/UC… gives the bare UC id, a handle stays")
        void extractChannelId_realFactory_returnsBareUcIdOrHandle() {
            assertEquals("UCROKYPep-UuODNwyipe6JMw",
                    gateway.extractChannelId("https://www.youtube.com/channel/UCROKYPep-UuODNwyipe6JMw"));
            assertEquals("@ZadTVchannel", gateway.extractChannelId("https://www.youtube.com/@ZadTVchannel"));
            assertNull(gateway.extractChannelId("https://example.com/channel/UCROKYPep-UuODNwyipe6JMw"));
        }

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

            // A probe in flight: another request holds the permit. (This test used to get there by letting the stub
            // downloader's NullPointerException escape fetchChannelInfo, which leaked the permit; a failed probe
            // now releases it and reopens the circuit, see otherGatewayCalls_neverLeaveTheHalfOpenProbeHeld.)
            assertTrue(circuitBreaker.allowProbe(), "the in-flight probe takes the permit");
            assertTrue(circuitBreaker.isProbeRequest(), "probe permit is held");

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
            assertInstanceOf(YouTubeGateway.CircuitOpenException.class, thrown);

            assertTrue(thrown.getMessage().contains("circuit breaker is open"),
                    "Error message should say circuit is open. Got: " + thrown.getMessage());
            assertTrue(thrown.getMessage().contains("Remaining cooldown"),
                    "Error message should include remaining cooldown. Got: " + thrown.getMessage());
            assertFalse(thrown.getMessage().contains("HALF_OPEN"),
                    "OPEN state should NOT mention HALF_OPEN. Got: " + thrown.getMessage());

            // Clean up
            testGateway.shutdown();
        }

        /** Slice C M2: a caller that records "if (isRateLimitError(e))" on a refusal would restart the cooldown forever. */
        @Test
        @DisplayName("The OPEN refusal is not a rate-limit signal")
        void openRefusal_isNotARateLimitError() {
            YouTubeCircuitBreaker circuitBreaker = new YouTubeCircuitBreaker(createTestProperties(), null);
            YouTubeGateway testGateway = new YouTubeGateway(ServiceList.YouTube, 3, null, circuitBreaker);
            circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
            try {
                IOException refusal = assertThrows(IOException.class, () -> testGateway.fetchChannelInfo("UCTest"));
                assertInstanceOf(YouTubeGateway.CircuitOpenException.class, refusal);
                assertFalse(circuitBreaker.isRateLimitError(refusal), "refusal read as a rate limit: " + refusal.getMessage());
            } finally {
                testGateway.shutdown();
            }
        }

        @Test
        @DisplayName("A RuntimeException from a tab call during the HALF_OPEN probe releases the probe")
        void tabCallRuntimeException_releasesHalfOpenProbe() throws Exception {
            ChannelTabExtractor tab = mock(ChannelTabExtractor.class);
            doThrow(new IllegalStateException("parse bug")).when(tab).fetchPage();
            when(tab.getPage(any())).thenThrow(new IllegalStateException("parse bug"));

            for (boolean initialPage : new boolean[] {true, false}) {
                YouTubeCircuitBreaker circuitBreaker = new YouTubeCircuitBreaker(createTestProperties(), null);
                circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
                circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
                YouTubeGateway testGateway = new YouTubeGateway(ServiceList.YouTube, 1, null, circuitBreaker);
                try {
                    assertThrows(IllegalStateException.class, () -> {
                        if (initialPage) {
                            testGateway.fetchTabPage(tab);
                        } else {
                            testGateway.getTabPage(tab, null);
                        }
                    });
                    assertFalse(circuitBreaker.isProbeRequest(), "the probe permit must be released");
                    assertEquals(YouTubeCircuitBreaker.State.OPEN, circuitBreaker.getCurrentState());
                } finally {
                    testGateway.shutdown();
                }
            }
        }

        /**
         * The other gateway calls during a HALF_OPEN probe. NewPipe's static calls hit this class's stub downloader,
         * whose null Response makes NewPipe throw a NullPointerException; search takes a throwing mock extractor.
         * Search never takes the probe (no breaker check) and fetchStreamInfo wraps any failure in an IOException
         * (executeWithProbeTimeout), so only the three static channel/playlist calls could leak it.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"fetchSearchPage", "getSearchPage", "fetchChannelInfo", "fetchPlaylistInfo",
                "getPlaylistMoreItems", "fetchStreamInfo"})
        void otherGatewayCalls_neverLeaveTheHalfOpenProbeHeld(String method) {
            YouTubeCircuitBreaker circuitBreaker = new YouTubeCircuitBreaker(createTestProperties(), null);
            circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
            circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
            YouTubeGateway g = new YouTubeGateway(ServiceList.YouTube, 1, null, circuitBreaker);
            String playlistId = "PLAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
            Page page = new Page("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", "token");
            Executable call;
            Class<? extends Exception> expected = RuntimeException.class;
            try {
                switch (method) {
                    case "fetchSearchPage" -> {
                        SearchExtractor search = mock(SearchExtractor.class);
                        doThrow(new IllegalStateException("parse bug")).when(search).fetchPage();
                        call = () -> g.fetchSearchPage(search);
                    }
                    case "getSearchPage" -> {
                        SearchExtractor search = mock(SearchExtractor.class);
                        when(search.getPage(any())).thenThrow(new IllegalStateException("parse bug"));
                        call = () -> g.getSearchPage(search, page);
                    }
                    case "fetchChannelInfo" -> call = () -> g.fetchChannelInfo("UCOll3M-P7oKs5cSrQ9ytt6g");
                    case "fetchPlaylistInfo" -> call = () -> g.fetchPlaylistInfo(playlistId);
                    case "getPlaylistMoreItems" -> call = () -> g.getPlaylistMoreItems(playlistId, page);
                    case "fetchStreamInfo" -> {
                        call = () -> g.fetchStreamInfo("eaOtcUNHDB8");
                        expected = IOException.class;   // "Probe request failed: …" wraps the NullPointerException
                    }
                    default -> throw new IllegalArgumentException(method);
                }

                assertThrows(expected, call);
                assertFalse(circuitBreaker.isProbeRequest(), method + " left the HALF_OPEN probe held");
                if (!method.endsWith("SearchPage")) {   // the probe-taking calls: a failed probe reopens the circuit
                    assertEquals(YouTubeCircuitBreaker.State.OPEN, circuitBreaker.getCurrentState(), method);
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                g.shutdown();
            }
        }

        /**
         * An Error (a NoSuchMethodError after a NewPipe bump, say) skips recordError's catch. The probe must still be
         * released, or HALF_OPEN blocks every breaker-checked call until a restart. fetchStreamInfo runs the NewPipe
         * call on the probe executor, which already turns the Error into an IOException.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"fetchChannelInfo", "fetchTabPage", "getTabPage", "fetchPlaylistInfo",
                "getPlaylistMoreItems", "fetchStreamInfo"})
        void aProbeThatThrowsAnError_releasesTheProbe(String method) throws Exception {
            NoSuchMethodError linkage = new NoSuchMethodError("NewPipe linkage");
            StreamingService youtube = mock(StreamingService.class, RETURNS_DEEP_STUBS);
            ChannelTabExtractor tab = mock(ChannelTabExtractor.class);
            YouTubeCircuitBreaker circuitBreaker = new YouTubeCircuitBreaker(createTestProperties(), null);
            circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
            circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
            YouTubeGateway g = new YouTubeGateway(youtube, 1, null, circuitBreaker);
            String playlistId = "PLAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
            Page page = new Page("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", "token");
            Executable call;
            Class<? extends Throwable> expected = NoSuchMethodError.class;
            try {
                switch (method) {
                    case "fetchChannelInfo" -> {
                        when(youtube.getChannelExtractor(anyString())).thenThrow(linkage);
                        call = () -> g.fetchChannelInfo("UCOll3M-P7oKs5cSrQ9ytt6g");
                    }
                    case "fetchTabPage" -> {
                        doThrow(linkage).when(tab).fetchPage();
                        call = () -> g.fetchTabPage(tab);
                    }
                    case "getTabPage" -> {
                        when(tab.getPage(any())).thenThrow(linkage);
                        call = () -> g.getTabPage(tab, page);
                    }
                    case "fetchPlaylistInfo" -> {
                        when(youtube.getPlaylistExtractor(anyString())).thenThrow(linkage);
                        call = () -> g.fetchPlaylistInfo(playlistId);
                    }
                    case "getPlaylistMoreItems" -> {
                        when(youtube.getPlaylistExtractor(anyString())).thenThrow(linkage);
                        call = () -> g.getPlaylistMoreItems(playlistId, page);
                    }
                    case "fetchStreamInfo" -> {
                        when(youtube.getStreamExtractor(anyString())).thenThrow(linkage);
                        call = () -> g.fetchStreamInfo("eaOtcUNHDB8");
                        expected = IOException.class;   // "Probe request failed: …" wraps the Error
                    }
                    default -> throw new IllegalArgumentException(method);
                }

                assertThrows(expected, call);
                assertFalse(circuitBreaker.isProbeRequest(), method + " left the HALF_OPEN probe held");
                assertEquals(YouTubeCircuitBreaker.State.OPEN, circuitBreaker.getCurrentState(), method);
            } finally {
                g.shutdown();
            }
        }

        /**
         * B17 (breaker-investigation option a1): YouTube refuses this IP's /player requests while its browse reads
         * keep working, so the bot check must not pause browse. Every other rate limit still counts.
         */
        @Test
        @DisplayName("B17: a /player bot check never reaches the shared breaker; a ReCaptcha (HTTP 429) still opens it")
        void playerBotCheck_isNotRecorded_reCaptchaStillIs() throws Exception {
            StreamingService youtube = mock(StreamingService.class, RETURNS_DEEP_STUBS);
            when(youtube.getStreamExtractor(anyString()))
                    .thenThrow(new SignInConfirmNotBotException("YouTube probably temporarily blocked anonymous watch "
                            + "access with this IP , got error LOGIN_REQUIRED: \"Sign in to confirm you're not a bot\""))
                    .thenThrow(new ReCaptchaException("reCaptcha Challenge requested",
                            "https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch"));
            YouTubeCircuitBreaker circuitBreaker = new YouTubeCircuitBreaker(createTestProperties(), null); // threshold 1
            YouTubeGateway g = new YouTubeGateway(youtube, 1, null, circuitBreaker);
            Instant t0 = Instant.parse("2026-10-27T06:00:00Z");
            g.clock = Clock.fixed(t0, ZoneOffset.UTC);
            try {
                assertThrows(SignInConfirmNotBotException.class, () -> g.fetchStreamInfo("eaOtcUNHDB8"));
                assertEquals(0, circuitBreaker.getStatusSnapshot().getTotalRateLimitErrors());
                assertEquals(YouTubeCircuitBreaker.State.CLOSED, circuitBreaker.getCurrentState());

                g.clock = Clock.fixed(t0.plus(Duration.ofMinutes(60)), ZoneOffset.UTC);   // B19: past the /player window
                assertThrows(ReCaptchaException.class, () -> g.fetchStreamInfo("eaOtcUNHDB8"));
                assertEquals(1, circuitBreaker.getStatusSnapshot().getTotalRateLimitErrors());
                assertEquals(YouTubeCircuitBreaker.State.OPEN, circuitBreaker.getCurrentState());
            } finally {
                g.shutdown();
            }
        }

        private static SignInConfirmNotBotException botCheck() {
            return new SignInConfirmNotBotException("YouTube probably temporarily blocked anonymous watch access with "
                    + "this IP , got error LOGIN_REQUIRED: \"Sign in to confirm you're not a bot\"");
        }

        /**
         * Stage 9 B19 (r6 P2-1): callers without a stop of their own (bulk preview/submit, the Reports page's lookups)
         * sent one refused /player request per row. After a bot check every /player call for 60 min fails at once,
         * as the same exception, so each caller's bot-check stop still fires, and sends nothing. Browse still goes out.
         */
        @Test
        @DisplayName("B19: a /player bot check refuses /player locally for 60 min without a request; browse still goes out")
        void playerBotCheck_refusesPlayerLocallyFor60Minutes() throws Exception {
            StreamingService youtube = mock(StreamingService.class, RETURNS_DEEP_STUBS);
            when(youtube.getStreamExtractor(anyString())).thenThrow(botCheck());
            when(youtube.getChannelExtractor(anyString())).thenThrow(new ParsingException("browse sent"));
            YouTubeCircuitBreaker circuitBreaker = new YouTubeCircuitBreaker(createTestProperties(), null);
            YouTubeGateway g = new YouTubeGateway(youtube, 1, null, circuitBreaker);
            Instant t0 = Instant.parse("2026-10-27T06:00:00Z");
            g.clock = Clock.fixed(t0, ZoneOffset.UTC);
            try {
                assertThrows(SignInConfirmNotBotException.class, () -> g.fetchStreamInfo("eaOtcUNHDB8"));
                for (int i = 0; i < 20; i++) {   // a Reports page's 20 lookups
                    SignInConfirmNotBotException local =
                            assertThrows(SignInConfirmNotBotException.class, () -> g.fetchStreamInfo("eaOtcUNHDB8"));
                    assertEquals(SignInConfirmNotBotException.class, local.getClass(), "the breaker's class-name check");
                    assertTrue(circuitBreaker.isRateLimitError(local), "the callers' bot-check stops read it as one");
                }
                g.clock = Clock.fixed(t0.plus(Duration.ofMinutes(60)).minusMillis(1), ZoneOffset.UTC);
                assertThrows(SignInConfirmNotBotException.class, () -> g.fetchStreamInfo("eaOtcUNHDB8"));
                verify(youtube, times(1)).getStreamExtractor(anyString());

                ParsingException browse =
                        assertThrows(ParsingException.class, () -> g.fetchChannelInfo("UCOll3M-P7oKs5cSrQ9ytt6g"));
                assertEquals("browse sent", browse.getMessage(), "browse is never refused by the /player window");

                g.clock = Clock.fixed(t0.plus(Duration.ofMinutes(60)), ZoneOffset.UTC);
                assertThrows(SignInConfirmNotBotException.class, () -> g.fetchStreamInfo("eaOtcUNHDB8"));
                verify(youtube, times(2)).getStreamExtractor(anyString());
                assertEquals(0, circuitBreaker.getStatusSnapshot().getTotalRateLimitErrors());
                assertEquals(YouTubeCircuitBreaker.State.CLOSED, circuitBreaker.getCurrentState());
            } finally {
                g.shutdown();
            }
        }

        /** B19: the local refusal comes before the shared breaker's permit, so it never takes a HALF_OPEN probe. */
        @Test
        @DisplayName("B19: a /player call refused by the window leaves the HALF_OPEN probe permit free")
        void playerRefusalWindow_neverTakesTheHalfOpenProbe() throws Exception {
            StreamingService youtube = mock(StreamingService.class, RETURNS_DEEP_STUBS);
            when(youtube.getStreamExtractor(anyString())).thenThrow(botCheck());
            YouTubeCircuitBreaker circuitBreaker = new YouTubeCircuitBreaker(createTestProperties(), null);
            YouTubeGateway g = new YouTubeGateway(youtube, 1, null, circuitBreaker);
            g.clock = Clock.fixed(Instant.parse("2026-10-27T06:00:00Z"), ZoneOffset.UTC);
            try {
                assertThrows(SignInConfirmNotBotException.class, () -> g.fetchStreamInfo("eaOtcUNHDB8"));   // CLOSED
                circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));   // a browse 429 opens it
                circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);   // its cooldown ended
                int level = circuitBreaker.getStatusSnapshot().getBackoffLevel();

                assertThrows(SignInConfirmNotBotException.class, () -> g.fetchStreamInfo("eaOtcUNHDB8"));

                verify(youtube, times(1)).getStreamExtractor(anyString());
                assertEquals(YouTubeCircuitBreaker.State.HALF_OPEN, circuitBreaker.getCurrentState());
                assertFalse(circuitBreaker.isOpen(), "the probe permit is still free for a browse read");
                assertEquals(level, circuitBreaker.getStatusSnapshot().getBackoffLevel());
            } finally {
                g.shutdown();
            }
        }

        /**
         * Stage 9 B19 (r6 P3-1): a bot check on the HALF_OPEN probe says nothing about browse. It gives the permit back
         * and leaves the breaker HALF_OPEN at its level, so the next request (a browse read, as /player is refused
         * locally now) probes again, instead of browse being paused for another cooldown.
         */
        @Test
        @DisplayName("B19: a bot check on the HALF_OPEN probe releases it; the breaker stays HALF_OPEN at its level")
        void playerBotCheckOnTheHalfOpenProbe_releasesItAndKeepsHalfOpen() throws Exception {
            StreamingService youtube = mock(StreamingService.class, RETURNS_DEEP_STUBS);
            when(youtube.getStreamExtractor(anyString())).thenThrow(botCheck());
            YouTubeCircuitBreaker circuitBreaker = new YouTubeCircuitBreaker(createTestProperties(), null);
            circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
            circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
            YouTubeCircuitBreaker.CircuitBreakerStatus before = circuitBreaker.getStatusSnapshot();
            YouTubeGateway g = new YouTubeGateway(youtube, 1, null, circuitBreaker);
            try {
                assertThrows(SignInConfirmNotBotException.class, () -> g.fetchStreamInfo("eaOtcUNHDB8"));   // the probe

                verify(youtube, times(1)).getStreamExtractor(anyString());
                assertEquals(YouTubeCircuitBreaker.State.HALF_OPEN, circuitBreaker.getCurrentState());
                assertFalse(circuitBreaker.isOpen(), "the permit is released: the next request probes");
                YouTubeCircuitBreaker.CircuitBreakerStatus after = circuitBreaker.getStatusSnapshot();
                assertEquals(before.getBackoffLevel(), after.getBackoffLevel());
                assertEquals(before.getCooldownUntil(), after.getCooldownUntil());
                assertEquals(before.getTotalRateLimitErrors(), after.getTotalRateLimitErrors());
            } finally {
                g.shutdown();
            }
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
