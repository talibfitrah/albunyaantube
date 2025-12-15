package com.albunyaan.tube.service;

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
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    @Nullable
    private final YouTubeThrottler throttler;

    @Nullable
    private final YouTubeCircuitBreaker circuitBreaker;

    @Autowired
    public YouTubeGateway(
            @Qualifier("newPipeYouTubeService") StreamingService youTubeService,
            @Value("${app.newpipe.executor.pool-size:3}") int poolSize,
            @Nullable YouTubeThrottler throttler,
            @Nullable YouTubeCircuitBreaker circuitBreaker) {
        this.youtube = youTubeService;
        this.executorService = Executors.newFixedThreadPool(poolSize);
        this.channelLinkHandlerFactory = YoutubeChannelLinkHandlerFactory.getInstance();
        this.playlistLinkHandlerFactory = YoutubePlaylistLinkHandlerFactory.getInstance();
        this.streamLinkHandlerFactory = YoutubeStreamLinkHandlerFactory.getInstance();
        this.throttler = throttler;
        this.circuitBreaker = circuitBreaker;

        logger.info("YouTubeGateway initialized with NewPipeExtractor (executor pool size: {}, throttler: {}, circuitBreaker: {})",
                poolSize, throttler != null ? "enabled" : "disabled", circuitBreaker != null ? "enabled" : "disabled");
        logger.info("Service: {}, ID: {}", youtube.getServiceInfo().getName(), youtube.getServiceId());
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

    // ==================== Rate Limiting Helpers ====================

    /**
     * Apply throttling before making a YouTube request.
     * Should be called before each external request.
     */
    private void applyThrottling() {
        if (throttler != null && throttler.isEnabled()) {
            throttler.throttle();
        }
    }

    /**
     * Check if circuit breaker allows requests.
     * Throws IOException if circuit is open.
     */
    private void checkCircuitBreaker() throws IOException {
        if (circuitBreaker != null && circuitBreaker.isOpen()) {
            long remainingMs = circuitBreaker.getRemainingCooldownMs();
            throw new IOException("YouTube circuit breaker is open. Remaining cooldown: " +
                    (remainingMs / 1000) + " seconds. Rate limiting detected - waiting for cooldown.");
        }
    }

    /**
     * Record a successful YouTube request.
     */
    private void recordSuccess() {
        if (circuitBreaker != null) {
            circuitBreaker.recordSuccess();
        }
    }

    /**
     * Record a failed YouTube request and check for rate limiting.
     */
    private void recordError(Exception e) {
        if (circuitBreaker != null && circuitBreaker.isRateLimitError(e)) {
            circuitBreaker.recordRateLimitError(e);
            logger.warn("Rate limit error detected, circuit breaker recording: {}", e.getMessage());
        }
    }

    // ==================== Search Operations ====================

    /**
     * Create a search extractor for the given query
     */
    public SearchExtractor createSearchExtractor(String query) throws ExtractionException {
        return youtube.getSearchExtractor(query);
    }

    // ==================== Channel Operations ====================

    /**
     * Fetch channel info by channel ID.
     * Applies throttling and circuit breaker protection.
     */
    public ChannelInfo fetchChannelInfo(String channelId) throws IOException, ExtractionException {
        checkCircuitBreaker();
        applyThrottling();

        try {
            // Use /channel/ format directly instead of link handler factory
            // The factory incorrectly generates /c/ URLs which return 404
            String url = buildChannelUrl(channelId);
            ChannelInfo result = ChannelInfo.getInfo(youtube, url);
            recordSuccess();
            return result;
        } catch (IOException | ExtractionException e) {
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
     */
    public ChannelTabExtractor createChannelTabExtractor(ListLinkHandler tab) throws ExtractionException {
        return youtube.getChannelTabExtractor(tab);
    }

    // ==================== Playlist Operations ====================

    /**
     * Fetch playlist info by playlist ID.
     * Applies throttling and circuit breaker protection.
     */
    public PlaylistInfo fetchPlaylistInfo(String playlistId) throws IOException, ExtractionException {
        checkCircuitBreaker();
        applyThrottling();

        try {
            String url = playlistLinkHandlerFactory.getUrl(playlistId);
            PlaylistInfo result = PlaylistInfo.getInfo(youtube, url);
            recordSuccess();
            return result;
        } catch (IOException | ExtractionException e) {
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
     * Get more items from a playlist page.
     * Applies throttling and circuit breaker protection.
     */
    public ListExtractor.InfoItemsPage<StreamInfoItem> getPlaylistMoreItems(String playlistId, Page page)
            throws IOException, ExtractionException {
        checkCircuitBreaker();
        applyThrottling();

        try {
            String url = playlistLinkHandlerFactory.getUrl(playlistId);
            ListExtractor.InfoItemsPage<StreamInfoItem> result = PlaylistInfo.getMoreItems(youtube, url, page);
            recordSuccess();
            return result;
        } catch (IOException | ExtractionException e) {
            recordError(e);
            throw e;
        }
    }

    // ==================== Video Operations ====================

    /**
     * Fetch stream info by video ID.
     * Applies throttling and circuit breaker protection.
     */
    public StreamInfo fetchStreamInfo(String videoId) throws IOException, ExtractionException {
        checkCircuitBreaker();
        applyThrottling();

        try {
            String url = streamLinkHandlerFactory.getUrl(videoId);
            StreamInfo result = StreamInfo.getInfo(youtube, url);
            recordSuccess();
            return result;
        } catch (IOException | ExtractionException e) {
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
}
