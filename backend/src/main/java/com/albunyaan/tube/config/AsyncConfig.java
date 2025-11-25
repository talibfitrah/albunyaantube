package com.albunyaan.tube.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

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
     * - rejectedExecutionHandler: CallerRunsPolicy - if queue is full, run in caller thread
     *   This prevents silent task loss and provides backpressure to the caller
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

        // Use CallerRunsPolicy with logging to prevent silent task rejection
        // When queue is full, the task runs in the calling thread (provides backpressure)
        executor.setRejectedExecutionHandler(new LoggingCallerRunsPolicy());

        executor.initialize();
        return executor;
    }

    /**
     * Custom rejection handler that logs when tasks are rejected and then runs them
     * in the caller's thread (CallerRunsPolicy behavior).
     *
     * This ensures:
     * 1. Tasks are never silently lost
     * 2. Administrators can monitor for capacity issues via logs
     * 3. Backpressure is applied to callers when the system is overloaded
     */
    private static class LoggingCallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (!executor.isShutdown()) {
                logger.warn("Validation executor queue full (capacity={}). Running task in caller thread. " +
                           "Consider increasing queue capacity or throttling requests. " +
                           "Active threads: {}, Pool size: {}, Queue size: {}",
                        executor.getQueue().size() + executor.getQueue().remainingCapacity(),
                        executor.getActiveCount(),
                        executor.getPoolSize(),
                        executor.getQueue().size());

                // Run in caller's thread (CallerRunsPolicy behavior)
                r.run();
            } else {
                logger.warn("Validation executor is shut down. Task rejected and discarded.");
            }
        }
    }
}

