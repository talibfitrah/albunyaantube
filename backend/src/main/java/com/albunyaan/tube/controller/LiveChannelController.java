package com.albunyaan.tube.controller;

import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.service.LiveEndpointService;
import com.albunyaan.tube.service.LiveTabResult;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Spec §4.1: the channel's current lives for the 1.0.4 apps' live-channel mode (§9.1), and the removal check of an exact
 * live link. {@link LiveEndpointService} answers from the poller's in-memory view; this maps its answers to HTTP: none
 * yet is 503 + Retry-After (the search index's shape, GlobalExceptionHandler), not found the apps' definitive 404.
 */
@RestController
@RequestMapping("/api/v1/live")
public class LiveChannelController {

    /** The trimmed shape the apps read (RV M10). Built by hand, never a raw model. */
    public record LiveChannelLives(String channelId, String status, List<Live> lives) {}

    public record Live(String id, String title) {}

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private final LiveEndpointService endpoint;

    public LiveChannelController(LiveEndpointService endpoint) {
        this.endpoint = endpoint;
    }

    @GetMapping("/channels/{channelId}")
    public ResponseEntity<LiveChannelLives> channel(@PathVariable String channelId,
                                                    @RequestParam(name = "live", required = false) String candidate) {
        if (candidate != null && !VIDEO_ID.matcher(candidate).matches()) {
            throw new IllegalArgumentException("live is not a video id");        // GlobalExceptionHandler: 400 JSON
        }
        List<LiveTabResult.LiveItem> lives = endpoint.lives(channelId, candidate);   // 404: ResourceNotFoundException
        if (lives == null) return retryLater();
        List<Live> body = lives.stream().map(l -> new Live(l.id(), l.title() == null ? "" : l.title())).toList();
        // a candidate answer is one client's evidence: never cached for another request
        CacheControl cache = candidate == null ? CacheControl.maxAge(15, TimeUnit.SECONDS).cachePublic() : CacheControl.noStore();
        return ResponseEntity.ok().cacheControl(cache)
                .body(new LiveChannelLives(channelId, body.isEmpty() ? "OFFLINE" : "LIVE", body));
    }

    /** The removal check of an exact live link while it plays: 204 while served, else the apps' definitive 404. */
    @GetMapping("/links/{videoId}")
    public ResponseEntity<Void> link(@PathVariable String videoId) {
        if (!VIDEO_ID.matcher(videoId).matches()) {
            throw new IllegalArgumentException("videoId is not a video id");     // GlobalExceptionHandler: 400 JSON
        }
        Boolean served = endpoint.linkServed(videoId);
        if (served == null) return retryLater();
        if (!served) throw new ResourceNotFoundException("LiveLink", videoId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static <T> ResponseEntity<T> retryLater() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "60").build();
    }
}
