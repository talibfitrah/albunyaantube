package com.albunyaan.tube.dto.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Cubic R-final5 P2 — strict deserializer rejects unknown JSON properties.
 * SyncService builds the upsert payload Map explicitly from typed getters,
 * so an unknown JSON field could not reach Firestore today; this annotation
 * closes the gap defensively against any future call site that might pipe a
 * raw deserialized Map through.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public class PutSubscriptionRequest {
    @NotBlank
    private String channelUrl;
    @NotBlank
    private String name;
    private String avatarUrl;
    @NotNull
    private Long subscribedAt;

    public String getChannelUrl() {
        return channelUrl;
    }

    public void setChannelUrl(String v) {
        this.channelUrl = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String v) {
        this.avatarUrl = v;
    }

    public Long getSubscribedAt() {
        return subscribedAt;
    }

    public void setSubscribedAt(Long v) {
        this.subscribedAt = v;
    }

    // A10 — import metadata fields
    private String approvalStatus;
    private String source;
    private Long importedAt;

    public String getApprovalStatus() {
        return approvalStatus;
    }

    public void setApprovalStatus(String v) {
        this.approvalStatus = v;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String v) {
        this.source = v;
    }

    public Long getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(Long v) {
        this.importedAt = v;
    }
}
