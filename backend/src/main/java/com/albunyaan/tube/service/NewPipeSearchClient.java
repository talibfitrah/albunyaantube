package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

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
     * @param pageToken opaque page token from a previous response (null or empty = first page)
     * @return raw page of {@link InfoItem}s plus a next-page token
     * @throws ResponseStatusException 400, before any YouTube request, for a token that does not decode or carries no
     *         continuation id (every search page this backend issues has one; YouTube answers a continuation of
     *         nothing with an empty last page)
     */
    public RawPage search(String query, YouTubeContentType type, String pageToken) throws Exception {
        Page decoded = gateway.decodePageToken(pageToken);
        if (pageToken != null && !pageToken.isEmpty()
                && (decoded == null || decoded.getId() == null || decoded.getId().isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pageToken");
        }
        List<String> filters = switch (type) {
            case ALL      -> List.of();
            case CHANNEL  -> List.of("channels");
            case PLAYLIST -> List.of("playlists");
            case VIDEO    -> List.of("videos");
        };

        SearchExtractor extractor = gateway.createSearchExtractor(query, filters);
        gateway.fetchSearchPage(extractor);

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
