package com.albunyaan.tube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Plan F (ADMIN-USER-01) — outbound mail feature flag + identity.
 */
@ConfigurationProperties(prefix = "mail")
public class MailProperties {
    private boolean enabled = false;
    private String fromAddress;
    private String fromDisplayName;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getFromDisplayName() { return fromDisplayName; }
    public void setFromDisplayName(String fromDisplayName) { this.fromDisplayName = fromDisplayName; }
}
