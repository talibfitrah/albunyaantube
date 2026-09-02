package com.albunyaan.tube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Backs {@code WellKnownController}'s AASA / assetlinks responses (iOS
 * Universal Links + Android App Links). See spec
 * {@code docs/superpowers/specs/2026-08-23-ios-app-design.md} §12.
 */
@Configuration
@ConfigurationProperties(prefix = "app.universal-links")
public class UniversalLinksProperties {

    /** Apple Developer Team ID, prefixed onto {@link #iosBundleId} to form the AASA appID. */
    private String appleTeamId = "72PF8SBQR6";

    /** iOS bundle id. Same string as the Android applicationId today, kept as its own key. */
    private String iosBundleId = "com.albunyaan.tube";

    /**
     * SHA-256 signing certificate fingerprints for Android Digital Asset
     * Links. Empty until Play App Signing is configured (2026-09-02) --
     * {@code WellKnownController} responds 404 for assetlinks.json while
     * this is empty rather than publish an empty, useless relation.
     */
    private List<String> androidSha256Fingerprints = List.of();

    public String getAppleTeamId() {
        return appleTeamId;
    }

    public void setAppleTeamId(String appleTeamId) {
        this.appleTeamId = appleTeamId;
    }

    public String getIosBundleId() {
        return iosBundleId;
    }

    public void setIosBundleId(String iosBundleId) {
        this.iosBundleId = iosBundleId;
    }

    public List<String> getAndroidSha256Fingerprints() {
        return androidSha256Fingerprints;
    }

    public void setAndroidSha256Fingerprints(List<String> androidSha256Fingerprints) {
        this.androidSha256Fingerprints = androidSha256Fingerprints;
    }
}
