package com.albunyaan.tube.service;

import com.albunyaan.tube.config.AzureProperties;
import com.albunyaan.tube.config.MailProperties;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.BodyType;
import com.microsoft.graph.models.EmailAddress;
import com.microsoft.graph.models.ItemBody;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.models.Recipient;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.item.sendmail.SendMailPostRequestBody;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.LinkedList;

/**
 * Plan F (ADMIN-USER-01) — Microsoft Graph mail sender.
 * Feature-gated by mail.enabled. When disabled, all sends return false without sending.
 * Sends are SYNCHRONOUS (CF-A-57): the caller waits for Graph's answer and gets a boolean --
 * true only when a message was handed to Graph -- so it can answer 503 MAIL_UNAVAILABLE
 * honestly instead of claiming a mail that never left. Failures are logged + audited.
 */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";

    private final ClientSecretCredential credential; // null when disabled; the one sending uses
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
            this.credential = new ClientSecretCredentialBuilder()
                    .tenantId(azure.getTenantId())
                    .clientId(azure.getClientId())
                    .clientSecret(azure.getClientSecret())
                    .build();
            this.graph = new GraphServiceClient(credential, GRAPH_SCOPE);
        } else {
            this.credential = null;
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

    /** Whether {@code mail.enabled=true}: lets callers skip work (Firebase link minting) that
     *  no mail will ever carry. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * CF-A-57: synchronous on purpose. Its only caller is the admin "reset password"
     * endpoint (one recipient, no bulk path), and {@code @Async} cannot return whether
     * Graph accepted the message -- so the admin was told "sent" with mail off.
     *
     * @return whether the message was actually handed to Graph; {@code false} when mail is
     *         disabled or Graph refused it.
     */
    public boolean sendPasswordResetEmail(String to, String resetLink) {
        return sendViaGraph(to, "password_reset",
                buildMessage(to, "Reset your FitrahTube password",
                        "Hi,\n\n"
                      + "We received a request to reset your FitrahTube password.\n"
                      + "Click the link below to set a new password:\n\n"
                      + resetLink + "\n\n"
                      + "This link expires in 1 hour. If you didn't request a reset, ignore this email — "
                      + "your account is safe.\n\n"
                      + "This is an automated message from " + fromDisplayName
                      + ". Replies to this address are not monitored.\n"));
    }

    /**
     * @return whether the message was actually handed to Graph. {@code false} when mail is
     *         disabled or Graph refused it -- the caller must not report "sent" then, because
     *         the apps run their own Firebase mailer only on a non-2xx answer.
     */
    public boolean sendEmailVerification(String to, String verificationLink) {
        return sendViaGraph(to, "email_verification",
                buildMessage(to, "Verify your FitrahTube email",
                        "Assalamu alaykum,\n\n"
                      + "Please verify your email address to complete your FitrahTube account.\n"
                      + "Click the link below:\n\n"
                      + verificationLink + "\n\n"
                      + "If you didn't create a FitrahTube account, ignore this email.\n\n"
                      + "This is an automated message from " + fromDisplayName
                      + ". Replies to this address are not monitored.\n"));
    }

    /**
     * Sent to the NEW address; the account's email changes only when that link is opened.
     *
     * @return whether the message was actually handed to Graph (same contract as above).
     */
    public boolean sendEmailChangeVerification(String to, String changeLink) {
        return sendViaGraph(to, "email_change",
                buildMessage(to, "Confirm your new FitrahTube email",
                        "Assalamu alaykum,\n\n"
                      + "We received a request to change your FitrahTube account email to this address.\n"
                      + "Click the link below to confirm:\n\n"
                      + changeLink + "\n\n"
                      + "If you didn't request this, ignore this email — nothing will change.\n\n"
                      + "This is an automated message from " + fromDisplayName
                      + ". Replies to this address are not monitored.\n"));
    }

    private boolean sendViaGraph(String to, String type, Message msg) {
        if (!enabled) {
            log.info("mail.disabled ({}) recipient={}", type, maskEmail(to));
            return false;
        }
        try {
            SendMailPostRequestBody body = new SendMailPostRequestBody();
            body.setMessage(msg);
            body.setSaveToSentItems(false);
            graph.users().byUserId(fromAddress).sendMail().post(body);
            meters.counter("email.send.success", "type", type).increment();
            log.info("{}.sent", type);
            return true;
        } catch (Exception e) {
            handleSendFailure(to, e, type);
            return false;
        }
    }

    /** Package-private for unit test override. */
    void handleSendFailure(String to, Exception e, String type) {
        log.error("{}.failed to={}", type, maskEmail(to), e);
        meters.counter("email.send.failure", "type", type).increment();
        // Cubic R5 P1: never pipe the raw `to` into an audit row — log-shippers
        // and CSV exporters get poisoned by CR/LF/control chars in unvalidated
        // recipient strings. Sanitise once here.
        String eventName = "USER_" + type.toUpperCase() + "_EMAIL_FAILED";
        auditLog.logSystem(
                eventName,
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

    /** For logs: {@code f***@gmail.com} -- enough to correlate a report, not a harvestable address. */
    public static String maskEmail(String email) {
        if (email == null) return "<null>";
        String clean = email.replaceAll("[\\p{Cntrl}]", "");
        int at = clean.indexOf('@');
        return at < 1 ? "***" : clean.charAt(0) + "***" + clean.substring(at);
    }

    Message buildMessage(String to, String subject, String textContent) {
        Message m = new Message();
        m.setSubject(subject);

        ItemBody body = new ItemBody();
        body.setContentType(BodyType.Text);
        body.setContent(textContent);
        m.setBody(body);

        Recipient r = new Recipient();
        EmailAddress addr = new EmailAddress();
        addr.setAddress(to);
        r.setEmailAddress(addr);
        LinkedList<Recipient> toRecipients = new LinkedList<>();
        toRecipients.add(r);
        m.setToRecipients(toRecipients);

        return m;
    }


    /**
     * Plan F risk §11.3 startup probe, scoped to what a Mail.Send-only app can prove (least
     * privilege: the app registration holds only Mail.Send, application):
     * <ul>
     *   <li>the same credential sending uses acquires a Graph token -- catches a wrong
     *       secret, tenant or client id;</li>
     *   <li>the token's {@code roles} claim contains {@code Mail.Send} -- catches missing
     *       admin consent.</li>
     * </ul>
     * It does NOT prove the from-address mailbox exists: reading a user needs
     * User.Read.All, which this app deliberately lacks (GET /users/{id} answers 403).
     * A wrong from-address surfaces on the first send instead.
     */
    public void verifyMailSendGranted() {
        if (!enabled) return;
        String jwt = credential.getTokenSync(new TokenRequestContext().addScopes(GRAPH_SCOPE)).getToken();
        Boolean granted = hasMailSendRole(jwt);
        if (granted == null) {
            // Graph tokens are documented as opaque (may change format or be encrypted); getting
            // one already proves the credential, and sending does not depend on reading it.
            log.warn("mail.startup-check.token-unreadable: Graph access token is not a readable JWT; "
                    + "Mail.Send role not verified");
        } else if (!granted) {
            throw new IllegalStateException("Graph token has no Mail.Send application role; "
                    + "grant admin consent for Mail.Send on the app registration");
        }
    }

    /**
     * Whether the access token's {@code roles} claim contains Mail.Send; {@code null} when the
     * token is not a readable JWT (three segments, JSON-object payload). Package-private for test.
     */
    static Boolean hasMailSendRole(String jwt) {
        JsonNode payload;
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) return null;
            payload = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception e) {
            return null;
        }
        if (!payload.isObject()) return null;
        for (JsonNode role : payload.path("roles")) {
            if ("Mail.Send".equals(role.asText())) return true;
        }
        return false;
    }
}
