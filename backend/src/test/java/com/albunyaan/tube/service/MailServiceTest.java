package com.albunyaan.tube.service;

import com.albunyaan.tube.config.AzureProperties;
import com.albunyaan.tube.config.MailProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
}
