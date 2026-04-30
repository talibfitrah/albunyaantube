package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.firestore.annotation.IgnoreExtraProperties;
import java.util.ArrayList;
import java.util.List;

@IgnoreExtraProperties
public class SearchableStream {

    @DocumentId
    private String streamId;

    private String title;
    private String titleNorm;       // lowercase + Arabic-normalized title for scoring
    private String thumbnailUrl;
    private String channelId;
    private String channelName;
    private String streamType;      // VIDEO, SHORT, LIVE
    private Long durationSeconds;
    private Long viewCount;
    private List<String> searchTokens = new ArrayList<>();
    private List<String> sourceKeys = new ArrayList<>();  // ["channel:UC...", "playlist:PL..."]
    private boolean visible;
    private Timestamp indexedAt;
    private Timestamp lastSeenAt;

    public SearchableStream() {}

    public String getStreamId() { return streamId; }
    public void setStreamId(String streamId) { this.streamId = streamId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTitleNorm() { return titleNorm; }
    public void setTitleNorm(String titleNorm) { this.titleNorm = titleNorm; }

    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }

    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public String getStreamType() { return streamType; }
    public void setStreamType(String streamType) { this.streamType = streamType; }

    public Long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Long durationSeconds) { this.durationSeconds = durationSeconds; }

    public Long getViewCount() { return viewCount; }
    public void setViewCount(Long viewCount) { this.viewCount = viewCount; }

    public List<String> getSearchTokens() { return searchTokens; }
    public void setSearchTokens(List<String> searchTokens) { this.searchTokens = searchTokens != null ? searchTokens : new ArrayList<>(); }

    public List<String> getSourceKeys() { return sourceKeys; }
    public void setSourceKeys(List<String> sourceKeys) { this.sourceKeys = sourceKeys != null ? sourceKeys : new ArrayList<>(); }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public Timestamp getIndexedAt() { return indexedAt; }
    public void setIndexedAt(Timestamp indexedAt) { this.indexedAt = indexedAt; }

    public Timestamp getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Timestamp lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
