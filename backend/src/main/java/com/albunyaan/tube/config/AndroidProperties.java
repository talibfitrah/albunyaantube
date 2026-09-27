package com.albunyaan.tube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Android App Links config for {@code WellKnownController}'s
 * assetlinks.json response. Keys per spec
 * {@code docs/superpowers/specs/2026-08-23-ios-app-design.md} §12.
 */
@Configuration
@ConfigurationProperties(prefix = "app.android")
public class AndroidProperties {

    /**
     * SHA-256 signing certificate fingerprints for Android Digital Asset
     * Links (comma-separated when set via an env var). Empty until Play App
     * Signing is configured (2026-09-02) -- {@code WellKnownController}
     * responds 404 for assetlinks.json while this is empty rather than
     * publish an empty, useless relation.
     */
    private List<String> sha256Fingerprints = List.of();

    public List<String> getSha256Fingerprints() {
        return sha256Fingerprints;
    }

    public void setSha256Fingerprints(List<String> sha256Fingerprints) {
        this.sha256Fingerprints = sha256Fingerprints;
    }
}
