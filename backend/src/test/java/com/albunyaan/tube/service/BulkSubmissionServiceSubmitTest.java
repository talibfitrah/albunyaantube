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
    private com.albunyaan.tube.repository.CategoryRepository categoryRepository;
    private BulkSubmissionService svc;

    @BeforeEach
    void setUp() {
        writer = mock(RegistrySubmissionWriter.class);
        dedupe = mock(RegistryDuplicateChecker.class);
        lateBatch = mock(RegistryDuplicateChecker.Batch.class);
        // late-dedupe always returns "no existing" for these tests
        when(dedupe.newBatch()).thenReturn(lateBatch);
        when(lateBatch.findExisting(any(), any())).thenReturn(Optional.empty());

        // Default: every categoryId looked up by the service exists.
        // Individual tests override this to exercise the INVALID_CATEGORY path.
        categoryRepository = mock(com.albunyaan.tube.repository.CategoryRepository.class);
        try {
            when(categoryRepository.existsById(any())).thenReturn(true);
        } catch (Exception ignored) {
            // mockito stub setup can't actually throw — the throws clause on existsById
            // forces this checked-exception swallow in the test scaffold.
        }

        svc = new BulkSubmissionService(
                new YouTubeUrlParser(),                          // real parser for round-trip metadata validation
                mock(YouTubeGateway.class),
                dedupe,
                writer,
                Executors.newFixedThreadPool(2),
                mock(PublicContentCacheService.class),
                mock(SortOrderService.class),
                categoryRepository);
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

    @Test
    void intraBatchDuplicate_secondRowFailsAsDuplicate_afterWriterPopulatesCache() throws Exception {
        // Row 1: writer succeeds, production code calls lateBatch.markAsExisting.
        when(writer.writeChannel(any(), any(), eq("PENDING"), eq("admin-uid"), eq(true))).thenReturn("doc-c-1");
        // First findExisting → empty; second findExisting (after markAsExisting) → present.
        // Mockito returns the configured values in order; the second call to the same mock
        // method gets the second thenReturn value, simulating the cache shadow effect.
        when(lateBatch.findExisting(eq(YouTubeContentType.CHANNEL), eq(CHANNEL_ID)))
                .thenReturn(Optional.empty(),
                            Optional.of(new RegistryDuplicateChecker.ExistingMatch("doc-c-1", "PENDING")));

        var rows = List.of(
                new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null),
                        List.of("cat-1")),
                new SubmitRow(1, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null),
                        List.of("cat-1"))
        );
        var req = new BulkSubmitRequest(rows, "PENDING");

        var resp = svc.submit(req, "admin-uid", true);

        assertEquals(2, resp.totalSubmitted());
        assertEquals(1, resp.added(), "row 1 should write the doc");
        assertEquals(1, resp.failed(), "row 2 should fail as duplicate after markAsExisting");
        assertEquals(SubmitStatus.ADDED, resp.results().get(0).status());
        assertEquals(SubmitStatus.FAILED, resp.results().get(1).status());
        assertEquals("DUPLICATE", resp.results().get(1).errorCode());

        // Production code must have populated the batch with the just-written entry so the
        // second row's findExisting returns the duplicate match. Without this, both rows
        // would write Firestore docs with no unique constraint.
        verify(lateBatch).markAsExisting(YouTubeContentType.CHANNEL, CHANNEL_ID, "doc-c-1", "PENDING");
        // Writer called exactly once — the second row was rejected before write.
        verify(writer, times(1)).writeChannel(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void invalidCategoryId_failsRow_withInvalidCategoryErrorCode() throws Exception {
        when(categoryRepository.existsById("cat-real")).thenReturn(true);
        when(categoryRepository.existsById("cat-fake")).thenReturn(false);

        var row = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null),
                List.of("cat-real", "cat-fake"));   // mix of valid + invalid
        var req = new BulkSubmitRequest(List.of(row), "PENDING");

        var resp = svc.submit(req, "admin-uid", true);

        assertEquals(0, resp.added());
        assertEquals(1, resp.failed());
        assertEquals(SubmitStatus.FAILED, resp.results().get(0).status());
        assertEquals("INVALID_CATEGORY", resp.results().get(0).errorCode());
        // Writer must not have been called when any categoryId is bogus —
        // ensures the entity's categoryIds list never contains a phantom ID.
        verify(writer, never()).writeChannel(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void categoryExistenceCached_acrossRows_minimizesRepoCalls() throws Exception {
        when(categoryRepository.existsById("cat-shared")).thenReturn(true);
        when(writer.writeChannel(any(), any(), eq("PENDING"), eq("admin-uid"), eq(true)))
                .thenReturn("doc-c-1", "doc-c-2", "doc-c-3");

        // Three rows with three different channel IDs but all sharing the same categoryId.
        // The first row's lookup populates the per-batch cache; rows 2-3 hit the cache.
        var rows = List.of(
                new SubmitRow(0, "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw",
                        YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata("UCuAXFkgsw1L7xaCfnd5JJOw", "C0", null, null, null, null, null, null, null),
                        List.of("cat-shared")),
                new SubmitRow(1, "https://www.youtube.com/channel/UCABCDEFGHIJKLMNOPQRSTUv",
                        YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata("UCABCDEFGHIJKLMNOPQRSTUv", "C1", null, null, null, null, null, null, null),
                        List.of("cat-shared")),
                new SubmitRow(2, "https://www.youtube.com/channel/UCZZZZZZZZZZZZZZZZZZZZZv",
                        YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata("UCZZZZZZZZZZZZZZZZZZZZZv", "C2", null, null, null, null, null, null, null),
                        List.of("cat-shared"))
        );
        svc.submit(new BulkSubmitRequest(rows, "PENDING"), "admin-uid", true);

        // existsById queried exactly once for the shared categoryId — proves the
        // per-submit dedupe cache works and a 25-row × 10-categoryId batch doesn't
        // hammer Firestore with 250 redundant lookups.
        verify(categoryRepository, times(1)).existsById("cat-shared");
    }
}
