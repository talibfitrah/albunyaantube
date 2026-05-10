package com.albunyaan.tube.service;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.ContentReport;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ReportReason;
import com.albunyaan.tube.model.ReportStatus;
import com.albunyaan.tube.model.ReportTargetType;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.ContentReportRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.StreamIndexService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
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
    @Mock
    VideoRepository videoRepository;
    @Mock
    ChannelRepository channelRepository;
    @Mock
    PlaylistRepository playlistRepository;
    @Mock
    PublicContentCacheService publicContentCacheService;
    @Mock
    StreamIndexService streamIndexService;

    Cache<String, AtomicInteger> rateLimitCache;
    ContentReportService service;

    private static final List<ReportReason> REASONS = List.of(ReportReason.MUSIC);
    private static final String DEVICE = "device-abc";

    @BeforeEach
    void setUp() {
        rateLimitCache = Caffeine.newBuilder().build();
        service = new ContentReportService(reportRepository, rateLimitCache, videoRepository, channelRepository, playlistRepository, publicContentCacheService, streamIndexService);
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

    // --- archiveReportedContent stream-index cleanup ---

    @Test
    void archiveReportedContent_video_callsMarkStreamArchived()
            throws ExecutionException, InterruptedException, TimeoutException {
        Video v = new Video();
        v.setYoutubeId("ytv-1");

        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ytv-1");
        // no parent context → falls through to archiveReportedContent
        when(reportRepository.findById("report-v1")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId("ytv-1")).thenReturn(Optional.of(v));
        when(videoRepository.save(any())).thenReturn(v);

        service.resolveReport("report-v1", ReportStatus.RESOLVED, "admin", null);

        verify(streamIndexService).markStreamArchived("ytv-1");
    }

    @Test
    void archiveReportedContent_channel_callsRemoveSource()
            throws ExecutionException, InterruptedException, TimeoutException {
        Channel ch = new Channel();
        ch.setYoutubeId("UCabc");

        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.CHANNEL);
        report.setTargetId("UCabc");
        when(reportRepository.findById("report-ch1")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(channelRepository.findByYoutubeId("UCabc")).thenReturn(Optional.of(ch));
        when(channelRepository.save(any())).thenReturn(ch);

        service.resolveReport("report-ch1", ReportStatus.RESOLVED, "admin", null);

        verify(streamIndexService).removeSource("CHANNEL", "UCabc");
    }

    @Test
    void archiveReportedContent_playlist_callsRemoveSource()
            throws ExecutionException, InterruptedException, TimeoutException {
        Playlist pl = new Playlist();
        pl.setYoutubeId("PLxyz");

        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.PLAYLIST);
        report.setTargetId("PLxyz");
        when(reportRepository.findById("report-pl1")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(playlistRepository.findByYoutubeId("PLxyz")).thenReturn(Optional.of(pl));
        when(playlistRepository.save(any())).thenReturn(pl);

        service.resolveReport("report-pl1", ReportStatus.RESOLVED, "admin", null);

        verify(streamIndexService).removeSource("PLAYLIST", "PLxyz");
    }
}
