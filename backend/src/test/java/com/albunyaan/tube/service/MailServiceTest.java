package com.albunyaan.tube.service;

import com.albunyaan.tube.config.AzureProperties;
import com.albunyaan.tube.config.MailProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MailServiceTest {

    @Test
    void disabledMail_shortCircuits_andDoesNotInitializeGraph() {
        MailProperties mail = new MailProperties();
        mail.setEnabled(false);
        mail.setFromAddress("noreply@fitrahtube.com");
        mail.setFromDisplayName("FitrahTube");
        AzureProperties azure = new AzureProperties();
        MeterRegistry meters = new SimpleMeterRegistry();
        AuditLogService auditLog = mock(AuditLogService.class);

        MailService svc = new MailService(mail, azure, meters, auditLog);

        // Should not throw, should not call Graph (no Graph client constructed at all).
        svc.sendPasswordResetEmail("user@example.com", "https://reset/link");

        verifyNoInteractions(auditLog);
        assertEquals(0.0, meters.counter("email.send.success", "type", "password_reset").count());
        assertEquals(0.0, meters.counter("email.send.failure", "type", "password_reset").count());
    }

    @Test
    void enabledMail_buildsCorrectMessage_andSends() throws Exception {
        MailProperties mail = new MailProperties();
        mail.setEnabled(false); // skip Graph init in constructor
        mail.setFromAddress("noreply@fitrahtube.com");
        mail.setFromDisplayName("FitrahTube");
        AzureProperties azure = new AzureProperties();
        MeterRegistry meters = new SimpleMeterRegistry();
        AuditLogService auditLog = mock(AuditLogService.class);

        MailService svc = new MailService(mail, azure, meters, auditLog);

        com.microsoft.graph.models.Message msg = svc.buildPasswordResetMessage(
                "user@example.com", "https://app.fitrahtube.com/reset/abc");

        assertEquals("Reset your FitrahTube password", msg.getSubject());
        assertEquals(com.microsoft.graph.models.BodyType.Text, msg.getBody().getContentType());
        assertTrue(msg.getBody().getContent().contains("https://app.fitrahtube.com/reset/abc"));
        assertTrue(msg.getBody().getContent().contains("This link expires in 1 hour"));
        assertTrue(msg.getBody().getContent().contains("FitrahTube"));
        assertEquals(1, msg.getToRecipients().size());
        assertEquals("user@example.com",
                msg.getToRecipients().get(0).getEmailAddress().getAddress());
    }

    @Test
    void enabledMail_whenSendThrows_logsCountsAndAudits() {
        MailProperties mail = new MailProperties();
        mail.setEnabled(false); // skip Graph init in constructor
        mail.setFromAddress("noreply@fitrahtube.com");
        mail.setFromDisplayName("FitrahTube");
        AzureProperties azure = new AzureProperties();
        MeterRegistry meters = new SimpleMeterRegistry();
        AuditLogService auditLog = mock(AuditLogService.class);

        class TestableMailService extends MailService {
            TestableMailService() { super(mail, azure, meters, auditLog); }
            void simulateFailure(String to) {
                handleSendFailure(to, new RuntimeException("graph 503"));
            }
        }
        TestableMailService svc = new TestableMailService();

        svc.simulateFailure("user@example.com");

        assertEquals(1.0, meters.counter("email.send.failure", "type", "password_reset").count());
        verify(auditLog).logSystem(
                eq("USER_PASSWORD_RESET_EMAIL_FAILED"),
                eq("user"),
                eq("user@example.com"),
                anyString());
    }
}
