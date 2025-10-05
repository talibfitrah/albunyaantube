package com.albunyaan.tube.dto;

public class DownloadPolicyDto {
    private boolean allowed;
    private String reason;
    private boolean requiresEula;

    public DownloadPolicyDto() {}

    public DownloadPolicyDto(boolean allowed, String reason, boolean requiresEula) {
        this.allowed = allowed;
        this.reason = reason;
        this.requiresEula = requiresEula;
    }

    public static DownloadPolicyDto allowed() {
        return new DownloadPolicyDto(true, "Download allowed", false);
    }

    public static DownloadPolicyDto allowedWithEula() {
        return new DownloadPolicyDto(true, "Download allowed after EULA acceptance", true);
    }

    public static DownloadPolicyDto denied(String reason) {
        return new DownloadPolicyDto(false, reason, false);
    }

    public boolean isAllowed() { return allowed; }
    public void setAllowed(boolean allowed) { this.allowed = allowed; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public boolean isRequiresEula() { return requiresEula; }
    public void setRequiresEula(boolean requiresEula) { this.requiresEula = requiresEula; }
}
