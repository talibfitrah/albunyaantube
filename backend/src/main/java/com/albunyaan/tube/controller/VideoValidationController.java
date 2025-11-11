package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.VideoValidationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Video Validation Controller
 *
 * REST API for manual video validation triggers and viewing validation history.
 * Admin-only endpoints.
 */
@RestController
@RequestMapping("/api/admin/videos")
public class VideoValidationController {

    private final VideoValidationService videoValidationService;

    public VideoValidationController(VideoValidationService videoValidationService) {
        this.videoValidationService = videoValidationService;
    }

    /**
     * Manually trigger video validation
     * POST /api/admin/videos/validate
     */
    @PostMapping("/validate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ValidationRunResponse> triggerValidation(
            @AuthenticationPrincipal FirebaseUserDetails user,
            @RequestParam(required = false) Integer maxVideos
    ) {
        // Validate maxVideos parameter
        if (maxVideos != null && (maxVideos < 1 || maxVideos > 1000)) {
            return ResponseEntity.badRequest()
                    .body(new ValidationRunResponse(false, "maxVideos must be between 1 and 1000", null));
        }

        try {
            ValidationRun result = videoValidationService.validateStandaloneVideos(
                    "MANUAL",
                    user.getUid(),
                    user.getEmail(),
                    maxVideos
            );

            return ResponseEntity.ok(new ValidationRunResponse(
                    true,
                    "Video validation completed",
                    result
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ValidationRunResponse(
                            false,
                            "Validation failed: " + e.getMessage(),
                            null
                    ));
        }
    }

    /**
     * Get validation run status by ID
     * GET /api/admin/videos/validation-status/{runId}
     */
    @GetMapping("/validation-status/{runId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<?> getValidationStatus(@PathVariable String runId) {
        try {
            ValidationRun run = videoValidationService.getValidationRunById(runId);

            if (run == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Validation run not found"));
            }

            return ResponseEntity.ok(run);

        } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch validation status: " + e.getMessage()));
        }
    }

    /**
     * Get validation history
     * GET /api/admin/videos/validation-history
     */
    @GetMapping("/validation-history")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<?> getValidationHistory(
            @RequestParam(required = false, defaultValue = "20") int limit
    ) {
        // Validate limit parameter
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Limit must be between 1 and 100"));
        }

        try {
            List<ValidationRun> history = videoValidationService.getValidationHistory(limit);

            Map<String, Object> response = new HashMap<>();
            response.put("runs", history);
            response.put("count", history.size());

            return ResponseEntity.ok(response);

        } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch validation history: " + e.getMessage()));
        }
    }

    /**
     * Get latest validation run
     * GET /api/admin/videos/validation-latest
     */
    @GetMapping("/validation-latest")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<?> getLatestValidation() {
        try {
            ValidationRun latest = videoValidationService.getLatestValidationRun();

            if (latest == null) {
                return ResponseEntity.ok(Map.of("message", "No validation runs found"));
            }

            return ResponseEntity.ok(latest);

        } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch latest validation: " + e.getMessage()));
        }
    }

    // Response DTOs

    public static class ValidationRunResponse {
        public boolean success;
        public String message;
        public ValidationRun data;

        public ValidationRunResponse(boolean success, String message, ValidationRun data) {
            this.success = success;
            this.message = message;
            this.data = data;
        }
    }
}

