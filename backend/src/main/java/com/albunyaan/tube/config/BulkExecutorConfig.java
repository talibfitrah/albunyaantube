package com.albunyaan.tube.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared 5-thread pool for bulk preview metadata fan-out.
 * Singleton bean; lifecycle managed by Spring.
 *
 * <p>named daemon threads so a thread-dump during
 * incident triage identifies bulk-preview work distinctly, and so a hung pool
 * doesn't block JVM shutdown.
 */
@Configuration
public class BulkExecutorConfig {

    /** Destroyed automatically by Spring on context shutdown. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService bulkPreviewExecutor() {
        AtomicInteger n = new AtomicInteger();
        return Executors.newFixedThreadPool(5, r -> {
            Thread t = new Thread(r, "bulk-preview-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
