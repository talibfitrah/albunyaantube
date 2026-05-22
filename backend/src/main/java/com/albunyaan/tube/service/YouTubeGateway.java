package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.PreviewErrorCode;
import com.albunyaan.tube.dto.registry.PreviewMetadata;
import com.albunyaan.tube.model.VideoType;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException;
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

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
            @Qualifier("newPipeYouTubeService") StreamingService youtubeService,
            @Value("${app.newpipe.executor.pool-size:3}") int poolSize,
            @Nullable YouTubeThrottler throttler,
            @Nullable YouTubeCircuitBreaker circuitBreaker) {
        this.youtube = youtubeService;
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
     * Throws IOException if circuit is open or probe already in progress.
     *
     * When in HALF_OPEN state, only ONE request is allowed to proceed as the probe.
     * This method atomically acquires the probe permit to ensure single-probe semantics.
     *
     * Logic flow:
     * 1. Call isOpen() first - handles OPEN state cooldown and transitions OPEN→HALF_OPEN
     * 2. If isOpen() returns true:
     *    - If state is HALF_OPEN, it means a probe is already in progress → specific message
     *    - Otherwise, state is OPEN with cooldown remaining → generic message
     * 3. If isOpen() returns false:
     *    - State is CLOSED → proceed
     *    - State is HALF_OPEN with no probe yet → call allowProbe() to acquire permit
     */
    private void checkCircuitBreaker() throws IOException {
        if (circuitBreaker == null) {
            return;
        }

        // Step 1: Check if circuit is blocking (also triggers OPEN→HALF_OPEN transition if cooldown expired)
        if (circuitBreaker.isOpen()) {
            // isOpen() returned true - either OPEN with cooldown or HALF_OPEN with probe in progress
            YouTubeCircuitBreaker.State currentState = circuitBreaker.getCurrentState();
            if (currentState == YouTubeCircuitBreaker.State.HALF_OPEN) {
                // HALF_OPEN and isOpen() returned true means probeInProgress is true
                throw new IOException("YouTube circuit breaker is in HALF_OPEN state and probe already in progress. " +
                        "Waiting for probe result before allowing more requests.");
            } else {
                // OPEN state with cooldown remaining
                long remainingMs = circuitBreaker.getRemainingCooldownMs();
                throw new IOException("YouTube circuit breaker is open. Remaining cooldown: " +
                        (remainingMs / 1000) + " seconds. Rate limiting detected - waiting for cooldown.");
            }
        }

        // Step 2: isOpen() returned false - either CLOSED or HALF_OPEN with no probe yet
        // Try to acquire probe permit (no-op if CLOSED, acquires permit if HALF_OPEN)
        if (!circuitBreaker.allowProbe()) {
            // This can only happen in a race condition: another thread acquired the permit
            // between our isOpen() check and this allowProbe() call
            throw new IOException("YouTube circuit breaker is in HALF_OPEN state and probe already in progress. " +
                    "Waiting for probe result before allowing more requests.");
        }

        // Proceed with request - either CLOSED or we're the probe request
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
     * If this is a probe request (HALF_OPEN state), ensures the probe permit is cleared
     * even for non-rate-limit errors to prevent the circuit from getting stuck.
     */
    private void recordError(Exception e) {
        if (circuitBreaker == null) {
            return;
        }

        if (circuitBreaker.isRateLimitError(e)) {
            // Rate limit error - record it (will increase backoff if in HALF_OPEN)
            circuitBreaker.recordRateLimitError(e);
            logger.warn("Rate limit error detected, circuit breaker recording: {}", e.getMessage());
        } else if (circuitBreaker.isProbeRequest()) {
            // Non-rate-limit error during probe - must clear probe permit to avoid stuck state
            circuitBreaker.recordProbeFailure(e);
            logger.warn("Probe failed with non-rate-limit error: {}", e.getMessage());
        }
        // For non-rate-limit errors outside of probe: no action needed
    }

    /**
     * Execute an operation with probe timeout if this is a probe request.
     * Probe requests (during HALF_OPEN state) use a shorter timeout to quickly
     * determine if YouTube is responsive again.
     */
    private <T> T executeWithProbeTimeout(Callable<T> operation) throws IOException, ExtractionException {
        // Check if this is a probe request
        boolean isProbe = circuitBreaker != null && circuitBreaker.isProbeRequest();

        if (!isProbe) {
            // Not a probe - execute normally
            try {
                return operation.call();
            } catch (IOException | ExtractionException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Unexpected error during YouTube operation", e);
            }
        }

        // Probe request - apply timeout
        int timeoutSeconds = circuitBreaker.getProbeTimeoutSeconds();
        logger.debug("Executing probe request with {}s timeout", timeoutSeconds);

        Future<T> future = executorService.submit(operation);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Probe request timed out after " + timeoutSeconds + " seconds");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            } else if (cause instanceof ExtractionException) {
                throw (ExtractionException) cause;
            } else {
                throw new IOException("Probe request failed: " + cause.getMessage(), cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Probe request interrupted", e);
        }
    }

    // ==================== Search Operations ====================

    /**
     * Create a search extractor for the given query
     */
    public SearchExtractor createSearchExtractor(String query) throws ExtractionException {
        return youtube.getSearchExtractor(query);
    }

    /**
     * Create a search extractor with content-type filters.
     * Valid filter values: "videos", "channels", "playlists" (see YoutubeSearchQueryHandlerFactory).
     */
    public SearchExtractor createSearchExtractor(String query, List<String> contentFilters)
            throws ExtractionException {
        return youtube.getSearchExtractor(query, contentFilters, "");
    }

    /**
     * Fetch the initial page for a search extractor.
     *
     * Intentionally does NOT check the validation circuit breaker — that breaker
     * governs background batch jobs (channel/video/playlist validation). Blocking
     * interactive moderator search because a nightly validation run hit a rate limit
     * is a poor trade-off. Rate-limit errors from search still get recorded via
     * recordError() so the breaker state stays accurate for validation jobs.
     */
    public void fetchSearchPage(SearchExtractor extractor) throws IOException, ExtractionException {
        applyThrottling();

        try {
            extractor.fetchPage();
            recordSuccess();
        } catch (IOException | ExtractionException e) {
            recordError(e);
            throw e;
        }
    }

    /**
     * Get a specific page from a search extractor (pagination).
     *
     * Same reasoning as {@link #fetchSearchPage}: no circuit-breaker check for
     * interactive search. Rate-limit errors are still recorded.
     */
    public ListExtractor.InfoItemsPage<InfoItem> getSearchPage(SearchExtractor extractor, Page page)
            throws IOException, ExtractionException {
        applyThrottling();

        try {
            ListExtractor.InfoItemsPage<InfoItem> result = extractor.getPage(page);
            recordSuccess();
            return result;
        } catch (IOException | ExtractionException e) {
            recordError(e);
            throw e;
        }
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
     * Extract the YouTube channel ID from a channel URL (e.g. "/channel/UCxxxx" → "UCxxxx").
     * Returns null if parsing fails.
     */
    public String extractChannelId(String url) {
        try {
            return channelLinkHandlerFactory.getId(url);
        } catch (Exception e) {
            logger.debug("Could not extract channel ID from '{}': {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Extract the YouTube playlist ID from a playlist URL.
     * Returns null if parsing fails.
     */
    public String extractPlaylistId(String url) {
        try {
            return playlistLinkHandlerFactory.getId(url);
        } catch (Exception e) {
            logger.debug("Could not extract playlist ID from '{}': {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Extract the YouTube video ID from a video URL.
     * Returns null if parsing fails.
     */
    public String extractVideoId(String url) {
        try {
            return streamLinkHandlerFactory.getId(url);
        } catch (Exception e) {
            logger.debug("Could not extract video ID from '{}': {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Create a channel tab extractor for the given tab
     */
    public ChannelTabExtractor createChannelTabExtractor(ListLinkHandler tab) throws ExtractionException {
        return youtube.getChannelTabExtractor(tab);
    }

    /**
     * Fetch the initial page for a channel tab extractor with throttling and circuit breaker protection.
     * This should be used instead of calling extractor.fetchPage() directly.
     */
    public void fetchTabPage(ChannelTabExtractor extractor) throws IOException, ExtractionException {
        checkCircuitBreaker();
        applyThrottling();

        try {
            extractor.fetchPage();
            recordSuccess();
        } catch (IOException | ExtractionException e) {
            recordError(e);
            throw e;
        }
    }

    /**
     * Get a specific page from a channel tab extractor with throttling and circuit breaker protection.
     * This should be used instead of calling extractor.getPage() directly for pagination.
     */
    public ListExtractor.InfoItemsPage<InfoItem> getTabPage(ChannelTabExtractor extractor, Page page)
            throws IOException, ExtractionException {
        checkCircuitBreaker();
        applyThrottling();

        try {
            ListExtractor.InfoItemsPage<InfoItem> result = extractor.getPage(page);
            recordSuccess();
            return result;
        } catch (IOException | ExtractionException e) {
            recordError(e);
            throw e;
        }
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
     * Applies throttling, circuit breaker protection, and probe timeout (for HALF_OPEN probes).
     */
    public StreamInfo fetchStreamInfo(String videoId) throws IOException, ExtractionException {
        checkCircuitBreaker();
        applyThrottling();

        try {
            String url = streamLinkHandlerFactory.getUrl(videoId);
            // Use probe timeout wrapper for probe requests
            StreamInfo result = executeWithProbeTimeout(() -> StreamInfo.getInfo(youtube, url));
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
     * Decode string token to NewPipe Page object.
     *
     * <p>The token feeds into {@code extractor.getPage(decoded)} which
     * dispatches an HTTP GET via NewPipe's OkHttp client. Without
     * validation a moderator-supplied token could point at any URL —
     * internal endpoints, attacker hosts, or YouTube redirect helpers
     * that 302 to the {@code q=} param (NewPipe follows redirects).
     * Restrict to HTTPS YouTube hosts and deny the known open-redirect
     * paths. Unverifiable tokens return null (caller treats as first
     * page). Architectural fix — HMAC-signed tokens or an OkHttp
     * network interceptor — is tracked in the plan-doc Review
     * follow-ups (F1a).
     */
    public Page decodePageToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(token);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            if (scheme == null || host == null) {
                logger.warn("Rejected pageToken with no scheme or host");
                return null;
            }
            // HTTPS only — drops http:// open-redirect surface.
            if (!"https".equalsIgnoreCase(scheme)) {
                logger.warn("Rejected pageToken with non-https scheme: {}", scheme);
                return null;
            }
            // NewPipe pagination URLs are always on youtube.com hosts
            // (InnerTube API + HTML pages); CDN hosts like googlevideo.com
            // never produce a Page token.
            String hostLower = host.toLowerCase(java.util.Locale.ROOT);
            boolean allowed = hostLower.equals("youtube.com")
                    || hostLower.endsWith(".youtube.com")
                    || hostLower.equals("youtu.be");
            if (!allowed) {
                logger.warn("Rejected pageToken with non-YouTube host: {}", hostLower);
                return null;
            }
            // Deny YouTube's open-redirect entry points. Exact-path match
            // (not substring) so legitimate paths sharing these substrings
            // don't false-positive. Chain bypass is still possible if a
            // future YouTube path 302s to one of these (see F1a follow-up).
            String pathLower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
            boolean redirectHelper = pathLower.equals("/redirect")
                    || pathLower.startsWith("/redirect/")
                    || pathLower.equals("/url")
                    || pathLower.startsWith("/url/")
                    || pathLower.equals("/oembed")
                    || pathLower.startsWith("/oembed/")
                    || pathLower.equals("/attribution_link")
                    || pathLower.startsWith("/attribution_link/");
            if (redirectHelper) {
                logger.warn("Rejected pageToken with redirect-shaped path: {}", pathLower);
                return null;
            }
            return new Page(token);
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to decode page token (URI parse): {}", e.getMessage());
            return null;
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

    // -------------------------------------------------------------------------
    // BULK-01 (T5) — single dispatch for the bulk preview pipeline
    // -------------------------------------------------------------------------

    /**
     * Fetch metadata for one item by detected type, mapping all NewPipe exceptions
     * to {@link com.albunyaan.tube.dto.registry.PreviewErrorCode} values.
     *
     * <p>The existing per-type methods ({@code fetchChannelInfo}, {@code fetchPlaylistInfo},
     * {@code fetchStreamInfo}) accept a YouTube ID, so {@code youtubeId} is passed to them;
     * {@code normalizedUrl} is carried through only for future logging or callers that need it.
     */
    public PreviewFetchResult fetchByDetectedType(
            YouTubeContentType type,
            String youtubeId,
            String normalizedUrl) {
        logger.debug("BULK-01: fetching {} youtubeId={} normalizedUrl={}", type, youtubeId, normalizedUrl);
        try {
            return switch (type) {
                case CHANNEL  -> mapChannel(fetchChannelInfo(youtubeId), youtubeId);
                case PLAYLIST -> mapPlaylist(fetchPlaylistInfo(youtubeId), youtubeId);
                case VIDEO    -> mapVideo(fetchStreamInfo(youtubeId), youtubeId);
                default       -> PreviewFetchResult.error(PreviewErrorCode.UNSUPPORTED_TYPE);
            };
        } catch (AccountTerminatedException e) {
            return PreviewFetchResult.error(PreviewErrorCode.CHANNEL_TERMINATED);
        } catch (PrivateContentException e) {
            return PreviewFetchResult.error(PreviewErrorCode.PRIVATE_CONTENT);
        } catch (AgeRestrictedContentException e) {
            return PreviewFetchResult.error(PreviewErrorCode.AGE_RESTRICTED);
        } catch (GeographicRestrictionException e) {
            return PreviewFetchResult.error(PreviewErrorCode.GEO_RESTRICTED);
        } catch (ContentNotAvailableException e) {
            return PreviewFetchResult.error(PreviewErrorCode.CONTENT_NOT_AVAILABLE);
        } catch (ExtractionException e) {
            return PreviewFetchResult.error(PreviewErrorCode.NEWPIPE_PARSING_ERROR);
        } catch (IOException e) {
            return PreviewFetchResult.error(PreviewErrorCode.NETWORK_ERROR);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                return PreviewFetchResult.error(PreviewErrorCode.NETWORK_ERROR);
            }
            return PreviewFetchResult.error(PreviewErrorCode.NEWPIPE_PARSING_ERROR);
        }
    }

    private PreviewFetchResult mapChannel(
            ChannelInfo info, String youtubeId) {
        var m = new PreviewMetadata(
                youtubeId,
                info.getName(),
                pickThumb(info.getAvatars()),
                null, null,
                info.getSubscriberCount() == -1L ? null : info.getSubscriberCount(),
                null, null, null);
        return PreviewFetchResult.ok(m, null);
    }

    private PreviewFetchResult mapPlaylist(
            PlaylistInfo info, String youtubeId) {
        var m = new PreviewMetadata(
                youtubeId,
                info.getName(),
                pickThumb(info.getThumbnails()),
                info.getUploaderName(),
                deriveChannelIdFromUrl(info.getUploaderUrl()),
                null,
                info.getStreamCount() == -1L ? null : info.getStreamCount(),
                null, null);
        return PreviewFetchResult.ok(m, null);
    }

    private PreviewFetchResult mapVideo(
            StreamInfo info, String youtubeId) {
        VideoType vt =
                (info.getStreamType() == StreamType.LIVE_STREAM
                 || info.getStreamType() == StreamType.AUDIO_LIVE_STREAM)
                ? VideoType.LIVE
                : VideoType.STANDARD;

        Long duration = (vt == VideoType.LIVE && info.getDuration() == 0)
                ? null : info.getDuration();

        var m = new PreviewMetadata(
                youtubeId,
                info.getName(),
                pickThumb(info.getThumbnails()),
                info.getUploaderName(),
                deriveChannelIdFromUrl(info.getUploaderUrl()),
                null, null,
                duration,
                info.getViewCount() == -1L ? null : info.getViewCount());
        return PreviewFetchResult.ok(m, vt);
    }

    private static String pickThumb(List<Image> imgs) {
        if (imgs == null || imgs.isEmpty()) return null;
        return imgs.stream()
                .filter(i -> i.getEstimatedResolutionLevel()
                        == Image.ResolutionLevel.HIGH)
                .findFirst()
                .or(() -> imgs.stream()
                        .filter(i -> i.getEstimatedResolutionLevel()
                                == Image.ResolutionLevel.MEDIUM)
                        .findFirst())
                .or(() -> imgs.stream().findFirst())
                .map(Image::getUrl)
                .orElse(null);
    }

    private static String deriveChannelIdFromUrl(String uploaderUrl) {
        if (uploaderUrl == null) return null;
        var m = Pattern
                .compile("/channel/(UC[a-zA-Z0-9_-]{22})")
                .matcher(uploaderUrl);
        return m.find() ? m.group(1) : null;
    }
}
