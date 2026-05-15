package com.albunyaan.tube.service;

import com.albunyaan.tube.config.MailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Plan F (ADMIN-USER-01) risk §11.3 — when mail is enabled, prove the configured
 * from-address mailbox is reachable via Graph at startup. Hard-fail if not, so the
 * operator catches the misconfiguration immediately (rather than discovering it
 * the first time an admin clicks "Reset password").
 */
@Component
public class MailServiceStartupCheck implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MailServiceStartupCheck.class);

    private final MailProperties mail;
    private final MailService mailService;
    private final boolean failOnError;

    public MailServiceStartupCheck(MailProperties mail,
                                    MailService mailService,
                                    @Value("${mail.startup-check.fail-on-error:true}") boolean failOnError) {
        this.mail = mail;
        this.mailService = mailService;
        this.failOnError = failOnError;
    }

    /**
     * Bound the Graph reachability probe so a hung DNS lookup or a degraded Graph
     * endpoint can't pin application startup forever. The Graph SDK does not
     * expose a client-side per-call timeout we can set declaratively, so we run
     * the probe on a dedicated single-thread executor and time it out at the JVM
     * level. The executor is shut down (with shutdownNow + interrupt) after the
     * check so a hung blocking call doesn't leak the worker — using
     * ForkJoinPool.commonPool() (the CompletableFuture default) would abandon
     * the blocked thread on commonPool without ever cancelling the underlying
     * call.
     */
    static final int STARTUP_CHECK_TIMEOUT_SECONDS = 30;

    @Override
    public void run(ApplicationArguments args) {
        if (!mail.isEnabled()) {
            log.info("mail.startup-check.skipped (mail.enabled=false)");
            return;
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mail-startup-check");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<?> future = executor.submit(mailService::verifyFromMailboxReachable);
            future.get(STARTUP_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("mail.startup-check.ok from={}", mail.getFromAddress());
        } catch (TimeoutException e) {
            log.error("mail.startup-check.timeout from={} after={}s",
                    mail.getFromAddress(), STARTUP_CHECK_TIMEOUT_SECONDS);
            if (failOnError) {
                throw new IllegalStateException(
                        "Mail startup check timed out after "
                                + STARTUP_CHECK_TIMEOUT_SECONDS
                                + "s for " + mail.getFromAddress(), e);
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("mail.startup-check.failed from={} error={}",
                    mail.getFromAddress(), cause.getMessage(), cause);
            if (failOnError) {
                throw new IllegalStateException(
                        "Mail startup check failed for " + mail.getFromAddress(), cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (failOnError) {
                throw new IllegalStateException(
                        "Mail startup check interrupted for " + mail.getFromAddress(), e);
            }
        } finally {
            // shutdownNow interrupts the worker; if the Graph SDK respects
            // interrupts the blocked call returns immediately. If it doesn't
            // (uninterruptible socket read), the daemon thread dies with the
            // JVM and doesn't pin the rest of startup.
            executor.shutdownNow();
        }
    }
}
