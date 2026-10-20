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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock
    com.albunyaan.tube.search.SearchIndex searchIndex;

    Cache<String, AtomicInteger> rateLimitCache;
    Cache<String, Object> workspaceCache;
    ContentReportService service;

    private static final List<ReportReason> REASONS = List.of(ReportReason.MUSIC);
    private static final String DEVICE = "device-abc";

    @BeforeEach
    void setUp() {
        rateLimitCache = Caffeine.newBuilder().build();
        workspaceCache = Caffeine.newBuilder().build();
        workspaceCache.put("exclusions", "stale");
        service = new ContentReportService(reportRepository, rateLimitCache, videoRepository, channelRepository, playlistRepository, publicContentCacheService, streamIndexService, searchIndex, workspaceCache);
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
            throws ExecutionException, InterruptedException, TimeoutException, java.io.IOException {
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
        verify(searchIndex, never()).sourcesListing(anyString());
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

    // --- inner-search R34: a report opened from search has no parent ----------------------------

    @Test
    void aParentlessReportOnAnInnerVideoExcludesItEverywhereItIsListed() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ilgiwqpcOC8");
        when(reportRepository.findById("report-inner")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.empty());
        when(searchIndex.listingsLoaded()).thenReturn(true);
        when(searchIndex.sourcesListing("ilgiwqpcOC8")).thenReturn(List.of(
                "channel:UCfassTU6mGWIj-yb26_ADjQ", "playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe"));
        Channel ch = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        Playlist pl = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(Optional.of(ch));
        when(playlistRepository.findByYoutubeId("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe")).thenReturn(Optional.of(pl));

        service.resolveReport("report-inner", ReportStatus.RESOLVED, "admin", null);

        verify(channelRepository).save(ch);
        verify(playlistRepository).save(pl);
        assertThat(ch.getExcludedItems().getVideos()).containsExactly("ilgiwqpcOC8");
        assertThat(pl.getExcludedVideoIds()).containsExactly("ilgiwqpcOC8");
        verify(publicContentCacheService).evictPublicContentCaches();
        verify(videoRepository, never()).save(any());
        assertThat(workspaceCache.getIfPresent("exclusions")).as("the admin Exclusions workspace re-reads").isNull();
    }

    @Test
    void aParentlessReportOnAVideoNoSourceListsOnlyLogs() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("lesson99999");
        when(reportRepository.findById("report-none")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId("lesson99999")).thenReturn(Optional.empty());
        when(searchIndex.listingsLoaded()).thenReturn(true);
        when(searchIndex.sourcesListing("lesson99999")).thenReturn(List.of());

        service.resolveReport("report-none", ReportStatus.RESOLVED, "admin", null);

        verify(channelRepository, never()).save(any());
        verify(playlistRepository, never()).save(any());
        verify(publicContentCacheService, never()).evictPublicContentCaches();
    }

    @Test
    void aReportWithAParentStillExcludesOnThatParentOnly() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ilgiwqpcOC8");
        report.setParentType(ReportTargetType.CHANNEL);
        report.setParentId("UCfassTU6mGWIj-yb26_ADjQ");
        Channel ch = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        when(reportRepository.findById("report-parent")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(Optional.of(ch));

        service.resolveReport("report-parent", ReportStatus.RESOLVED, "admin", null);

        verify(channelRepository).save(ch);
        verifyNoInteractions(searchIndex);
        assertThat(workspaceCache.getIfPresent("exclusions")).as("the admin Exclusions workspace re-reads").isNull();
    }

    // --- Fix round 1: a miss is never a clean "resolved" -----------------------------------------

    /** R34 needs the listings loaded: a cold resolve is refused (503, Retry-After) before the report is marked, so it stays PENDING. */
    @Test
    void aParentlessReportBeforeTheIndexIsBuiltIsRefusedAndStaysPending() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ilgiwqpcOC8");
        report.setStatus(ReportStatus.PENDING);
        when(reportRepository.findById("report-cold")).thenReturn(Optional.of(report));
        when(searchIndex.listingsLoaded()).thenReturn(false);
        when(videoRepository.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveReport("report-cold", ReportStatus.RESOLVED, "admin", null))
                .isInstanceOf(com.albunyaan.tube.search.SearchIndex.NotReadyException.class);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
        verify(reportRepository, never()).update(any());
        verify(searchIndex, never()).sourcesListing(anyString());
    }

    /** A parented report whose parent is gone falls back to R34; while the index is cold that miss lands in the note. */
    @Test
    void aParentGoneFallbackWhileTheIndexIsColdNotesTheMiss() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ilgiwqpcOC8");
        report.setParentType(ReportTargetType.CHANNEL);
        report.setParentId("UCgone");
        when(reportRepository.findById("report-gone")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(channelRepository.findByYoutubeId("UCgone")).thenReturn(Optional.empty());
        when(videoRepository.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.empty());
        when(searchIndex.listingsLoaded()).thenReturn(false);

        ContentReport out = service.resolveReport("report-gone", ReportStatus.RESOLVED, "admin", null);

        assertThat(out.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(out.getResolutionNote()).contains("search index");
        verify(searchIndex, never()).sourcesListing(anyString());
    }

    @Test
    void aSourceThatFailsToSaveIsNamedInTheResolutionNote() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ilgiwqpcOC8");
        when(reportRepository.findById("report-partial")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.empty());
        when(searchIndex.listingsLoaded()).thenReturn(true);
        when(searchIndex.sourcesListing("ilgiwqpcOC8")).thenReturn(List.of(
                "channel:UCfassTU6mGWIj-yb26_ADjQ", "playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe"));
        Channel ch = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        Playlist pl = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(Optional.of(ch));
        when(playlistRepository.findByYoutubeId("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe")).thenReturn(Optional.of(pl));
        when(playlistRepository.save(pl)).thenThrow(new TimeoutException("write timed out"));

        ContentReport out = service.resolveReport("report-partial", ReportStatus.RESOLVED, "admin", "music video");

        verify(channelRepository).save(ch);
        assertThat(out.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(out.getResolutionNote()).startsWith("music video")
                .contains("playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe").doesNotContain("channel:UCfassTU6mGWIj-yb26_ADjQ");
        verify(reportRepository, times(2)).update(report);
        verify(publicContentCacheService).evictPublicContentCaches();
    }

    @Test
    void reResolvingAnAlreadyExcludedVideoWritesNothingTwice() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ilgiwqpcOC8");
        when(reportRepository.findById("report-again")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.empty());
        when(searchIndex.listingsLoaded()).thenReturn(true);
        when(searchIndex.sourcesListing("ilgiwqpcOC8")).thenReturn(List.of(
                "channel:UCfassTU6mGWIj-yb26_ADjQ", "playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe"));
        Channel ch = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        ch.getExcludedItems().getVideos().add("ilgiwqpcOC8");
        Playlist pl = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        pl.getExcludedVideoIds().add("ilgiwqpcOC8");
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(Optional.of(ch));
        when(playlistRepository.findByYoutubeId("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe")).thenReturn(Optional.of(pl));

        ContentReport out = service.resolveReport("report-again", ReportStatus.RESOLVED, "admin", null);

        verify(channelRepository, never()).save(any());
        verify(playlistRepository, never()).save(any());
        assertThat(ch.getExcludedItems().getVideos()).containsExactly("ilgiwqpcOC8");
        assertThat(pl.getExcludedVideoIds()).containsExactly("ilgiwqpcOC8");
        verify(reportRepository, times(1)).update(any());
        assertThat(out.getResolutionNote()).isNull();
    }
}
