package com.albunyaan.tube.service;

import com.albunyaan.tube.config.AzureProperties;
import com.albunyaan.tube.config.MailProperties;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Plan F (ADMIN-USER-01) — Microsoft Graph mail sender.
 * Feature-gated by mail.enabled. When disabled, all sends are no-ops.
 * Failures are logged + audited; the caller is never blocked.
 */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final GraphServiceClient graph; // null when disabled
    private final String fromAddress;
    private final String fromDisplayName;
    private final boolean enabled;
    private final MeterRegistry meters;
    private final AuditLogService auditLog;

    public MailService(MailProperties mail,
                       AzureProperties azure,
                       MeterRegistry meters,
                       AuditLogService auditLog) {
        this.enabled = mail.isEnabled();
        this.fromAddress = mail.getFromAddress();
        this.fromDisplayName = mail.getFromDisplayName();
        this.meters = meters;
        this.auditLog = auditLog;

        if (enabled) {
            ClientSecretCredential cred = new ClientSecretCredentialBuilder()
                    .tenantId(azure.getTenantId())
                    .clientId(azure.getClientId())
                    .clientSecret(azure.getClientSecret())
                    .build();
            this.graph = new GraphServiceClient(cred,
                    "https://graph.microsoft.com/.default");
        } else {
            this.graph = null;
        }
    }

    @Async
    public void sendPasswordResetEmail(String to, String resetLink) {
        if (!enabled) {
            log.info("mail.disabled to={}", to);
            return;
        }
        // Happy path + failure path implemented in T4 + T5.
        throw new UnsupportedOperationException("implemented in T4");
    }
}
