package com.albunyaan.tube.dto.sync;

public class SubscriptionSyncDto extends SyncRowDto {
    private String channelUrl;
    private String name;
    private String avatarUrl;
    private long subscribedAt;

    public String getChannelUrl()           { return channelUrl; }
    public void setChannelUrl(String v)     { this.channelUrl = v; }
    public String getName()                 { return name; }
    public void setName(String v)           { this.name = v; }
    public String getAvatarUrl()            { return avatarUrl; }
    public void setAvatarUrl(String v)      { this.avatarUrl = v; }
    public long getSubscribedAt()           { return subscribedAt; }
    public void setSubscribedAt(long v)     { this.subscribedAt = v; }
}
