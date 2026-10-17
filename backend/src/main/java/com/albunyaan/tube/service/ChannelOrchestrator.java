package com.albunyaan.tube.service;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.dto.BatchValidationResult;
import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.PaginatedItemsResponse;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.dto.PlaylistItemDto;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.dto.StreamItemDto;
import com.albunyaan.tube.util.ThumbnailUrls;
import com.albunyaan.tube.util.YouTubeUrlUtils;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException;
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * P2-T3: Channel Orchestrator
 *
 * Coordinates channel, playlist, and video operations using the YouTubeGateway.
 * Handles:
 * - Fetching channel/playlist/video details
 * - Getting nested content (videos from channel, playlists from channel)
 * - Validation operations
 * - Batch validation with throttling and circuit breaker protection
 * - DTO mapping
 *
 * Rate Limiting Protection:
 * - Uses YouTubeThrottler to space out requests
 * - Uses YouTubeCircuitBreaker to detect and handle rate limiting
 * - Sequential processing for batch validations (no concurrent requests)
 */
@Service
public class ChannelOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(ChannelOrchestrator.class);
    private static final int DEFAULT_RESULTS = 20;

    /**
     * NewPipe 0.26.5's "channel does not exist" messages: "This channel doesn't exist." (InnerTube JSON error 404,
     * YoutubeChannelHelper) and YouTube's own deleted-channel alert as defaultAlertsCheck words it. A bare HTTP 404
     * ("Not found ("404 …")") is NOT one: getValidJsonResponseBody checks the 404 before the final URL, so a request
     * that ended at /oops reads the same. Any other or changed wording falls back to ERROR.
     */
    private static final Pattern CHANNEL_NOT_FOUND = Pattern.compile(
            "This channel doesn[\u2019']t exist\\.|Got error: \"This channel does not exist\\.\"");

    /** Exact ERROR alert YouTube shows for a deleted playlist, as defaultAlertsCheck words it (owner ruling R3). */
    private static final Pattern PLAYLIST_NOT_FOUND = Pattern.compile("Got error: \"The playlist does not exist\\.\"");

    /**
     * Playability reasons that name a removal, as YoutubeStreamExtractor.checkPlayabilityStatus words an
     * unclassified non-OK status: Got error STATUS: "reason". These archive directly.
     */
    private static final Pattern VIDEO_REMOVED = Pattern.compile("Got error [A-Z_]+: \"(?:This video is private"
            + "|Private video"
            + "|This video has been removed\\b.*|This video is no longer available\\b.*)\"");

    /**
     * YouTube's short age gate (LOGIN_REQUIRED "Sign in to confirm your age" without "inappropriate for some users"),
     * which NewPipe 0.26.5 leaves untyped. A stable gate, archived like AgeRestrictedContentException.
     */
    private static final Pattern VIDEO_AGE_GATE =
            Pattern.compile("Got error LOGIN_REQUIRED: \"Sign in to confirm your age\\.?\"");

    /**
     * Archive a channel only when it is definitively gone. The 1.0.4 branch's LiveTabReader has its own not-found
     * rule (first request only, and still a bare 404); the two rules must be reconciled when 1.0.4 merges.
     */
    static boolean channelGone(ExtractionException e) {
        return e instanceof AccountTerminatedException
                || (e instanceof ContentNotAvailableException
                    && CHANNEL_NOT_FOUND.matcher(String.valueOf(e.getMessage())).matches());
    }

    /**
     * Archive a playlist only when its owner's account is terminated or YouTube's exact deleted-playlist alert comes
     * back. NewPipe 0.26.5's YoutubePlaylistExtractor has no not-found text of its own; every other
     * ContentNotAvailableException it surfaces is an HTTP 404 from either of its two browse requests (metadata, then
     * a NewPipe-built continuation), an /oops redirect or another ERROR alert.
     */
    static boolean playlistGone(ExtractionException e) {
        return e instanceof AccountTerminatedException
                || (e instanceof ContentNotAvailableException
                    && PLAYLIST_NOT_FOUND.matcher(String.valueOf(e.getMessage())).matches());
    }

    /**
     * Archive a video directly only when it is removed, private or terminated, or behind a gate the app cannot pass
     * (age, including the short "Sign in to confirm your age" gate NewPipe leaves untyped; payment/members; Music
     * Premium). A geo-block (the server's region only), HTTP 404 (NewPipe cannot say which request got it:
     * visitor_id, player or next), /oops and every other playability status are ERROR. So is a generic reason
     * ("Video unavailable", "This video is unavailable", "This video isn't available anymore"): YouTube can keep the
     * real cause (e.g. a geo-block) in errorScreen.playerErrorMessageRenderer.subreason, which NewPipe's message
     * omits; an admin archives such a video.
     */
    static boolean videoGoneOrGated(ExtractionException e) {
        return e instanceof AccountTerminatedException
                || e instanceof PrivateContentException
                || e instanceof AgeRestrictedContentException
                || e instanceof PaidContentException
                || e instanceof YoutubeMusicPremiumContentException
                || (e instanceof ContentNotAvailableException
                    && (VIDEO_REMOVED.matcher(String.valueOf(e.getMessage())).matches()
                        || VIDEO_AGE_GATE.matcher(String.valueOf(e.getMessage())).matches()));
    }

    private final YouTubeGateway gateway;
    private final YouTubeThrottler throttler;
    private final YouTubeCircuitBreaker circuitBreaker;
    private final YouTubeOEmbedClient oEmbedClient;

    public ChannelOrchestrator(
            YouTubeGateway gateway,
            @Nullable YouTubeThrottler throttler,
            @Nullable YouTubeCircuitBreaker circuitBreaker,
            @Nullable YouTubeOEmbedClient oEmbedClient) {
        this.gateway = gateway;
        this.throttler = throttler;
        this.circuitBreaker = circuitBreaker;
        this.oEmbedClient = oEmbedClient;
        logger.info("ChannelOrchestrator initialized with throttler: {}, circuitBreaker: {}",
                throttler != null, circuitBreaker != null);
    }

    // ==================== Channel Operations ====================

    /**
     * Get channel details by channel ID or URL
     */
    @Cacheable(value = "newpipeChannelInfo", key = "#channelId", unless = "#result == null")
    public ChannelInfo getChannelDetails(String channelId) throws IOException {
        try {
            logger.debug("Fetching channel details for: {}", channelId);
            ChannelInfo info = gateway.fetchChannelInfo(channelId);
            logger.debug("Channel '{}' has {} subscribers", info.getName(), info.getSubscriberCount());
            return info;
        } catch (ExtractionException e) {
            logger.error("Failed to fetch channel details for '{}': {}", channelId, e.getMessage());
            throw new IOException("Channel fetch failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get videos from a channel (with pagination)
     */
    public List<StreamInfoItem> getChannelVideos(String channelId, String pageToken) throws IOException {
        return getChannelTabContentPaginated(channelId, pageToken, null, "videos").items;
    }

    /**
     * Get videos from a channel with optional search filter
     */
    public List<StreamInfoItem> getChannelVideos(String channelId, String pageToken, String searchQuery) throws IOException {
        return getChannelTabContentPaginated(channelId, pageToken, searchQuery, "videos").items;
    }

    /**
     * Result holder for paginated channel tab content
     */
    static class PaginatedTabResult {
        final List<StreamInfoItem> items;
        final String nextPageToken;

        PaginatedTabResult(List<StreamInfoItem> items, String nextPageToken) {
            this.items = items;
            this.nextPageToken = nextPageToken;
        }
    }

    /**
     * Generic method to fetch paginated content from a channel tab (videos, shorts, or livestreams).
     */
    private PaginatedTabResult getChannelTabContentPaginated(
            String channelId, String pageToken, String searchQuery, String tabFilter) throws IOException {
        logger.debug("Fetching {} for channel: {}, pageToken: {}, search: {}", tabFilter, channelId, pageToken, searchQuery);

        ChannelInfo channelInfo = getChannelDetails(channelId);
        List<StreamInfoItem> items = new ArrayList<>();
        String nextToken = null;

        try {
            // Find the requested tab
            ListLinkHandler targetTab = null;
            for (ListLinkHandler tab : channelInfo.getTabs()) {
                List<String> filters = tab.getContentFilters();
                if ("videos".equals(tabFilter)) {
                    if (filters.isEmpty() || filters.contains("videos") || filters.contains("uploads")) {
                        targetTab = tab;
                        break;
                    }
                } else if (filters.contains(tabFilter)) {
                    targetTab = tab;
                    break;
                }
            }

            if (targetTab == null) {
                logger.debug("No '{}' tab found for channel: {}", tabFilter, channelId);
                return new PaginatedTabResult(items, null);
            }

            // Fetch the tab with throttling and circuit breaker protection
            ChannelTabExtractor tabExtractor = gateway.createChannelTabExtractor(targetTab);
            gateway.fetchTabPage(tabExtractor);

            // Get requested page or initial page
            ListExtractor.InfoItemsPage<InfoItem> page;
            if (pageToken != null && !pageToken.isEmpty()) {
                Page requestedPage = gateway.decodePageToken(pageToken);
                if (requestedPage != null) {
                    page = gateway.getTabPage(tabExtractor, requestedPage);
                } else {
                    page = tabExtractor.getInitialPage();
                }
            } else {
                page = tabExtractor.getInitialPage();
            }

            // Process pages with early termination
            boolean hasSearchQuery = searchQuery != null && !searchQuery.trim().isEmpty();
            String lowerQuery = hasSearchQuery ? searchQuery.trim().toLowerCase() : null;

            while (page != null && items.size() < DEFAULT_RESULTS) {
                for (InfoItem item : page.getItems()) {
                    if (item instanceof StreamInfoItem) {
                        StreamInfoItem streamItem = (StreamInfoItem) item;

                        if (hasSearchQuery) {
                            if (streamItem.getName().toLowerCase().contains(lowerQuery)) {
                                items.add(streamItem);
                            }
                            // When searching, process ALL items on the current page to avoid
                            // skipping matches that appear after the DEFAULT_RESULTS-th match.
                            // The page may return slightly more than DEFAULT_RESULTS, which is
                            // acceptable to avoid permanently losing matches.
                        } else {
                            items.add(streamItem);
                            if (items.size() >= DEFAULT_RESULTS) {
                                break;
                            }
                        }
                    }
                }

                if (items.size() >= DEFAULT_RESULTS) {
                    // We filled the page — encode the next page token from the current page
                    if (page.hasNextPage()) {
                        nextToken = gateway.encodePageToken(page.getNextPage());
                    }
                    break;
                }

                if (page.hasNextPage()) {
                    try {
                        Page nextPage = page.getNextPage();
                        page = gateway.getTabPage(tabExtractor, nextPage);
                        // If we'll exit the loop (not enough items but no more pages),
                        // nextToken stays null
                    } catch (Exception e) {
                        logger.warn("Failed to fetch next page of {}: {}", tabFilter, e.getMessage());
                        break;
                    }
                } else {
                    break;
                }
            }

            // If we exited because the last page still has more items
            if (items.size() < DEFAULT_RESULTS && page != null && page.hasNextPage()) {
                nextToken = gateway.encodePageToken(page.getNextPage());
            }

        } catch (Exception e) {
            logger.warn("Failed to fetch channel {}: {}", tabFilter, e.getMessage());
        }

        logger.debug("Fetched {} {} from channel, hasMore: {}", items.size(), tabFilter, nextToken != null);
        return new PaginatedTabResult(items, nextToken);
    }

    /**
     * Get shorts from a channel (with pagination)
     */
    public PaginatedTabResult getChannelShorts(String channelId, String pageToken) throws IOException {
        return getChannelTabContentPaginated(channelId, pageToken, null, "shorts");
    }

    /**
     * Get live streams from a channel (with pagination)
     */
    public PaginatedTabResult getChannelLiveStreams(String channelId, String pageToken) throws IOException {
        return getChannelTabContentPaginated(channelId, pageToken, null, "livestreams");
    }

    /**
     * Result holder for paginated playlist items
     */
    static class PaginatedPlaylistResult {
        final List<PlaylistInfoItem> items;
        final String nextPageToken;

        PaginatedPlaylistResult(List<PlaylistInfoItem> items, String nextPageToken) {
            this.items = items;
            this.nextPageToken = nextPageToken;
        }
    }

    /**
     * Get playlists from a channel (returns list for backward compatibility)
     */
    public List<PlaylistInfoItem> getChannelPlaylists(String channelId, String pageToken) throws IOException {
        return getChannelPlaylistsPaginated(channelId, pageToken).items;
    }

    /**
     * Get playlists from a channel with pagination support
     */
    public PaginatedPlaylistResult getChannelPlaylistsPaginated(String channelId, String pageToken) throws IOException {
        logger.debug("Fetching playlists for channel: {}, pageToken: {}", channelId, pageToken);

        ChannelInfo channelInfo = getChannelDetails(channelId);

        if (channelInfo == null) {
            logger.warn("Channel info is null for channel: {}", channelId);
            return new PaginatedPlaylistResult(Collections.emptyList(), null);
        }

        List<ListLinkHandler> tabs = channelInfo.getTabs();
        List<PlaylistInfoItem> playlists = new ArrayList<>();
        String nextToken = null;

        if (tabs == null || tabs.isEmpty()) {
            logger.warn("No tabs found for channel: {}", channelId);
            return new PaginatedPlaylistResult(playlists, null);
        }

        logger.debug("Channel has {} tabs available", tabs.size());

        try {
            // Find the playlists tab
            ListLinkHandler playlistsTab = null;
            for (ListLinkHandler tab : tabs) {
                if (tab.getContentFilters().contains("playlists")) {
                    playlistsTab = tab;
                    break;
                }
            }

            if (playlistsTab == null) {
                logger.debug("No playlists tab found for channel: {}", channelId);
                return new PaginatedPlaylistResult(playlists, null);
            }

            // Fetch the playlists tab with throttling and circuit breaker protection
            ChannelTabExtractor tabExtractor = gateway.createChannelTabExtractor(playlistsTab);
            gateway.fetchTabPage(tabExtractor);

            // Get initial page or requested page
            ListExtractor.InfoItemsPage<InfoItem> page;
            if (pageToken != null && !pageToken.isEmpty()) {
                Page requestedPage = gateway.decodePageToken(pageToken);
                if (requestedPage != null) {
                    page = gateway.getTabPage(tabExtractor, requestedPage);
                } else {
                    page = tabExtractor.getInitialPage();
                }
            } else {
                page = tabExtractor.getInitialPage();
            }

            // Extract playlist items
            for (InfoItem item : page.getItems()) {
                if (item instanceof PlaylistInfoItem) {
                    playlists.add((PlaylistInfoItem) item);
                }
            }

            // Capture next page token
            if (page.hasNextPage()) {
                nextToken = gateway.encodePageToken(page.getNextPage());
            }

            logger.debug("Fetched {} playlists from channel, hasMore: {}", playlists.size(), nextToken != null);

        } catch (ExtractionException e) {
            String errorMsg = "Failed to fetch playlists for channel " + channelId + ": " + e.getMessage();
            logger.error(errorMsg, e);
            throw new IOException(errorMsg, e);
        } catch (Exception e) {
            String errorMsg = "Unexpected error fetching playlists for channel " + channelId + ": " + e.getMessage();
            logger.error(errorMsg, e);
            throw new IOException(errorMsg, e);
        }

        return new PaginatedPlaylistResult(playlists, nextToken);
    }

    // ==================== Playlist Operations ====================

    /**
     * Get playlist details by playlist ID
     */
    @Cacheable(value = "newpipePlaylistInfo", key = "#playlistId", unless = "#result == null")
    public PlaylistInfo getPlaylistDetails(String playlistId) throws IOException {
        try {
            logger.debug("Fetching playlist details for: {}", playlistId);
            PlaylistInfo info = gateway.fetchPlaylistInfo(playlistId);
            logger.debug("Playlist '{}' has {} videos", info.getName(), info.getStreamCount());
            return info;
        } catch (ExtractionException e) {
            logger.error("Failed to fetch playlist details for '{}': {}", playlistId, e.getMessage());
            throw new IOException("Playlist fetch failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get videos in a playlist (with pagination) - backward compatible
     */
    public List<StreamInfoItem> getPlaylistVideos(String playlistId, String pageToken) throws IOException {
        return getPlaylistVideosPaginated(playlistId, pageToken, null).items;
    }

    /**
     * Get videos in a playlist with optional search filter - backward compatible
     */
    public List<StreamInfoItem> getPlaylistVideos(String playlistId, String pageToken, String searchQuery) throws IOException {
        return getPlaylistVideosPaginated(playlistId, pageToken, searchQuery).items;
    }

    /**
     * Get videos in a playlist with pagination support
     */
    public PaginatedTabResult getPlaylistVideosPaginated(String playlistId, String pageToken, String searchQuery) throws IOException {
        logger.debug("Fetching videos for playlist: {}, pageToken: {}, search: {}", playlistId, pageToken, searchQuery);

        PlaylistInfo playlistInfo = getPlaylistDetails(playlistId);
        List<StreamInfoItem> videos = new ArrayList<>();
        String nextToken = null;

        boolean hasSearchQuery = searchQuery != null && !searchQuery.trim().isEmpty();
        String lowerQuery = hasSearchQuery ? searchQuery.trim().toLowerCase() : null;

        // Determine starting page
        List<StreamInfoItem> pageItems;
        Page upstreamNextPage;

        if (pageToken != null && !pageToken.isEmpty()) {
            try {
                Page requestedPage = gateway.decodePageToken(pageToken);
                if (requestedPage != null) {
                    ListExtractor.InfoItemsPage<StreamInfoItem> page = gateway.getPlaylistMoreItems(playlistId, requestedPage);
                    pageItems = new ArrayList<>(page.getItems());
                    upstreamNextPage = page.hasNextPage() ? page.getNextPage() : null;
                } else {
                    logger.warn("Failed to decode page token for playlist {}: invalid token '{}'", playlistId, pageToken);
                    pageItems = new ArrayList<>();
                    upstreamNextPage = null;
                }
            } catch (Exception e) {
                logger.warn("Failed to fetch page for playlist: {}", e.getMessage());
                pageItems = new ArrayList<>();
                upstreamNextPage = null;
            }
        } else {
            List<StreamInfoItem> relatedItems = playlistInfo.getRelatedItems();
            pageItems = relatedItems != null ? new ArrayList<>(relatedItems) : new ArrayList<>();
            upstreamNextPage = playlistInfo.getNextPage();
        }

        if (hasSearchQuery) {
            // When searching, loop through pages until we have enough matches or run out of pages.
            // This prevents returning 0 filtered results with hasMore=true.
            while (true) {
                for (StreamInfoItem item : pageItems) {
                    if (item.getName().toLowerCase().contains(lowerQuery)) {
                        videos.add(item);
                    }
                }

                if (videos.size() >= DEFAULT_RESULTS || upstreamNextPage == null) {
                    break;
                }

                // Haven't found enough matches — try the next upstream page
                try {
                    ListExtractor.InfoItemsPage<StreamInfoItem> morePage = gateway.getPlaylistMoreItems(playlistId, upstreamNextPage);
                    pageItems = new ArrayList<>(morePage.getItems());
                    upstreamNextPage = morePage.hasNextPage() ? morePage.getNextPage() : null;
                } catch (Exception e) {
                    logger.warn("Failed to fetch next page for playlist search: {}", e.getMessage());
                    break;
                }
            }

            if (upstreamNextPage != null) {
                nextToken = gateway.encodePageToken(upstreamNextPage);
            }
        } else {
            videos.addAll(pageItems);
            if (upstreamNextPage != null) {
                nextToken = gateway.encodePageToken(upstreamNextPage);
            }
        }

        logger.debug("Fetched {} videos from playlist, hasMore: {}", videos.size(), nextToken != null);
        return new PaginatedTabResult(videos, nextToken);
    }

    // ==================== Video Operations ====================

    /**
     * Get video details by video ID
     */
    @Cacheable(value = "newpipeVideoInfo", key = "#videoId", unless = "#result == null")
    public StreamInfo getVideoDetails(String videoId) throws IOException {
        try {
            logger.debug("Fetching video details for: {}", videoId);
            StreamInfo info = gateway.fetchStreamInfo(videoId);
            logger.debug("Video '{}' has {} views, duration: {}s",
                    info.getName(), info.getViewCount(), info.getDuration());
            return info;
        } catch (ExtractionException e) {
            logger.error("Failed to fetch video details for '{}': {}", videoId, e.getMessage());
            throw new IOException("Video fetch failed: " + e.getMessage(), e);
        }
    }

    // ==================== Validation Operations ====================

    /**
     * Validate and fetch channel by YouTube ID
     */
    @Cacheable(value = "newpipeChannelValidation", key = "#youtubeId", unless = "#result == null")
    public ChannelInfo validateAndFetchChannel(String youtubeId) {
        try {
            return getChannelDetails(youtubeId);
        } catch (IOException e) {
            logger.warn("Channel validation failed for {}: {}", youtubeId, e.getMessage());
            return null;
        }
    }

    /**
     * Validate and fetch playlist by YouTube ID
     */
    @Cacheable(value = "newpipePlaylistValidation", key = "#youtubeId", unless = "#result == null")
    public PlaylistInfo validateAndFetchPlaylist(String youtubeId) {
        try {
            return getPlaylistDetails(youtubeId);
        } catch (IOException e) {
            logger.warn("Playlist validation failed for {}: {}", youtubeId, e.getMessage());
            return null;
        }
    }

    /**
     * Validate and fetch video by YouTube ID
     */
    @Cacheable(value = "newpipeVideoValidation", key = "#youtubeId", unless = "#result == null")
    public StreamInfo validateAndFetchVideo(String youtubeId) {
        try {
            return getVideoDetails(youtubeId);
        } catch (IOException e) {
            logger.warn("Video validation failed for {}: {}", youtubeId, e.getMessage());
            return null;
        }
    }

    // ==================== Batch Validation ====================

    /**
     * Batch validate and fetch channels
     * @deprecated Use {@link #batchValidateChannelsWithDetails(List)} for proper error handling
     */
    @Deprecated
    public Map<String, ChannelInfo> batchValidateChannels(List<String> youtubeIds) {
        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        logger.debug("Batch validating {} channels", youtubeIds.size());

        Map<String, ChannelInfo> channelMap = Collections.synchronizedMap(new HashMap<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String channelId : youtubeIds) {
            CompletableFuture<Void> future = gateway.runAsync(() -> {
                try {
                    ChannelInfo info = getChannelDetails(channelId);
                    if (info != null) {
                        channelMap.put(channelId, info);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to validate channel {}: {}", channelId, e.getMessage());
                }
            });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        logger.debug("Batch validation completed: {}/{} channels found", channelMap.size(), youtubeIds.size());
        return channelMap;
    }

    /**
     * Batch validate channels with detailed error categorization.
     * Distinguishes between content that doesn't exist vs transient errors.
     *
     * Uses SEQUENTIAL processing with throttling to avoid triggering YouTube rate limits.
     * Previous implementation used concurrent requests which caused anti-bot blocks.
     *
     * notFound (the caller archives) ONLY when {@link #channelGone} says the channel is definitively gone. NewPipe
     * also throws a plain ContentNotAvailableException for any InnerTube JSON error (5xx, 429, 403, 400), any other
     * ERROR alert and an /oops redirect; those, rate limits, parse errors and IOException are all ERROR (retry later).
     *
     * Rate Limiting Protection:
     * - Checks circuit breaker before processing
     * - Processes items sequentially (no concurrent requests)
     * - Applies throttle delay between each request
     * - Records rate limit errors to circuit breaker
     *
     * @param youtubeIds List of YouTube channel IDs to validate
     * @return BatchValidationResult with valid, notFound, and error categories
     */
    public BatchValidationResult<ChannelInfo> batchValidateChannelsWithDetails(List<String> youtubeIds) {
        BatchValidationResult<ChannelInfo> result = new BatchValidationResult<>();

        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return result;
        }

        // Check circuit breaker before starting
        if (circuitBreaker != null && circuitBreaker.isOpen()) {
            logger.warn("Batch channel validation skipped - circuit breaker is OPEN. " +
                        "Marking {} items as SKIPPED (will retry on next run).", youtubeIds.size());
            // Mark all as SKIPPED (not errors) - they should NOT have status/lastValidatedAt updated
            // This preserves their position in the retry queue
            for (String id : youtubeIds) {
                result.addSkipped(id);
            }
            return result;
        }

        logger.info("Batch validating {} channels SEQUENTIALLY (throttling handled by gateway)", youtubeIds.size());

        // Process SEQUENTIALLY to avoid rate limiting (throttling/circuit breaker handled by YouTubeGateway)
        for (int i = 0; i < youtubeIds.size(); i++) {
            String channelId = youtubeIds.get(i);

            // Check circuit breaker before each request
            if (circuitBreaker != null && circuitBreaker.isOpen()) {
                logger.warn("Stopping batch validation at item {}/{} - circuit breaker opened. " +
                            "Skipping remaining {} items (will retry on next run).",
                        i + 1, youtubeIds.size(), youtubeIds.size() - i);
                // Mark remaining items as SKIPPED (not errors) - they will be retried on next run
                // without updating their status/lastValidatedAt
                for (int j = i; j < youtubeIds.size(); j++) {
                    result.addSkipped(youtubeIds.get(j));
                }
                break;
            }

            // Note: Throttling and circuit breaker recording are handled by YouTubeGateway.
            // Do NOT duplicate here to avoid over-escalation of cooldown/backoff.

            try {
                // Call gateway directly to get the raw NewPipe exceptions
                ChannelInfo info = gateway.fetchChannelInfo(channelId);
                if (info != null) {
                    result.addValid(channelId, info);
                    logger.debug("Channel {} exists on YouTube: {}", channelId, info.getName());
                } else {
                    // Null result without exception - should not happen, but treat as error
                    logger.warn("Channel {} returned null info without exception", channelId);
                    result.addError(channelId, "Null response from YouTube");
                }
            } catch (ExtractionException e) {
                // Check if this is a rate limit error (detection only - recording is in gateway)
                if (circuitBreaker != null && circuitBreaker.isRateLimitError(e)) {
                    logger.error("Channel {} RATE LIMITED: {} ({})",
                            channelId, e.getMessage(), e.getClass().getSimpleName());
                    result.addError(channelId, "Rate limit: " + e.getMessage());
                } else if (channelGone(e)) {
                    logger.info("Channel {} CONFIRMED not available: {} ({})",
                            channelId, e.getMessage(), e.getClass().getSimpleName());
                    result.addNotFound(channelId);
                } else {
                    // Other extraction errors - could be parsing issues, format changes, etc.
                    logger.warn("Channel {} extraction error (will retry): {} ({})",
                            channelId, e.getMessage(), e.getClass().getSimpleName());
                    result.addError(channelId, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            } catch (IOException e) {
                // Network errors - definitely transient, retry later
                logger.warn("Channel {} network error (will retry): {}", channelId, e.getMessage());
                result.addError(channelId, "Network error: " + e.getMessage());
            }

            // Log progress every 10 items
            if ((i + 1) % 10 == 0 || i == youtubeIds.size() - 1) {
                logger.debug("Channel validation progress: {}/{} (valid={}, notFound={}, errors={})",
                        i + 1, youtubeIds.size(),
                        result.getValidCount(), result.getNotFoundCount(), result.getErrorCount());
            }
        }

        logger.info("Channel batch validation completed: valid={}, notFound={}, errors={}",
                result.getValidCount(), result.getNotFoundCount(), result.getErrorCount());
        return result;
    }

    /**
     * Batch validate and fetch playlists
     * @deprecated Use {@link #batchValidatePlaylistsWithDetails(List)} for proper error handling
     */
    @Deprecated
    public Map<String, PlaylistInfo> batchValidatePlaylists(List<String> youtubeIds) {
        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        logger.debug("Batch validating {} playlists", youtubeIds.size());

        Map<String, PlaylistInfo> playlistMap = Collections.synchronizedMap(new HashMap<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String playlistId : youtubeIds) {
            CompletableFuture<Void> future = gateway.runAsync(() -> {
                try {
                    PlaylistInfo info = getPlaylistDetails(playlistId);
                    if (info != null) {
                        playlistMap.put(playlistId, info);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to validate playlist {}: {}", playlistId, e.getMessage());
                }
            });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        logger.debug("Batch validation completed: {}/{} playlists found", playlistMap.size(), youtubeIds.size());
        return playlistMap;
    }

    /**
     * Batch validate playlists with detailed error categorization.
     * notFound (the caller archives) ONLY when {@link #playlistGone}; everything else is ERROR (retry later).
     *
     * Uses SEQUENTIAL processing with throttling to avoid triggering YouTube rate limits.
     *
     * @param youtubeIds List of YouTube playlist IDs to validate
     * @return BatchValidationResult with valid, notFound, and error categories
     */
    public BatchValidationResult<PlaylistInfo> batchValidatePlaylistsWithDetails(List<String> youtubeIds) {
        BatchValidationResult<PlaylistInfo> result = new BatchValidationResult<>();

        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return result;
        }

        // Check circuit breaker before starting
        if (circuitBreaker != null && circuitBreaker.isOpen()) {
            logger.warn("Batch playlist validation skipped - circuit breaker is OPEN. " +
                        "Marking {} items as SKIPPED (will retry on next run).", youtubeIds.size());
            // Mark all as SKIPPED (not errors) - they should NOT have status/lastValidatedAt updated
            for (String id : youtubeIds) {
                result.addSkipped(id);
            }
            return result;
        }

        logger.info("Batch validating {} playlists SEQUENTIALLY (throttling handled by gateway)", youtubeIds.size());

        // Process SEQUENTIALLY to avoid rate limiting (throttling/circuit breaker handled by YouTubeGateway)
        for (int i = 0; i < youtubeIds.size(); i++) {
            String playlistId = youtubeIds.get(i);

            // Check circuit breaker before each request
            if (circuitBreaker != null && circuitBreaker.isOpen()) {
                logger.warn("Stopping batch validation at item {}/{} - circuit breaker opened. " +
                            "Skipping remaining {} items (will retry on next run).",
                        i + 1, youtubeIds.size(), youtubeIds.size() - i);
                // Mark remaining items as SKIPPED (not errors) - they will be retried on next run
                for (int j = i; j < youtubeIds.size(); j++) {
                    result.addSkipped(youtubeIds.get(j));
                }
                break;
            }

            // Note: Throttling and circuit breaker recording are handled by YouTubeGateway.
            // Do NOT duplicate here to avoid over-escalation of cooldown/backoff.

            try {
                PlaylistInfo info = gateway.fetchPlaylistInfo(playlistId);
                if (info != null) {
                    result.addValid(playlistId, info);
                    logger.debug("Playlist {} exists on YouTube: {}", playlistId, info.getName());
                } else {
                    logger.warn("Playlist {} returned null info without exception", playlistId);
                    result.addError(playlistId, "Null response from YouTube");
                }
            } catch (ExtractionException e) {
                // Check if this is a rate limit error (detection only - recording is in gateway)
                if (circuitBreaker != null && circuitBreaker.isRateLimitError(e)) {
                    logger.error("Playlist {} RATE LIMITED: {} ({})",
                            playlistId, e.getMessage(), e.getClass().getSimpleName());
                    result.addError(playlistId, "Rate limit: " + e.getMessage());
                } else if (playlistGone(e)) {
                    logger.info("Playlist {} CONFIRMED not available: {} ({})",
                            playlistId, e.getMessage(), e.getClass().getSimpleName());
                    result.addNotFound(playlistId);
                } else {
                    logger.warn("Playlist {} extraction error (will retry): {} ({})",
                            playlistId, e.getMessage(), e.getClass().getSimpleName());
                    result.addError(playlistId, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            } catch (IOException e) {
                logger.warn("Playlist {} network error (will retry): {}", playlistId, e.getMessage());
                result.addError(playlistId, "Network error: " + e.getMessage());
            }

            // Log progress every 10 items
            if ((i + 1) % 10 == 0 || i == youtubeIds.size() - 1) {
                logger.debug("Playlist validation progress: {}/{} (valid={}, notFound={}, errors={})",
                        i + 1, youtubeIds.size(),
                        result.getValidCount(), result.getNotFoundCount(), result.getErrorCount());
            }
        }

        logger.info("Playlist batch validation completed: valid={}, notFound={}, errors={}",
                result.getValidCount(), result.getNotFoundCount(), result.getErrorCount());
        return result;
    }

    /**
     * Batch validate and fetch videos
     * @deprecated Use {@link #batchValidateVideosWithDetails(List)} for proper error handling
     */
    @Deprecated
    public Map<String, StreamInfo> batchValidateVideos(List<String> youtubeIds) {
        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        logger.debug("Batch validating {} videos", youtubeIds.size());

        Map<String, StreamInfo> videoMap = Collections.synchronizedMap(new HashMap<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String videoId : youtubeIds) {
            CompletableFuture<Void> future = gateway.runAsync(() -> {
                try {
                    StreamInfo info = getVideoDetails(videoId);
                    if (info != null) {
                        videoMap.put(videoId, info);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to validate video {}: {}", videoId, e.getMessage());
                }
            });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        logger.debug("Batch validation completed: {}/{} videos found", videoMap.size(), youtubeIds.size());
        return videoMap;
    }

    /**
     * Batch validate videos with detailed error categorization.
     * notFound (the caller archives) ONLY when {@link #videoGoneOrGated}; everything else is ERROR (retry later).
     *
     * Uses SEQUENTIAL processing with throttling to avoid triggering YouTube rate limits.
     *
     * @param youtubeIds List of YouTube video IDs to validate
     * @return BatchValidationResult with valid, notFound, and error categories
     */
    public BatchValidationResult<StreamInfo> batchValidateVideosWithDetails(List<String> youtubeIds) {
        BatchValidationResult<StreamInfo> result = new BatchValidationResult<>();

        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return result;
        }

        // Check circuit breaker before starting
        if (circuitBreaker != null && circuitBreaker.isOpen()) {
            logger.warn("Batch video validation skipped - circuit breaker is OPEN. " +
                        "Marking {} items as SKIPPED (will retry on next run).", youtubeIds.size());
            // Mark all as SKIPPED (not errors) - they should NOT have status/lastValidatedAt updated
            for (String id : youtubeIds) {
                result.addSkipped(id);
            }
            return result;
        }

        logger.info("Batch validating {} videos SEQUENTIALLY (throttling handled by gateway)", youtubeIds.size());

        // Process SEQUENTIALLY to avoid rate limiting (throttling/circuit breaker handled by YouTubeGateway)
        for (int i = 0; i < youtubeIds.size(); i++) {
            String videoId = youtubeIds.get(i);

            // Check circuit breaker before each request
            if (circuitBreaker != null && circuitBreaker.isOpen()) {
                logger.warn("Stopping batch validation at item {}/{} - circuit breaker opened. " +
                            "Skipping remaining {} items (will retry on next run).",
                        i + 1, youtubeIds.size(), youtubeIds.size() - i);
                // Mark remaining items as SKIPPED (not errors) - they will be retried on next run
                for (int j = i; j < youtubeIds.size(); j++) {
                    result.addSkipped(youtubeIds.get(j));
                }
                break;
            }

            // Note: Throttling and circuit breaker recording are handled by YouTubeGateway.
            // Do NOT duplicate here to avoid over-escalation of cooldown/backoff.

            try {
                StreamInfo info = gateway.fetchStreamInfo(videoId);
                if (info != null) {
                    result.addValid(videoId, info);
                    logger.debug("Video {} exists on YouTube: {}", videoId, info.getName());
                } else {
                    logger.warn("Video {} returned null info without exception", videoId);
                    result.addError(videoId, "Null response from YouTube");
                }
            } catch (ExtractionException e) {
                // Gone or gated first: the short age gate reads like a sign-in rate limit but is a stable gate.
                if (videoGoneOrGated(e)) {
                    logger.info("Video {} CONFIRMED not available: {} ({})",
                            videoId, e.getMessage(), e.getClass().getSimpleName());
                    result.addNotFound(videoId);
                } else if (circuitBreaker != null && circuitBreaker.isRateLimitError(e)) {
                    // Detection only - recording is in gateway
                    logger.error("Video {} RATE LIMITED: {} ({})",
                            videoId, e.getMessage(), e.getClass().getSimpleName());
                    result.addError(videoId, "Rate limit: " + e.getMessage());
                } else {
                    logger.warn("Video {} extraction error (will retry): {} ({})",
                            videoId, e.getMessage(), e.getClass().getSimpleName());
                    result.addError(videoId, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            } catch (IOException e) {
                logger.warn("Video {} network error (will retry): {}", videoId, e.getMessage());
                result.addError(videoId, "Network error: " + e.getMessage());
            }

            // Log progress every 10 items
            if ((i + 1) % 10 == 0 || i == youtubeIds.size() - 1) {
                logger.debug("Video validation progress: {}/{} (valid={}, notFound={}, errors={})",
                        i + 1, youtubeIds.size(),
                        result.getValidCount(), result.getNotFoundCount(), result.getErrorCount());
            }
        }

        logger.info("Video batch validation completed: valid={}, notFound={}, errors={}",
                result.getValidCount(), result.getNotFoundCount(), result.getErrorCount());
        return result;
    }

    // ==================== DTO-First Methods ====================

    /**
     * Get channel details as DTO
     */
    public ChannelDetailsDto getChannelDetailsDto(String channelId) throws IOException {
        ChannelInfo channel = getChannelDetails(channelId);
        return mapToChannelDetailsDto(channel);
    }

    /**
     * Get channel videos as DTOs.
     * Cached only for base calls (no pageToken) to avoid caching degraded paginated results.
     * Uses sync=true for single-flight cache fill (prevents duplicate YouTube requests).
     */
    @Cacheable(value = CacheConfig.CACHE_NEWPIPE_CHANNEL_VIDEOS,
            key = "#channelId",
            sync = true,
            condition = "#pageToken == null")
    public List<StreamItemDto> getChannelVideosDto(String channelId, String pageToken) throws IOException {
        return getChannelVideos(channelId, pageToken).stream()
                .map(this::mapToStreamItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get channel videos as DTOs with optional search filter.
     * Cached only for base calls (no pageToken, no search) to avoid caching degraded or filtered results.
     * Note: shares cache key (#channelId) with the 2-arg overload — safe because both delegate
     * to the same getChannelVideos() when pageToken=null and searchQuery=null/blank.
     */
    @Cacheable(value = CacheConfig.CACHE_NEWPIPE_CHANNEL_VIDEOS,
            key = "#channelId",
            sync = true,
            condition = "#pageToken == null && (#searchQuery == null || #searchQuery.isBlank())")
    public List<StreamItemDto> getChannelVideosDto(String channelId, String pageToken, String searchQuery) throws IOException {
        return getChannelVideos(channelId, pageToken, searchQuery).stream()
                .map(this::mapToStreamItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get channel playlists as DTOs.
     * Cached only for base calls (no pageToken).
     */
    @Cacheable(value = CacheConfig.CACHE_NEWPIPE_CHANNEL_PLAYLISTS,
            key = "#channelId",
            sync = true,
            condition = "#pageToken == null")
    public List<PlaylistItemDto> getChannelPlaylistsDto(String channelId, String pageToken) throws IOException {
        return getChannelPlaylists(channelId, pageToken).stream()
                .map(this::mapToPlaylistItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get playlist details as DTO
     */
    public PlaylistDetailsDto getPlaylistDetailsDto(String playlistId) throws IOException {
        PlaylistInfo playlist = getPlaylistDetails(playlistId);
        return mapToPlaylistDetailsDto(playlist);
    }

    /**
     * Get playlist videos as DTOs.
     * Cached only for base calls (no pageToken).
     */
    @Cacheable(value = CacheConfig.CACHE_NEWPIPE_PLAYLIST_VIDEOS,
            key = "#playlistId",
            sync = true,
            condition = "#pageToken == null")
    public List<StreamItemDto> getPlaylistVideosDto(String playlistId, String pageToken) throws IOException {
        return getPlaylistVideos(playlistId, pageToken).stream()
                .map(this::mapToStreamItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get playlist videos as DTOs with optional search filter.
     * Cached only for base calls (no pageToken, no search).
     * Note: shares cache key (#playlistId) with the 2-arg overload — safe because both delegate
     * to the same getPlaylistVideos() when pageToken=null and searchQuery=null/blank.
     */
    @Cacheable(value = CacheConfig.CACHE_NEWPIPE_PLAYLIST_VIDEOS,
            key = "#playlistId",
            sync = true,
            condition = "#pageToken == null && (#searchQuery == null || #searchQuery.isBlank())")
    public List<StreamItemDto> getPlaylistVideosDto(String playlistId, String pageToken, String searchQuery) throws IOException {
        return getPlaylistVideos(playlistId, pageToken, searchQuery).stream()
                .map(this::mapToStreamItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ==================== Paginated DTO Methods ====================

    /**
     * Get channel videos as paginated DTOs
     */
    public PaginatedItemsResponse<StreamItemDto> getChannelVideosDtoPaginated(String channelId, String pageToken, String searchQuery) throws IOException {
        PaginatedTabResult result = getChannelTabContentPaginated(channelId, pageToken, searchQuery, "videos");
        List<StreamItemDto> dtos = result.items.stream()
                .map(this::mapToStreamItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return new PaginatedItemsResponse<>(dtos, result.nextPageToken);
    }

    /**
     * Get channel shorts as paginated DTOs
     */
    public PaginatedItemsResponse<StreamItemDto> getChannelShortsDtoPaginated(String channelId, String pageToken) throws IOException {
        PaginatedTabResult result = getChannelShorts(channelId, pageToken);
        List<StreamItemDto> dtos = result.items.stream()
                .map(this::mapToStreamItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return new PaginatedItemsResponse<>(dtos, result.nextPageToken);
    }

    /**
     * Get channel live streams as paginated DTOs
     */
    public PaginatedItemsResponse<StreamItemDto> getChannelLiveStreamsDtoPaginated(String channelId, String pageToken) throws IOException {
        PaginatedTabResult result = getChannelLiveStreams(channelId, pageToken);
        List<StreamItemDto> dtos = result.items.stream()
                .map(this::mapToStreamItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return new PaginatedItemsResponse<>(dtos, result.nextPageToken);
    }

    /**
     * Get channel playlists as paginated DTOs
     */
    public PaginatedItemsResponse<PlaylistItemDto> getChannelPlaylistsDtoPaginated(String channelId, String pageToken) throws IOException {
        PaginatedPlaylistResult result = getChannelPlaylistsPaginated(channelId, pageToken);
        List<PlaylistItemDto> dtos = result.items.stream()
                .map(this::mapToPlaylistItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return new PaginatedItemsResponse<>(dtos, result.nextPageToken);
    }

    /**
     * Get playlist videos as paginated DTOs
     */
    public PaginatedItemsResponse<StreamItemDto> getPlaylistVideosDtoPaginated(String playlistId, String pageToken, String searchQuery) throws IOException {
        PaginatedTabResult result = getPlaylistVideosPaginated(playlistId, pageToken, searchQuery);
        List<StreamItemDto> dtos = result.items.stream()
                .map(this::mapToStreamItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return new PaginatedItemsResponse<>(dtos, result.nextPageToken);
    }

    /**
     * Get video details as DTO
     */
    public StreamDetailsDto getVideoDetailsDto(String videoId) throws IOException {
        StreamInfo video = getVideoDetails(videoId);
        return mapToStreamDetailsDto(video);
    }

    /**
     * Validate and fetch channel as DTO
     */
    public ChannelDetailsDto validateAndFetchChannelDto(String youtubeId) {
        ChannelInfo info = validateAndFetchChannel(youtubeId);
        return info != null ? mapToChannelDetailsDto(info) : null;
    }

    /**
     * Validate and fetch playlist as DTO
     */
    public PlaylistDetailsDto validateAndFetchPlaylistDto(String youtubeId) {
        PlaylistInfo info = validateAndFetchPlaylist(youtubeId);
        return info != null ? mapToPlaylistDetailsDto(info) : null;
    }

    /**
     * Validate and fetch video as DTO
     */
    public StreamDetailsDto validateAndFetchVideoDto(String youtubeId) {
        StreamInfo info = validateAndFetchVideo(youtubeId);
        return info != null ? mapToStreamDetailsDto(info) : null;
    }

    /**
     * Batch validate channels and return as DTOs
     * @deprecated Use {@link #batchValidateChannelsDtoWithDetails(List)} for proper error handling
     */
    @Deprecated
    public Map<String, ChannelDetailsDto> batchValidateChannelsDto(List<String> youtubeIds) {
        return batchValidateChannels(youtubeIds).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> mapToChannelDetailsDto(e.getValue())
                ));
    }

    /**
     * Batch validate playlists and return as DTOs
     * @deprecated Use {@link #batchValidatePlaylistsDtoWithDetails(List)} for proper error handling
     */
    @Deprecated
    public Map<String, PlaylistDetailsDto> batchValidatePlaylistsDto(List<String> youtubeIds) {
        return batchValidatePlaylists(youtubeIds).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> mapToPlaylistDetailsDto(e.getValue())
                ));
    }

    /**
     * Batch validate videos and return as DTOs
     * @deprecated Use {@link #batchValidateVideosDtoWithDetails(List)} for proper error handling
     */
    @Deprecated
    public Map<String, StreamDetailsDto> batchValidateVideosDto(List<String> youtubeIds) {
        return batchValidateVideos(youtubeIds).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> mapToStreamDetailsDto(e.getValue())
                ));
    }

    /**
     * Batch validate channels and return as DTOs with detailed error categorization.
     * Properly distinguishes between content that doesn't exist vs transient errors.
     *
     * @param youtubeIds List of YouTube channel IDs to validate
     * @return BatchValidationResult with valid DTOs, notFound IDs, and error IDs
     */
    public BatchValidationResult<ChannelDetailsDto> batchValidateChannelsDtoWithDetails(List<String> youtubeIds) {
        BatchValidationResult<ChannelInfo> rawResult = batchValidateChannelsWithDetails(youtubeIds);
        BatchValidationResult<ChannelDetailsDto> dtoResult = new BatchValidationResult<>();

        // Convert valid entries to DTOs
        rawResult.getValid().forEach((id, info) ->
                dtoResult.addValid(id, mapToChannelDetailsDto(info)));

        // Copy notFound, error, and skipped sets
        rawResult.getNotFound().forEach(dtoResult::addNotFound);
        rawResult.getErrorMessages().forEach(dtoResult::addError);
        rawResult.getSkipped().forEach(dtoResult::addSkipped);

        return dtoResult;
    }

    /**
     * Batch validate playlists and return as DTOs with detailed error categorization.
     * Properly distinguishes between content that doesn't exist vs transient errors.
     *
     * @param youtubeIds List of YouTube playlist IDs to validate
     * @return BatchValidationResult with valid DTOs, notFound IDs, and error IDs
     */
    public BatchValidationResult<PlaylistDetailsDto> batchValidatePlaylistsDtoWithDetails(List<String> youtubeIds) {
        BatchValidationResult<PlaylistInfo> rawResult = batchValidatePlaylistsWithDetails(youtubeIds);
        BatchValidationResult<PlaylistDetailsDto> dtoResult = new BatchValidationResult<>();

        // Convert valid entries to DTOs
        rawResult.getValid().forEach((id, info) ->
                dtoResult.addValid(id, mapToPlaylistDetailsDto(info)));

        // Copy notFound, error, and skipped sets
        rawResult.getNotFound().forEach(dtoResult::addNotFound);
        rawResult.getErrorMessages().forEach(dtoResult::addError);
        rawResult.getSkipped().forEach(dtoResult::addSkipped);

        return dtoResult;
    }

    /**
     * Batch validate videos and return as DTOs with detailed error categorization.
     * Properly distinguishes between content that doesn't exist vs transient errors.
     *
     * @param youtubeIds List of YouTube video IDs to validate
     * @return BatchValidationResult with valid DTOs, notFound IDs, and error IDs
     */
    public BatchValidationResult<StreamDetailsDto> batchValidateVideosDtoWithDetails(List<String> youtubeIds) {
        BatchValidationResult<StreamInfo> rawResult = batchValidateVideosWithDetails(youtubeIds);
        BatchValidationResult<StreamDetailsDto> dtoResult = new BatchValidationResult<>();

        // Convert valid entries to DTOs
        rawResult.getValid().forEach((id, info) ->
                dtoResult.addValid(id, mapToStreamDetailsDto(info)));

        // Copy notFound, error, and skipped sets
        rawResult.getNotFound().forEach(dtoResult::addNotFound);
        rawResult.getErrorMessages().forEach(dtoResult::addError);
        rawResult.getSkipped().forEach(dtoResult::addSkipped);

        return dtoResult;
    }

    // ==================== DTO Mapping Methods ====================

    /**
     * Map ChannelInfo to ChannelDetailsDto
     */
    public ChannelDetailsDto mapToChannelDetailsDto(ChannelInfo channel) {
        if (channel == null) {
            return null;
        }

        ChannelDetailsDto dto = new ChannelDetailsDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(channel.getUrl()));
        dto.setName(channel.getName());
        dto.setUrl(channel.getUrl());
        dto.setDescription(channel.getDescription());
        dto.setSubscriberCount(channel.getSubscriberCount());
        dto.setStreamCount(null);
        dto.setTags(channel.getTags());

        if (channel.getAvatars() != null && !channel.getAvatars().isEmpty()) {
            dto.setThumbnailUrl(channel.getAvatars().get(0).getUrl());
        }

        if (channel.getBanners() != null && !channel.getBanners().isEmpty()) {
            dto.setBannerUrl(channel.getBanners().get(0).getUrl());
        }

        return dto;
    }

    /**
     * Map PlaylistInfo to PlaylistDetailsDto
     */
    public com.albunyaan.tube.dto.PlaylistDetailsDto mapToPlaylistDetailsDto(PlaylistInfo playlist) {
        if (playlist == null) {
            return null;
        }

        com.albunyaan.tube.dto.PlaylistDetailsDto dto = new com.albunyaan.tube.dto.PlaylistDetailsDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(playlist.getUrl()));
        dto.setName(playlist.getName());
        dto.setUrl(playlist.getUrl());
        dto.setDescription(playlist.getDescription() != null ? playlist.getDescription().getContent() : "");
        dto.setUploaderName(playlist.getUploaderName());
        dto.setUploaderUrl(playlist.getUploaderUrl());
        dto.setStreamCount(playlist.getStreamCount());

        if (playlist.getThumbnails() != null && !playlist.getThumbnails().isEmpty()) {
            dto.setThumbnailUrl(playlist.getThumbnails().get(0).getUrl());
        }

        if (playlist.getRelatedItems() != null) {
            List<StreamItemDto> streamItems = playlist.getRelatedItems().stream()
                    .map(this::mapToStreamItemDto)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            dto.setRelatedStreams(streamItems);
        }

        // A custom-thumbnail playlist's header is a signed /pl_c/ URL that 404s within days (or it is
        // no_thumbnail.jpg). Use the first video's stable /vi/ image from this same fetch; oEmbed only
        // when the page has no items. Every path (validator, bulk, single add) maps through here.
        if (ThumbnailUrls.isBrokenPlaylistThumbnail(dto.getThumbnailUrl())) {
            String firstVideo = dto.getRelatedStreams() == null || dto.getRelatedStreams().isEmpty() ? null
                    : "https://i.ytimg.com/vi/" + dto.getRelatedStreams().get(0).getId() + "/hqdefault.jpg";
            if (!ThumbnailUrls.isBrokenPlaylistThumbnail(firstVideo)) {
                dto.setThumbnailUrl(firstVideo);
            } else if (oEmbedClient != null) {
                oEmbedClient.playlistThumbnailUrl(dto.getId()).ifPresent(dto::setThumbnailUrl);
            }
        }

        return dto;
    }

    /**
     * Map StreamInfo to StreamDetailsDto
     */
    public com.albunyaan.tube.dto.StreamDetailsDto mapToStreamDetailsDto(StreamInfo stream) {
        if (stream == null) {
            return null;
        }

        com.albunyaan.tube.dto.StreamDetailsDto dto = new com.albunyaan.tube.dto.StreamDetailsDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(stream.getUrl()));
        dto.setName(stream.getName());
        dto.setUrl(stream.getUrl());
        dto.setDescription(stream.getDescription() != null ? stream.getDescription().getContent() : "");
        dto.setUploaderName(stream.getUploaderName());
        dto.setUploaderUrl(stream.getUploaderUrl());
        dto.setViewCount(stream.getViewCount());
        dto.setDuration(stream.getDuration());
        dto.setLikeCount(stream.getLikeCount());
        dto.setDislikeCount(stream.getDislikeCount());
        dto.setCategory(stream.getCategory());
        dto.setTags(stream.getTags());

        if (stream.getThumbnails() != null && !stream.getThumbnails().isEmpty()) {
            dto.setThumbnailUrl(stream.getThumbnails().get(0).getUrl());
        }

        if (stream.getUploadDate() != null) {
            try {
                dto.setUploadDate(stream.getUploadDate()
                        .offsetDateTime()
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            } catch (Exception e) {
                logger.warn("Failed to format upload date: {}", e.getMessage());
            }
        }

        return dto;
    }

    /**
     * Map StreamInfoItem to StreamItemDto
     */
    public StreamItemDto mapToStreamItemDto(StreamInfoItem stream) {
        if (stream == null) {
            return null;
        }

        StreamItemDto dto = new StreamItemDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(stream.getUrl()));
        dto.setName(stream.getName());
        dto.setUrl(stream.getUrl());
        dto.setUploaderName(stream.getUploaderName());
        dto.setUploaderUrl(stream.getUploaderUrl());
        dto.setViewCount(stream.getViewCount());
        dto.setDuration(stream.getDuration());

        // Detect stream type
        if (stream.isShortFormContent()) {
            dto.setStreamType("SHORT");
        } else if (stream.getStreamType() == StreamType.LIVE_STREAM || stream.getStreamType() == StreamType.AUDIO_LIVE_STREAM) {
            dto.setStreamType("LIVESTREAM");
        } else {
            dto.setStreamType("VIDEO");
        }

        if (stream.getThumbnails() != null && !stream.getThumbnails().isEmpty()) {
            dto.setThumbnailUrl(stream.getThumbnails().get(0).getUrl());
        }

        if (stream.getUploadDate() != null) {
            try {
                dto.setUploadDate(stream.getUploadDate()
                        .offsetDateTime()
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            } catch (Exception e) {
                logger.warn("Failed to format upload date: {}", e.getMessage());
            }
        }

        return dto;
    }

    /**
     * Map PlaylistInfoItem to PlaylistItemDto
     */
    public PlaylistItemDto mapToPlaylistItemDto(PlaylistInfoItem playlist) {
        if (playlist == null) {
            return null;
        }

        PlaylistItemDto dto = new PlaylistItemDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(playlist.getUrl()));
        dto.setName(playlist.getName());
        dto.setUrl(playlist.getUrl());
        dto.setUploaderName(playlist.getUploaderName());
        dto.setStreamCount(playlist.getStreamCount());

        if (playlist.getThumbnails() != null && !playlist.getThumbnails().isEmpty()) {
            dto.setThumbnailUrl(playlist.getThumbnails().get(0).getUrl());
        }

        return dto;
    }

}
