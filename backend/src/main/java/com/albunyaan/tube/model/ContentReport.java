package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;

import java.util.List;

public class ContentReport {

    @DocumentId
    private String id;

    private ReportTargetType targetType;
    private String targetId;
    private List<ReportReason> reasons;
    private String otherDescription;
    private String deviceId;
    private ReportStatus status;
    private Timestamp createdAt;
    private Timestamp resolvedAt;
    private String resolvedBy;
    private String resolutionNote;

    public ContentReport() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public ReportTargetType getTargetType() { return targetType; }
    public void setTargetType(ReportTargetType targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public List<ReportReason> getReasons() { return reasons; }
    public void setReasons(List<ReportReason> reasons) { this.reasons = reasons; }

    public String getOtherDescription() { return otherDescription; }
    public void setOtherDescription(String otherDescription) { this.otherDescription = otherDescription; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public ReportStatus getStatus() { return status; }
    public void setStatus(ReportStatus status) { this.status = status; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public Timestamp getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Timestamp resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }

    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }
}
