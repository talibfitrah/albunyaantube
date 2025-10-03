package com.albunyaan.tube.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * FIREBASE-MIGRATE-04: Async Configuration
 *
 * Enables @Async annotation for asynchronous task execution.
 * Used for audit logging to avoid blocking request threads.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
