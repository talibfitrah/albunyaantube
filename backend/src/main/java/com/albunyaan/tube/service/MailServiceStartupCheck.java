package com.albunyaan.tube.service;

import com.albunyaan.tube.config.MailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

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

    @Override
    public void run(ApplicationArguments args) {
        if (!mail.isEnabled()) {
            log.info("mail.startup-check.skipped (mail.enabled=false)");
            return;
        }
        try {
            mailService.verifyFromMailboxReachable();
            log.info("mail.startup-check.ok from={}", mail.getFromAddress());
        } catch (Exception e) {
            log.error("mail.startup-check.failed from={} error={}",
                    mail.getFromAddress(), e.getMessage(), e);
            if (failOnError) {
                throw new IllegalStateException(
                        "Mail startup check failed for " + mail.getFromAddress(), e);
            }
        }
    }
}
