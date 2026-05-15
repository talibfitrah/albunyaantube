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
            // Explicit checks so a missing Azure value yields a clearly attributed
            // startup failure rather than a stack trace pointing into MSAL/Azure
            // SDK internals (ClientSecretCredentialBuilder throws an NPE that does
            // not mention which field is missing).
            requireConfigured("azure.tenant-id", azure.getTenantId());
            requireConfigured("azure.client-id", azure.getClientId());
            requireConfigured("azure.client-secret", azure.getClientSecret());
            requireConfigured("mail.from-address", this.fromAddress);
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

    private static void requireConfigured(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "mail.enabled=true but " + key + " is not set. " +
                    "Either set " + key + " in application.yml / env or disable mail.");
        }
    }

    /**
     * Cubic R5 P1: routes to bounded {@code mailExecutor} instead of Spring's
     * default {@code SimpleAsyncTaskExecutor}. The latter spawns an unbounded
     * thread per send — a bulk-reset wave would create one HTTP-bound thread
     * per recipient.
     */
    @Async("mailExecutor")
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
        // Cubic R5 P1: never pipe the raw `to` into an audit row — log-shippers
        // and CSV exporters get poisoned by CR/LF/control chars in unvalidated
        // recipient strings. Sanitise once here.
        auditLog.logSystem(
                "USER_PASSWORD_RESET_EMAIL_FAILED",
                "user",
                sanitiseRecipientForAudit(to),
                "mail-service: error=" + e.getClass().getSimpleName());
    }

    /**
     * Returns the recipient in a form safe to embed in an audit row: strips
     * CR/LF/control chars, caps length to 254 chars (RFC 5321 max), and
     * collapses anything that isn't a plausible RFC 5322 mailbox to the
     * literal string {@code <invalid>}. We deliberately do not throw — the
     * mail path itself already failed and we want the audit row written
     * regardless. Package-private for test.
     */
    static String sanitiseRecipientForAudit(String to) {
        if (to == null) return "<null>";
        // strip CR / LF / control chars (header-injection vector for log shippers)
        String stripped = to.replaceAll("[\\p{Cntrl}]", "");
        if (stripped.isBlank() || stripped.length() > 254) return "<invalid>";
        // Minimal RFC 5322 shape: local@domain, no spaces, exactly one @.
        if (stripped.indexOf('@') < 1 || stripped.lastIndexOf('@') != stripped.indexOf('@')) {
            return "<invalid>";
        }
        if (stripped.contains(" ") || stripped.contains(",") || stripped.contains(";")) {
            return "<invalid>";
        }
        return stripped;
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

    /** Plan F risk §11.3 — Graph users.byUserId(fromAddress).get() smoke call. */
    public void verifyFromMailboxReachable() {
        if (!enabled) return;
        graph.users().byUserId(fromAddress).get();
    }
}
