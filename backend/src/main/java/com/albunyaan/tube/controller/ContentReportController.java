package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.ContentReport;
import com.albunyaan.tube.model.ReportReason;
import com.albunyaan.tube.model.ReportStatus;
import com.albunyaan.tube.model.ReportTargetType;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.ContentReportService;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@RestController
public class ContentReportController {

    private static final String HEADER_DEVICE_ID = "X-Device-Id";

    private final ContentReportService reportService;

    public ContentReportController(ContentReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/api/v1/reports")
    public ResponseEntity<?> submitReport(
            @Valid @RequestBody SubmitReportRequest body,
            HttpServletRequest request) {
        String deviceKey = request.getHeader(HEADER_DEVICE_ID);
        if (deviceKey == null || deviceKey.isBlank()) {
            deviceKey = request.getRemoteAddr();
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
