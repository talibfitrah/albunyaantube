package com.albunyaan.tube.service;

import com.albunyaan.tube.model.ContentReport;
import com.albunyaan.tube.model.ReportReason;
import com.albunyaan.tube.model.ReportStatus;
import com.albunyaan.tube.model.ReportTargetType;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.ContentReportRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.cloud.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ContentReportService {

    private static final Logger log = LoggerFactory.getLogger(ContentReportService.class);
    private static final int RATE_LIMIT_MAX = 5;
    private static final String ANONYMOUS_DEVICE_KEY = "ANONYMOUS_DEVICE";

    private final ContentReportRepository reportRepository;
    private final Cache<String, AtomicInteger> rateLimitCache;
    private final VideoRepository videoRepository;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final PublicContentCacheService publicContentCacheService;

    public ContentReportService(
            ContentReportRepository reportRepository,
            @Qualifier("reportRateLimitCache") Cache<String, AtomicInteger> rateLimitCache,
            VideoRepository videoRepository,
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            PublicContentCacheService publicContentCacheService) {
        this.reportRepository = reportRepository;
        this.rateLimitCache = rateLimitCache;
        this.videoRepository = videoRepository;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.publicContentCacheService = publicContentCacheService;
    }

    public ContentReport submitReport(
            ReportTargetType targetType, String targetId,
            List<ReportReason> reasons, String otherDescription, String deviceKey)
            throws ExecutionException, InterruptedException, TimeoutException {
        return submitReport(targetType, targetId, reasons, otherDescription, deviceKey,
                null, null, null);
    }

    public ContentReport submitReport(
            ReportTargetType targetType, String targetId,
            List<ReportReason> reasons, String otherDescription, String deviceKey,
            ReportTargetType parentType, String parentId, String contentSubType)
            throws ExecutionException, InterruptedException, TimeoutException {

        checkRateLimit(deviceKey);

        ContentReport report = new ContentReport();
        report.setTargetType(targetType);
        report.setTargetId(targetId);
        report.setReasons(reasons);
        report.setOtherDescription(otherDescription);
        report.setDeviceId(deviceKey);
        report.setStatus(ReportStatus.PENDING);
        report.setCreatedAt(Timestamp.now());

        // Parent context is only meaningful when (a) targetType is the
        // child shape (VIDEO or PLAYLIST) AND (b) parentType is a
        // container (CHANNEL or PLAYLIST). Drop bogus combinations
        // silently — the report is still useful, just without the
        // resolve→exclude side-effect.
        if (parentType == ReportTargetType.CHANNEL || parentType == ReportTargetType.PLAYLIST) {
            if (parentId != null && !parentId.isBlank()) {
                report.setParentType(parentType);
                report.setParentId(parentId);
            }
        }
        if (contentSubType != null && !contentSubType.isBlank()) {
            // Normalize to upper-case and only accept the documented values.
            String normalized = contentSubType.toUpperCase();
            if ("SHORT".equals(normalized) || "LIVESTREAM".equals(normalized) || "POST".equals(normalized)) {
                report.setContentSubType(normalized);
            }
        }

        ContentReport saved = reportRepository.save(report);

        try {
            reportRepository.writeAdminNotification(saved.getId(), targetType, targetId);
        } catch (Exception e) {
            log.warn("Failed to write admin notification for report {}: {}", saved.getId(), e.getMessage());
        }

        return saved;
    }

    public List<ContentReport> getReports(ReportStatus status, ReportTargetType targetType, int page, int size)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (status != null && targetType != null) {
            return reportRepository.findByStatusAndTargetType(status, targetType, page, size);
        } else if (status != null) {
            return reportRepository.findByStatus(status, page, size);
        } else if (targetType != null) {
            return reportRepository.findAllByTargetType(targetType, page, size);
        }
        return reportRepository.findAll(page, size);
    }

    public ContentReport resolveReport(String reportId, ReportStatus newStatus, String resolvedBy, String note)
            throws ExecutionException, InterruptedException, TimeoutException {
        ContentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));
        report.setStatus(newStatus);
        report.setResolvedAt(Timestamp.now());
        report.setResolvedBy(resolvedBy);
        report.setResolutionNote(note);
        ContentReport saved = reportRepository.update(report);
        if (newStatus == ReportStatus.RESOLVED) {
            // When the report carries parent context, prefer adding the
            // target to the parent's exclusion list — that's the
            // user-visible side-effect the admin cares about and it does
            // not destroy the underlying content (vs. archiveReportedContent
            // which flips ValidationStatus to ARCHIVED app-wide). When
            // there's no parent context, fall back to the legacy
            // archive-everywhere behaviour so existing reports keep
            // working.
            boolean excluded = false;
            if (report.getParentType() != null && report.getParentId() != null) {
                excluded = addReportTargetToParentExclusionList(report);
            }
            if (!excluded) {
                archiveReportedContent(report.getTargetType(), report.getTargetId());
            } else {
                // Exclusion changes affect public listings — bust the
                // public-content cache so users see the change immediately.
                publicContentCacheService.evictPublicContentCaches();
            }
        }
        return saved;
    }

    /**
     * Add the report's target to its parent's exclusion list.
     * Returns true if the exclusion was applied (parent existed and add
     * succeeded), false otherwise (caller should fall back to archive).
     */
    private boolean addReportTargetToParentExclusionList(ContentReport report) {
        ReportTargetType parentType = report.getParentType();
        String parentId = report.getParentId();
        ReportTargetType targetType = report.getTargetType();
        String targetId = report.getTargetId();
        String contentSubType = report.getContentSubType();
        if (parentType == null || parentId == null || targetId == null || targetType == null) return false;

        try {
            switch (parentType) {
                case CHANNEL -> {
                    var opt = channelRepository.findByYoutubeId(parentId);
                    if (opt.isEmpty()) {
                        log.warn("Cannot exclude target {}: parent channel {} not found", targetId, parentId);
                        return false;
                    }
                    var channel = opt.get();
                    var excluded = channel.getExcludedItems();
                    if (excluded == null) excluded = new com.albunyaan.tube.model.Channel.ExcludedItems();
                    String storageType = resolveChannelStorageType(targetType, contentSubType);
                    if (storageType == null) {
                        log.warn("Cannot map report target ({}, sub={}) to a channel exclusion bucket", targetType, contentSubType);
                        return false;
                    }
                    boolean added = addToChannelExclusions(excluded, storageType, targetId);
                    if (added) {
                        channel.setExcludedItems(excluded);
                        channelRepository.save(channel);
                    }
                    return true; // even no-op (already excluded) counts — don't fall back to archive
                }
                case PLAYLIST -> {
                    if (targetType != ReportTargetType.VIDEO) {
                        log.warn("Playlist parent only supports VIDEO target, got {}", targetType);
                        return false;
                    }
                    var opt = playlistRepository.findByYoutubeId(parentId);
                    if (opt.isEmpty()) {
                        log.warn("Cannot exclude target {}: parent playlist {} not found", targetId, parentId);
                        return false;
                    }
                    var playlist = opt.get();
                    var ids = playlist.getExcludedVideoIds();
                    if (ids == null) ids = new java.util.ArrayList<>();
                    if (!ids.contains(targetId)) {
                        ids.add(targetId);
                        playlist.setExcludedVideoIds(ids);
                        playlistRepository.save(playlist);
                    }
                    return true;
                }
                default -> {
                    return false;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to add target {} to parent {} exclusion list: {}", targetId, parentId, e.getMessage());
            return false;
        }
    }

    /**
     * Map a report target+sub-type pair to the Channel.ExcludedItems
     * bucket name. Mirrors the resolveStorageType helper in
     * ExclusionsWorkspaceController so the two flows produce identical
     * exclusion records.
     */
    private static String resolveChannelStorageType(ReportTargetType targetType, String contentSubType) {
        if (targetType == ReportTargetType.PLAYLIST) return "PLAYLIST";
        if (targetType == ReportTargetType.VIDEO) {
            if (contentSubType == null) return "VIDEO";
            return switch (contentSubType) {
                case "SHORT" -> "SHORT";
                case "LIVESTREAM" -> "LIVESTREAM";
                case "POST" -> "POST";
                default -> "VIDEO";
            };
        }
        return null; // CHANNEL target inside CHANNEL parent makes no sense
    }

    /**
     * Append [excludeId] to the right Channel.ExcludedItems bucket.
     * Returns true when the list grew; false if the id was already there
     * or the bucket name was unrecognised. Mirrors the helper in
     * ExclusionsWorkspaceController; intentionally duplicated to keep
     * each controller/service free of the other's concerns.
     */
    private static boolean addToChannelExclusions(
            com.albunyaan.tube.model.Channel.ExcludedItems excluded,
            String storageType, String excludeId) {
        java.util.List<String> list;
        switch (storageType) {
            case "VIDEO": list = excluded.getVideos(); break;
            case "PLAYLIST": list = excluded.getPlaylists(); break;
            case "LIVESTREAM": list = excluded.getLiveStreams(); break;
            case "SHORT": list = excluded.getShorts(); break;
            case "POST": list = excluded.getPosts(); break;
            default: return false;
        }
        if (list == null) {
            list = new java.util.ArrayList<>();
            switch (storageType) {
                case "VIDEO": excluded.setVideos(list); break;
                case "PLAYLIST": excluded.setPlaylists(list); break;
                case "LIVESTREAM": excluded.setLiveStreams(list); break;
                case "SHORT": excluded.setShorts(list); break;
                case "POST": excluded.setPosts(list); break;
            }
        }
        if (!list.contains(excludeId)) {
            list.add(excludeId);
            return true;
        }
        return false;
    }

    private void archiveReportedContent(ReportTargetType targetType, String targetId) {
        boolean archived = false;
        try {
            switch (targetType) {
                case VIDEO -> {
                    var opt = videoRepository.findByYoutubeId(targetId);
                    if (opt.isPresent()) {
                        opt.get().setValidationStatus(ValidationStatus.ARCHIVED);
                        videoRepository.save(opt.get());
                        archived = true;
                    } else {
                        log.warn("Cannot archive video {}: not found in database", targetId);
                    }
                }
                case CHANNEL -> {
                    var opt = channelRepository.findByYoutubeId(targetId);
                    if (opt.isPresent()) {
                        opt.get().setValidationStatus(ValidationStatus.ARCHIVED);
                        channelRepository.save(opt.get());
                        archived = true;
                    } else {
                        log.warn("Cannot archive channel {}: not found in database", targetId);
                    }
                }
                case PLAYLIST -> {
                    var opt = playlistRepository.findByYoutubeId(targetId);
                    if (opt.isPresent()) {
                        opt.get().setValidationStatus(ValidationStatus.ARCHIVED);
                        playlistRepository.save(opt.get());
                        archived = true;
                    } else {
                        log.warn("Cannot archive playlist {}: not found in database", targetId);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to archive {} {}: {}", targetType, targetId, e.getMessage());
        }
        if (archived) {
            publicContentCacheService.evictPublicContentCaches();
        }
    }

    public ReportStats getStats() throws ExecutionException, InterruptedException, TimeoutException {
        long pending = reportRepository.countByStatus(ReportStatus.PENDING);
        long resolved = reportRepository.countByStatus(ReportStatus.RESOLVED);
        long rejected = reportRepository.countByStatus(ReportStatus.REJECTED);
        Timestamp since = Timestamp.ofTimeSecondsAndNanos(
                Instant.now().minus(3, ChronoUnit.HOURS).getEpochSecond(), 0);
        long newLast3h = reportRepository.countCreatedAfter(since);
        return new ReportStats(pending, resolved, rejected, newLast3h);
    }

    private void checkRateLimit(String deviceKey) {
        if (deviceKey == null || deviceKey.isBlank()) deviceKey = ANONYMOUS_DEVICE_KEY;
        AtomicInteger count = rateLimitCache.get(deviceKey, k -> new AtomicInteger(0));
        int current = count.incrementAndGet();
        if (current > RATE_LIMIT_MAX) {
            count.decrementAndGet();
            throw new RateLimitExceededException("Report rate limit exceeded for device: " + deviceKey);
        }
    }

    public record ReportStats(long pending, long resolved, long rejected, long newLast3h) {}

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) { super(message); }
    }
}
