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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
    final com.albunyaan.tube.repository.LiveChannelStateRepository liveStates =
            org.mockito.Mockito.mock(com.albunyaan.tube.repository.LiveChannelStateRepository.class);
    final LiveChannelService live = new LiveChannelService(org.mockito.Mockito.mock(LiveTabReader.class), liveStates,
            java.time.Clock.systemUTC());
    ContentReportService service;

    private static final List<ReportReason> REASONS = List.of(ReportReason.MUSIC);
    private static final String DEVICE = "device-abc";

    @BeforeEach
    void setUp() {
        rateLimitCache = Caffeine.newBuilder().build();
        workspaceCache = Caffeine.newBuilder().build();
        workspaceCache.put("exclusions", "stale");
        live.loadStates(List.of());
        service = new ContentReportService(reportRepository, rateLimitCache, videoRepository, channelRepository, playlistRepository, publicContentCacheService, streamIndexService, searchIndex, workspaceCache, live);
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

        verify(channelRepository).update(eq(ch), any());
        verify(playlistRepository).update(eq(pl), any());
        assertThat(ch.getExcludedItems().getVideos()).containsExactly("ilgiwqpcOC8");
        assertThat(pl.getExcludedVideoIds()).containsExactly("ilgiwqpcOC8");
        verify(publicContentCacheService, org.mockito.Mockito.atLeastOnce()).evictPublicContentCaches();
        assertThat(workspaceCache.getIfPresent("exclusions")).as("the admin Exclusions workspace re-reads").isNull();
    }

    /** The one Video document a parentless resolve may write: the id's take-down record, never in the catalog. */
    private Video takenDown(String youtubeId) throws Exception {
        var saved = org.mockito.ArgumentCaptor.forClass(Video.class);
        verify(videoRepository).save(saved.capture()); // once: its sources are a field update (B14), never a second save
        Video v = saved.getValue();
        assertThat(v.getYoutubeId()).isEqualTo(youtubeId);
        assertThat(v.getStatus()).as("never APPROVED or PENDING: not served, not in the approvals queue").isEqualTo("REJECTED");
        assertThat(v.getValidationStatus()).as("listed under Archived content, where the admin can delete it")
                .isEqualTo(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
        assertThat(v.getSource()).as("marks it a take-down record, which Restore deletes").isEqualTo("REPORT");
        return v;
    }

    /** Stage 9 Cubic r3 P3: a failure after the record is saved must not read "Not taken down". */
    @Test
    void aFailureAfterTheTakeDownRecordIsSavedSaysItWasTakenDown() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ilgiwqpcOC8");
        when(reportRepository.findById("report-io")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.empty());
        when(searchIndex.listingsLoaded()).thenReturn(true);
        when(searchIndex.sourcesListing("ilgiwqpcOC8")).thenThrow(new java.io.IOException("disk"));

        ContentReport out = service.resolveReport("report-io", ReportStatus.RESOLVED, "admin", null);

        verify(videoRepository).save(any());
        assertThat(out.getResolutionNote()).doesNotContain("Not taken down").contains("Taken down").contains("by hand");
    }

    /** Stage 9 B11 P3: a take-down record is written only for a video id; any other reported string gets a note. */
    @Test
    void aReportOnAStringThatIsNoVideoIdWritesNoTakeDownRecord() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("../../x");
        when(reportRepository.findById("report-junk")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId("../../x")).thenReturn(Optional.empty());
        when(searchIndex.listingsLoaded()).thenReturn(true);

        ContentReport out = service.resolveReport("report-junk", ReportStatus.RESOLVED, "admin", null);

        verify(videoRepository, never()).save(any());
        verify(streamIndexService, never()).markStreamArchived(anyString());
        assertThat(out.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(out.getResolutionNote()).contains("not a video id");
    }

    /**
     * Stage 9 Cubic r2 P2: the exclusion holds on every source, including one crawled after the resolve. Any Video
     * document hides its id from search on every source (SearchIndex.videoIds), so a video with none gets one.
     */
    @Test
    void aParentlessReportRecordsATakeDownThatHoldsOnSourcesCrawledLater() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ilgiwqpcOC8");
        when(reportRepository.findById("report-later")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.empty());
        when(searchIndex.listingsLoaded()).thenReturn(true);
        when(searchIndex.sourcesListing("ilgiwqpcOC8")).thenReturn(List.of("channel:UCfassTU6mGWIj-yb26_ADjQ"));
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ"))
                .thenReturn(Optional.of(new Channel("UCfassTU6mGWIj-yb26_ADjQ")));

        ContentReport out = service.resolveReport("report-later", ReportStatus.RESOLVED, "admin", null);

        takenDown("ilgiwqpcOC8");
        verify(streamIndexService).markStreamArchived("ilgiwqpcOC8");
        assertThat(out.getResolutionNote()).isNull();
    }

    /**
     * Stage 9 B16 (Cubic r6 P2): the sources listing the video are read off its stream's index doc, which the take-down's
     * markStreamArchived deletes. Read after it, they come back empty: no exclusion and nothing recorded for Restore.
     */
    @Test
    void aTakeDownReadsTheSourcesListingTheVideoBeforeItRemovesItsStream() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ilgiwqpcOC8");
        when(reportRepository.findById("report-order")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.empty());
        when(searchIndex.listingsLoaded()).thenReturn(true);
        java.util.concurrent.atomic.AtomicBoolean streamRemoved = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv -> { streamRemoved.set(true); return null; })
                .when(streamIndexService).markStreamArchived("ilgiwqpcOC8");
        when(searchIndex.sourcesListing("ilgiwqpcOC8")).thenAnswer(inv ->
                streamRemoved.get() ? List.of() : List.of("channel:UCfassTU6mGWIj-yb26_ADjQ"));
        Channel ch = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        org.mockito.Mockito.lenient().when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(Optional.of(ch));

        service.resolveReport("report-order", ReportStatus.RESOLVED, "admin", null);

        verify(videoRepository).updateIfStatusAndSource(any(), org.mockito.ArgumentMatchers.eq("REJECTED"),
                org.mockito.ArgumentMatchers.eq("REPORT"), org.mockito.ArgumentMatchers.eq("takeDownExclusions"),
                org.mockito.ArgumentMatchers.eq(List.of("channel:UCfassTU6mGWIj-yb26_ADjQ")));   // recorded for Restore
        assertThat(ch.getExcludedItems().getVideos()).containsExactly("ilgiwqpcOC8");
        verify(streamIndexService).markStreamArchived("ilgiwqpcOC8");
    }

    /**
     * Stage 9 B16 (Cubic r6 P2, R12): a take-down of a link the live poller ended writes the same ARCHIVED the poller
     * wrote. It is the admin's now: the poller's restore record of it goes, so the link live again is not restored.
     */
    @Test
    void aTakeDownOfALinkThePollerEndedDropsItsRestoreRecord() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("LiveLink001");
        when(reportRepository.findById("report-live")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        Video ended = new Video("LiveLink001");
        ended.setId("doc-live");
        ended.setStatus("APPROVED");
        ended.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);   // the poller's archive
        when(videoRepository.findByYoutubeId("LiveLink001")).thenReturn(Optional.of(ended));
        com.albunyaan.tube.model.LiveChannelState state = com.albunyaan.tube.model.LiveChannelState.unknown("UCliveLink0000000000000a");
        state.getArchived().put("LiveLink001", "VALID");
        state.getArchivedFrom().put("LiveLink001", List.of("cat-live"));
        live.loadStates(List.of(state));

        ContentReport out = service.resolveReport("report-live", ReportStatus.RESOLVED, "admin", null);

        assertThat(live.state("UCliveLink0000000000000a").getArchived()).doesNotContainKey("LiveLink001");
        assertThat(live.state("UCliveLink0000000000000a").getArchivedFrom()).doesNotContainKey("LiveLink001");
        verify(liveStates).save(any());
        assertThat(out.getResolutionNote()).isNull();
    }

    /** A parentless VIDEO report on {@code id}, whose doc is gone, and a live channel state still holding its restore record. */
    private String staleRestoreRecord(String id, String channel) throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId(id);
        when(reportRepository.findById("report-" + id)).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId(id)).thenReturn(Optional.empty());   // an admin deleted the poller's doc
        when(searchIndex.listingsLoaded()).thenReturn(true);
        when(searchIndex.sourcesListing(id)).thenReturn(List.of());
        com.albunyaan.tube.model.LiveChannelState state = com.albunyaan.tube.model.LiveChannelState.unknown(channel);
        state.getArchived().put(id, "VALID");
        state.getArchivedFrom().put(id, List.of("cat-live"));
        live.loadStates(List.of(state));
        return "report-" + id;
    }

    /**
     * Stage 9 B19 (r6 P3-3): the poller's restore record outlives a doc an admin deleted. A take-down then writes a new
     * record (REJECTED, ARCHIVED) for the id; a record left in place "restores" it out of Archived content, and back
     * into its old LIVE category, when the id goes live again.
     */
    @Test
    void aTakeDownOfAnIdWithNoDocDropsAStaleRestoreRecord() throws Exception {
        String reportId = staleRestoreRecord("LiveLink002", "UCliveLink0000000000000b");

        ContentReport out = service.resolveReport(reportId, ReportStatus.RESOLVED, "admin", null);

        takenDown("LiveLink002");
        assertThat(live.state("UCliveLink0000000000000b").getArchived()).doesNotContainKey("LiveLink002");
        assertThat(live.state("UCliveLink0000000000000b").getArchivedFrom()).doesNotContainKey("LiveLink002");
        assertThat(out.getResolutionNote()).isNull();
    }

    /** B19: with no doc too, a failed drop is named in the note, also when the exclusions after it succeed. */
    @Test
    void aTakeDownOfAnIdWithNoDocWhoseRestoreDropFailsSaysSo() throws Exception {
        String reportId = staleRestoreRecord("LiveLink003", "UCliveLink0000000000000c");
        doThrow(new ExecutionException(new RuntimeException("firestore down"))).when(liveStates).save(any());

        ContentReport out = service.resolveReport(reportId, ReportStatus.RESOLVED, "admin", null);

        takenDown("LiveLink003");
        assertThat(out.getResolutionNote()).contains("the live poller may put it back");
    }

    /** Stage 9 Cubic r2 P2: no source lists it yet, so there is nothing to exclude on; the take-down is still recorded. */
    @Test
    void aParentlessReportOnAVideoNoSourceListsYetStillRecordsTheTakeDown() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("lesson99999");
        when(reportRepository.findById("report-none")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId("lesson99999")).thenReturn(Optional.empty());
        when(searchIndex.listingsLoaded()).thenReturn(true);
        when(searchIndex.sourcesListing("lesson99999")).thenReturn(List.of());

        service.resolveReport("report-none", ReportStatus.RESOLVED, "admin", null);

        takenDown("lesson99999");
        verify(channelRepository, never()).update(any(), any());
        verify(playlistRepository, never()).update(any(), any());
        verify(publicContentCacheService).evictPublicContentCaches();
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

        verify(channelRepository).update(eq(ch), any());
        verifyNoInteractions(searchIndex);
        assertThat(workspaceCache.getIfPresent("exclusions")).as("the admin Exclusions workspace re-reads").isNull();
    }

    /**
     * Stage 9 Cubic r8 #9: a failed exclusion (one Firestore timeout) is not "no parent". It is a miss the admin sees,
     * never a fall-through to the site-wide take-down (410, gone from search) under a clean "resolved".
     */
    @Test
    void aParentExclusionThatFailsIsAMissNeverATakeDown() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ilgiwqpcOC8");
        report.setParentType(ReportTargetType.CHANNEL);
        report.setParentId("UCfassTU6mGWIj-yb26_ADjQ");
        Channel ch = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        when(reportRepository.findById("report-timeout")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(Optional.of(ch));
        when(channelRepository.update(eq(ch), any())).thenThrow(new TimeoutException("write timed out"));

        ContentReport out = service.resolveReport("report-timeout", ReportStatus.RESOLVED, "admin", "music video");

        verify(videoRepository, never()).findByYoutubeId(anyString());
        verify(videoRepository, never()).save(any());
        verify(streamIndexService, never()).markStreamArchived(anyString());
        verifyNoInteractions(searchIndex);
        assertThat(out.getResolutionNote()).startsWith("music video\n")
                .contains("Not excluded on channel:UCfassTU6mGWIj-yb26_ADjQ", "TimeoutException", "not taken down", "by hand");
        verify(reportRepository, times(2)).update(report);
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
        when(playlistRepository.update(eq(pl), any())).thenThrow(new TimeoutException("write timed out"));

        ContentReport out = service.resolveReport("report-partial", ReportStatus.RESOLVED, "admin", "music video");

        verify(channelRepository).update(eq(ch), any());
        assertThat(out.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(out.getResolutionNote()).startsWith("music video")
                .contains("playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe").doesNotContain("channel:UCfassTU6mGWIj-yb26_ADjQ");
        verify(reportRepository, times(2)).update(report);
        verify(publicContentCacheService, org.mockito.Mockito.atLeastOnce()).evictPublicContentCaches();
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

        verify(channelRepository, never()).update(any(), any());
        verify(playlistRepository, never()).update(any(), any());
        assertThat(ch.getExcludedItems().getVideos()).containsExactly("ilgiwqpcOC8");
        assertThat(pl.getExcludedVideoIds()).containsExactly("ilgiwqpcOC8");
        verify(reportRepository, times(1)).update(any());
        assertThat(out.getResolutionNote()).isNull();
    }

    // --- Stage 9 B13: Restore undoes a take-down whole --------------------------------------------

    /**
     * The record names the sources the take-down excluded the id on, and only those: an admin's earlier exclusion stays.
     * Stage 9 B14 (re-review P3-1, Cubic r5 #2): written as that one field, only while the doc is still the take-down
     * record. A second full save turned a re-add that landed meanwhile back into a take-down.
     */
    @Test
    void aTakeDownRecordNamesOnlyTheSourcesItExcludedTheVideoOnAndNeverRewritesTheDoc() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ilgiwqpcOC8");
        when(reportRepository.findById("report-names")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.empty());
        when(searchIndex.listingsLoaded()).thenReturn(true);
        when(searchIndex.sourcesListing("ilgiwqpcOC8")).thenReturn(List.of("channel:UCfassTU6mGWIj-yb26_ADjQ",
                "channel:UCalready0000000000000aa", "playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe"));
        Channel already = new Channel("UCalready0000000000000aa");
        already.getExcludedItems().getVideos().add("ilgiwqpcOC8");   // an admin excluded it there before the report
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ"))
                .thenReturn(Optional.of(new Channel("UCfassTU6mGWIj-yb26_ADjQ")));
        when(channelRepository.findByYoutubeId("UCalready0000000000000aa")).thenReturn(Optional.of(already));
        when(playlistRepository.findByYoutubeId("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe"))
                .thenReturn(Optional.of(new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe")));
        when(videoRepository.save(any())).thenAnswer(inv -> {             // the real save assigns the new doc's id
            Video v = inv.getArgument(0);
            v.setId("doc-record");
            return v;
        });

        service.resolveReport("report-names", ReportStatus.RESOLVED, "admin", null);

        takenDown("ilgiwqpcOC8");
        verify(videoRepository).updateIfStatusAndSource("doc-record", "REJECTED", "REPORT", "takeDownExclusions",
                List.of("channel:UCfassTU6mGWIj-yb26_ADjQ", "playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe"));
    }

    /** Restore lifts what the take-down did: the exclusions it added and the stream it hid, then deletes its record. */
    @Test
    void liftingATakeDownUndoesItsExclusionsAndItsStreamThenDeletesTheRecord() throws Exception {
        Video record = new Video("ilgiwqpcOC8");
        record.setId("doc-takedown");
        record.setStatus("REJECTED");
        record.setSource("REPORT");
        record.setTakeDownExclusions(List.of("channel:UCfassTU6mGWIj-yb26_ADjQ", "playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe"));
        Channel ch = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        ch.getExcludedItems().getVideos().addAll(List.of("ilgiwqpcOC8", "keptExcl001"));
        Playlist pl = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        pl.getExcludedVideoIds().add("ilgiwqpcOC8");
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(Optional.of(ch));
        when(playlistRepository.findByYoutubeId("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe")).thenReturn(Optional.of(pl));

        service.liftTakeDown(record);

        assertThat(ch.getExcludedItems().getVideos()).containsExactly("keptExcl001");
        assertThat(pl.getExcludedVideoIds()).isEmpty();
        verify(channelRepository).update(eq(ch), any());
        verify(playlistRepository).update(eq(pl), any());
        verify(streamIndexService).markStreamRestored("ilgiwqpcOC8");
        var order = inOrder(videoRepository, streamIndexService);
        order.verify(videoRepository).deleteByIdIfStatusIn("doc-takedown", java.util.Set.of("REJECTED"));
        order.verify(streamIndexService).releaseVideoId("ilgiwqpcOC8");   // B25 (r13 #5): after the delete, search shows it at once
        verify(publicContentCacheService).evictPublicContentCaches();
        assertThat(workspaceCache.getIfPresent("exclusions")).as("the admin Exclusions workspace re-reads").isNull();
    }

    // --- Stage 9 B14 ------------------------------------------------------------------------------

    private static Video recordExcludedOn(String... sourceKeys) {
        Video record = new Video("ilgiwqpcOC8");
        record.setId("doc-takedown");
        record.setStatus("REJECTED");
        record.setSource("REPORT");
        record.setTakeDownExclusions(List.of(sourceKeys));
        return record;
    }

    /** Re-review P3-2: a stream restore that fails stops Restore before the delete, so the record stays for a retry. */
    @Test
    void aFailedStreamRestoreKeepsTheRecordSoRestoreCanRunAgain() throws Exception {
        var streams = org.mockito.Mockito.mock(com.albunyaan.tube.repository.SearchableStreamRepository.class);
        when(streams.markVisible("ilgiwqpcOC8")).thenThrow(new TimeoutException("write timed out"));
        var restoring = new ContentReportService(reportRepository, rateLimitCache, videoRepository, channelRepository,
                playlistRepository, publicContentCacheService, new StreamIndexService(streams, searchIndex), searchIndex,
                workspaceCache, live);

        assertThatThrownBy(() -> restoring.liftTakeDown(recordExcludedOn())).isInstanceOf(TimeoutException.class);

        verify(videoRepository, never()).deleteByIdIfStatusIn(anyString(), any());
    }

    /** Re-review P3-5: a Restore whose delete loses its race still shows the exclusions it already lifted, at once. */
    @Test
    void aRestoreThatLosesItsDeleteStillShowsTheExclusionsItLifted() throws Exception {
        Channel ch = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        ch.getExcludedItems().getVideos().add("ilgiwqpcOC8");
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(Optional.of(ch));
        doThrow(new IllegalStateException("Cannot delete video doc-takedown: status APPROVED not in [REJECTED]"))
                .when(videoRepository).deleteByIdIfStatusIn("doc-takedown", java.util.Set.of("REJECTED"));

        assertThatThrownBy(() -> service.liftTakeDown(recordExcludedOn("channel:UCfassTU6mGWIj-yb26_ADjQ")))
                .isInstanceOf(IllegalStateException.class);

        verify(channelRepository).update(eq(ch), any());
        verify(publicContentCacheService).evictPublicContentCaches();
        assertThat(workspaceCache.getIfPresent("exclusions")).as("the admin Exclusions workspace re-reads").isNull();
    }

    /**
     * Cubic r5 #3: a re-add writes into the take-down record (B11) and lifts the exclusions its report added, as Restore
     * does; else the re-added video stays excluded on those sources with no record why. A PENDING re-add too: the doc
     * itself gates it until approval (410 at the public gate, its stream blocked in search).
     * Stage 9 B23 (Cubic r11 #13): and its stream's archive, as Restore does: the index loads only visible streams, so a
     * stream left invisible would stay out of search for good once the re-added doc goes.
     */
    @Test
    void aReAddOverATakeDownRecordLiftsItAsRestoreDoes() throws Exception {
        Channel ch = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        ch.getExcludedItems().getVideos().addAll(List.of("ilgiwqpcOC8", "keptExcl001"));
        Playlist pl = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        pl.getExcludedVideoIds().add("ilgiwqpcOC8");
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(Optional.of(ch));
        when(playlistRepository.findByYoutubeId("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe")).thenReturn(Optional.of(pl));
        when(videoRepository.findById("doc-takedown")).thenReturn(Optional.of(
                recordExcludedOn("channel:UCfassTU6mGWIj-yb26_ADjQ", "playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe")));
        Video reAdded = new Video("ilgiwqpcOC8");
        reAdded.setId("doc-takedown");
        reAdded.setStatus("PENDING");
        when(videoRepository.saveIfStatus(reAdded, "REJECTED")).thenReturn(reAdded);

        assertThat(service.reAdd(reAdded)).isSameAs(reAdded);

        verify(streamIndexService).markStreamRestored("ilgiwqpcOC8");
        assertThat(ch.getExcludedItems().getVideos()).containsExactly("keptExcl001");
        assertThat(pl.getExcludedVideoIds()).isEmpty();
        verify(channelRepository).update(eq(ch), any());
        verify(playlistRepository).update(eq(pl), any());
        assertThat(workspaceCache.getIfPresent("exclusions")).as("the admin Exclusions workspace re-reads").isNull();
    }

    /** Cubic r5 #3: the lift follows the write: a re-add that loses its race (409) lifts nothing. */
    @Test
    void aReAddThatLosesItsRaceLiftsNothing() throws Exception {
        when(videoRepository.findById("doc-takedown")).thenReturn(Optional.of(recordExcludedOn("channel:UCfassTU6mGWIj-yb26_ADjQ")));
        Video reAdded = new Video("ilgiwqpcOC8");
        reAdded.setId("doc-takedown");
        when(videoRepository.saveIfStatus(reAdded, "REJECTED")).thenThrow(new IllegalStateException("found APPROVED"));

        assertThatThrownBy(() -> service.reAdd(reAdded)).isInstanceOf(IllegalStateException.class);

        verify(channelRepository, never()).findByYoutubeId(anyString());
        verify(channelRepository, never()).update(any(), any());
    }

    /**
     * Stage 9 B23 (Cubic r11 #1; r10 re-review #2, #3), replacing B21's REJECTED: a take-down only archives a doc, so it
     * keeps its review state. Restore (VALID) then gives the item back to the review it was in, and the importers' rows
     * follow that review (no reject fan-out: it would delete their rows, which no Restore brings back). The archive is
     * the hold: its submitter cannot withdraw an ARCHIVED doc, whatever status it is moved to (the repositories' CAS).
     */
    @Test
    void aTakeDownKeepsTheReviewStateOfASubmissionSoRestoreGivesItBack() throws Exception {
        Video pending = new Video("ilgiwqpcOC8");
        pending.setSource("MODERATOR");
        pending.setSubmittedBy("mod-uid");
        Video approved = new Video("xc7keR2piUM");
        approved.setStatus("APPROVED");
        Channel bounced = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        bounced.setStatus("REQUEST_CHANGES");
        Playlist pendingList = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        when(videoRepository.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.of(pending));
        when(videoRepository.findByYoutubeId("xc7keR2piUM")).thenReturn(Optional.of(approved));
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(Optional.of(bounced));
        when(playlistRepository.findByYoutubeId("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe")).thenReturn(Optional.of(pendingList));
        when(reportRepository.update(any())).thenAnswer(i -> i.getArgument(0));
        for (var target : List.of(java.util.Map.entry(ReportTargetType.VIDEO, "ilgiwqpcOC8"),
                java.util.Map.entry(ReportTargetType.VIDEO, "xc7keR2piUM"),
                java.util.Map.entry(ReportTargetType.CHANNEL, "UCfassTU6mGWIj-yb26_ADjQ"),
                java.util.Map.entry(ReportTargetType.PLAYLIST, "PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe"))) {
            ContentReport report = new ContentReport();
            report.setTargetType(target.getKey());
            report.setTargetId(target.getValue());
            when(reportRepository.findById("report-" + target.getValue())).thenReturn(Optional.of(report));

            service.resolveReport("report-" + target.getValue(), ReportStatus.RESOLVED, "admin", null);
        }

        verify(videoRepository).save(pending);
        verify(channelRepository).save(bounced);
        verify(playlistRepository).save(pendingList);
        assertThat(List.of(pending.getStatus(), approved.getStatus(), bounced.getStatus(), pendingList.getStatus()))
                .as("the review state is kept").containsExactly("PENDING", "APPROVED", "REQUEST_CHANGES", "PENDING");
        assertThat(List.of(pending.getValidationStatus(), approved.getValidationStatus(), bounced.getValidationStatus(),
                pendingList.getValidationStatus())).containsOnly(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
        assertThat(pending.getSource()).as("no take-down record: Restore keeps the doc").isEqualTo("MODERATOR");
    }

    // ---- Cubic users3: a report's exclusion add and Restore's remove change the doc as stored now ----

    /** update() as the repository runs it: `change` on the doc as stored now, not on the copy the caller read. */
    private void storedNow(Channel channel, Playlist playlist) throws Exception {
        when(channelRepository.update(any(), any())).thenAnswer(inv -> {
            inv.<java.util.function.Consumer<Channel>>getArgument(1).accept(channel);
            return channel;
        });
        when(playlistRepository.update(any(), any())).thenAnswer(inv -> {
            inv.<java.util.function.Consumer<Playlist>>getArgument(1).accept(playlist);
            return playlist;
        });
    }

    /** Another report excluded a video on both sources after this one read them: both exclusions are stored. */
    @Test
    void aReportsExclusionKeepsOneWrittenSinceItsRead() throws Exception {
        ContentReport report = new ContentReport();
        report.setTargetType(ReportTargetType.VIDEO);
        report.setTargetId("ilgiwqpcOC8");
        when(reportRepository.findById("report-race")).thenReturn(Optional.of(report));
        when(reportRepository.update(any())).thenReturn(report);
        when(videoRepository.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.empty());
        when(searchIndex.listingsLoaded()).thenReturn(true);
        when(searchIndex.sourcesListing("ilgiwqpcOC8")).thenReturn(List.of(
                "channel:UCfassTU6mGWIj-yb26_ADjQ", "playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe"));
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(Optional.of(new Channel("UCfassTU6mGWIj-yb26_ADjQ")));
        when(playlistRepository.findByYoutubeId("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe"))
                .thenReturn(Optional.of(new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe")));
        Channel storedCh = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        storedCh.getExcludedItems().getVideos().add("xc7keR2piUM");
        Playlist storedPl = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        storedPl.getExcludedVideoIds().add("xc7keR2piUM");
        storedNow(storedCh, storedPl);

        service.resolveReport("report-race", ReportStatus.RESOLVED, "admin", null);

        verify(channelRepository, never()).save(any());
        verify(playlistRepository, never()).save(any());
        assertThat(storedCh.getExcludedItems().getVideos()).containsExactly("xc7keR2piUM", "ilgiwqpcOC8");
        assertThat(storedCh.getExcludedItems().getTotalExcludedCount()).isEqualTo(2);
        assertThat(storedPl.getExcludedVideoIds()).containsExactly("xc7keR2piUM", "ilgiwqpcOC8");
        assertThat(storedPl.getExcludedVideoCount()).isEqualTo(2);
    }

    /** Restore lifts its own exclusion from the doc as stored now: one added since its read stays. */
    @Test
    void aRestoreLiftKeepsAnExclusionWrittenSinceItsRead() throws Exception {
        Video record = recordExcludedOn("channel:UCfassTU6mGWIj-yb26_ADjQ");
        record.setTakeDownExclusions(List.of("channel:UCfassTU6mGWIj-yb26_ADjQ", "playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe"));
        Channel readCh = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        readCh.getExcludedItems().getVideos().add("ilgiwqpcOC8");
        Playlist readPl = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        readPl.getExcludedVideoIds().add("ilgiwqpcOC8");
        when(channelRepository.findByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(Optional.of(readCh));
        when(playlistRepository.findByYoutubeId("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe")).thenReturn(Optional.of(readPl));
        Channel storedCh = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        storedCh.getExcludedItems().getVideos().addAll(List.of("ilgiwqpcOC8", "xc7keR2piUM"));
        Playlist storedPl = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        storedPl.getExcludedVideoIds().addAll(List.of("ilgiwqpcOC8", "xc7keR2piUM"));
        storedNow(storedCh, storedPl);

        service.liftTakeDown(record);

        verify(channelRepository, never()).save(any());
        verify(playlistRepository, never()).save(any());
        assertThat(storedCh.getExcludedItems().getVideos()).containsExactly("xc7keR2piUM");
        assertThat(storedPl.getExcludedVideoIds()).containsExactly("xc7keR2piUM");
    }
}
