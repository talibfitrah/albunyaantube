package com.albunyaan.tube.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics configuration for monitoring application performance.
 * Integrates with Spring Boot Actuator and Prometheus for observability.
 */
@Configuration
public class MetricsConfig {

    /**
     * Customize the meter registry with common tags
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                .commonTags(
                        "application", "albunyaan-tube",
                        "service", "backend-api"
                );
    }

    /**
     * Custom metrics bean for application-specific monitoring
     */
    @Bean
    public ApplicationMetrics applicationMetrics(MeterRegistry registry) {
        return new ApplicationMetrics(registry);
    }

    /**
     * Application-specific metrics holder
     */
    public static class ApplicationMetrics {
        private final MeterRegistry registry;
        private final ConcurrentHashMap<String, Timer> timerCache;
        private final AtomicLong pendingApprovalsCount;

        public ApplicationMetrics(MeterRegistry registry) {
            this.registry = registry;
            this.timerCache = new ConcurrentHashMap<>();
            this.pendingApprovalsCount = new AtomicLong(0);

            // Register the pending approvals gauge once during initialization
            registry.gauge("approval.pending.count", pendingApprovalsCount);
        }

        /**
         * Record Firestore query execution time
         */
        public void recordFirestoreQuery(String collection, String operation, long durationMs) {
            Timer timer = registry.timer("firestore.query.duration",
                    Tags.of("collection", collection, "operation", operation));
            timer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        /**
         * Record cache hit/miss
         */
        public void recordCacheHit(String cacheName, boolean hit) {
            registry.counter("cache.access",
                    "cache", cacheName,
                    "result", hit ? "hit" : "miss"
            ).increment();
        }

        /**
         * Record approval workflow action
         */
        public void recordApprovalAction(String entityType, String action) {
            registry.counter("approval.action",
                    "entity_type", entityType,
                    "action", action
            ).increment();
        }

        /**
         * Record download token generation
         */
        public void recordDownloadToken(boolean success) {
            registry.counter("download.token.generation",
                    "success", String.valueOf(success)
            ).increment();
        }

        /**
         * Record YouTube API call
         */
        public void recordYoutubeApiCall(String endpoint, boolean success, long durationMs) {
            String cacheKey = "youtube.api.call.duration:" + endpoint + ":" + success;
            Timer timer = timerCache.computeIfAbsent(cacheKey, key ->
                    Timer.builder("youtube.api.call.duration")
                            .description("Time taken for YouTube API call")
                            .tag("endpoint", endpoint)
                            .tag("success", String.valueOf(success))
                            .register(registry)
            );
            timer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        /**
         * Record content validation check
         */
        public void recordContentValidation(String contentType, String status) {
            registry.counter("content.validation",
                    "content_type", contentType,
                    "status", status
            ).increment();
        }

        /**
         * Update the pending approvals count.
         * The gauge is registered once during initialization and tracks the AtomicLong.
         */
        public void recordPendingApprovalsCount(long count) {
            pendingApprovalsCount.set(count);
        }
    }
}
