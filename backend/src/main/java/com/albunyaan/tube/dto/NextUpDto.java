package com.albunyaan.tube.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.ArrayList;
import java.util.List;

/**
 * BACKEND-DL-02: Next-Up Recommendation Response DTO
 *
 * Standardized to match CursorPageDto pattern with data[] and pageInfo.
 */
public class NextUpDto {

    private List<VideoItem> data;

    @JsonProperty("pageInfo")
    private CursorPageDto.PageInfo pageInfo;

    public NextUpDto() {
        this.data = new ArrayList<>();
        this.pageInfo = new CursorPageDto.PageInfo(null);
    }

    public NextUpDto(List<VideoItem> data, String nextCursor) {
        this.data = data;
        this.pageInfo = new CursorPageDto.PageInfo(nextCursor);
    }

    public List<VideoItem> getData() {
        return data;
    }

    public void setData(List<VideoItem> data) {
        this.data = data;
    }

    public CursorPageDto.PageInfo getPageInfo() {
        return pageInfo;
    }

    public void setPageInfo(CursorPageDto.PageInfo pageInfo) {
        this.pageInfo = pageInfo;
    }

    /**
     * @deprecated Use getData() instead. Included for backward compatibility.
     */
    @Deprecated
    @JsonGetter("items")
    public List<VideoItem> getItems() {
        return data;
    }

    /**
     * @deprecated Use setData() instead
     */
    @Deprecated
    @JsonSetter("items")
    public void setItems(List<VideoItem> items) {
        this.data = items;
    }

    /**
     * @deprecated Use getPageInfo().getNextCursor() instead. Included for backward compatibility.
     */
    @Deprecated
    @JsonGetter("nextCursor")
    public String getNextCursor() {
        return pageInfo != null ? pageInfo.getNextCursor() : null;
    }

    /**
     * @deprecated Use setPageInfo() instead
     */
    @Deprecated
    @JsonSetter("nextCursor")
    public void setNextCursor(String nextCursor) {
        if (this.pageInfo == null) {
            this.pageInfo = new CursorPageDto.PageInfo(nextCursor);
        } else {
            this.pageInfo.setNextCursor(nextCursor);
        }
    }

    /**
     * Video item for next-up queue
     */
    public static class VideoItem {
        private String id;
        private String title;
        private String channelName;
        private int durationSeconds;
        private String thumbnailUrl;
        private String category;

        public VideoItem() {
        }

        public VideoItem(String id, String title, String channelName, int durationSeconds, 
                        String thumbnailUrl, String category) {
            this.id = id;
            this.title = title;
            this.channelName = channelName;
            this.durationSeconds = durationSeconds;
            this.thumbnailUrl = thumbnailUrl;
            this.category = category;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getChannelName() {
            return channelName;
        }

        public void setChannelName(String channelName) {
            this.channelName = channelName;
        }

        public int getDurationSeconds() {
            return durationSeconds;
        }

        public void setDurationSeconds(int durationSeconds) {
            this.durationSeconds = durationSeconds;
        }

        public String getThumbnailUrl() {
            return thumbnailUrl;
        }

        public void setThumbnailUrl(String thumbnailUrl) {
            this.thumbnailUrl = thumbnailUrl;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }
    }
}

