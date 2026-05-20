package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Thin injectable wrapper around {@link YouTubeGateway} search operations.
 *
 * <p>Keeping NewPipe's static API behind a Spring {@code @Component} makes
 * {@link YouTubeSearchService} trivially unit-testable with Mockito.</p>
 */
@Component
public class NewPipeSearchClient {

    private final YouTubeGateway gateway;

    public NewPipeSearchClient(YouTubeGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * Execute a typed YouTube search and return one page of raw results.
     *
     * @param query     search query string
     * @param type      content type to filter by
     * @param pageToken opaque page token from a previous response (null = first page)
     * @return raw page of {@link InfoItem}s plus a next-page token
     */
    public RawPage search(String query, YouTubeContentType type, String pageToken) throws Exception {
        List<String> filters = switch (type) {
            case ALL      -> List.of();
            case CHANNEL  -> List.of("channels");
            case PLAYLIST -> List.of("playlists");
            case VIDEO    -> List.of("videos");
        };

        SearchExtractor extractor = gateway.createSearchExtractor(query, filters);
        gateway.fetchSearchPage(extractor);

        Page decoded = gateway.decodePageToken(pageToken);
        InfoItemsPage<InfoItem> page = (decoded == null)
                ? extractor.getInitialPage()
                : gateway.getSearchPage(extractor, decoded);

        return new RawPage(page.getItems(), gateway.encodePageToken(page.getNextPage()));
    }

    /**
     * Raw result carrier: items on this page + encoded token for the next page.
     */
    public record RawPage(List<InfoItem> items, String nextPageToken) {}
}
