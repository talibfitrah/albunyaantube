package com.albunyaan.tube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * iOS Universal Links config for {@code WellKnownController}'s AASA
 * response. Keys per spec
 * {@code docs/superpowers/specs/2026-08-23-ios-app-design.md} §12.
 */
@Configuration
@ConfigurationProperties(prefix = "app.ios")
public class IosProperties {

    /** Apple Developer Team ID, prefixed onto {@link #bundleId} to form the AASA appID. */
    private String teamId = "72PF8SBQR6";

    /** iOS bundle id. Same string as the Android applicationId today, kept as its own key. */
    private String bundleId = "com.albunyaan.tube";

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public String getBundleId() {
        return bundleId;
    }

    public void setBundleId(String bundleId) {
        this.bundleId = bundleId;
    }
}
