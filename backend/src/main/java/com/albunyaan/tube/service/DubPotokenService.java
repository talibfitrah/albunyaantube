package com.albunyaan.tube.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Mints YouTube GVS (streaming) poTokens for dub-audio playback.
 *
 * <p>Web-family dub audio URLs require a videoId-bound GVS poToken to stream past the first ~1&nbsp;MB.
 * That token must come from a "full" (sps=3) BotGuard attestation, which an Android WebView cannot
 * produce — it only yields an sps=2 "preview" token that caps at 1&nbsp;MB (proven on-device with both
 * the app's client and the upstream {@code bgutils-js} library). Node/desktop-class environments DO
 * produce sps=3, so the actual minting runs in a small sidecar (the {@code bgutil-ytdlp-pot-provider}
 * service, BotGuard in Node) deployed alongside this app. This service is a thin cache-and-proxy:
 * one mint per videoId per ~6&nbsp;h (the token's lifetime), so steady-state load is negligible.
 *
 * <p>Configure the sidecar location with {@code dub.potoken.sidecar-url} (default
 * {@code http://localhost:4416}). Returns {@code null} on any failure so the caller can degrade
 * gracefully (the Android app then keeps the VR original audio — never breaks playback).
 */
@Service
public class DubPotokenService {

    private static final Logger log = LoggerFactory.getLogger(DubPotokenService.class);

    /** Tokens live ~6&nbsp;h; cache just under that so we never hand out an about-to-expire one. */
    private static final Duration POT_TTL = Duration.ofHours(5);

    /**
     * YouTube video IDs are exactly 11 URL-safe-base64 chars. Validating before the cache lookup keeps
     * an unauthenticated caller from polluting the cache or amplifying BotGuard mints with junk ids
     * (each distinct miss would otherwise fire a 30&nbsp;s sidecar mint). Anything else returns null -> 404.
     */
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private final Cache<String, String> potCache = Caffeine.newBuilder()
            .expireAfterWrite(POT_TTL)
            .maximumSize(10_000)
            .build();

    /**
     * Failed mints are negative-cached briefly so a format-valid but unmintable id doesn't re-fire a
     * 30&nbsp;s sidecar mint on every request (cache-stampede / DoS-amplification guard).
     */
    private static final Duration NEG_TTL = Duration.ofSeconds(60);
    private final Cache<String, Boolean> mintFailureCache = Caffeine.newBuilder()
            .expireAfterWrite(NEG_TTL)
            .maximumSize(10_000)
            .build();

    /**
     * Bounds concurrent BotGuard mints. The endpoint is public/unauthenticated and each mint blocks up
     * to 30&nbsp;s on the single Node sidecar, so an unauthenticated flood of distinct valid-format ids
     * could otherwise pin every servlet worker (thread-pool-exhaustion DoS) and abuse the server as a
     * free token-mint oracle. Excess concurrent requests fail fast (null -> 404) and the app keeps the
     * VR original. Steady-state legitimate load is one mint per videoId per ~6&nbsp;h, far under this cap.
     */
    private static final int MAX_CONCURRENT_MINTS = 6;
    private final Semaphore mintPermits = new Semaphore(MAX_CONCURRENT_MINTS);

    private final HttpClient http;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String sidecarUrl;

    // @Autowired is REQUIRED: this class has a second (test-seam) constructor, so Spring can no longer
    // pick the injection constructor implicitly and would fail with "No default constructor found".
    @org.springframework.beans.factory.annotation.Autowired
    public DubPotokenService(
            @Value("${dub.potoken.sidecar-url:http://localhost:4416}") String sidecarUrl) {
        this(sidecarUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /** Test seam: inject a mock {@link HttpClient} to verify validation/cache behavior without a sidecar. */
    DubPotokenService(String sidecarUrl, HttpClient http) {
        this.sidecarUrl = sidecarUrl.endsWith("/") ? sidecarUrl.substring(0, sidecarUrl.length() - 1) : sidecarUrl;
        this.http = http;
    }

    /**
     * Return a cached or freshly-minted videoId-bound GVS poToken, or {@code null} on failure.
     * Cached per videoId for {@link #POT_TTL}.
     */
    public String getPotoken(String videoId) {
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) {
            return null;
        }
        String cached = potCache.getIfPresent(videoId);
        if (cached != null) {
            return cached;
        }
        // Recently-failed id: skip the sidecar (negative cache) so a bad/unmintable id can't re-fire a
        // 30 s mint every request.
        if (mintFailureCache.getIfPresent(videoId) != null) {
            return null;
        }
        // Bound concurrent mints. Fail fast rather than block a servlet worker for up to 30 s when the
        // sidecar is already saturated — the app degrades to the VR original.
        if (!mintPermits.tryAcquire()) {
            log.warn("Dub poToken mint rejected: {} concurrent mints in flight (videoId={})",
                    MAX_CONCURRENT_MINTS, videoId);
            return null;
        }
        String minted;
        try {
            minted = mint(videoId);
        } finally {
            mintPermits.release();
        }
        if (minted != null && !minted.isBlank()) {
            potCache.put(videoId, minted);
            return minted;
        }
        mintFailureCache.put(videoId, Boolean.TRUE);
        return null;
    }

    private String mint(String videoId) {
        try {
            String body = objectMapper.writeValueAsString(
                    java.util.Map.of("content_binding", videoId));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sidecarUrl + "/get_pot"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Dub poToken sidecar returned HTTP {} for videoId={}", response.statusCode(), videoId);
                return null;
            }
            JsonNode node = objectMapper.readTree(response.body());
            JsonNode pot = node.get("poToken");
            if (pot == null || pot.asText().isBlank()) {
                log.warn("Dub poToken sidecar response missing poToken for videoId={}", videoId);
                return null;
            }
            return pot.asText();
        } catch (Exception ex) {
            log.warn("Dub poToken mint failed for videoId={}: {}", videoId, ex.toString());
            return null;
        }
    }
}
