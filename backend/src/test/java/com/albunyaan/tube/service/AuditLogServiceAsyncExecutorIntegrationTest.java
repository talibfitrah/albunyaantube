package com.albunyaan.tube.service;

import com.albunyaan.tube.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BACKEND-AUTH-01 review-pipeline finding F5:
 *
 * Verifies the {@code auditExecutor} bean exists with the configured
 * {@code "audit-"} thread-name prefix, so that {@code @Async("auditExecutor")}
 * on AuditLogService binds to a bounded pool instead of Spring's default
 * unbounded SimpleAsyncTaskExecutor.
 *
 * Pre-F5: the bean did not exist, and {@code @Async} (no qualifier) ran on
 * SimpleAsyncTaskExecutor → unbounded thread spawn. Migration-driven volume
 * could exhaust the thread pool / heap.
 */
class AuditLogServiceAsyncExecutorIntegrationTest extends BaseIntegrationTest {

    @Autowired
    @Qualifier("auditExecutor")
    Executor auditExecutor;

    @Test
    void auditExecutor_isWiredAndUsesAuditThreadPrefix() throws Exception {
        assertNotNull(auditExecutor,
                "auditExecutor bean must exist for @Async(\"auditExecutor\") to bind");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> capturedName = new AtomicReference<>();
        auditExecutor.execute(() -> {
            capturedName.set(Thread.currentThread().getName());
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "auditExecutor probe must complete within 5s");
        assertNotNull(capturedName.get());
        assertTrue(capturedName.get().startsWith("audit-"),
                "Probe ran on thread '" + capturedName.get() + "' — expected 'audit-' prefix. "
              + "F5 wiring is wrong: AuditLogService @Async methods are not bound to the bounded executor.");
    }
}
