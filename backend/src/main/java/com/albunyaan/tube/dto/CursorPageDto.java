package com.albunyaan.tube.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Generic cursor-based pagination response for public API.
 *
 * Wire format:
 * {
 *   "data": [...],
 *   "pageInfo": {
 *     "nextCursor": "string|null",
 *     "hasNext": boolean,
 *     "totalCount": number|null,
 *     "truncated": boolean|null
 *   }
 * }
 *
 * Note: totalCount and truncated are optional. truncated indicates whether
 * results were capped by safety limits (e.g., workspace exclusions aggregation).
 */
public class CursorPageDto<T> {
    private List<T> data;

    @JsonProperty("pageInfo")
    private PageInfo pageInfo;

    public CursorPageDto() {
    }

    public CursorPageDto(List<T> data, String nextCursor) {
        this.data = data;
        this.pageInfo = new PageInfo(nextCursor);
    }

    public CursorPageDto(List<T> data, String nextCursor, Integer totalCount) {
        this.data = data;
        this.pageInfo = new PageInfo(nextCursor, totalCount);
    }

    public CursorPageDto(List<T> data, String nextCursor, Integer totalCount, Boolean truncated) {
        this.data = data;
        this.pageInfo = new PageInfo(nextCursor, totalCount, truncated);
    }

    public List<T> getData() {
        return data;
    }

    public void setData(List<T> data) {
        this.data = data;
    }

    public PageInfo getPageInfo() {
        return pageInfo;
    }

    public void setPageInfo(PageInfo pageInfo) {
        this.pageInfo = pageInfo;
    }

    public static class PageInfo {
        private String nextCursor;
        private boolean hasNext;
        private Integer totalCount;

        /**
         * Indicates whether the total results were truncated due to safety limits.
         * When true, the totalCount and results may be incomplete.
         *
         * This is used by the workspace exclusions aggregation to signal when
         * hard limits (e.g., 500 channels, 1000 playlists) have been hit.
         */
        private Boolean truncated;

        public PageInfo() {
        }

        public PageInfo(String nextCursor) {
            this.nextCursor = nextCursor;
            this.hasNext = nextCursor != null;
        }

        public PageInfo(String nextCursor, Integer totalCount) {
            this.nextCursor = nextCursor;
            this.hasNext = nextCursor != null;
            this.totalCount = totalCount;
        }

        public PageInfo(String nextCursor, Integer totalCount, Boolean truncated) {
            this.nextCursor = nextCursor;
            this.hasNext = nextCursor != null;
            this.totalCount = totalCount;
            this.truncated = truncated;
        }

        public String getNextCursor() {
            return nextCursor;
        }

        public void setNextCursor(String nextCursor) {
            this.nextCursor = nextCursor;
            this.hasNext = nextCursor != null;
        }

        public boolean isHasNext() {
            return hasNext;
        }

        public Integer getTotalCount() {
            return totalCount;
        }

        public void setTotalCount(Integer totalCount) {
            this.totalCount = totalCount;
        }

        public Boolean getTruncated() {
            return truncated;
        }

        public void setTruncated(Boolean truncated) {
            this.truncated = truncated;
        }
    }
}

