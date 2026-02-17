package com.albunyaan.tube.dto;

import java.util.List;

/**
 * Generic paginated response wrapper for channel/playlist sub-content.
 * Contains a list of items and an optional nextPageToken for infinite scroll.
 */
public class PaginatedItemsResponse<T> {
    private List<T> items;
    private String nextPageToken;

    public PaginatedItemsResponse() {}

    public PaginatedItemsResponse(List<T> items, String nextPageToken) {
        this.items = items;
        this.nextPageToken = nextPageToken;
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }

    public String getNextPageToken() {
        return nextPageToken;
    }

    public void setNextPageToken(String nextPageToken) {
        this.nextPageToken = nextPageToken;
    }
}
