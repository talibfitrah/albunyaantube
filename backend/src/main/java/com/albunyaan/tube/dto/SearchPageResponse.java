package com.albunyaan.tube.dto;

import java.util.List;

/**
 * Paginated search response with nextPageToken for infinite scroll
 */
public class SearchPageResponse {
    private List<EnrichedSearchResult> items;
    private String nextPageToken;
    private Integer totalResults;

    public SearchPageResponse() {
    }

    public SearchPageResponse(List<EnrichedSearchResult> items, String nextPageToken, Integer totalResults) {
        this.items = items;
        this.nextPageToken = nextPageToken;
        this.totalResults = totalResults;
    }

    public List<EnrichedSearchResult> getItems() {
        return items;
    }

    public void setItems(List<EnrichedSearchResult> items) {
        this.items = items;
    }

    public String getNextPageToken() {
        return nextPageToken;
    }

    public void setNextPageToken(String nextPageToken) {
        this.nextPageToken = nextPageToken;
    }

    public Integer getTotalResults() {
        return totalResults;
    }

    public void setTotalResults(Integer totalResults) {
        this.totalResults = totalResults;
    }
}
