package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.model.ContentReport;
import com.albunyaan.tube.model.ReportReason;
import com.albunyaan.tube.model.ReportStatus;
import com.albunyaan.tube.model.ReportTargetType;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.ContentReportService;
import com.albunyaan.tube.service.YouTubeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@RestController
public class ContentReportController {

    private static final String HEADER_DEVICE_ID = "X-Device-Id";

    private final ContentReportService reportService;
    private final YouTubeService youTubeService;

    public ContentReportController(ContentReportService reportService, YouTubeService youTubeService) {
        this.reportService = reportService;
        this.youTubeService = youTubeService;
    }

    @PostMapping("/api/v1/reports")
    public ResponseEntity<?> submitReport(
            @Valid @RequestBody SubmitReportRequest body,
            HttpServletRequest request) {
        String deviceKey = request.getHeader(HEADER_DEVICE_ID);
        if (deviceKey == null || deviceKey.isBlank()) {
            // Refuse rather than falling back to request.getRemoteAddr(): behind a reverse
            // proxy that does not forward X-Forwarded-For, every anonymous caller would share
            // the proxy's IP and (a) DoS legitimate clients out of the rate-limit bucket, or
            // (b) bypass rate limiting entirely by churning the missing header. The Android
            // app always sends X-Device-Id via NetworkModule, so refusing is safe.
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Missing X-Device-Id header"));
        }
        try {
            ContentReport saved = reportService.submitReport(
                    body.targetType(), body.targetId(),
                    body.reasons(), body.otherDescription(), deviceKey,
                    body.parentType(), body.parentId(), body.contentSubType());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("id", saved.getId(), "status", saved.getStatus()));
        } catch (ContentReportService.RateLimitExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded. Please try again later."));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to submit report."));
        } catch (ExecutionException | TimeoutException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to submit report."));
        }
    }

    @GetMapping("/api/admin/reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<List<ContentReport>> getReports(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) ReportTargetType targetType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<ContentReport> reports = reportService.getReports(status, targetType, page, size);
        return ResponseEntity.ok(reports);
    }

    @PatchMapping("/api/admin/reports/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<ContentReport> resolveReport(
            @PathVariable String id,
            @Valid @RequestBody ResolveReportRequest body,
            @AuthenticationPrincipal FirebaseUserDetails user)
            throws ExecutionException, InterruptedException, TimeoutException {
        String resolvedBy = user != null ? user.getEmail() : "unknown";
        ContentReport updated = reportService.resolveReport(id, body.status(), resolvedBy, body.note());
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/api/admin/reports/stats")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<ContentReportService.ReportStats> getStats()
            throws ExecutionException, InterruptedException, TimeoutException {
        return ResponseEntity.ok(reportService.getStats());
    }

    /**
     * Admin-only metadata lookup for any YouTube video/playlist/channel ID,
     * regardless of approval status. The reports table needs to render a
     * title and thumbnail even when the reported item isn't in the registry
     * (loose videos, child playlists/videos under approved parents) — the
     * public /api/v1/{type}/{id} endpoints 404 on unapproved IDs, leaving
     * the admin staring at an opaque YouTube ID. This goes straight to
     * NewPipe so unregistered items always render with real metadata.
     */
    @GetMapping("/api/admin/reports/lookup")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Map<String, String>> lookupMetadata(
            @RequestParam ReportTargetType type,
            @RequestParam @NotBlank @Size(max = 128) String id) {
        try {
            switch (type) {
                case VIDEO -> {
                    StreamDetailsDto video = youTubeService.getVideoDetailsDto(id);
                    Map<String, String> body = new java.util.HashMap<>();
                    body.put("title", video.getName());
                    body.put("name", video.getName());
                    body.put("thumbnailUrl", video.getThumbnailUrl());
                    return ResponseEntity.ok(body);
                }
                case PLAYLIST -> {
                    PlaylistDetailsDto playlist = youTubeService.getPlaylistDetailsDto(id);
                    Map<String, String> body = new java.util.HashMap<>();
                    body.put("title", playlist.getName());
                    body.put("name", playlist.getName());
                    body.put("thumbnailUrl", playlist.getThumbnailUrl());
                    return ResponseEntity.ok(body);
                }
                case CHANNEL -> {
                    ChannelDetailsDto channel = youTubeService.getChannelDetailsDto(id);
                    Map<String, String> body = new java.util.HashMap<>();
                    body.put("title", channel.getName());
                    body.put("name", channel.getName());
                    body.put("thumbnailUrl", channel.getThumbnailUrl());
                    return ResponseEntity.ok(body);
                }
                default -> {
                    return ResponseEntity.badRequest().build();
                }
            }
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    public record SubmitReportRequest(
            @NotNull ReportTargetType targetType,
            @NotBlank @Size(max = 128) String targetId,
            @NotEmpty @Size(max = 10) List<ReportReason> reasons,
            @Size(max = 500) String otherDescription,
            // Optional parent context: set when reporting an item from a
            // channel- or playlist-detail screen. parentType is CHANNEL or
            // PLAYLIST; parentId is the parent's YouTube ID. contentSubType
            // narrows VIDEO target into SHORT / LIVESTREAM / POST so the
            // resolve flow puts the exclusion in the correct bucket.
            ReportTargetType parentType,
            @Size(max = 128) String parentId,
            @Size(max = 16) String contentSubType) {}

    public record ResolveReportRequest(@NotNull ReportStatus status, @Size(max = 1000) String note) {}
}
