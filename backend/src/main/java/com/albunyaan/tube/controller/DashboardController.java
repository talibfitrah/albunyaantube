package com.albunyaan.tube.controller;

import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.model.Channel;
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

    public DashboardController(
            CategoryRepository categoryRepository,
            ChannelRepository channelRepository,
            UserRepository userRepository
    ) {
        this.categoryRepository = categoryRepository;
        this.channelRepository = channelRepository;
        this.userRepository = userRepository;
    }

    /**
     * Get dashboard metrics
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public ResponseEntity<DashboardMetrics> getDashboardMetrics(
            @RequestParam(required = false) String timeframe
    ) throws ExecutionException, InterruptedException {

        // Count totals
        long totalCategories = categoryRepository.findAll().size();
        long totalChannels = channelRepository.findAll().size();
        long totalUsers = userRepository.findAll().size();

        // Count by status
        List<Channel> allChannels = channelRepository.findAll();
        long pendingChannels = allChannels.stream()
                .filter(ch -> "pending".equals(ch.getStatus()))
                .count();
        long approvedChannels = allChannels.stream()
                .filter(ch -> "approved".equals(ch.getStatus()))
                .count();
        long rejectedChannels = allChannels.stream()
                .filter(ch -> "rejected".equals(ch.getStatus()))
                .count();

        // Recent activity (last 10 approved channels)
        List<RecentActivity> recentActivity = allChannels.stream()
                .filter(ch -> "approved".equals(ch.getStatus()))
                .sorted((a, b) -> {
                    if (a.getUpdatedAt() == null) return 1;
                    if (b.getUpdatedAt() == null) return -1;
                    return b.getUpdatedAt().compareTo(a.getUpdatedAt());
                })
                .limit(10)
                .map(ch -> new RecentActivity(
                        "channel_approved",
                        ch.getYoutubeId(), // Using YouTube ID as identifier
                        ch.getApprovedBy(),
                        ch.getUpdatedAt() != null ? ch.getUpdatedAt().toString() : null
                ))
                .collect(Collectors.toList());

        DashboardMetrics metrics = new DashboardMetrics();
        metrics.totalCategories = totalCategories;
        metrics.totalChannels = totalChannels;
        metrics.totalUsers = totalUsers;
        metrics.pendingApprovals = pendingChannels;
        metrics.approvedChannels = approvedChannels;
        metrics.rejectedChannels = rejectedChannels;
        metrics.recentActivity = recentActivity;

        return ResponseEntity.ok(metrics);
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

                    if ("approved".equals(channel.getStatus())) {
                        categoryStats.approvedChannels++;
                    } else if ("pending".equals(channel.getStatus())) {
                        categoryStats.pendingChannels++;
                    }
                }
            }
        }

        return ResponseEntity.ok(stats);
    }

    // DTOs

    public static class DashboardMetrics {
        public long totalCategories;
        public long totalChannels;
        public long totalUsers;
        public long pendingApprovals;
        public long approvedChannels;
        public long rejectedChannels;
        public List<RecentActivity> recentActivity;
    }

    public static class RecentActivity {
        public String type;
        public String title;
        public String actorUid;
        public String timestamp;

        public RecentActivity(String type, String title, String actorUid, String timestamp) {
            this.type = type;
            this.title = title;
            this.actorUid = actorUid;
            this.timestamp = timestamp;
        }
    }

    public static class CategoryStats {
        public int totalChannels = 0;
        public int approvedChannels = 0;
        public int pendingChannels = 0;
    }
}
