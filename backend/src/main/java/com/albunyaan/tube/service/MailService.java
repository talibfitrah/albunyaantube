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
        try {
            com.microsoft.graph.models.Message msg = buildPasswordResetMessage(to, resetLink);
            com.microsoft.graph.users.item.sendmail.SendMailPostRequestBody body =
                    new com.microsoft.graph.users.item.sendmail.SendMailPostRequestBody();
            body.setMessage(msg);
            body.setSaveToSentItems(false);
            graph.users().byUserId(fromAddress).sendMail().post(body);
            meters.counter("email.send.success", "type", "password_reset").increment();
            log.info("password_reset_email.sent to={}", to);
        } catch (Exception e) {
            handleSendFailure(to, e);
        }
    }

    /** Package-private for unit test override. */
    void handleSendFailure(String to, Exception e) {
        log.error("password_reset_email.failed to={}", to, e);
        meters.counter("email.send.failure", "type", "password_reset").increment();
        auditLog.logSystem(
                "USER_PASSWORD_RESET_EMAIL_FAILED",
                "user",
                to,
                "mail-service: error=" + e.getClass().getSimpleName());
    }

    /** Package-private for unit-testability. */
    com.microsoft.graph.models.Message buildPasswordResetMessage(String to, String link) {
        com.microsoft.graph.models.Message m = new com.microsoft.graph.models.Message();
        m.setSubject("Reset your FitrahTube password");

        com.microsoft.graph.models.ItemBody body = new com.microsoft.graph.models.ItemBody();
        body.setContentType(com.microsoft.graph.models.BodyType.Text);
        body.setContent(
                "Hi,\n\n"
              + "We received a request to reset your FitrahTube password.\n"
              + "Click the link below to set a new password:\n\n"
              + link + "\n\n"
              + "This link expires in 1 hour. If you didn't request a reset, ignore this email — "
              + "your account is safe.\n\n"
              + "This is an automated message from " + fromDisplayName
              + ". Replies to this address are not monitored.\n");
        m.setBody(body);

        com.microsoft.graph.models.Recipient r = new com.microsoft.graph.models.Recipient();
        com.microsoft.graph.models.EmailAddress addr = new com.microsoft.graph.models.EmailAddress();
        addr.setAddress(to);
        r.setEmailAddress(addr);
        java.util.LinkedList<com.microsoft.graph.models.Recipient> toRecipients = new java.util.LinkedList<>();
        toRecipients.add(r);
        m.setToRecipients(toRecipients);

        return m;
    }
}
