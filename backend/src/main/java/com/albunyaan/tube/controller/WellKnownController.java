package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.AndroidProperties;
import com.albunyaan.tube.config.IosProperties;
import com.albunyaan.tube.exception.ResourceNotFoundException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Serves the two "well-known" discovery files iOS Universal Links and
 * Android App Links verify against before either platform will open
 * {@code app.fitrahtube.com} URLs in-app instead of a browser. Both must be
 * reachable anonymously as plain {@code application/json}, with no redirect
 * -- see spec {@code docs/superpowers/specs/2026-08-23-ios-app-design.md}
 * §12 and the Universal Link notes in §6/§8. Permitted anonymously by exact
 * path in {@link com.albunyaan.tube.security.SecurityConfig}; before that,
 * both 403'd through Spring Security's default {@code anyRequest().authenticated()}
 * fallback for an unmapped path.
 */
@RestController
public class WellKnownController {

    /** Android Digital Asset Links {@code package_name}. Not config-driven
     *  (only the fingerprints are, per spec) -- this is the same stable app id CLAUDE.md
     *  pins as back-compat-only, so it is a literal here like it is elsewhere in the repo. */
    private static final String ANDROID_PACKAGE_NAME = "com.albunyaan.tube";

    /**
     * Universal Link paths the iOS app's parser actually resolves, derived
     * from {@code ios/FitrahTube/App/DeepLinkParser.swift:26-32}: the
     * {@code https} branch there accepts a 2-segment path whose first
     * segment is {@code watch|channel|playlist} (mapping "watch" to the
     * video route) and explicitly rejects "shorts"
     * ({@code segments[0] != "shorts"} guard) and anything not exactly 2
     * segments. That branch also strips a leading "api" segment, so
     * {@code /api/watch/*} etc. parse too -- left out of this list on
     * purpose per this ticket's instruction to advertise the human-facing
     * paths only, not the REST API surface.
     */
    private static final List<String> AASA_PATHS = List.of("/watch/*", "/channel/*", "/playlist/*");

    private final IosProperties iosProperties;
    private final AndroidProperties androidProperties;

    public WellKnownController(IosProperties iosProperties, AndroidProperties androidProperties) {
        this.iosProperties = iosProperties;
        this.androidProperties = androidProperties;
    }

    @GetMapping({"/.well-known/apple-app-site-association", "/apple-app-site-association"})
    public ResponseEntity<Map<String, Object>> appleAppSiteAssociation() {
        Map<String, Object> detail = Map.of(
                "appID", iosProperties.getTeamId() + "." + iosProperties.getBundleId(),
                "paths", AASA_PATHS);
        Map<String, Object> body = Map.of(
                "applinks", Map.of(
                        "apps", List.of(),
                        "details", List.of(detail)));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .body(body);
    }

    @GetMapping("/.well-known/assetlinks.json")
    public ResponseEntity<List<Map<String, Object>>> assetlinks() {
        List<String> fingerprints = androidProperties.getSha256Fingerprints();
        if (fingerprints.isEmpty()) {
            // Standard 404 envelope (GlobalExceptionHandler) rather than an
            // empty relation array -- an empty assetlinks.json would still
            // be valid JSON but would make App Links verification fail
            // silently instead of visibly.
            throw new ResourceNotFoundException("No Android signing fingerprints configured for assetlinks.json");
        }
        Map<String, Object> entry = Map.of(
                "relation", List.of("delegate_permission/common.handle_all_urls"),
                "target", Map.of(
                        "namespace", "android_app",
                        "package_name", ANDROID_PACKAGE_NAME,
                        "sha256_cert_fingerprints", fingerprints));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(entry));
    }
}
