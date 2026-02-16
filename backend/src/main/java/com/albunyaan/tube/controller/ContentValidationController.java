package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.AsyncConfig;
import com.albunyaan.tube.dto.ArchivedContentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.albunyaan.tube.dto.ArchivedCountsDto;
import com.albunyaan.tube.dto.ContentActionRequestDto;
import com.albunyaan.tube.dto.ContentActionResultDto;
import com.albunyaan.tube.dto.ValidationRunDto;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AuditLogService;
import com.albunyaan.tube.service.ContentValidationService;
import com.albunyaan.tube.service.YouTubeCircuitBreaker;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;

/**
 * Content Validation Controller
 *
 * REST API for content validation and archived content review.
 * Supports channels, playlists, and videos.
 * Admin-only endpoints.
 */
@RestController
@RequestMapping("/api/admin/content-validation")
public class ContentValidationController {

    private static final Logger log = LoggerFactory.getLogger(ContentValidationController.class);

    private final ContentValidationService contentValidationService;
    private final CategoryRepository categoryRepository;
    private final Executor validationExecutor;
    private final AsyncConfig.LoggingCallerRunsPolicy rejectionHandler;
    private final YouTubeCircuitBreaker youTubeCircuitBreaker;
    private final AuditLogService auditLogService;

    // Track currently running validations to prevent duplicates
    private volatile boolean isValidationRunning = false;

    public ContentValidationController(
            ContentValidationService contentValidationService,
            CategoryRepository categoryRepository,
            Executor validationExecutor,
            AsyncConfig.LoggingCallerRunsPolicy rejectionHandler,
            YouTubeCircuitBreaker youTubeCircuitBreaker,
            AuditLogService auditLogService
    ) {
        this.contentValidationService = contentValidationService;
        this.categoryRepository = categoryRepository;
        this.validationExecutor = validationExecutor;
        this.rejectionHandler = rejectionHandler;
        this.youTubeCircuitBreaker = youTubeCircuitBreaker;
        this.auditLogService = auditLogService;
    }

    // ==================== Validation Triggers ====================

