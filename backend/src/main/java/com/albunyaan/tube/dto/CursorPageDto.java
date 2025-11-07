package com.albunyaan.tube.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Generic cursor-based pagination response for public API.
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

        public PageInfo() {
        }

        public PageInfo(String nextCursor) {
            this.nextCursor = nextCursor;
        }

        public String getNextCursor() {
            return nextCursor;
        }

        public void setNextCursor(String nextCursor) {
            this.nextCursor = nextCursor;
        }
    }
}

