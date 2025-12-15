package com.albunyaan.tube.service;

import com.albunyaan.tube.config.YouTubeThrottleProperties;
import com.albunyaan.tube.exception.CircuitBreakerOpenException;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P2-T3: YouTube Gateway
 *
 * Low-level abstraction over NewPipeExtractor that shields callers from
 * library-specific details and version differences.
 *
 * This class:
 * - Manages the StreamingService and link handler factories
 * - Provides direct access to NewPipe objects (ChannelInfo, PlaylistInfo, etc.)
 * - Handles pagination encoding/decoding
 * - Manages the executor service for batch operations
 * - Implements request throttling to prevent YouTube rate limiting
 * - Integrates circuit breaker for rate-limit protection
 *
 * Does NOT:
 * - Apply caching (handled by orchestrators)
 * - Map to DTOs (handled by orchestrators)
 * - Apply business logic
 */
@Component
public class YouTubeGateway {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeGateway.class);
    private final ExecutorService executorService;

    private final StreamingService youtube;
    private final YoutubeChannelLinkHandlerFactory channelLinkHandlerFactory;
    private final YoutubePlaylistLinkHandlerFactory playlistLinkHandlerFactory;
    private final YoutubeStreamLinkHandlerFactory streamLinkHandlerFactory;
    private final YouTubeThrottleProperties throttleProperties;
    private final YouTubeCircuitBreakerService circuitBreakerService;

    // Track last request time for throttling
    private final AtomicLong lastRequestTimeMs = new AtomicLong(0);
    private final Object throttleLock = new Object();

    @Autowired
    public YouTubeGateway(
            @Qualifier("newPipeYouTubeService") StreamingService youTubeService,
            @Value("${app.newpipe.executor.pool-size:1}") int poolSize,
            YouTubeThrottleProperties throttleProperties,
            YouTubeCircuitBreakerService circuitBreakerService) {
        this.youtube = youTubeService;
        this.executorService = Executors.newFixedThreadPool(poolSize);
        this.channelLinkHandlerFactory = YoutubeChannelLinkHandlerFactory.getInstance();
        this.playlistLinkHandlerFactory = YoutubePlaylistLinkHandlerFactory.getInstance();
        this.streamLinkHandlerFactory = YoutubeStreamLinkHandlerFactory.getInstance();
        this.throttleProperties = throttleProperties;
        this.circuitBreakerService = circuitBreakerService;

        logger.info("YouTubeGateway initialized with NewPipeExtractor (executor pool size: {})", poolSize);
        logger.info("Throttling enabled: {}, delay: {}ms, jitter: {}ms",
                throttleProperties.isEnabled(),
                throttleProperties.getDelayBetweenItemsMs(),
                throttleProperties.getJitterMs());
        logger.info("Circuit breaker status: {}", circuitBreakerService.getStatus());
        logger.info("Service: {}, ID: {}", youtube.getServiceInfo().getName(), youtube.getServiceId());
    }

    /**
     * Check circuit breaker and apply throttle delay before making a YouTube request.
     * Uses synchronized block to ensure only one request is made at a time
     * and delay is applied between requests.
     *
     * @throws CircuitBreakerOpenException if the circuit breaker is open
     */
    private void checkCircuitBreakerAndThrottle() throws CircuitBreakerOpenException {
        // Check circuit breaker first - use tryAllowRequest to get status in single call
        YouTubeCircuitBreakerService.AllowRequestResult result = circuitBreakerService.tryAllowRequest();
        if (!result.allowed()) {
            throw new CircuitBreakerOpenException(
                    "Circuit breaker is open due to YouTube rate limiting. Cooldown until: " +
                    (result.status().cooldownUntil() != null ? result.status().cooldownUntil() : "unknown"),
                    result.status()
            );
        }

        // Then apply throttle
        if (!throttleProperties.isEnabled()) {
            return;
        }

        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            long lastRequest = lastRequestTimeMs.get();
            long delay = throttleProperties.calculateDelayWithJitter();

            if (lastRequest > 0 && delay > 0) {
                long timeSinceLastRequest = now - lastRequest;
                long remainingDelay = delay - timeSinceLastRequest;

                if (remainingDelay > 0) {
                    try {
                        logger.debug("Throttling YouTube request: waiting {}ms", remainingDelay);
                        Thread.sleep(remainingDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("Throttle delay interrupted");
                    }
                }
            }

            lastRequestTimeMs.set(System.currentTimeMillis());
        }
    }

    /**
     * Check if circuit breaker is currently blocking requests.
     * Use this before starting a batch to fail fast without attempting individual requests.
     *
     * Note: This method checks if cooldown has expired for OPEN state. If cooldown has
     * expired, returns false (not blocking) so batch operations can trigger recovery
     * via the normal tryAllowRequest() flow which handles OPEN→HALF_OPEN transition.
     *
     * @return true if breaker is definitely blocking requests (OPEN with active cooldown, or UNKNOWN)
     */
    public boolean isCircuitBreakerBlocking() {
        YouTubeCircuitBreakerService.CircuitBreakerStatus status = circuitBreakerService.getStatus();

        if ("UNKNOWN".equals(status.state())) {
            // Fail-safe: unknown state means we can't determine, block to be safe
            return true;
        }

        if ("OPEN".equals(status.state())) {
            // OPEN state: check if cooldown has expired
            if (status.cooldownUntil() != null) {
                java.time.Instant now = java.time.Instant.now();
                if (now.isAfter(status.cooldownUntil())) {
                    // Cooldown expired - not blocking, allow batch to proceed
                    // Individual requests will trigger OPEN→HALF_OPEN via tryAllowRequest()
                    return false;
                }
            }
            // Cooldown still active, definitely blocking
            return true;
        }

        // CLOSED or HALF_OPEN: not blocking
        // Note: HALF_OPEN allows exactly one probe request via tryAllowRequest()
        return false;
    }

    /**
     * Record a successful YouTube request with the circuit breaker.
     */
    private void recordSuccess() {
        circuitBreakerService.recordSuccess();
    }

    /**
     * Record an error with the circuit breaker if it's a rate-limit error.
     */
    private void recordError(Exception e) {
        circuitBreakerService.recordRateLimitError(e);
    }

    /**
     * Shutdown the executor service when the bean is destroyed
     */
    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down YouTubeGateway executor service...");
        executorService.shutdown();

        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Executor service did not terminate in time, forcing shutdown...");
                executorService.shutdownNow();

                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("Executor service did not terminate even after forced shutdown");
                }
            } else {
                logger.info("Executor service shut down successfully");
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for executor service shutdown", e);
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
    }

    // ==================== Search Operations ====================

    /**
     * Create a search extractor for the given query
     *
     * @throws CircuitBreakerOpenException if circuit breaker is open
     */
    public SearchExtractor createSearchExtractor(String query) throws ExtractionException, CircuitBreakerOpenException {
        checkCircuitBreakerAndThrottle();
        try {
            SearchExtractor extractor = youtube.getSearchExtractor(query);
            recordSuccess();
            return extractor;
        } catch (Exception e) {
            recordError(e);
            throw e;
        }
    }

    // ==================== Channel Operations ====================

    /**
     * Fetch channel info by channel ID
     *
     * @throws CircuitBreakerOpenException if circuit breaker is open
     */
    public ChannelInfo fetchChannelInfo(String channelId) throws IOException, ExtractionException, CircuitBreakerOpenException {
        checkCircuitBreakerAndThrottle();
        // Use /channel/ format directly instead of link handler factory
        // The factory incorrectly generates /c/ URLs which return 404
        String url = buildChannelUrl(channelId);
        try {
            ChannelInfo info = ChannelInfo.getInfo(youtube, url);
            recordSuccess();
            return info;
        } catch (Exception e) {
            recordError(e);
            throw e;
        }
    }

    /**
     * Get channel URL from channel ID
     *
     * Note: We use /channel/ format directly because NewPipeExtractor's
     * YoutubeChannelLinkHandlerFactory.getUrl() incorrectly generates /c/ URLs
     * for channel IDs (UCxxxx), which YouTube's API rejects with 404.
     */
    public String getChannelUrl(String channelId) throws ExtractionException {
        return buildChannelUrl(channelId);
    }

    /**
     * Build channel URL using the correct format.
     * Channel IDs (starting with UC) must use /channel/ format.
     * Custom URLs (handles) use /c/ or /@.
     */
    private String buildChannelUrl(String channelId) {
        // Channel IDs always start with UC
        if (channelId != null && !channelId.isEmpty() && channelId.startsWith("UC")) {
            return "https://www.youtube.com/channel/" + channelId;
        }
        // Fall back to factory for other formats (handles, custom URLs)
        try {
            return channelLinkHandlerFactory.getUrl(channelId);
        } catch (ParsingException | IllegalArgumentException e) {
            // Factory failed to parse - fall back to /channel/ format
            logger.debug("Link handler factory failed for channelId '{}': {}", channelId, e.getMessage());
            return "https://www.youtube.com/channel/" + (channelId != null ? channelId : "");
        }
    }

    /**
     * Create a channel tab extractor for the given tab
     *
     * @throws CircuitBreakerOpenException if circuit breaker is open
     */
    public ChannelTabExtractor createChannelTabExtractor(ListLinkHandler tab) throws ExtractionException, CircuitBreakerOpenException {
        checkCircuitBreakerAndThrottle();
        try {
            ChannelTabExtractor extractor = youtube.getChannelTabExtractor(tab);
            recordSuccess();
            return extractor;
        } catch (Exception e) {
            recordError(e);
            throw e;
        }
    }

    // ==================== Playlist Operations ====================

    /**
     * Fetch playlist info by playlist ID
     *
     * @throws CircuitBreakerOpenException if circuit breaker is open
     */
    public PlaylistInfo fetchPlaylistInfo(String playlistId) throws IOException, ExtractionException, CircuitBreakerOpenException {
        checkCircuitBreakerAndThrottle();
        String url = playlistLinkHandlerFactory.getUrl(playlistId);
        try {
            PlaylistInfo info = PlaylistInfo.getInfo(youtube, url);
            recordSuccess();
            return info;
        } catch (Exception e) {
            recordError(e);
            throw e;
        }
    }

    /**
     * Get playlist URL from playlist ID
     */
    public String getPlaylistUrl(String playlistId) throws ExtractionException {
        return playlistLinkHandlerFactory.getUrl(playlistId);
    }

    /**
     * Get more items from a playlist page
     *
     * @throws CircuitBreakerOpenException if circuit breaker is open
     */
    public ListExtractor.InfoItemsPage<StreamInfoItem> getPlaylistMoreItems(String playlistId, Page page)
            throws IOException, ExtractionException, CircuitBreakerOpenException {
        checkCircuitBreakerAndThrottle();
        String url = playlistLinkHandlerFactory.getUrl(playlistId);
        try {
            ListExtractor.InfoItemsPage<StreamInfoItem> items = PlaylistInfo.getMoreItems(youtube, url, page);
            recordSuccess();
            return items;
        } catch (Exception e) {
            recordError(e);
            throw e;
        }
    }

    // ==================== Video Operations ====================

    /**
     * Fetch stream info by video ID
     *
     * @throws CircuitBreakerOpenException if circuit breaker is open
     */
    public StreamInfo fetchStreamInfo(String videoId) throws IOException, ExtractionException, CircuitBreakerOpenException {
        checkCircuitBreakerAndThrottle();
        String url = streamLinkHandlerFactory.getUrl(videoId);
        try {
            StreamInfo info = StreamInfo.getInfo(youtube, url);
            recordSuccess();
            return info;
        } catch (Exception e) {
            recordError(e);
            throw e;
        }
    }

    /**
     * Get video URL from video ID
     */
    public String getVideoUrl(String videoId) throws ExtractionException {
        return streamLinkHandlerFactory.getUrl(videoId);
    }

    // ==================== Pagination Helpers ====================

    /**
     * Encode NewPipe Page object to string token
     */
    public String encodePageToken(Page page) {
        if (page == null) {
            return null;
        }
        try {
            return page.getUrl();
        } catch (Exception e) {
            logger.warn("Failed to encode page token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Decode string token to NewPipe Page object
     */
    public Page decodePageToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            return new Page(token);
        } catch (Exception e) {
            logger.warn("Failed to decode page token: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Batch Operations ====================

    /**
     * Get the executor service for batch operations
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Run an async operation using the shared executor
     */
    public CompletableFuture<Void> runAsync(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, executorService);
    }

    // ==================== Status (for monitoring) ====================

    /**
     * Get throttle configuration status for monitoring/debugging.
     */
    public ThrottleStatus getThrottleStatus() {
        return new ThrottleStatus(
                throttleProperties.isEnabled(),
                throttleProperties.getDelayBetweenItemsMs(),
                throttleProperties.getJitterMs(),
                lastRequestTimeMs.get()
        );
    }

    /**
     * Get circuit breaker status for monitoring/debugging.
     */
    public YouTubeCircuitBreakerService.CircuitBreakerStatus getCircuitBreakerStatus() {
        return circuitBreakerService.getStatus();
    }

    /**
     * Check if a rate-limit error is detected.
     * Useful for callers to determine error handling strategy.
     */
    public boolean isRateLimitError(Exception e) {
        return circuitBreakerService.isRateLimitError(e);
    }

    /**
     * Throttle status for monitoring.
     */
    public record ThrottleStatus(
            boolean enabled,
            long delayMs,
            long jitterMs,
            long lastRequestTimeMs
    ) {}
}
