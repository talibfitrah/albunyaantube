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

        checkRateLimit(deviceKey);

        ContentReport report = new ContentReport();
        report.setTargetType(targetType);
        report.setTargetId(targetId);
        report.setReasons(reasons);
        report.setOtherDescription(otherDescription);
        report.setDeviceId(deviceKey);
        report.setStatus(ReportStatus.PENDING);
        report.setCreatedAt(Timestamp.now());

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
            archiveReportedContent(report.getTargetType(), report.getTargetId());
        }
        return saved;
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
