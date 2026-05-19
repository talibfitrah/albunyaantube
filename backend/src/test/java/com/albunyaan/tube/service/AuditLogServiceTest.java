package com.albunyaan.tube.service;

import com.albunyaan.tube.model.AuditLog;
import com.albunyaan.tube.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Plan G B4 — unit tests for {@link AuditLogService#logProfileEdit}.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock private AuditLogRepository auditLogRepository;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogRepository, null, null);
    }

    @Test
    void logProfileEdit_persistsAuditRow() throws Exception {
        Map<String, Object> diff = Map.of(
                "displayName", Map.of("from", "Old", "to", "New"));

        auditLogService.logProfileEdit("u1", diff);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog row = captor.getValue();
        assertThat(row.getActorUid()).isEqualTo("u1");
        assertThat(row.getAction()).isEqualTo("PROFILE_EDIT");
        assertThat(row.getDetails()).containsKey("displayName");
        assertThat(row.getTimestamp()).isNotNull();
    }
}
