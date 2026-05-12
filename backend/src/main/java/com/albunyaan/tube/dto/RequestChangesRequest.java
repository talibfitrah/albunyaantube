package com.albunyaan.tube.dto;

import jakarta.validation.constraints.NotBlank;

public class RequestChangesRequest {
    @NotBlank private String note;
    @NotBlank private String contentType;   // "channel" | "playlist" | "video"

    public String getNote()              { return note; }
    public void setNote(String v)        { this.note = v; }
    public String getContentType()       { return contentType; }
    public void setContentType(String v) { this.contentType = v; }
}
