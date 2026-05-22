package com.albunyaan.tube.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BULK-01 (T7) — shared 5-thread pool for bulk preview metadata fan-out.
 * Singleton bean; lifecycle managed by Spring.
 */
@Configuration
public class BulkExecutorConfig {

    /** Destroyed automatically by Spring on context shutdown. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService bulkPreviewExecutor() {
        return Executors.newFixedThreadPool(5);
    }
}
