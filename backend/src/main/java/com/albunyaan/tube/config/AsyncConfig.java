package com.albunyaan.tube.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FIREBASE-MIGRATE-04: Async Configuration
 *
 * Enables @Async annotation for asynchronous task execution.
 * Used for audit logging to avoid blocking request threads.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);
    private static final LoggingCallerRunsPolicy rejectionHandler = new LoggingCallerRunsPolicy();

    /**
     * Dedicated executor for content validation tasks.
     *
     * Uses a bounded thread pool instead of the common fork-join pool because:
     * - Validation does blocking I/O (Firestore/NewPipe calls)
     * - Common pool has limited threads (CPU cores) and can be starved
     * - Bounded pool prevents unbounded thread growth under repeated triggers
     *
     * Configuration rationale:
     * - corePoolSize=2: Minimum threads kept alive for quick startup
     * - maxPoolSize=4: Cap concurrent validation runs (each does sequential I/O)
     * - queueCapacity=10: Buffer for burst requests; rejects if exceeded
     * - keepAlive=60s: Idle threads above core are reclaimed after 1 minute
     * - rejectedExecutionHandler: Custom handler that logs and throws exception
     *   This prevents silent task loss and provides 503 response to users
     */
    @Bean(name = "validationExecutor")
    public Executor validationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("validation-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        // Use custom rejection handler that logs metrics and throws exception
        executor.setRejectedExecutionHandler(rejectionHandler);

        executor.initialize();
        return executor;
    }

    /**
     * Expose the rejection handler as a bean so its metrics can be monitored.
     * Use this to track validation executor overload events.
     */
    @Bean(name = "validationRejectionHandler")
    public LoggingCallerRunsPolicy validationRejectionHandler() {
        return rejectionHandler;
    }

    /**
     * Custom rejection handler that logs when tasks are rejected and throws an exception.
     *
     * This ensures:
     * 1. Tasks are never silently lost - caller is notified via exception
     * 2. Administrators can monitor for capacity issues via logs and metrics
     * 3. Controller can return 503 Service Unavailable to users when overloaded
     * 4. Rejection count is tracked for monitoring
     *
     * Made public so metrics can be exposed via health/metrics endpoints.
     */
    public static class LoggingCallerRunsPolicy implements RejectedExecutionHandler {
        private final AtomicLong rejectionCount = new AtomicLong(0);

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (!executor.isShutdown()) {
                long count = rejectionCount.incrementAndGet();

                logger.warn("Validation executor queue full (capacity={}). Task REJECTED (total rejections: {}). " +
                           "System is overloaded - consider increasing queue capacity or throttling requests. " +
                           "Active threads: {}/{}, Queue size: {}/{}",
                        executor.getQueue().size() + executor.getQueue().remainingCapacity(),
                        count,
                        executor.getActiveCount(),
                        executor.getMaximumPoolSize(),
                        executor.getQueue().size(),
                        executor.getQueue().remainingCapacity() + executor.getQueue().size());

                // Throw exception so controller can return 503 Service Unavailable
                throw new RejectedExecutionException(
                    "Validation system is currently overloaded. Please try again in a few minutes. " +
                    "(Active validations: " + executor.getActiveCount() + "/" + executor.getMaximumPoolSize() + ", " +
                    "Queue: " + executor.getQueue().size() + "/" +
                    (executor.getQueue().size() + executor.getQueue().remainingCapacity()) + ")"
                );
            } else {
                logger.warn("Validation executor is shut down. Task rejected and discarded.");
                throw new RejectedExecutionException("Validation system is shutting down. Please try again later.");
            }
        }

        /**
         * Get the total number of rejected tasks since startup.
         * Used for monitoring and alerting.
         */
        public long getRejectionCount() {
            return rejectionCount.get();
        }
    }
}

