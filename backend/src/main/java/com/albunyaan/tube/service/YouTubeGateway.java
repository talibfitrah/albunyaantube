package com.albunyaan.tube.service;

import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
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
    private static final ExecutorService executorService = Executors.newFixedThreadPool(3);

    private final StreamingService youtube;
    private final YoutubeChannelLinkHandlerFactory channelLinkHandlerFactory;
    private final YoutubePlaylistLinkHandlerFactory playlistLinkHandlerFactory;
    private final YoutubeStreamLinkHandlerFactory streamLinkHandlerFactory;

    @Autowired
    public YouTubeGateway(@Qualifier("newPipeYouTubeService") StreamingService youTubeService) {
        this.youtube = youTubeService;
        this.channelLinkHandlerFactory = YoutubeChannelLinkHandlerFactory.getInstance();
        this.playlistLinkHandlerFactory = YoutubePlaylistLinkHandlerFactory.getInstance();
        this.streamLinkHandlerFactory = YoutubeStreamLinkHandlerFactory.getInstance();

        logger.info("YouTubeGateway initialized with NewPipeExtractor");
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

    // ==================== Search Operations ====================

    /**
     * Create a search extractor for the given query
     */
    public SearchExtractor createSearchExtractor(String query) throws ExtractionException {
        return youtube.getSearchExtractor(query);
    }

    // ==================== Channel Operations ====================

    /**
     * Fetch channel info by channel ID
     */
    public ChannelInfo fetchChannelInfo(String channelId) throws IOException, ExtractionException {
        String url = channelLinkHandlerFactory.getUrl(channelId);
        return ChannelInfo.getInfo(youtube, url);
    }

    /**
     * Get channel URL from channel ID
     */
    public String getChannelUrl(String channelId) throws ExtractionException {
        return channelLinkHandlerFactory.getUrl(channelId);
    }

    /**
     * Create a channel tab extractor for the given tab
     */
    public ChannelTabExtractor createChannelTabExtractor(ListLinkHandler tab) throws ExtractionException {
        return youtube.getChannelTabExtractor(tab);
    }

    // ==================== Playlist Operations ====================

    /**
     * Fetch playlist info by playlist ID
     */
    public PlaylistInfo fetchPlaylistInfo(String playlistId) throws IOException, ExtractionException {
        String url = playlistLinkHandlerFactory.getUrl(playlistId);
        return PlaylistInfo.getInfo(youtube, url);
    }

    /**
     * Get playlist URL from playlist ID
     */
    public String getPlaylistUrl(String playlistId) throws ExtractionException {
        return playlistLinkHandlerFactory.getUrl(playlistId);
    }

    /**
     * Get more items from a playlist page
     */
    public ListExtractor.InfoItemsPage<StreamInfoItem> getPlaylistMoreItems(String playlistId, Page page)
            throws IOException, ExtractionException {
        String url = playlistLinkHandlerFactory.getUrl(playlistId);
        return PlaylistInfo.getMoreItems(youtube, url, page);
    }

    // ==================== Video Operations ====================

    /**
     * Fetch stream info by video ID
     */
    public StreamInfo fetchStreamInfo(String videoId) throws IOException, ExtractionException {
        String url = streamLinkHandlerFactory.getUrl(videoId);
        return StreamInfo.getInfo(youtube, url);
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