    /**
     * Start async validation of all content types (channels, playlists, videos).
     * Returns immediately with the run ID for progress polling.
     * POST /api/admin/content-validation/validate/all
     */
    @PostMapping("/validate/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> validateAllContent(
            @AuthenticationPrincipal FirebaseUserDetails user,
            @RequestParam(required = false) Integer maxItems
    ) {
        // Prevent duplicate validations running concurrently
        if (isValidationRunning) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "success", false,
                            "error", "A validation is already running. Please wait for it to complete."
                    ));
        }

        if (maxItems != null && maxItems < 1) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "maxItems must be at least 1 when provided"));
        }

        // Create a preliminary validation run to get the ID
        ValidationRun run = new ValidationRun("MANUAL", user.getUid(), user.getEmail());
        run.setCurrentPhase("STARTING");

        try {
            // Mark validation as running
            isValidationRunning = true;
            // Save initial run to get an ID
            run = contentValidationService.saveValidationRun(run);
            final String runId = run.getId();
            final Integer finalMaxItems = maxItems;
            final String uid = user.getUid();
            final String email = user.getEmail();

            // Run validation asynchronously on dedicated executor (not common fork-join pool)
            // This prevents blocking Firestore I/O from starving other async operations
            try {
                CompletableFuture.runAsync(() -> {
                    try {
                        contentValidationService.validateAllContentAsync(
                                runId,
                                "MANUAL",
                                uid,
                                email,
                                finalMaxItems
                        );
                    } catch (Exception e) {
                        // Error handling is done inside the service
                    } finally {
                        // Always reset the flag when validation completes (success or failure)
                        isValidationRunning = false;
                    }
                }, validationExecutor);

                // Return immediately with the run ID
                return ResponseEntity.accepted().body(Map.of(
                        "success", true,
                        "message", "Validation started",
                        "runId", runId,
                        "status", "RUNNING"
                ));

            } catch (RejectedExecutionException e) {
                // Executor queue is full - system is overloaded
                // Reset flag since validation won't run
                isValidationRunning = false;
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of(
                                "success", false,
                                "error", e.getMessage(),
                                "retryAfter", 60 // Suggest retry after 60 seconds
                        ));
            }

        } catch (Exception e) {
            // Reset flag on any error before async execution
            isValidationRunning = false;
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "error", "Failed to start validation: " + e.getMessage()
                    ));
        }
    }

    /**
     * Get validation run status by ID (for progress polling).
     * GET /api/admin/content-validation/status/{runId}
     */
    @GetMapping("/status/{runId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<?> getValidationStatus(@PathVariable String runId) {
        try {
            ValidationRun run = contentValidationService.getValidationRunById(runId);

            if (run == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Validation run not found: " + runId));
            }

            return ResponseEntity.ok(ValidationRunDto.fromModel(run));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get validation status: " + e.getMessage()));
        }
    }

    /**
     * Validate channels only.
     * POST /api/admin/content-validation/validate/channels
     */
    @PostMapping("/validate/channels")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> validateChannels(
            @AuthenticationPrincipal FirebaseUserDetails user,
            @RequestParam(required = false) Integer maxItems
    ) {
        if (maxItems != null && (maxItems < 1 || maxItems > 1000)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "maxItems must be between 1 and 1000"));
        }

        try {
            ValidationRun result = contentValidationService.validateChannels(
                    "MANUAL",
                    user.getUid(),
                    user.getEmail(),
                    maxItems
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Channel validation completed",
                    "data", ValidationRunDto.fromModel(result)
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "error", "Validation failed: " + e.getMessage()
                    ));
        }
    }

    /**
     * Validate playlists only.
     * POST /api/admin/content-validation/validate/playlists
     */
    @PostMapping("/validate/playlists")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> validatePlaylists(
            @AuthenticationPrincipal FirebaseUserDetails user,
            @RequestParam(required = false) Integer maxItems
    ) {
        if (maxItems != null && (maxItems < 1 || maxItems > 1000)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "maxItems must be between 1 and 1000"));
        }

        try {
            ValidationRun result = contentValidationService.validatePlaylists(
                    "MANUAL",
                    user.getUid(),
                    user.getEmail(),
                    maxItems
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Playlist validation completed",
                    "data", ValidationRunDto.fromModel(result)
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "error", "Validation failed: " + e.getMessage()
                    ));
        }
    }

    /**
     * Validate videos only.
     * POST /api/admin/content-validation/validate/videos
     */
    @PostMapping("/validate/videos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> validateVideos(
            @AuthenticationPrincipal FirebaseUserDetails user,
            @RequestParam(required = false) Integer maxItems
    ) {
        if (maxItems != null && (maxItems < 1 || maxItems > 1000)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "maxItems must be between 1 and 1000"));
        }

        try {
            ValidationRun result = contentValidationService.validateVideos(
                    "MANUAL",
                    user.getUid(),
                    user.getEmail(),
                    maxItems
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Video validation completed",
                    "data", ValidationRunDto.fromModel(result)
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "error", "Validation failed: " + e.getMessage()
                    ));
        }
    }

    // ==================== Archived Content ====================

    /**
     * Get counts of archived content by type.
     * GET /api/admin/content-validation/archived/counts
     */
    @GetMapping("/archived/counts")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<?> getArchivedCounts() {
        try {
            ContentValidationService.ArchivedCounts counts = contentValidationService.getArchivedCounts();
            return ResponseEntity.ok(new ArchivedCountsDto(
                    counts.getChannels(),
                    counts.getPlaylists(),
                    counts.getVideos()
            ));
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch archived counts: " + e.getMessage()));
        }
    }

    /**
     * Get archived channels.
     * GET /api/admin/content-validation/archived/channels
     */
    @GetMapping("/archived/channels")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<?> getArchivedChannels(
            @RequestParam(required = false, defaultValue = "100") int limit
    ) {
        if (limit < 1 || limit > 500) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Limit must be between 1 and 500"));
        }

        try {
            // Limit is applied at Firestore query level for efficiency
            List<Channel> channels = contentValidationService.getArchivedChannels(limit);
            List<ArchivedContentDto> dtos = new ArrayList<>();

            channels.forEach(channel -> {
                dtos.add(new ArchivedContentDto()
                        .id(channel.getId())
                        .type("CHANNEL")
                        .youtubeId(channel.getYoutubeId())
                        .title(channel.getName())
                        .thumbnailUrl(channel.getThumbnailUrl())
                        .category(channel.getCategory() != null ? channel.getCategory().getName() : null)
                        .archivedAt(channel.getUpdatedAt())
                        .lastValidatedAt(channel.getLastValidatedAt())
                        .metadata(formatChannelMetadata(channel))
                );
            });

            // hasMore indicates if there might be more items beyond this page
            boolean hasMore = dtos.size() == limit;

            return ResponseEntity.ok(Map.of(
                    "data", dtos,
                    "count", dtos.size(),
                    "hasMore", hasMore
            ));

        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch archived channels: " + e.getMessage()));
        }
    }

    /**
     * Get archived playlists.
     * GET /api/admin/content-validation/archived/playlists
     */
    @GetMapping("/archived/playlists")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<?> getArchivedPlaylists(
            @RequestParam(required = false, defaultValue = "100") int limit
    ) {
        if (limit < 1 || limit > 500) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Limit must be between 1 and 500"));
        }

        try {
            // Limit is applied at Firestore query level for efficiency
            List<Playlist> playlists = contentValidationService.getArchivedPlaylists(limit);
            List<ArchivedContentDto> dtos = new ArrayList<>();

            playlists.forEach(playlist -> {
                // Look up first category from categoryIds
                String categoryName = null;
                List<String> categoryIds = playlist.getCategoryIds();
                if (categoryIds != null && !categoryIds.isEmpty()) {
                    try {
                        Optional<Category> category = categoryRepository.findById(categoryIds.get(0));
                        categoryName = category.map(Category::getName).orElse(null);
                    } catch (Exception e) {
                        // Ignore category lookup errors
                    }
                }

                dtos.add(new ArchivedContentDto()
                        .id(playlist.getId())
                        .type("PLAYLIST")
                        .youtubeId(playlist.getYoutubeId())
                        .title(playlist.getTitle())
                        .thumbnailUrl(playlist.getThumbnailUrl())
                        .category(categoryName)
                        .archivedAt(playlist.getUpdatedAt())
                        .lastValidatedAt(playlist.getLastValidatedAt())
                        .metadata(formatPlaylistMetadata(playlist))
                );
            });

            boolean hasMore = dtos.size() == limit;

            return ResponseEntity.ok(Map.of(
                    "data", dtos,
                    "count", dtos.size(),
                    "hasMore", hasMore
            ));

        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch archived playlists: " + e.getMessage()));
        }
    }

    /**
     * Get archived videos.
     * GET /api/admin/content-validation/archived/videos
     */
    @GetMapping("/archived/videos")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<?> getArchivedVideos(
            @RequestParam(required = false, defaultValue = "100") int limit
    ) {
        if (limit < 1 || limit > 500) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Limit must be between 1 and 500"));
        }

        try {
            // Limit is applied at Firestore query level for efficiency
            List<Video> videos = contentValidationService.getArchivedVideos(limit);
            List<ArchivedContentDto> dtos = new ArrayList<>();

            videos.forEach(video -> {
                dtos.add(new ArchivedContentDto()
                        .id(video.getId())
                        .type("VIDEO")
                        .youtubeId(video.getYoutubeId())
                        .title(video.getTitle())
                        .thumbnailUrl(video.getThumbnailUrl())
                        .category(video.getCategory() != null ? video.getCategory().getName() : null)
                        .archivedAt(video.getUpdatedAt())
                        .lastValidatedAt(video.getLastValidatedAt())
                        .metadata(formatVideoMetadata(video))
                );
            });

            boolean hasMore = dtos.size() == limit;

            return ResponseEntity.ok(Map.of(
                    "data", dtos,
                    "count", dtos.size(),
                    "hasMore", hasMore
            ));

        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch archived videos: " + e.getMessage()));
        }
    }

    // ==================== Content Actions ====================

    /**
     * Perform bulk action on archived content (delete or restore).
     * POST /api/admin/content-validation/action
     */
    @PostMapping("/action")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> performBulkAction(
            @AuthenticationPrincipal FirebaseUserDetails user,
            @RequestBody ContentActionRequestDto request
    ) {
        if (!request.isValid()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid request. Required: action (DELETE/RESTORE), type (CHANNEL/PLAYLIST/VIDEO), ids (non-empty list)"));
        }

        try {
            ContentValidationService.BulkActionResult result;

            if ("DELETE".equalsIgnoreCase(request.getAction())) {
                result = contentValidationService.deleteContent(
                        request.getType(),
                        request.getIds(),
                        user.getUid(),
                        user.getEmail()
                );
            } else if ("RESTORE".equalsIgnoreCase(request.getAction())) {
                result = contentValidationService.restoreContent(
                        request.getType(),
                        request.getIds(),
                        user.getUid(),
                        user.getEmail()
                );
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid action. Must be DELETE or RESTORE"));
            }

            ContentActionResultDto response = new ContentActionResultDto(request.getAction(), request.getType())
                    .success(result.getSuccessCount())
                    .failure(result.getFailedCount(), result.getErrors())
                    .message(buildResultMessage(request.getAction(), result));

            if (result.getFailedCount() > 0 && result.getSuccessCount() > 0) {
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(response);
            } else if (result.getFailedCount() > 0) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Action failed: " + e.getMessage()));
        }
    }

    // ==================== Metrics ====================

    /**
     * Get validation executor metrics.
     * GET /api/admin/content-validation/metrics
     *
     * Returns metrics about the validation executor including rejection count,
     * which can be used for monitoring and alerting on system overload.
     */
    @GetMapping("/metrics")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<?> getValidationMetrics() {
        long rejectionCount = rejectionHandler.getRejectionCount();

        // Get actual executor metrics if available
        Map<String, Object> executorMetrics;
        if (validationExecutor instanceof ThreadPoolTaskExecutor) {
            ThreadPoolTaskExecutor tpExecutor = (ThreadPoolTaskExecutor) validationExecutor;
            ThreadPoolExecutor threadPool = tpExecutor.getThreadPoolExecutor();

            executorMetrics = Map.of(
                    "corePoolSize", tpExecutor.getCorePoolSize(),
                    "maxPoolSize", tpExecutor.getMaxPoolSize(),
                    "queueCapacity", tpExecutor.getQueueCapacity(),
                    "activeCount", threadPool.getActiveCount(),
                    "poolSize", threadPool.getPoolSize(),
                    "queueSize", threadPool.getQueue().size(),
                    "completedTaskCount", threadPool.getCompletedTaskCount()
            );
        } else {
            // Fallback for non-ThreadPoolTaskExecutor (shouldn't happen in production)
            executorMetrics = Map.of(
                    "corePoolSize", 2,
                    "maxPoolSize", 4,
                    "queueCapacity", 10,
                    "note", "Using default values - executor type not supported for dynamic metrics"
            );
        }

        return ResponseEntity.ok(Map.of(
                "executor", executorMetrics,
                "rejections", Map.of(
                        "total", rejectionCount,
                        "description", "Total number of validation tasks rejected due to queue overflow since startup"
                ),
                "validationRunning", isValidationRunning
        ));
    }

    // ==================== Circuit Breaker ====================

    /**
     * Get YouTube circuit breaker status (read-only, no side effects).
     * GET /api/admin/content-validation/circuit-breaker
     */
    @GetMapping("/circuit-breaker")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<?> getCircuitBreakerStatus() {
        return ResponseEntity.ok(youTubeCircuitBreaker.getStatusSnapshot());
    }

    /**
     * Reset YouTube circuit breaker to CLOSED state.
     * POST /api/admin/content-validation/circuit-breaker/reset
     */
    @PostMapping("/circuit-breaker/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> resetCircuitBreaker(
            @AuthenticationPrincipal FirebaseUserDetails user
    ) {
        try {
            boolean persisted = youTubeCircuitBreaker.reset();
            YouTubeCircuitBreaker.CircuitBreakerStatus status = youTubeCircuitBreaker.getStatusSnapshot();

            if (!persisted || status.isOpen()) {
                String message;
                if (!persisted && status.isOpen()) {
                    message = "Reset not durable and circuit breaker reopened";
                } else if (!persisted) {
                    message = "Reset not durable";
                } else {
                    message = "Reset reopened";
                }
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of(
                                "message", message,
                                "persisted", persisted,
                                "circuitOpen", status.isOpen(),
                                "status", status
                        ));
            }

            try {
                auditLogService.log("circuit_breaker_reset", "system", "youtube_circuit_breaker", user);
            } catch (Exception e) {
                log.warn("Audit logging failed for circuit breaker reset by {}: {}", user.getEmail(), e.getMessage());
            }

            return ResponseEntity.ok(Map.of(
                    "message", "Circuit breaker reset to CLOSED",
                    "status", status
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to reset circuit breaker: " + e.getMessage()));
        }
    }

    // ==================== Validation History ====================

    /**
     * Get validation run history.
     * GET /api/admin/content-validation/history
     */
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<?> getValidationHistory(
            @RequestParam(required = false, defaultValue = "20") int limit
    ) {
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Limit must be between 1 and 100"));
        }

        try {
            List<ValidationRun> history = contentValidationService.getValidationHistory(limit);
            List<ValidationRunDto> dtos = history.stream()
                    .map(ValidationRunDto::fromModel)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "data", dtos,
                    "count", dtos.size()
            ));

        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch validation history: " + e.getMessage()));
        }
    }

    /**
     * Get latest validation run.
     * GET /api/admin/content-validation/latest
     */
    @GetMapping("/latest")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<?> getLatestValidation() {
        try {
            ValidationRun latest = contentValidationService.getLatestValidationRun();

            if (latest == null) {
                return ResponseEntity.ok(Map.of("message", "No validation runs found"));
            }

            return ResponseEntity.ok(ValidationRunDto.fromModel(latest));

        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch latest validation: " + e.getMessage()));
        }
    }

    // ==================== Helper Methods ====================

    private String formatChannelMetadata(Channel channel) {
        StringBuilder sb = new StringBuilder();
        if (channel.getSubscribers() != null) {
            sb.append(formatNumber(channel.getSubscribers())).append(" subscribers");
        }
        if (channel.getVideoCount() != null) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(channel.getVideoCount()).append(" videos");
        }
        return sb.toString();
    }

    private String formatPlaylistMetadata(Playlist playlist) {
        StringBuilder sb = new StringBuilder();
        if (playlist.getItemCount() != null) {
            sb.append(playlist.getItemCount()).append(" videos");
        }
        return sb.toString();
    }

    private String formatVideoMetadata(Video video) {
        StringBuilder sb = new StringBuilder();
        if (video.getDurationSeconds() != null) {
            sb.append(formatDuration(video.getDurationSeconds().longValue()));
        }
        if (video.getViewCount() != null) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(formatNumber(video.getViewCount())).append(" views");
        }
        return sb.toString();
    }

    private String formatNumber(Long number) {
        if (number == null) return "0";
        if (number >= 1_000_000_000) {
            return String.format("%.1fB", number / 1_000_000_000.0);
        }
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        }
        if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }

    private String formatDuration(Long seconds) {
        if (seconds == null) return "";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format("%d:%02d", minutes, secs);
    }

    private String buildResultMessage(String action, ContentValidationService.BulkActionResult result) {
        String actionPastTense = "DELETE".equalsIgnoreCase(action) ? "deleted" : "restored";
        if (result.getFailedCount() == 0) {
            return String.format("Successfully %s %d item(s)", actionPastTense, result.getSuccessCount());
        } else if (result.getSuccessCount() == 0) {
            return String.format("Failed to %s all %d item(s)", action.toLowerCase(), result.getFailedCount());
        } else {
            return String.format("Partially completed: %d %s, %d failed",
                    result.getSuccessCount(), actionPastTense, result.getFailedCount());
        }
    }
}
