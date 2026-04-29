package com.albunyaan.tube.service;

import com.albunyaan.tube.model.ContentReport;
import com.albunyaan.tube.model.ReportReason;
import com.albunyaan.tube.model.ReportTargetType;
import com.albunyaan.tube.repository.ContentReportRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentReportServiceTest {

    @Mock
    ContentReportRepository reportRepository;

    Cache<String, AtomicInteger> rateLimitCache;
    ContentReportService service;

    private static final List<ReportReason> REASONS = List.of(ReportReason.MUSIC);
    private static final String DEVICE = "device-abc";

    @BeforeEach
    void setUp() {
        rateLimitCache = Caffeine.newBuilder().build();
        service = new ContentReportService(reportRepository, rateLimitCache);
    }

    @Test
    void submitReport_underLimit_savesAndReturns()
            throws ExecutionException, InterruptedException, TimeoutException {
        ContentReport saved = new ContentReport();
        saved.setId("report-1");
        when(reportRepository.save(any())).thenReturn(saved);

        ContentReport result = service.submitReport(
                ReportTargetType.VIDEO, "vid-1", REASONS, null, DEVICE);

        assertThat(result.getId()).isEqualTo("report-1");
        verify(reportRepository, times(1)).save(any());
    }

    @Test
    void submitReport_exceedsRateLimit_throwsException()
            throws ExecutionException, InterruptedException, TimeoutException {
        ContentReport saved = new ContentReport();
        when(reportRepository.save(any())).thenReturn(saved);

        // Submit up to the limit (5)
        for (int i = 0; i < 5; i++) {
            service.submitReport(ReportTargetType.VIDEO, "vid-1", REASONS, null, DEVICE);
        }

        // 6th attempt must throw
        assertThatThrownBy(() ->
                service.submitReport(ReportTargetType.VIDEO, "vid-1", REASONS, null, DEVICE))
                .isInstanceOf(ContentReportService.RateLimitExceededException.class);
    }

    @Test
    void submitReport_nullDeviceKey_usesAnonymousBucket()
            throws ExecutionException, InterruptedException, TimeoutException {
        ContentReport saved = new ContentReport();
        when(reportRepository.save(any())).thenReturn(saved);

        service.submitReport(ReportTargetType.VIDEO, "vid-1", REASONS, null, null);

        // ANONYMOUS_DEVICE bucket should have a count of 1
        AtomicInteger count = rateLimitCache.getIfPresent("ANONYMOUS_DEVICE");
        assertThat(count).isNotNull();
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void submitReport_blankDeviceKey_usesAnonymousBucket()
            throws ExecutionException, InterruptedException, TimeoutException {
        ContentReport saved = new ContentReport();
        when(reportRepository.save(any())).thenReturn(saved);

        service.submitReport(ReportTargetType.VIDEO, "vid-1", REASONS, null, "   ");

        AtomicInteger count = rateLimitCache.getIfPresent("ANONYMOUS_DEVICE");
        assertThat(count).isNotNull();
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void submitReport_adminNotificationFails_doesNotFailSave()
            throws ExecutionException, InterruptedException, TimeoutException {
        ContentReport saved = new ContentReport();
        saved.setId("report-2");
        when(reportRepository.save(any())).thenReturn(saved);
        doThrow(new RuntimeException("Firestore unavailable"))
                .when(reportRepository).writeAdminNotification(anyString(), any(), anyString());

        // Must not throw even when notification fails
        ContentReport result = service.submitReport(
                ReportTargetType.VIDEO, "vid-1", REASONS, null, DEVICE);

        assertThat(result.getId()).isEqualTo("report-2");
    }
}
