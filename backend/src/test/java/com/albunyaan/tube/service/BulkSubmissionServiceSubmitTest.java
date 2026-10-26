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
    private static final String VIDEO_ID = "sCQwxFEMlZ4";
    private static final String VIDEO_URL = "https://www.youtube.com/live/" + VIDEO_ID;
    private static final String PLAYLIST_ID = "PLxxxxxxxxxxxxxxxxxxxxxx";
    private static final String PLAYLIST_URL = "https://www.youtube.com/playlist?list=" + PLAYLIST_ID;

    private RegistrySubmissionWriter writer;
    private RegistryDuplicateChecker dedupe;
    private RegistryDuplicateChecker.Batch lateBatch;
    private com.albunyaan.tube.repository.CategoryRepository categoryRepository;
    private YouTubeGateway gateway;
    private SubmissionRateLimiter submissionRateLimiter;
    private LiveCategoryGuard liveCategoryGuard;
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

        // Default: gateway.fetchByDetectedType returns a successful authoritative
        // metadata that echoes the youtubeId/type passed in. Individual tests can
        // override to simulate fetch errors or to verify the writer received the
        // *authoritative* metadata rather than the client-supplied row.metadata().
        gateway = mock(YouTubeGateway.class);
        when(gateway.fetchByDetectedType(any(), any(), any())).thenAnswer(inv -> {
            YouTubeContentType type = inv.getArgument(0);
            String id = inv.getArgument(1);
            return PreviewFetchResult.ok(
                    new PreviewMetadata(id, "Authoritative", "https://i.ytimg.com/auth.jpg",
                            "AuthChannel", "UCauth", 999L, null, null, null),
                    type == YouTubeContentType.VIDEO ? VideoType.STANDARD : null
            );
        });

        // Default: rate-limiter grants all slots (returns null). Tests for the
        // 429 path override this.
        submissionRateLimiter = mock(SubmissionRateLimiter.class);
        when(submissionRateLimiter.tryAcquire(any(), anyInt())).thenReturn(null);

        liveCategoryGuard = mock(LiveCategoryGuard.class);   // checkAll answers an empty map: no LIVE row
        svc = new BulkSubmissionService(
                new YouTubeUrlParser(),                          // real parser for round-trip metadata validation
                gateway,
                dedupe,
                writer,
                Executors.newFixedThreadPool(2),
                mock(PublicContentCacheService.class),
                mock(SortOrderService.class),
                categoryRepository,
                submissionRateLimiter,
                liveCategoryGuard);
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
        // VideoType is now sourced from the gateway's authoritative fetch, not row.videoType().
        // Override the default stub so the gateway claims this video is LIVE.
        when(gateway.fetchByDetectedType(eq(YouTubeContentType.VIDEO), eq(VIDEO_ID), any()))
                .thenReturn(PreviewFetchResult.ok(
                        new PreviewMetadata(VIDEO_ID, "Live Vid", "https://i.ytimg.com/t.jpg",
                                "Ch", CHANNEL_ID, null, null, null, 50L),
                        VideoType.LIVE));
        when(writer.writeVideo(any(), eq(VideoType.LIVE), any(), eq("APPROVED"), eq("admin-uid"), eq(true), any())).thenReturn("doc-v-1");

        var row = new SubmitRow(0, VIDEO_URL,
                YouTubeContentType.VIDEO, VideoType.LIVE,
                new PreviewMetadata(VIDEO_ID, "Live Vid", "https://i.ytimg.com/t.jpg", "Ch", CHANNEL_ID, null, null, null, 50L),
                List.of("cat-1"));
        var req = new BulkSubmitRequest(List.of(row), "APPROVED");

        var resp = svc.submit(req, "admin-uid", true);

        assertEquals(1, resp.added());
        verify(writer).writeVideo(any(), eq(VideoType.LIVE), any(), eq("APPROVED"), eq("admin-uid"), eq(true), any());
    }

    /** Stage 9 B11: a REJECTED video (a report's take-down record too) is re-added into its doc, never a second one. */
    @Test
    void aRejectedVideoIsReAddedIntoItsDoc() throws Exception {
        when(lateBatch.findExisting(eq(YouTubeContentType.VIDEO), eq("TakeDown001")))
                .thenReturn(Optional.of(new RegistryDuplicateChecker.ExistingMatch("doc-takedown", "REJECTED")));
        when(writer.writeVideo(any(), any(), any(), any(), any(), anyBoolean(), eq("doc-takedown"))).thenReturn("doc-takedown");
        var row = new SubmitRow(0, "https://www.youtube.com/watch?v=TakeDown001", YouTubeContentType.VIDEO,
                VideoType.STANDARD, new PreviewMetadata("TakeDown001", "v", null, null, null, null, null, null, null),
                List.of("cat-1"));

        var resp = svc.submit(new BulkSubmitRequest(List.of(row), "APPROVED"), "admin-uid", true);

        assertEquals(1, resp.added());
        assertEquals("doc-takedown", resp.results().get(0).registryId());
    }

    /**
     * Stage 9 B16 (Cubic r6 P1): only an admin re-adds over a REJECTED doc. A moderator's row is the DUPLICATE any other
     * registered row is: never written into a take-down record or an admin's rejection, nor beside one as a second doc.
     */
    @Test
    void aModeratorsRowOverARejectedDocIsADuplicate() throws Exception {
        when(lateBatch.findExisting(any(), any()))
                .thenReturn(Optional.of(new RegistryDuplicateChecker.ExistingMatch("doc-rejected", "REJECTED")));
        var rows = List.of(
                new SubmitRow(0, "https://www.youtube.com/watch?v=TakeDown001", YouTubeContentType.VIDEO, VideoType.STANDARD,
                        new PreviewMetadata("TakeDown001", "v", null, null, null, null, null, null, null), List.of("cat-1")),
                new SubmitRow(1, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null), List.of("cat-1")));

        var resp = svc.submit(new BulkSubmitRequest(rows, "PENDING"), "mod-uid", false);

        assertEquals(0, resp.added());
        assertEquals(List.of("DUPLICATE", "DUPLICATE"), resp.results().stream().map(SubmitResult::errorCode).toList());
        verify(writer, never()).writeVideo(any(), any(), any(), any(), any(), anyBoolean(), any());
        verify(writer, never()).writeChannel(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void perRowFailure_aggregatedNotAborting() throws Exception {
        when(writer.writeChannel(any(), any(), any(), any(), anyBoolean())).thenReturn("doc-c-1");
        when(writer.writePlaylist(any(), any(), any(), any(), anyBoolean())).thenThrow(new RuntimeException("firestore down"));
        when(writer.writeVideo(any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn("doc-v-1");

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
        // As the real Batch: findExisting answers empty until markAsExisting records the written doc, however
        // often it is asked (the duplicate check before the live guard asks too).
        java.util.concurrent.atomic.AtomicBoolean marked = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv -> { marked.set(true); return null; })
                .when(lateBatch).markAsExisting(any(), any(), any(), any());
        when(lateBatch.findExisting(eq(YouTubeContentType.CHANNEL), eq(CHANNEL_ID)))
                .thenAnswer(inv -> marked.get()
                        ? Optional.of(new RegistryDuplicateChecker.ExistingMatch("doc-c-1", "PENDING"))
                        : Optional.empty());

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
    void existsByIdThrowsRuntimeException_failsRowAsInvalidCategory_notWholeSubmit() throws Exception {
        // Firestore SDK can throw FirestoreException (RuntimeException) on
        // transient errors. The previous narrow catch (only ExecutionException
        // + TimeoutException + InterruptedException) let RuntimeException
        // propagate past the per-row try/catch and 500 the entire submit.
        // Now the row fails closed with INVALID_CATEGORY and the batch
        // continues processing other rows.
        when(categoryRepository.existsById("cat-flaky"))
                .thenThrow(new RuntimeException("firestore transient"));

        var row = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null),
                List.of("cat-flaky"));
        var req = new BulkSubmitRequest(List.of(row), "PENDING");

        // Service does NOT throw — the row is reported as failed.
        var resp = svc.submit(req, "admin-uid", true);
        assertEquals(1, resp.failed());
        assertEquals(0, resp.added());
        assertEquals("INVALID_CATEGORY", resp.results().get(0).errorCode());
        verify(writer, never()).writeChannel(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void sortOrderServiceCheckedException_doesNotDuplicateResultEntry() throws Exception {
        // SortOrderService.addContentToCategory declares
        // ExecutionException/InterruptedException/TimeoutException. The
        // previous inner catch was narrow (RuntimeException), so a checked
        // exception fell through to the outer catch which appended a SECOND
        // result entry for the row (FAILED, WRITE_ERROR) AFTER the ADDED
        // entry was already appended. Result: added=1, failed=1, results
        // has 2 entries for 1 row, totalSubmitted=1 — contract violation.
        // After widening the inner catch to all Exception, the throw is
        // swallowed and the row keeps its single ADDED entry.
        when(writer.writeChannel(any(), any(), eq("APPROVED"), eq("admin-uid"), eq(true)))
                .thenReturn("doc-c-1");
        // Use the field reference to the service's SortOrderService — we
        // need to access it directly to stub. Construct a new service with
        // a tracked sortOrderService.
        var sortOrderMock = mock(SortOrderService.class);
        doThrow(new java.util.concurrent.TimeoutException("firestore slow"))
                .when(sortOrderMock).addContentToCategory(any(), any(), any());

        var svcWithSortMock = new BulkSubmissionService(
                new YouTubeUrlParser(),
                gateway,
                dedupe,
                writer,
                java.util.concurrent.Executors.newFixedThreadPool(2),
                mock(PublicContentCacheService.class),
                sortOrderMock,
                categoryRepository,
                submissionRateLimiter,
                liveCategoryGuard);

        var row = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null),
                List.of("cat-1"));
        // APPROVED so the sortOrder side-effect path runs.
        var req = new BulkSubmitRequest(List.of(row), "APPROVED");

        var resp = svcWithSortMock.submit(req, "admin-uid", true);

        assertEquals(1, resp.totalSubmitted(), "exactly 1 row submitted");
        assertEquals(1, resp.results().size(), "exactly 1 result entry — no duplicate from outer Exception catch");
        assertEquals(1, resp.added(), "row counted as ADDED (sortOrder failure does not roll back the write)");
        assertEquals(0, resp.failed());
        assertEquals(SubmitStatus.ADDED, resp.results().get(0).status());
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
    void clientCraftedMetadata_overriddenByAuthoritativeFetch() throws Exception {
        // Trust boundary: the writer must receive the metadata that GATEWAY (NewPipe)
        // returned, never the metadata the moderator's client put in row.metadata.
        // Without re-fetch in submit, a moderator could preview a legitimate URL then
        // craft (title, subscribers, channelName, thumbnailUrl) values that flow into
        // the public feed after admin approval — a public-facing-data spoofing attack.
        PreviewMetadata trustedFromGateway = new PreviewMetadata(
                CHANNEL_ID, "Real Channel", "https://i.ytimg.com/real.jpg",
                null, null, 100L, null, null, null);
        when(gateway.fetchByDetectedType(eq(YouTubeContentType.CHANNEL), eq(CHANNEL_ID), any()))
                .thenReturn(PreviewFetchResult.ok(trustedFromGateway, null));
        when(writer.writeChannel(any(), any(), any(), any(), anyBoolean())).thenReturn("doc-c-1");

        PreviewMetadata spoofedFromClient = new PreviewMetadata(
                CHANNEL_ID, "<SPOOFED MUST NEVER LAND>", "https://attacker.example/track.gif",
                null, null, 999_999_999L, null, null, null);
        var row = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                spoofedFromClient, List.of("cat-1"));
        var req = new BulkSubmitRequest(List.of(row), "PENDING");

        svc.submit(req, "admin-uid", true);

        // Writer must receive the gateway's authoritative metadata.
        verify(writer).writeChannel(eq(trustedFromGateway), any(), any(), any(), anyBoolean());
        // And must NOT receive the spoofed client metadata.
        verify(writer, never()).writeChannel(eq(spoofedFromClient), any(), any(), any(), anyBoolean());
    }

    @Test
    void fetchError_failsRow_withErrorCodeFromGateway() {
        // Gateway claims the video is age-restricted; submit must propagate the
        // error code (not the writer) and never write.
        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenReturn(PreviewFetchResult.error(
                        com.albunyaan.tube.dto.registry.PreviewErrorCode.AGE_RESTRICTED));

        var row = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null),
                List.of("cat-1"));
        var req = new BulkSubmitRequest(List.of(row), "PENDING");

        var resp = svc.submit(req, "admin-uid", true);

        assertEquals(0, resp.added());
        assertEquals(1, resp.failed());
        assertEquals("AGE_RESTRICTED", resp.results().get(0).errorCode());
        verifyNoInteractions(writer);
    }

    @Test
    void bulkSubmit_consumesRowCountMinusOneExtraSlotsFromLimiter() throws Exception {
        when(writer.writeChannel(any(), any(), any(), any(), anyBoolean())).thenReturn("doc-c-1");

        var rows = List.of(
                new SubmitRow(0, "https://www.youtube.com/channel/UC1AXFkgsw1L7xaCfnd5JJOw",
                        YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata("UC1AXFkgsw1L7xaCfnd5JJOw", "C0", null, null, null, null, null, null, null),
                        List.of("cat-1")),
                new SubmitRow(1, "https://www.youtube.com/channel/UC2BCDEFGHIJKLMNOPQRSTUv",
                        YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata("UC2BCDEFGHIJKLMNOPQRSTUv", "C1", null, null, null, null, null, null, null),
                        List.of("cat-1")),
                new SubmitRow(2, "https://www.youtube.com/channel/UC3ZZZZZZZZZZZZZZZZZZZZv",
                        YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata("UC3ZZZZZZZZZZZZZZZZZZZZv", "C2", null, null, null, null, null, null, null),
                        List.of("cat-1"))
        );
        svc.submit(new BulkSubmitRequest(rows, "PENDING"), "admin-uid", true);

        // Interceptor already consumed 1 slot; service consumes the remaining
        // (3 - 1) = 2 to make total consumption equal to row count.
        verify(submissionRateLimiter).tryAcquire("admin-uid", 2);
    }

    @Test
    void bulkSubmit_singleRow_doesNotConsumeExtraSlots() throws Exception {
        when(writer.writeChannel(any(), any(), any(), any(), anyBoolean())).thenReturn("doc-c-1");

        var row = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                new PreviewMetadata(CHANNEL_ID, "C", null, null, null, null, null, null, null),
                List.of("cat-1"));
        svc.submit(new BulkSubmitRequest(List.of(row), "PENDING"), "admin-uid", true);

        // rows.size() - 1 = 0, so tryAcquire is not called from the service
        // (the interceptor already handled the single slot).
        verify(submissionRateLimiter, never()).tryAcquire(any(), anyInt());
    }

    @Test
    void bulkSubmit_rateLimited_throws429() {
        // Limiter says retry in 60 seconds — extra-slot acquisition fails.
        when(submissionRateLimiter.tryAcquire(any(), anyInt())).thenReturn(60L);

        var rows = List.of(
                new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata(CHANNEL_ID, "C", null, null, null, null, null, null, null),
                        List.of("cat-1")),
                new SubmitRow(1, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata(CHANNEL_ID, "C", null, null, null, null, null, null, null),
                        List.of("cat-1"))
        );
        var req = new BulkSubmitRequest(rows, "PENDING");

        // Custom BulkSubmissionRateLimitedException, not ResponseStatusException —
        // GlobalExceptionHandler turns it into a 429 with the
        // {"code":"RATE_LIMIT","retryAfterSeconds":N,...} shape + Retry-After
        // header to match the single-add interceptor response.
        var ex = assertThrows(BulkSubmissionRateLimitedException.class,
                () -> svc.submit(req, "admin-uid", true));
        assertEquals(60L, ex.getRetryAfterSec());
        // Writer must never have been called once rate-limited.
        verifyNoInteractions(writer);
    }

    @Test
    void fetchThrows_failsRow_withFetchError() {
        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenThrow(new RuntimeException("NewPipe timeout"));

        var row = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null),
                List.of("cat-1"));
        var req = new BulkSubmitRequest(List.of(row), "PENDING");

        var resp = svc.submit(req, "admin-uid", true);

        assertEquals(0, resp.added());
        assertEquals(1, resp.failed());
        assertEquals("FETCH_ERROR", resp.results().get(0).errorCode());
        verifyNoInteractions(writer);
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

    /** D13 / pre-flight M5: a live-category video row is written with the guard's verified channel, not the fetched one. */
    @Test
    void aLiveCategoryVideoRowIsWrittenWithTheVerifiedChannel() throws Exception {
        when(liveCategoryGuard.checkAll(eq(true), any())).thenReturn(java.util.Map.of("0", "UCROKYPep-UuODNwyipe6JMw"));
        when(writer.writeVideo(any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn("doc-v-live");
        var row = new SubmitRow(0, "https://www.youtube.com/watch?v=Rs7St51oDDc", YouTubeContentType.VIDEO, null,
                new PreviewMetadata("Rs7St51oDDc", "Sunnah", null, null, null, null, null, null, null), List.of("cat-live"));

        svc.submit(new BulkSubmitRequest(List.of(row), "APPROVED"), "admin-uid", true);

        var written = org.mockito.ArgumentCaptor.forClass(PreviewMetadata.class);
        verify(writer).writeVideo(written.capture(), any(), any(), any(), any(), anyBoolean(), any());
        assertEquals("UCROKYPep-UuODNwyipe6JMw", written.getValue().channelId());   // the gateway stub says "UCauth"
    }

    /** Stage 9 Cubic P3: an already-registered row is DUPLICATE before the live guard runs, never a refusal of the whole request. */
    @Test
    void aDuplicateRowIsReportedDuplicateAndNeverReachesTheLiveGuard() throws Exception {
        when(lateBatch.findExisting(eq(YouTubeContentType.VIDEO), eq(VIDEO_ID)))
                .thenReturn(Optional.of(new RegistryDuplicateChecker.ExistingMatch("doc-ended-live", "APPROVED")));
        when(liveCategoryGuard.checkAll(eq(true), argThat(changes -> changes.stream().anyMatch(c -> VIDEO_ID.equals(c.youtubeId())))))
                .thenThrow(new com.albunyaan.tube.exception.LiveCategoryException(org.springframework.http.HttpStatus.CONFLICT,
                        com.albunyaan.tube.exception.LiveCategoryException.NOT_LIVE, "the stream has ended", java.util.Map.of()));
        when(writer.writeChannel(any(), any(), any(), any(), anyBoolean())).thenReturn("doc-c-1");
        var rows = List.of(
                new SubmitRow(0, VIDEO_URL, YouTubeContentType.VIDEO, null,
                        new PreviewMetadata(VIDEO_ID, "Ended live", null, null, null, null, null, null, null), List.of("cat-live")),
                new SubmitRow(1, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null), List.of("cat-1")));

        var resp = svc.submit(new BulkSubmitRequest(rows, "APPROVED"), "admin-uid", true);

        assertEquals("DUPLICATE", resp.results().get(0).errorCode());
        assertEquals(SubmitStatus.ADDED, resp.results().get(1).status());
    }

    /**
     * Stage 9 Cubic r2 P2: a single add of the same id that lands while this submit fetches its rows is caught by a
     * registry read right before the write, never by the answer cached before the live guard. Real checker, so the test
     * holds whichever batches the service keeps.
     */
    @Test
    void aSingleAddThatLandsDuringTheRowFetchIsADuplicateNotASecondDoc() throws Exception {
        var channels = mock(com.albunyaan.tube.repository.ChannelRepository.class);
        var concurrent = new com.albunyaan.tube.model.Channel(CHANNEL_ID);
        concurrent.setId("doc-single-add");
        concurrent.setStatus("PENDING");
        java.util.concurrent.atomic.AtomicBoolean landed = new java.util.concurrent.atomic.AtomicBoolean();
        when(channels.findByYoutubeId(CHANNEL_ID)).thenAnswer(i -> landed.get() ? Optional.of(concurrent) : Optional.empty());
        when(gateway.fetchByDetectedType(eq(YouTubeContentType.CHANNEL), eq(CHANNEL_ID), any())).thenAnswer(i -> {
            landed.set(true);                                                  // the single add commits meanwhile
            return PreviewFetchResult.ok(new PreviewMetadata(CHANNEL_ID, "Ch", null, "Ch", CHANNEL_ID, 1L, null, null, null), null);
        });
        var real = new BulkSubmissionService(new YouTubeUrlParser(), gateway,
                new RegistryDuplicateChecker(channels, mock(com.albunyaan.tube.repository.PlaylistRepository.class),
                        mock(com.albunyaan.tube.repository.VideoRepository.class)),
                writer, Executors.newFixedThreadPool(1), mock(PublicContentCacheService.class), mock(SortOrderService.class),
                categoryRepository, submissionRateLimiter, liveCategoryGuard);
        var row = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null), List.of("cat-1"));

        var resp = real.submit(new BulkSubmitRequest(List.of(row), "PENDING"), "admin-uid", true);

        assertEquals("DUPLICATE", resp.results().get(0).errorCode());
        verify(writer, never()).writeChannel(any(), any(), any(), any(), anyBoolean());
    }

    /** Fix round 1: a failed categories read inside the guard is a retryable 503, not a 409, and nothing is written. */
    @Test
    void aFailedLiveCategoryCheckIs503CheckUnavailable() throws Exception {
        when(liveCategoryGuard.checkAll(anyBoolean(), any()))
                .thenThrow(new java.util.concurrent.ExecutionException("categories read failed", null));
        var row = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null), List.of("cat-1"));

        var e = assertThrows(com.albunyaan.tube.exception.LiveCategoryException.class,
                () -> svc.submit(new BulkSubmitRequest(List.of(row), "APPROVED"), "admin-uid", true));

        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertEquals(com.albunyaan.tube.exception.LiveCategoryException.CHECK_UNAVAILABLE, e.getCode());
        verifyNoInteractions(writer);
    }

    @Test
    void anInterruptedLiveCategoryCheckIs503AndKeepsTheFlag() throws Exception {
        when(liveCategoryGuard.checkAll(anyBoolean(), any())).thenThrow(new InterruptedException());
        var row = new SubmitRow(0, CHANNEL_URL, YouTubeContentType.CHANNEL, null,
                new PreviewMetadata(CHANNEL_ID, "Ch", null, null, null, null, null, null, null), List.of("cat-1"));

        var e = assertThrows(com.albunyaan.tube.exception.LiveCategoryException.class,
                () -> svc.submit(new BulkSubmitRequest(List.of(row), "APPROVED"), "admin-uid", true));

        assertTrue(Thread.interrupted());                                     // also clears it for the next test
        assertEquals(com.albunyaan.tube.exception.LiveCategoryException.CHECK_UNAVAILABLE, e.getCode());
    }
}
