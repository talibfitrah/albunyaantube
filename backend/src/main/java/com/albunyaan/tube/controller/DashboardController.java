package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.VideoValidationService;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.cloud.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * FIREBASE-MIGRATE-04: Dashboard Controller
 *
 * Provides analytics and metrics for the admin dashboard.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);
    private static final String CATEGORY_STATS_CACHE_KEY = "all";

    private final CategoryRepository categoryRepository;
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final VideoValidationService videoValidationService;
    private final Cache<String, Map<String, ?>> dashboardCategoryStatsCache;

    public DashboardController(
            CategoryRepository categoryRepository,
            ChannelRepository channelRepository,
            UserRepository userRepository,
            VideoValidationService videoValidationService,
            Cache<String, Map<String, ?>> dashboardCategoryStatsCache
    ) {
        this.categoryRepository = categoryRepository;
        this.channelRepository = channelRepository;
        this.userRepository = userRepository;
        this.videoValidationService = videoValidationService;
        this.dashboardCategoryStatsCache = dashboardCategoryStatsCache;
    }

    /**
     * Get dashboard metrics
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<DashboardMetricsResponse> getDashboardMetrics(
            @RequestParam(required = false, defaultValue = "LAST_7_DAYS") String timeframe
    ) throws Exception {

        // Count totals - using optimized count queries
        long totalCategories = categoryRepository.count();
        long totalChannels = channelRepository.countAll();
        long totalUsers = userRepository.countAll();

        // Count by status - using database-level queries instead of loading all channels
        long pendingChannels = channelRepository.countByStatus("PENDING");
        long approvedChannels = channelRepository.countByStatus("APPROVED");

        // Count moderators - using optimized query
        long totalModerators = userRepository.countModerators();

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
     * Get statistics by category using server-side aggregation queries with caching.
     *
     * This endpoint uses a 5-minute cache to prevent N+1 Firestore aggregation queries
     * from exhausting quota when the dashboard is refreshed frequently or has many categories.
     *
     * Performance characteristics:
     * - First request: O(3N) Firestore count queries where N = number of categories
     * - Subsequent requests within 5 minutes: O(1) cache hit, no Firestore queries
     *
     * @return Map of category ID to channel statistics
     */
    @GetMapping("/stats/by-category")
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, CategoryStats>> getStatsByCategory()
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Try cache first
        Map<String, ?> cached = dashboardCategoryStatsCache.getIfPresent(CATEGORY_STATS_CACHE_KEY);
        if (cached != null) {
            log.debug("Dashboard category stats cache hit");
            return ResponseEntity.ok((Map<String, CategoryStats>) cached);
        }

        log.debug("Dashboard category stats cache miss - fetching from Firestore");
        Map<String, CategoryStats> stats = new HashMap<>();

        // Get all categories first (this is a bounded, small dataset)
        var categories = categoryRepository.findAll();

        // For each category, use server-side count queries instead of loading all channels
        for (var category : categories) {
            String categoryId = category.getId();
            CategoryStats categoryStats = new CategoryStats();

            // Use server-side aggregation queries - no document reads needed
            categoryStats.totalChannels = (int) channelRepository.countByCategoryId(categoryId);
            categoryStats.approvedChannels = (int) channelRepository.countByCategoryIdAndStatus(categoryId, "APPROVED");
            categoryStats.pendingChannels = (int) channelRepository.countByCategoryIdAndStatus(categoryId, "PENDING");

            stats.put(categoryId, categoryStats);
        }

        // Cache the result for 5 minutes (TTL configured in CacheConfig)
        dashboardCategoryStatsCache.put(CATEGORY_STATS_CACHE_KEY, stats);
        log.debug("Dashboard category stats cached for {} categories", stats.size());

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

