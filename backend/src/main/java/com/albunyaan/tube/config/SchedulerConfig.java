package com.albunyaan.tube.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Provides a dedicated thread pool for {@code @Scheduled} triggers.
 *
 * Without this bean, Spring Boot falls back to a single-thread scheduler. The
 * three validation schedulers (video / channel / playlist) cron-stagger 30 min
 * apart and rely on that separation to never overlap, but with a single
 * scheduler thread a hung run (Firestore write timeout, NewPipe stall mid-batch)
 * would queue subsequent triggers behind it — they'd fire late, sequentially,
 * defeating the staggering.
 *
 * Pool size = 3 = one slot per validation scheduler. Concurrency safety still
 * relies on the per-scheduler Firestore distributed lock, so even if all three
 * fire concurrently they can't double-extract.
 */
@Configuration
public class SchedulerConfig {

    @Bean
    public TaskScheduler validationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("validation-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
