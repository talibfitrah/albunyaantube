package com.albunyaan.tube.dto.sync;

public class PlaylistSyncDto extends SyncRowDto {
    private String playlistUrl;
    private String name;
    private String thumbnailUrl;
    private String uploaderName;
    private long savedAt;
    // A10 — import metadata fields
    private String approvalStatus;
    private String source;
    private Long importedAt;

    public String getPlaylistUrl()          { return playlistUrl; }
    public void setPlaylistUrl(String v)    { this.playlistUrl = v; }
    public String getName()                 { return name; }
    public void setName(String v)           { this.name = v; }
    public String getThumbnailUrl()         { return thumbnailUrl; }
    public void setThumbnailUrl(String v)   { this.thumbnailUrl = v; }
    public String getUploaderName()         { return uploaderName; }
    public void setUploaderName(String v)   { this.uploaderName = v; }
    public long getSavedAt()                { return savedAt; }
    public void setSavedAt(long v)          { this.savedAt = v; }
    public String getApprovalStatus()       { return approvalStatus; }
    public void setApprovalStatus(String v) { this.approvalStatus = v; }
    public String getSource()               { return source; }
    public void setSource(String v)         { this.source = v; }
    public Long getImportedAt()             { return importedAt; }
    public void setImportedAt(Long v)       { this.importedAt = v; }
}
