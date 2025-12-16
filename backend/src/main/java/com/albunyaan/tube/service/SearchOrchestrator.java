package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.EnrichedSearchResult;
import com.albunyaan.tube.dto.SearchPageResponse;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P2-T3: Search Orchestrator
 *
 * Coordinates YouTube search operations using the YouTubeGateway.
 * Handles:
 * - Search queries with pagination
 * - Type-filtered searches (channels, playlists, videos)
 * - Conversion to EnrichedSearchResult DTOs
 * - Caching of search results
 */
@Service
public class SearchOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(SearchOrchestrator.class);
    private static final int DEFAULT_SEARCH_RESULTS = 20;

    private final YouTubeGateway gateway;

    public SearchOrchestrator(YouTubeGateway gateway) {
        this.gateway = gateway;
        logger.info("SearchOrchestrator initialized");
    }

    /**
     * Unified search for all content types with pagination
     * Returns mixed results (channels, playlists, videos) like YouTube's native search
     */
    public SearchPageResponse searchAllEnrichedPaged(String query, String pageToken) throws IOException {
        try {
            logger.debug("Searching YouTube for: '{}' with pageToken: {}", query, pageToken);

            // Create search extractor
            SearchExtractor extractor = gateway.createSearchExtractor(query);

            // Fetch initial page with throttling and circuit breaker protection
            gateway.fetchSearchPage(extractor);

            List<InfoItem> allItems = new ArrayList<>(extractor.getInitialPage().getItems());
            Page nextPage = extractor.getInitialPage().getNextPage();

            // If pageToken provided, navigate to that page with throttling/circuit breaker protection
            if (pageToken != null && !pageToken.isEmpty()) {
                try {
                    Page requestedPage = gateway.decodePageToken(pageToken);
                    if (requestedPage != null) {
                        ListExtractor.InfoItemsPage<InfoItem> page = gateway.getSearchPage(extractor, requestedPage);
                        allItems = new ArrayList<>(page.getItems());
                        nextPage = page.getNextPage();
                    }
                } catch (Exception e) {
                    logger.warn("Failed to decode or fetch requested page, using initial page: {}", e.getMessage());
                }
            }

            // Convert to enriched results
            List<EnrichedSearchResult> enrichedResults = new ArrayList<>();
            for (InfoItem item : allItems) {
                EnrichedSearchResult result = convertToEnrichedResult(item);
                if (result != null) {
                    enrichedResults.add(result);
                }
            }

            // Encode next page token
            String nextPageToken = gateway.encodePageToken(nextPage);

            logger.debug("Search returned {} results, nextPageToken: {}",
                    enrichedResults.size(), nextPageToken != null ? "present" : "null");

            return new SearchPageResponse(
                    enrichedResults,
                    nextPageToken,
                    enrichedResults.size()
            );

        } catch (ExtractionException e) {
            logger.error("NewPipe extraction failed for query '{}': {}", query, e.getMessage(), e);
            throw new IOException("YouTube search failed: " + e.getMessage(), e);
        }
    }

    /**
     * Unified search for all content types (no pagination)
     * @deprecated Use searchAllEnrichedPaged for pagination support
     */
    @Deprecated
    @Cacheable(value = "newpipeSearchResults", key = "'all:' + #query", unless = "#result == null || #result.isEmpty()")
    public List<EnrichedSearchResult> searchAllEnriched(String query) throws IOException {
        SearchPageResponse response = searchAllEnrichedPaged(query, null);
        return response.getItems();
    }

    /**
     * Search for channels by query with full statistics (with caching)
     */
    @Cacheable(value = "newpipeSearchResults", key = "'channel:' + #query", unless = "#result == null || #result.isEmpty()")
    public List<EnrichedSearchResult> searchChannelsEnriched(String query) throws IOException {
        return searchByType(query, Collections.singletonList(InfoItem.InfoType.CHANNEL));
    }

    /**
     * Search for playlists by query with full details (with caching)
     */
    @Cacheable(value = "newpipeSearchResults", key = "'playlist:' + #query", unless = "#result == null || #result.isEmpty()")
    public List<EnrichedSearchResult> searchPlaylistsEnriched(String query) throws IOException {
        return searchByType(query, Collections.singletonList(InfoItem.InfoType.PLAYLIST));
    }

    /**
     * Search for videos by query with full statistics (with caching)
     */
    @Cacheable(value = "newpipeSearchResults", key = "'video:' + #query", unless = "#result == null || #result.isEmpty()")
    public List<EnrichedSearchResult> searchVideosEnriched(String query) throws IOException {
        return searchByType(query, Collections.singletonList(InfoItem.InfoType.STREAM));
    }

    /**
     * Search by specific type(s)
     */
    private List<EnrichedSearchResult> searchByType(String query, List<InfoItem.InfoType> types) throws IOException {
        try {
            logger.debug("Searching YouTube for type {} with query: '{}'", types, query);

            SearchExtractor extractor = gateway.createSearchExtractor(query);
            gateway.fetchSearchPage(extractor);

            List<InfoItem> items = extractor.getInitialPage().getItems();
            List<EnrichedSearchResult> results = new ArrayList<>();

            // Filter by requested types
            for (InfoItem item : items) {
                if (types.contains(item.getInfoType())) {
                    EnrichedSearchResult result = convertToEnrichedResult(item);
                    if (result != null) {
                        results.add(result);
                        if (results.size() >= DEFAULT_SEARCH_RESULTS) {
                            break;
                        }
                    }
                }
            }

            logger.debug("Search returned {} results for type {}", results.size(), types);
            return results;

        } catch (ExtractionException e) {
            logger.error("NewPipe search failed for query '{}': {}", query, e.getMessage(), e);
            throw new IOException("YouTube search failed: " + e.getMessage(), e);
        }
    }

    /**
     * Convert NewPipe InfoItem to EnrichedSearchResult
     */
    private EnrichedSearchResult convertToEnrichedResult(InfoItem item) {
        try {
            if (item instanceof ChannelInfoItem) {
                return EnrichedSearchResult.fromChannelInfoItem((ChannelInfoItem) item);
            } else if (item instanceof PlaylistInfoItem) {
                return EnrichedSearchResult.fromPlaylistInfoItem((PlaylistInfoItem) item);
            } else if (item instanceof StreamInfoItem) {
                return EnrichedSearchResult.fromStreamInfoItem((StreamInfoItem) item);
            }
            return null;
        } catch (Exception e) {
            logger.warn("Failed to convert InfoItem to EnrichedSearchResult: {}", e.getMessage());
            return null;
        }
    }
}
