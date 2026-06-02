package com.albunyaan.tube.dto.sync;

public class SubscriptionSyncDto extends SyncRowDto {
    private String channelUrl;
    private String name;
    private String avatarUrl;
    private long subscribedAt;
    // A10 — import metadata fields
    private String approvalStatus;
    private String source;
    private Long importedAt;

    public String getChannelUrl()           { return channelUrl; }
    public void setChannelUrl(String v)     { this.channelUrl = v; }
    public String getName()                 { return name; }
    public void setName(String v)           { this.name = v; }
    public String getAvatarUrl()            { return avatarUrl; }
    public void setAvatarUrl(String v)      { this.avatarUrl = v; }
    public long getSubscribedAt()           { return subscribedAt; }
    public void setSubscribedAt(long v)     { this.subscribedAt = v; }
    public String getApprovalStatus()       { return approvalStatus; }
    public void setApprovalStatus(String v) { this.approvalStatus = v; }
    public String getSource()               { return source; }
    public void setSource(String v)         { this.source = v; }
    public Long getImportedAt()             { return importedAt; }
    public void setImportedAt(Long v)       { this.importedAt = v; }
}
