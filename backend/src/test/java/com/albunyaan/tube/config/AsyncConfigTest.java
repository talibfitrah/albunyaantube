package com.albunyaan.tube.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncConfigTest {

    /** Public forgot-password: when the pool is full the send is DROPPED. CallerRunsPolicy would run
     *  it on the request thread, and the answer's timing would say whether the account exists. */
    @Test
    void passwordResetExecutorDropsOverflowInsteadOfRunningItOnTheCaller() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().passwordResetExecutor();
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        CountDownLatch release = new CountDownLatch(1);
        int capacity = pool.getMaximumPoolSize() + pool.getQueue().remainingCapacity();
        for (int i = 0; i < capacity; i++) {
            executor.execute(() -> {
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        assertDoesNotThrow(() -> executor.execute(() -> ranOn.set(Thread.currentThread())));

        assertNull(ranOn.get(), "the overflow send ran on the caller's thread");
        release.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertNull(ranOn.get(), "the overflow send was queued, not dropped");
    }
}
