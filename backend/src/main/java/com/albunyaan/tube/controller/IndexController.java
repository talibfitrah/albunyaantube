package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.IndexStreamsRequest;
import com.albunyaan.tube.dto.StreamItemDto;
import com.albunyaan.tube.service.StreamIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class IndexController {

    private static final Logger log = LoggerFactory.getLogger(IndexController.class);

    // YouTube video IDs are exactly 11 alphanumeric/dash/underscore chars
    private static final Pattern STREAM_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    // YouTube channel IDs: "UC" + 22 chars = 24 total
    private static final Pattern CHANNEL_ID_PATTERN = Pattern.compile("^UC[A-Za-z0-9_-]{22}$");
    // Playlist IDs vary in length and prefix
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{10,50}$");
    private static final Set<String> ALLOWED_THUMBNAIL_HOSTS = Set.of(
            "i.ytimg.com", "i9.ytimg.com", "yt3.ggpht.com", "yt3.googleusercontent.com"
    );
    private static final int MAX_ITEMS = 50;
    private static final long RATE_LIMIT_MS = 30_000L; // 30 seconds per device per source

    // sourceKey → (deviceId → lastRequestTime). Intentionally unbounded for simplicity.
    private final Map<String, Map<String, Long>> rateLimitMap = new ConcurrentHashMap<>();

    private final StreamIndexService streamIndexService;

    public IndexController(StreamIndexService streamIndexService) {
        this.streamIndexService = streamIndexService;
    }

    @PostMapping("/index/streams")
    public ResponseEntity<Void> indexStreams(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestBody IndexStreamsRequest request) {

        if (!"CHANNEL".equals(request.getSourceType()) && !"PLAYLIST".equals(request.getSourceType())) {
            return ResponseEntity.badRequest().build();
        }
        if (!isValidSourceId(request.getSourceType(), request.getSourceId())) {
            return ResponseEntity.badRequest().build();
        }
        if (deviceId != null && isRateLimited(request.getSourceType() + ":" + request.getSourceId(), deviceId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        List<StreamItemDto> items = request.getItems() == null ? List.of() : request.getItems().stream()
                .limit(MAX_ITEMS)
                .filter(this::isValidItem)
                .collect(Collectors.toList());

        String sourceId = request.getSourceId();
        String sourceType = request.getSourceType();

        CompletableFuture.runAsync(() -> {
            try {
                if ("CHANNEL".equals(sourceType)) {
                    streamIndexService.indexFromChannel(sourceId, items);
                } else {
                    streamIndexService.indexFromPlaylist(sourceId, items);
                }
            } catch (Exception e) {
                log.warn("Async indexing failed for {} {}: {}", sourceType, sourceId, e.getMessage());
            }
        });

        return ResponseEntity.accepted().build();
    }

    private boolean isValidSourceId(String sourceType, String id) {
        if (id == null) return false;
        return "CHANNEL".equals(sourceType)
                ? CHANNEL_ID_PATTERN.matcher(id).matches()
                : PLAYLIST_ID_PATTERN.matcher(id).matches();
    }

    private boolean isValidItem(StreamItemDto item) {
        if (item.getId() == null || !STREAM_ID_PATTERN.matcher(item.getId()).matches()) return false;
        if (item.getName() == null || item.getName().isBlank() || item.getName().length() > 300) return false;
        if (item.getThumbnailUrl() != null) {
            try {
                String host = new URL(item.getThumbnailUrl()).getHost();
                if (!ALLOWED_THUMBNAIL_HOSTS.contains(host)) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private boolean isRateLimited(String sourceKey, String deviceId) {
        Map<String, Long> byDevice = rateLimitMap.computeIfAbsent(sourceKey, k -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        Long last = byDevice.get(deviceId);
        if (last != null && now - last < RATE_LIMIT_MS) return true;
        byDevice.put(deviceId, now);
        return false;
    }
}
