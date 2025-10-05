package com.albunyaan.tube.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * BACKEND-DL-02: Next-Up Recommendation Response DTO
 */
public class NextUpDto {

    private List<VideoItem> items;
    private String nextCursor;

    public NextUpDto() {
        this.items = new ArrayList<>();
    }

    public NextUpDto(List<VideoItem> items, String nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }

    public List<VideoItem> getItems() {
        return items;
    }

    public void setItems(List<VideoItem> items) {
        this.items = items;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public void setNextCursor(String nextCursor) {
        this.nextCursor = nextCursor;
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
