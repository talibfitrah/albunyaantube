package com.albunyaan.tube.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

public class IndexStreamsRequest {

    private String sourceType;  // "CHANNEL" or "PLAYLIST"
    private String sourceId;    // YouTube channel/playlist ID
    @Valid
    @Size(max = 60)
    private List<StreamItemDto> items;

    public IndexStreamsRequest() {}

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public List<StreamItemDto> getItems() { return items; }
    public void setItems(List<StreamItemDto> items) { this.items = items; }
}
