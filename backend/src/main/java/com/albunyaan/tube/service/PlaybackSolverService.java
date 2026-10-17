package com.albunyaan.tube.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * The thin layer between the iOS app and the local challenge solver (spec §4.6, R26-R30): the kill flag, validation
 * tightened to the observed shapes, a 6 h result cache, at most 8 in flight and 20 a second, and the 60 s
 * {@code /player} copy. The {@link DubPotokenService} shape. Logs nothing per request (R21); while the sidecar cannot
 * be reached, one ERROR line an hour (R38).
 */
@Service
public class PlaybackSolverService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackSolverService.class);
    static final Pattern PLAYER_ID = Pattern.compile("[0-9a-f]{8}");
    static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_=-]+");
    private static final Duration SIDECAR_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration PLAYER_COPY = Duration.ofSeconds(60);
    private static final int MAX_IN_FLIGHT = 8;
    private static final int MAX_PER_SECOND = 20;

    /** What the controller sends: HTTP status, JSON body, and Retry-After seconds (null = no header). */
    public record Answer(int status, Object body, Integer retryAfter) {
        static Answer ok(Object body) { return new Answer(200, body, null); }
        static Answer of(int status, Integer retryAfter) { return new Answer(status, Map.of(), retryAfter); }
    }

    private record Sidecar(int status, Map<String, Object> json) {}

    private final String url;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
    private final Cache<String, String> answers = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofHours(6))
            .build();
    /** Non-final only so PlaybackSolveIT can flip and pin them; nothing else writes them. */
    private volatile boolean enabled;
    private Clock clock;
    private long windowSecond = Long.MIN_VALUE;   // guarded by this
    private int windowCount;                      // guarded by this
    private volatile Map<String, Object> playerCopy;
    private volatile Instant playerCopiedAt = Instant.EPOCH;
    private volatile Instant lastUnreachableLog = Instant.EPOCH;

    @Autowired
    public PlaybackSolverService(@Value("${playback.solver.url:http://127.0.0.1:4417}") String url,
                                 @Value("${playback.solver.enabled:true}") boolean enabled,
                                 Clock clock) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.enabled = enabled;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(SIDECAR_TIMEOUT).build();
    }

    // ponytail: no single-flight, so callers that meet an expired (or warming, uncached) copy each ask Node's cheap
    // /player; add a lock or a 503 copy if /player traffic ever shows up in Node's CPU.
    public Answer player() {
        if (!enabled) return Answer.of(503, 120);
        Instant now = clock.instant();
        Map<String, Object> copy = playerCopy;
        if (copy != null && now.isBefore(playerCopiedAt.plus(PLAYER_COPY))) return Answer.ok(copy);
        Sidecar reply = call(HttpRequest.newBuilder(URI.create(url + "/player")).GET());
        if (reply.status() != 200) return Answer.of(503, 120);
        playerCopy = reply.json();
        playerCopiedAt = now;
        return Answer.ok(reply.json());
    }

    public Answer solve(String playerId, List<String> n, List<String> s) {
        if (!valid(playerId, n, s)) return Answer.of(400, null);
        if (!enabled) return Answer.of(503, 120);
        Map<String, String> nOut = new LinkedHashMap<>();
        Map<String, String> sOut = new LinkedHashMap<>();
        List<String> nMissing = fromCache(playerId, "n", n, nOut);
        List<String> sMissing = fromCache(playerId, "s", s, sOut);
        if (!nMissing.isEmpty() || !sMissing.isEmpty()) {
            if (!withinGlobalRate() || !inFlight.tryAcquire()) return Answer.of(503, 10);
            Sidecar reply;
            try {
                reply = call(HttpRequest.newBuilder(URI.create(url + "/solve"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body(playerId, nMissing, sMissing))));
            } finally {
                inFlight.release();
            }
            if (reply.status() == 409) return Answer.of(409, null);
            if (reply.status() != 200) return Answer.of(503, 120);
            remember(playerId, "n", nMissing, reply.json().get("n"), nOut);
            remember(playerId, "s", sMissing, reply.json().get("s"), sOut);
        }
        return Answer.ok(Map.of("n", nOut, "s", sOut));
    }

    static boolean valid(String playerId, List<String> n, List<String> s) {
        return playerId != null && PLAYER_ID.matcher(playerId).matches()
                && n != null && n.size() >= 1 && n.size() <= 4 && n.stream().allMatch(x -> shaped(x, 8, 32))
                && s != null && s.size() <= 64 && s.stream().allMatch(x -> shaped(x, 64, 160));
    }

    private static boolean shaped(String x, int min, int max) {
        return x != null && x.length() >= min && x.length() <= max && CHALLENGE.matcher(x).matches();
    }

    private List<String> fromCache(String playerId, String kind, List<String> challenges, Map<String, String> out) {
        List<String> missing = new ArrayList<>();
        for (String c : challenges) {
            String hit = answers.getIfPresent(playerId + "|" + kind + "|" + c);
            if (hit != null) out.put(c, hit); else if (!missing.contains(c)) missing.add(c);
        }
        return missing;
    }

    private void remember(String playerId, String kind, List<String> asked, Object solved, Map<String, String> out) {
        if (!(solved instanceof Map<?, ?> map)) return;
        for (String c : asked) {
            if (map.get(c) instanceof String answer) {
                out.put(c, answer);
                answers.put(playerId + "|" + kind + "|" + c, answer);
            }
        }
    }

    /** M9: the semaphore bounds concurrency, not rate. A one-second fixed window across all users. */
    // ponytail: a fixed window lets up to 40 through across a second boundary; a sliding window if Node ever feels it.
    private synchronized boolean withinGlobalRate() {
        long second = clock.millis() / 1000;
        if (second != windowSecond) { windowSecond = second; windowCount = 0; }
        return ++windowCount <= MAX_PER_SECOND;
    }

    private String body(String playerId, List<String> n, List<String> s) {
        try {
            return json.writeValueAsString(Map.of("playerId", playerId, "n", n, "s", s));
        } catch (Exception e) {
            throw new IllegalStateException(e);   // a map of strings always serializes
        }
    }

    private Sidecar call(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(SIDECAR_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = response.statusCode() == 200
                    ? json.readValue(response.body(), new TypeReference<>() { })
                    : Map.of();
            return new Sidecar(response.statusCode(), body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Sidecar(-1, Map.of());
        } catch (Exception e) {
            Instant now = clock.instant();
            if (!now.isBefore(lastUnreachableLog.plus(Duration.ofHours(1)))) {
                lastUnreachableLog = now;
                // R38: at most once an hour. The class name only: a parse error's message can quote a body that
                // holds challenges and answers (R21).
                log.error("playback solver unreachable at {}: {}", url, e.getClass().getSimpleName());
            }
            return new Sidecar(-1, Map.of());
        }
    }
}
