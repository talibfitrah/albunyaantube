package com.albunyaan.tube.model;

import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.firestore.annotation.IgnoreExtraProperties;
import java.util.ArrayList;
import java.util.List;

@IgnoreExtraProperties
public class SearchableStream {

    @DocumentId
    private String streamId;

    private String title;
    private String thumbnailUrl;
    private String channelName;
    private Long durationSeconds;
    private Long viewCount;
    private List<String> sourceKeys = new ArrayList<>();  // ["channel:UC...", "playlist:PL..."]
    private boolean visible;

    public SearchableStream() {}

    public String getStreamId() { return streamId; }
    public void setStreamId(String streamId) { this.streamId = streamId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public Long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Long durationSeconds) { this.durationSeconds = durationSeconds; }

    public Long getViewCount() { return viewCount; }
    public void setViewCount(Long viewCount) { this.viewCount = viewCount; }

    public List<String> getSourceKeys() { return sourceKeys; }
    public void setSourceKeys(List<String> sourceKeys) { this.sourceKeys = sourceKeys != null ? sourceKeys : new ArrayList<>(); }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
}
