package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.config.ValidationProperties;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.repository.SystemSettingsRepository;
import com.albunyaan.tube.service.ContentValidationService;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.YouTubeCircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Channel Validation Scheduler.
 *
 * Periodically re-validates approved channels against YouTube. The validation
 * pass also refreshes cached metadata (subscriber count, video count, name,
 * description, thumbnail) via {@link ContentValidationService#validateChannels}
 * → {@code refreshChannelMetadata}. This is what keeps the subscriber count on
 * the public Channels list in line with what users see when they open the
 * channel detail screen — the detail screen extracts live, the list reads from
 * Firestore, and without this scheduler the list value freezes at whatever it
 * was when the channel was approved.
 *
 * Rate-limit safety:
 * - {@code app.validation.youtube.throttle} (3s + ≤1s jitter) is enforced
 *   inside {@code batchValidateChannelsDtoWithDetails}, so 30 channels = ~2 min.
 * - Circuit breaker auto-pauses on rate-limit detection.
 * - {@code max-items-per-run} caps each run.
 * - {@code getChannelsForValidation} skips channels validated within the last
 *   24h, so the daily cron only ever touches stale rows.
 *
 * Mirrors {@link VideoValidationScheduler} — same lock + heartbeat + circuit
 * breaker pattern.
 */
@Component
public class ChannelValidationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ChannelValidationScheduler.class);

    /** Distributed lock key for channel validation scheduler. */
    private static final String LOCK_KEY = "channel_validation_scheduler";

    /** Heartbeat interval in seconds (3 minutes). */
    private static final int HEARTBEAT_INTERVAL_SECONDS = 180;

    /** Default lock TTL in seconds if config is not available. Matches video. */
    private static final int DEFAULT_LOCK_TTL_SECONDS = 7200; // 2 hours

    private final ContentValidationService contentValidationService;
    private final PublicContentCacheService publicContentCacheService;
    private final ValidationProperties validationProperties;
    private final YouTubeCircuitBreaker circuitBreaker;
    private final SystemSettingsRepository systemSettingsRepository;

    private final String instanceId;
    private final ScheduledExecutorService heartbeatExecutor;

    public ChannelValidationScheduler(
            ContentValidationService contentValidationService,
            PublicContentCacheService publicContentCacheService,
            ValidationProperties validationProperties,
            YouTubeCircuitBreaker circuitBreaker,
            SystemSettingsRepository systemSettingsRepository) {
        this.contentValidationService = contentValidationService;
        this.publicContentCacheService = publicContentCacheService;
        this.validationProperties = validationProperties;
        this.circuitBreaker = circuitBreaker;
        this.systemSettingsRepository = systemSettingsRepository;
        this.instanceId = generateInstanceId();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "channel-lock-heartbeat");
            t.setDaemon(true);
            return t;
        });

        logger.info("ChannelValidationScheduler initialized - enabled: {}, cron: {}, maxItems: {}, instanceId: {}",
                validationProperties.getChannel().getScheduler().isEnabled(),
                validationProperties.getChannel().getScheduler().getCron(),
                validationProperties.getChannel().getMaxItemsPerRun(),
                instanceId);
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down ChannelValidationScheduler heartbeat executor");
        heartbeatExecutor.shutdown();
        try {
            if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatExecutor.shutdownNow();
                logger.warn("Heartbeat executor did not terminate gracefully, forced shutdown");
            }
        } catch (InterruptedException e) {
            heartbeatExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private String generateInstanceId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            return hostname + "-" + pid;
        } catch (Exception e) {
            String fallbackId = "instance-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            logger.warn("Could not determine hostname for instance ID, using fallback: {}", fallbackId);
            return fallbackId;
        }
    }

    private int getLockTtlSeconds() {
        int ttlMinutes = validationProperties.getChannel().getScheduler().getLockTtlMinutes();
        return ttlMinutes > 0 ? ttlMinutes * 60 : DEFAULT_LOCK_TTL_SECONDS;
    }

    private ScheduledFuture<?> startLockHeartbeat() {
        try {
            return heartbeatExecutor.scheduleAtFixedRate(
                    () -> {
                        try {
                            boolean extended = systemSettingsRepository.tryAcquireLock(
                                    LOCK_KEY, instanceId, getLockTtlSeconds());
                            if (extended) {
                                logger.debug("Lock heartbeat: extended channel-validation lock TTL");
                            } else {
                                logger.warn("Lock heartbeat: failed to extend channel-validation lock");
                            }
                        } catch (Exception e) {
                            logger.warn("Lock heartbeat failed: {}", e.getMessage());
                        }
                    },
                    HEARTBEAT_INTERVAL_SECONDS,
                    HEARTBEAT_INTERVAL_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            logger.warn("Failed to start lock heartbeat: {}", e.getMessage());
            return null;
        }
    }

    private void stopLockHeartbeat(ScheduledFuture<?> heartbeat) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
            logger.debug("Lock heartbeat stopped");
        }
    }

    /**
     * Scheduled validation run using configurable cron expression.
     * Default: "0 30 6 * * ?" (6:30 AM UTC daily, staggered 30 min after the
     * video validator so the two don't compete for extraction budget).
     */
    @Scheduled(cron = "${app.validation.channel.scheduler.cron:0 30 6 * * ?}", zone = "UTC")
    public void scheduledValidation() {
        logger.info("Scheduled channel validation triggered");
        runValidation("scheduled");
    }

    private void runValidation(String trigger) {
        if (!validationProperties.getChannel().getScheduler().isEnabled()) {
            logger.info("Channel validation scheduler is DISABLED - skipping {} run", trigger);
            return;
        }

        if (circuitBreaker.isOpen()) {
            YouTubeCircuitBreaker.CircuitBreakerStatus status = circuitBreaker.getStatus();
            logger.warn("Channel validation skipped - circuit breaker is OPEN. " +
                            "Cooldown remaining: {} minutes. Last error: {} - {}",
                    status.getRemainingCooldownMs() / 60000,
                    status.getLastErrorType(),
                    status.getLastErrorMessage());
            return;
        }

        if (!systemSettingsRepository.tryAcquireLock(LOCK_KEY, instanceId, getLockTtlSeconds())) {
            logger.info("Channel validation skipped - another instance holds the lock");
            return;
        }

        ScheduledFuture<?> heartbeat = startLockHeartbeat();

        try {
            int maxItems = validationProperties.getChannel().getMaxItemsPerRun();
            logger.info("Starting {} channel validation (max items: {}, instanceId: {})",
                    trigger, maxItems, instanceId);

            ValidationRun result = contentValidationService.validateChannels(
                    ValidationRun.TRIGGER_SCHEDULED,
                    null,
                    "Channel Validation Scheduler (" + trigger + ")",
                    maxItems
            );

            logger.info("{} channel validation completed - Run ID: {}, Status: {}, Checked: {}, Archived: {}, Errors: {}",
                    trigger,
                    result.getId(),
                    result.getStatus(),
                    result.getChannelsChecked(),
                    result.getChannelsMarkedArchived(),
                    result.getErrorCount()
            );

            if (ValidationRun.STATUS_COMPLETED.equals(result.getStatus())) {
                evictPublicContentCaches();
            } else {
                logger.warn("{} channel validation did not complete (status: {}), skipping cache eviction",
                        trigger, result.getStatus());
            }

        } catch (Exception e) {
            logger.error("{} channel validation failed", trigger, e);
            if (circuitBreaker.isRateLimitError(e)) {
                circuitBreaker.recordRateLimitError(e);
            }
        } finally {
            stopLockHeartbeat(heartbeat);
            systemSettingsRepository.releaseLock(LOCK_KEY, instanceId);
        }
    }

    /**
     * Evict the canonical public content cache set so the mobile app sees the
     * refreshed metadata on its next list/search fetch instead of waiting out
     * the 1h Caffeine TTL. Delegates to {@link PublicContentCacheService} so
     * we cover the same caches every other content-mutation site clears
     * (content, search, category-tree).
     */
    private void evictPublicContentCaches() {
        try {
            publicContentCacheService.evictPublicContentCaches();
            logger.debug("Evicted public content caches after channel validation");
        } catch (Exception e) {
            logger.warn("Failed to evict caches after channel validation: {}", e.getMessage());
        }
    }

    /** Whether a channel-validation run is currently in progress on any instance. */
    public boolean isRunning() {
        return systemSettingsRepository.isLockHeld(LOCK_KEY);
    }

    /**
     * Manually trigger a validation run (admin use). Respects circuit breaker
     * and distributed lock; ignores the {@code enabled} flag because manual
     * triggers are an explicit override.
     */
    public boolean triggerManualRun() {
        if (circuitBreaker.isOpen()) {
            logger.warn("Manual channel validation rejected - circuit breaker is OPEN");
            return false;
        }

        if (!systemSettingsRepository.tryAcquireLock(LOCK_KEY, instanceId, getLockTtlSeconds())) {
            logger.warn("Manual channel validation rejected - another instance holds the lock");
            return false;
        }

        ScheduledFuture<?> heartbeat = startLockHeartbeat();

        try {
            int maxItems = validationProperties.getChannel().getMaxItemsPerRun();
            logger.info("Starting manual channel validation (max items: {}, instanceId: {})",
                    maxItems, instanceId);

            ValidationRun result = contentValidationService.validateChannels(
                    ValidationRun.TRIGGER_MANUAL,
                    null,
                    "Manual Trigger",
                    maxItems
            );

            logger.info("Manual channel validation completed - Run ID: {}, Status: {}, Checked: {}, Archived: {}, Errors: {}",
                    result.getId(),
                    result.getStatus(),
                    result.getChannelsChecked(),
                    result.getChannelsMarkedArchived(),
                    result.getErrorCount()
            );

            if (ValidationRun.STATUS_COMPLETED.equals(result.getStatus())) {
                evictPublicContentCaches();
            }

            return true;
        } catch (Exception e) {
            logger.error("Manual channel validation failed", e);
            if (circuitBreaker.isRateLimitError(e)) {
                circuitBreaker.recordRateLimitError(e);
            }
            return false;
        } finally {
            stopLockHeartbeat(heartbeat);
            systemSettingsRepository.releaseLock(LOCK_KEY, instanceId);
        }
    }
}
