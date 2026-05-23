package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.*;
import com.albunyaan.tube.model.VideoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BulkSubmissionServiceSubmitTest {

    // Real UC-shaped channel id (22 chars after UC) so the parser accepts /channel/UC...
    private static final String CHANNEL_ID = "UCuAXFkgsw1L7xaCfnd5JJOw";
    private static final String CHANNEL_URL = "https://www.youtube.com/channel/" + CHANNEL_ID;
    private static final String VIDEO_ID = "dQw4w9WgXcQ";
    private static final String VIDEO_URL = "https://www.youtube.com/live/" + VIDEO_ID;
    private static final String PLAYLIST_ID = "PLxxxxxxxxxxxxxxxxxxxxxx";
    private static final String PLAYLIST_URL = "https://www.youtube.com/playlist?list=" + PLAYLIST_ID;

    private RegistrySubmissionWriter writer;
    private RegistryDuplicateChecker dedupe;
    private RegistryDuplicateChecker.Batch lateBatch;
    private BulkSubmissionService svc;

    @BeforeEach
    void setUp() {
        writer = mock(RegistrySubmissionWriter.class);
        dedupe = mock(RegistryDuplicateChecker.class);
        lateBatch = mock(RegistryDuplicateChecker.Batch.class);
        // late-dedupe always returns "no existing" for these tests
        when(dedupe.newBatch()).thenReturn(lateBatch);
        when(lateBatch.findExisting(any(), any())).thenReturn(Optional.empty());

        svc = new BulkSubmissionService(
                new YouTubeUrlParser(),                          // real parser for round-trip metadata validation
                mock(YouTubeGateway.class),
                dedupe,
                writer,
                Executors.newFixedThreadPool(2),
                mock(PublicContentCacheService.class),
                mock(SortOrderService.class));
    }

    @Test
    void moderator_submitChannel_alwaysPending() throws Exception {
        when(writer.writeChannel(any(), any(), eq("PENDING"), eq("mod-uid"), eq(false))).thenReturn("doc-c-1");

        var row = new SubmitRow(0, CHANNEL_URL,
                YouTubeContentType.CHANNEL, null,
                new PreviewMetadata(CHANNEL_ID, "Ch", "https://i.ytimg.com/thumb.jpg", null, null, 100L, null, null, null),
                List.of("cat-1"));
        var req = new BulkSubmitRequest(List.of(row), "APPROVED");   // moderator tries to bypass — ignored

        var resp = svc.submit(req, "mod-uid", false);

        assertEquals(1, resp.added());
        assertEquals(0, resp.failed());
        assertEquals("doc-c-1", resp.results().get(0).registryId());
        verify(writer).writeChannel(any(), any(), eq("PENDING"), eq("mod-uid"), eq(false));
    }

    @Test
    void admin_submitVideo_honorsApproved() throws Exception {
        when(writer.writeVideo(any(), eq(VideoType.LIVE), any(), eq("APPROVED"), eq("admin-uid"), eq(true))).thenReturn("doc-v-1");

        var row = new SubmitRow(0, VIDEO_URL,
                YouTubeContentType.VIDEO, VideoType.LIVE,
                new PreviewMetadata(VIDEO_ID, "Live Vid", "https://i.ytimg.com/t.jpg", "Ch", CHANNEL_ID, null, null, null, 50L),
                List.of("cat-1"));
        var req = new BulkSubmitRequest(List.of(row), "APPROVED");

        var resp = svc.submit(req, "admin-uid", true);

        assertEquals(1, resp.added());
        verify(writer).writeVideo(any(), eq(VideoType.LIVE), any(), eq("APPROVED"), eq("admin-uid"), eq(true));
    }

    @Test
    void perRowFailure_aggregatedNotAborting() throws Exception {
        when(writer.writeChannel(any(), any(), any(), any(), anyBoolean())).thenReturn("doc-c-1");
        when(writer.writePlaylist(any(), any(), any(), any(), anyBoolean())).thenThrow(new RuntimeException("firestore down"));
        when(writer.writeVideo(any(), any(), any(), any(), any(), anyBoolean())).thenReturn("doc-v-1");

        var rows = List.of(
                new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata(CHANNEL_ID, "c", null, null, null, null, null, null, null), List.of("cat-1")),
                new SubmitRow(1, PLAYLIST_URL, YouTubeContentType.PLAYLIST, null,
                        new PreviewMetadata(PLAYLIST_ID, "p", null, null, null, null, null, null, null), List.of("cat-1")),
                new SubmitRow(2, VIDEO_URL, YouTubeContentType.VIDEO, VideoType.STANDARD,
                        new PreviewMetadata(VIDEO_ID, "v", null, null, null, null, null, null, null), List.of("cat-1"))
        );
        var req = new BulkSubmitRequest(rows, "PENDING");

        var resp = svc.submit(req, "uid", true);

        assertEquals(3, resp.totalSubmitted());
        assertEquals(2, resp.added());
        assertEquals(1, resp.failed());
        assertEquals(SubmitStatus.FAILED, resp.results().get(1).status());
        assertNotNull(resp.results().get(1).errorCode());
    }

    @Test
    void admin_invalidStatus_rejectedWithBadRequest() {
        var row = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null),
                List.of("cat-1"));
        var req = new BulkSubmitRequest(List.of(row), "REJECTED");

        // REJECTED must not be settable via bulk path.
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> svc.submit(req, "admin-uid", true));
    }

    @Test
    void metadataYoutubeIdMismatch_failsRow_withYoutubeIdMismatch() {
        // URL parses to CHANNEL_ID, but metadata declares a different youtubeId — tampering check
        var tamperedRow = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                new PreviewMetadata("UCevilevilevilevilevilev", "Ch", null, null, null, null, null, null, null),
                List.of("cat-1"));
        var req = new BulkSubmitRequest(List.of(tamperedRow), "PENDING");

        var resp = svc.submit(req, "admin-uid", true);
        assertEquals(1, resp.failed());
        assertEquals("YOUTUBE_ID_MISMATCH", resp.results().get(0).errorCode());
    }

    @Test
    void detectedTypeMismatch_failsRow_withTypeMismatch() {
        // URL parses to CHANNEL but row claims VIDEO — type-mismatch check
        var tamperedRow = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.VIDEO, VideoType.STANDARD,
                new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null),
                List.of("cat-1"));
        var req = new BulkSubmitRequest(List.of(tamperedRow), "PENDING");

        var resp = svc.submit(req, "admin-uid", true);
        assertEquals(1, resp.failed());
        assertEquals("TYPE_MISMATCH", resp.results().get(0).errorCode());
    }

    @Test
    void lateDedupe_existingPending_failsRowAsDuplicate() {
        when(lateBatch.findExisting(eq(YouTubeContentType.CHANNEL), eq(CHANNEL_ID)))
                .thenReturn(Optional.of(new RegistryDuplicateChecker.ExistingMatch("existing-id", "PENDING")));

        var row = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null),
                List.of("cat-1"));
        var req = new BulkSubmitRequest(List.of(row), "PENDING");

        var resp = svc.submit(req, "admin-uid", true);
        assertEquals(1, resp.failed());
        assertEquals("DUPLICATE", resp.results().get(0).errorCode());
    }
}
