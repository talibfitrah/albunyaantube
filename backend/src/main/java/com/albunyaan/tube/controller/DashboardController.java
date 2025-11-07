package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.VideoValidationService;
import com.google.cloud.Timestamp;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * FIREBASE-MIGRATE-04: Dashboard Controller
 *
 * Provides analytics and metrics for the admin dashboard.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardController {

    private final CategoryRepository categoryRepository;
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final VideoValidationService videoValidationService;

    public DashboardController(
            CategoryRepository categoryRepository,
            ChannelRepository channelRepository,
            UserRepository userRepository,
            VideoValidationService videoValidationService
    ) {
        this.categoryRepository = categoryRepository;
        this.channelRepository = channelRepository;
        this.userRepository = userRepository;
        this.videoValidationService = videoValidationService;
    }

    /**
     * Get dashboard metrics
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<DashboardMetricsResponse> getDashboardMetrics(
            @RequestParam(required = false, defaultValue = "LAST_7_DAYS") String timeframe
    ) throws ExecutionException, InterruptedException {

        // Count totals
        long totalCategories = categoryRepository.findAll().size();
        long totalChannels = channelRepository.findAll().size();
        long totalUsers = userRepository.findAll().size();

        // Count by status
        List<Channel> allChannels = channelRepository.findAll();
        long pendingChannels = allChannels.stream()
                .filter(ch -> "PENDING".equalsIgnoreCase(ch.getStatus()))
                .count();
        long approvedChannels = allChannels.stream()
                .filter(ch -> "APPROVED".equalsIgnoreCase(ch.getStatus()))
                .count();

        // Count moderators (users with MODERATOR or ADMIN role)
        long totalModerators = userRepository.findAll().stream()
                .filter(user -> {
                    String role = user.getRole();
                    return "ADMIN".equalsIgnoreCase(role) || "MODERATOR".equalsIgnoreCase(role);
                })
                .count();

        // Create metrics in the expected frontend format
        DashboardMetricsData data = new DashboardMetricsData();

        // Pending moderation metric (comparison metric)
        data.pendingModeration = new ComparisonMetric(
                (int) pendingChannels,
                0, // TODO: Calculate previous period value
                determineTrend(pendingChannels, 0)
        );

        // Categories metric
        data.categories = new CategoryMetric(
                (int) totalCategories,
                0, // TODO: Calculate new this period
                (int) totalCategories // TODO: Calculate previous total
        );

        // Moderators metric (comparison metric)
        data.moderators = new ComparisonMetric(
                (int) totalModerators,
                (int) totalModerators, // TODO: Calculate previous period value
                "FLAT"
        );

        // Video validation metric
        try {
            ValidationRun latestRun = videoValidationService.getLatestValidationRun();
            if (latestRun != null) {
                data.videoValidation = new ValidationMetric(
                        latestRun.getStartedAt(),
                        latestRun.getVideosChecked(),
                        latestRun.getVideosMarkedUnavailable(),
                        latestRun.getErrorCount(),
                        latestRun.getStatus()
                );
            } else {
                data.videoValidation = new ValidationMetric(null, 0, 0, 0, "NEVER_RUN");
            }
        } catch (Exception e) {
            data.videoValidation = new ValidationMetric(null, 0, 0, 0, "ERROR");
        }

        // Create metadata
        DashboardMetricsMeta meta = new DashboardMetricsMeta();
        meta.generatedAt = java.time.Instant.now().toString();
        meta.timeRange = new TimeRange(
                java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS).toString(),
                java.time.Instant.now().toString(),
                timeframe
        );
        meta.cacheTtlSeconds = 300; // 5 minutes
        meta.warnings = new java.util.ArrayList<>(); // Initialize empty warnings list

        // Create response
        DashboardMetricsResponse response = new DashboardMetricsResponse();
        response.data = data;
        response.meta = meta;

        return ResponseEntity.ok(response);
    }

    private String determineTrend(long current, long previous) {
        if (current > previous) return "UP";
        if (current < previous) return "DOWN";
        return "FLAT";
    }

    /**
     * Get statistics by category
     */
    @GetMapping("/stats/by-category")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<Map<String, CategoryStats>> getStatsByCategory()
            throws ExecutionException, InterruptedException {

        Map<String, CategoryStats> stats = new HashMap<>();
        List<Channel> allChannels = channelRepository.findAll();

        for (Channel channel : allChannels) {
            // Channel can have multiple categories
            List<String> categoryIds = channel.getCategoryIds();
            if (categoryIds != null && !categoryIds.isEmpty()) {
                for (String categoryId : categoryIds) {
                    stats.putIfAbsent(categoryId, new CategoryStats());
                    CategoryStats categoryStats = stats.get(categoryId);
                    categoryStats.totalChannels++;

                    if ("APPROVED".equalsIgnoreCase(channel.getStatus())) {
                        categoryStats.approvedChannels++;
                    } else if ("PENDING".equalsIgnoreCase(channel.getStatus())) {
                        categoryStats.pendingChannels++;
                    }
                }
            }
        }

        return ResponseEntity.ok(stats);
    }

    // DTOs

    public static class DashboardMetricsResponse {
        public DashboardMetricsData data;
        public DashboardMetricsMeta meta;
    }

    public static class DashboardMetricsData {
        public ComparisonMetric pendingModeration;
        public CategoryMetric categories;
        public ComparisonMetric moderators;
        public ValidationMetric videoValidation;

        public DashboardMetricsData() {
        }
    }

    public static class ComparisonMetric {
        public int current;
        public int previous;
        public String trend;
        public Boolean thresholdBreached;

        public ComparisonMetric(int current, int previous, String trend) {
            this.current = current;
            this.previous = previous;
            this.trend = trend;
            this.thresholdBreached = false;
        }
    }

    public static class CategoryMetric {
        public int total;
        public int newThisPeriod;
        public int previousTotal;

        public CategoryMetric(int total, int newThisPeriod, int previousTotal) {
            this.total = total;
            this.newThisPeriod = newThisPeriod;
            this.previousTotal = previousTotal;
        }
    }

    public static class ValidationMetric {
        public Timestamp lastRunAt;
        public int videosChecked;
        public int videosMarkedUnavailable;
        public int validationErrors;
        public String status;

        public ValidationMetric(Timestamp lastRunAt, int checked, int markedUnavailable, int errors, String status) {
            this.lastRunAt = lastRunAt;
            this.videosChecked = checked;
            this.videosMarkedUnavailable = markedUnavailable;
            this.validationErrors = errors;
            this.status = status;
        }
    }

    public static class DashboardMetricsMeta {
        public String generatedAt;
        public TimeRange timeRange;
        public int cacheTtlSeconds;
        public java.util.List<String> warnings;
    }

    public static class TimeRange {
        public String start;
        public String end;
        public String label;

        public TimeRange(String start, String end, String label) {
            this.start = start;
            this.end = end;
            this.label = label;
        }
    }

    public static class CategoryStats {
        public int totalChannels = 0;
        public int approvedChannels = 0;
        public int pendingChannels = 0;
    }
}

