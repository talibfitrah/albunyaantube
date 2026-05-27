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

    private MailService createDisabledService(MeterRegistry meters, AuditLogService auditLog) {
        MailProperties mail = new MailProperties();
        mail.setEnabled(false);
        mail.setFromAddress("noreply@fitrahtube.com");
        mail.setFromDisplayName("FitrahTube");
        return new MailService(mail, new AzureProperties(), meters, auditLog);
    }

    @Test
    void disabledMail_shortCircuits_andDoesNotInitializeGraph() {
        MeterRegistry meters = new SimpleMeterRegistry();
        AuditLogService auditLog = mock(AuditLogService.class);
        MailService svc = createDisabledService(meters, auditLog);

        svc.sendPasswordResetEmail("user@example.com", "https://reset/link");

        verifyNoInteractions(auditLog);
        assertEquals(0.0, meters.counter("email.send.success", "type", "password_reset").count());
        assertEquals(0.0, meters.counter("email.send.failure", "type", "password_reset").count());
    }

    @Test
    void disabledMail_verificationEmail_shortCircuits() {
        MeterRegistry meters = new SimpleMeterRegistry();
        AuditLogService auditLog = mock(AuditLogService.class);
        MailService svc = createDisabledService(meters, auditLog);

        svc.sendEmailVerification("user@example.com", "https://verify/link");

        verifyNoInteractions(auditLog);
        assertEquals(0.0, meters.counter("email.send.success", "type", "email_verification").count());
    }

    @Test
    void buildMessage_setsSubjectBodyAndRecipient() {
        MeterRegistry meters = new SimpleMeterRegistry();
        AuditLogService auditLog = mock(AuditLogService.class);
        MailService svc = createDisabledService(meters, auditLog);

        com.microsoft.graph.models.Message msg = svc.buildMessage(
                "user@example.com", "Test Subject", "Test body content");

        assertEquals("Test Subject", msg.getSubject());
        assertEquals(com.microsoft.graph.models.BodyType.Text, msg.getBody().getContentType());
        assertTrue(msg.getBody().getContent().contains("Test body content"));
        assertEquals(1, msg.getToRecipients().size());
        assertEquals("user@example.com",
                msg.getToRecipients().get(0).getEmailAddress().getAddress());
    }

    @Test
    void handleSendFailure_logsCountsAndAudits() {
        MeterRegistry meters = new SimpleMeterRegistry();
        AuditLogService auditLog = mock(AuditLogService.class);

        class TestableMailService extends MailService {
            TestableMailService() {
                super(createMailProps(), new AzureProperties(), meters, auditLog);
            }
            private static MailProperties createMailProps() {
                MailProperties m = new MailProperties();
                m.setEnabled(false);
                m.setFromAddress("noreply@fitrahtube.com");
                m.setFromDisplayName("FitrahTube");
                return m;
            }
            void simulateFailure(String to, String type) {
                handleSendFailure(to, new RuntimeException("graph 503"), type);
            }
        }
        TestableMailService svc = new TestableMailService();

        svc.simulateFailure("user@example.com", "password_reset");
        assertEquals(1.0, meters.counter("email.send.failure", "type", "password_reset").count());
        verify(auditLog).logSystem(
                eq("USER_PASSWORD_RESET_EMAIL_FAILED"),
                eq("user"),
                eq("user@example.com"),
                anyString());

        svc.simulateFailure("user@example.com", "email_verification");
        assertEquals(1.0, meters.counter("email.send.failure", "type", "email_verification").count());
        verify(auditLog).logSystem(
                eq("USER_EMAIL_VERIFICATION_EMAIL_FAILED"),
                eq("user"),
                eq("user@example.com"),
                anyString());
    }
}
