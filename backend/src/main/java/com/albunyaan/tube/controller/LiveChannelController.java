package com.albunyaan.tube.controller;

import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.service.LiveChannelService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Spec §4.1: the channel's current lives for the 1.0.4 apps' live-channel mode (§9.1). Served from the poller's
 * in-memory snapshot with the R11 filter already applied: no Firestore read and no YouTube request per call, and no
 * query parameters (the public nudge is deleted, R17).
 */
@RestController
@RequestMapping("/api/v1/live")
public class LiveChannelController {

    /** The trimmed shape the apps read (RV M10). Built by hand, never a raw model. */
    public record LiveChannelLives(String channelId, String status, List<Live> lives) {}

    public record Live(String id, String title) {}

    private final LiveChannelService live;

    public LiveChannelController(LiveChannelService live) {
        this.live = live;
    }

    @GetMapping("/channels/{channelId}")
    public ResponseEntity<LiveChannelLives> channel(@PathVariable String channelId) {
        // 404 is the apps' definitive "not live" stop: never send it before a view built from the loaded states is
        // published. A failed boot load or view refresh waits for the poller's next cycle (~1 min after boot), which
        // publishes only after its Live-tab reads. Same 503 + Retry-After shape as the search index (GlobalExceptionHandler).
        if (!live.published()) {
            return retryLater();
        }
        if (!live.entries().containsKey(channelId)) {
            // A channel just added to a LIVE category is seeded at once but listed only by the next cycle (stage 9 Cubic r2).
            if (live.recentlyAdded(channelId)) return retryLater();
            throw new ResourceNotFoundException("LiveChannel", channelId);
        }
        List<Live> lives = live.served().getOrDefault(channelId, List.of()).stream()
                .map(l -> new Live(l.id(), l.title() == null ? "" : l.title())).toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(15, TimeUnit.SECONDS).cachePublic())
                .body(new LiveChannelLives(channelId, lives.isEmpty() ? "OFFLINE" : "LIVE", lives));
    }

    private static ResponseEntity<LiveChannelLives> retryLater() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "60").build();
    }
}
