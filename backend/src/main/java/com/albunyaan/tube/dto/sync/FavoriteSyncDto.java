package com.albunyaan.tube.dto.sync;

public class FavoriteSyncDto extends SyncRowDto {
    private String title;
    private String channelName;
    private String thumbnailUrl;
    private int durationSeconds;
    private long addedAt;

    public String getTitle()                { return title; }
    public void setTitle(String v)          { this.title = v; }
    public String getChannelName()          { return channelName; }
    public void setChannelName(String v)    { this.channelName = v; }
    public String getThumbnailUrl()         { return thumbnailUrl; }
    public void setThumbnailUrl(String v)   { this.thumbnailUrl = v; }
    public int getDurationSeconds()         { return durationSeconds; }
    public void setDurationSeconds(int v)   { this.durationSeconds = v; }
    public long getAddedAt()                { return addedAt; }
    public void setAddedAt(long v)          { this.addedAt = v; }
}
