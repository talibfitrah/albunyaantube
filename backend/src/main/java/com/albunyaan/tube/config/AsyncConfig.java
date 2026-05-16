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
     * Bounded executor for public-content fan-out queries (home feed, category content).
     *
     * Uses a separate pool from validationExecutor because:
     * - Public content requests are read-only Firestore queries (lighter than validation writes)
     * - Multiple concurrent /api/v1/home requests can be active simultaneously
     * - Common pool has limited threads (CPU cores) and blocks under concurrent fan-out
     *
     * Configuration rationale:
     * - corePoolSize=4: Handle typical concurrent request fan-out
     * - maxPoolSize=8: Burst capacity for parallel category loading
     * - queueCapacity=50: Buffer for spikes; CallerRunsPolicy degrades gracefully
     * - CallerRunsPolicy: When pool is saturated, calling thread runs the task (back-pressure).
     *   RISK: Under extreme load, the calling thread is a Tomcat HTTP thread, so it will block
     *   for the duration of a Firestore query. With all 8 pool threads + 50 queue slots full,
     *   this is unlikely in normal operation but could cascade under sustained high concurrency.
     *   Monitor for saturation and increase pool/queue if needed.
     */
    @Bean(name = "publicContentExecutor")
    public Executor publicContentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("pub-content-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        // CallerRunsPolicy: if pool saturated, request thread runs task directly (graceful degradation)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
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
     * F5: bounded executor for audit-log writes.
     *
     * Previously {@code @Async} on AuditLogService.log* methods ran on Spring's
     * default {@code SimpleAsyncTaskExecutor}, which spawns a NEW thread for every
     * task with no upper bound. The UserBackfillMigration calls
     * {@code auditLogService.logSystem(...)} once per user — on a sizeable user
     * base this spawned N threads, exhausting the thread pool / heap.
     *
     * Configuration rationale:
     * - corePoolSize=2: minimum threads kept alive for ambient logging.
     * - maxPoolSize=8: cap concurrent audit writes (each is a Firestore set).
     * - queueCapacity=200: absorbs short bursts from the migration without
     *   spinning up extra threads.
     * - CallerRunsPolicy: under saturation, the caller (e.g. migration loop)
     *   runs the audit write inline. Audit logs MUST NOT be silently lost
     *   (regulatory + post-incident forensic value), so we trade throughput
     *   for durability. The caller is a background migration thread, not a
     *   HTTP request thread, so this is safe.
     */
    @Bean(name = "auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("audit-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        // CallerRunsPolicy → never lose an audit write; degrade to inline on pressure.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Cubic R5 P1 — bounded executor for Graph mail sends.
     *
     * {@code @Async} on MailService.send* methods previously routed to Spring's
     * default {@code SimpleAsyncTaskExecutor}, which spawns a NEW thread per
     * send with no upper bound. A bulk-reset wave (operator initiates password
     * reset for N users) creates N concurrent Graph HTTP calls — exactly the
     * DoS surface F5 closed on AuditLogService.
     *
     * Configuration rationale:
     * - corePoolSize=2: ambient capacity for solo sends.
     * - maxPoolSize=4: keep concurrent Graph calls modest; Microsoft Graph
     *   has its own throttling and bursts will get 429-throttled anyway.
     * - queueCapacity=200: absorb a bulk-reset wave without spawning threads.
     * - CallerRunsPolicy: under saturation the caller (admin HTTP thread) runs
     *   the send inline. The send is fire-and-forget on the request path
     *   (caller already returned 202), so the inline cost is bounded.
     */
    @Bean(name = "mailExecutor")
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        // Cubic R-final7 P3 — bumped maxPoolSize 4 → 8 and queueCapacity
        // 200 → 500. Bulk operator waves saturated 4 threads and fell
        // into CallerRunsPolicy — HTTP thread served send inline, partially
        // defeating async dispatch. 8 threads + 500-deep queue gives bursts
        // breathing room; Graph throttle backpressures real overload.
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("mail-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Cubic R-final5 P2 — bounded executor for FB Auth token-revocation retries.
     *
     * <p>Pre-fix, {@code AuthService.updateUserRoleAsActor} ran a 3-attempt
     * retry loop with 200ms+400ms backoffs on the admin HTTP request thread.
     * Under bursty role-change traffic this stacks 600ms worst-case Tomcat
     * thread occupancy per request. Moving the retry to a bounded background
     * executor lets the role-change response return immediately after the
     * audit row commits; the revoke's success/failure pair audit decouples
     * the response from completion.
     *
     * Configuration rationale:
     * - corePoolSize=1: revoke is a rare, single-action background op.
     * - maxPoolSize=4: bursts (concurrent role downgrades) tolerated.
     * - queueCapacity=200: absorb operator role-change waves.
     * - CallerRunsPolicy: under saturation the admin HTTP thread runs the
     *   retry inline — same as the pre-fix behaviour, no regression vs old
     *   shape; healthy paths keep the response-time win.
     */
    @Bean(name = "authExecutor")
    public Executor authExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("auth-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
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

