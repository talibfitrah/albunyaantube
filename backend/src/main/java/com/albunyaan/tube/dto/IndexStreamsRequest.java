package com.albunyaan.tube.dto;

import java.util.List;

public class IndexStreamsRequest {

    private String sourceType;  // "CHANNEL" or "PLAYLIST"
    private String sourceId;    // YouTube channel/playlist ID
    private List<StreamItemDto> items;

    public IndexStreamsRequest() {}

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public List<StreamItemDto> getItems() { return items; }
    public void setItems(List<StreamItemDto> items) { this.items = items; }
}
