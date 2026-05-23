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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BulkSubmissionServicePreviewTest {

    private YouTubeUrlParser parser;
    private YouTubeGateway gateway;
    private RegistryDuplicateChecker dedupe;
    private RegistryDuplicateChecker.Batch batch;
    private BulkSubmissionService svc;

    @BeforeEach
    void setUp() {
        parser = new YouTubeUrlParser();
        gateway = mock(YouTubeGateway.class);
        dedupe = mock(RegistryDuplicateChecker.class);
        batch = mock(RegistryDuplicateChecker.Batch.class);
        when(dedupe.newBatch()).thenReturn(batch);
        // The submit-time writer is null here — T7 only tests preview
        svc = new BulkSubmissionService(parser, gateway, dedupe, null, Executors.newFixedThreadPool(2));
    }

    @Test
    void okRow_videoStandard() {
        when(batch.findExisting(any(), any())).thenReturn(Optional.empty());
        when(gateway.fetchByDetectedType(eq(YouTubeContentType.VIDEO), eq("dQw4w9WgXcQ"), any()))
                .thenReturn(PreviewFetchResult.ok(
                        new PreviewMetadata("dQw4w9WgXcQ", "Rick Astley", "thumb.jpg", "Rick", "UC1", null, null, 213L, 1000L),
                        VideoType.STANDARD));

        var req = new BulkPreviewRequest(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        var resp = svc.preview(req);

        assertEquals(1, resp.rows().size());
        var row = resp.rows().get(0);
        assertEquals(RowStatus.OK, row.status());
        assertEquals(YouTubeContentType.VIDEO, row.detectedType());
        assertEquals(VideoType.STANDARD, row.videoType());
        assertEquals("Rick Astley", row.metadata().title());
        assertNull(row.error());
    }

    @Test
    void shortsUrl_returnsUnsupportedShortsError() {
        var req = new BulkPreviewRequest(List.of("https://www.youtube.com/shorts/abcdefghijk"));
        var resp = svc.preview(req);

        assertEquals(RowStatus.ERROR, resp.rows().get(0).status());
        assertEquals(PreviewErrorCode.UNSUPPORTED_SHORTS, resp.rows().get(0).error().code());
        verifyNoInteractions(gateway);
    }

    @Test
    void duplicatePending_marksDuplicate() {
        when(batch.findExisting(eq(YouTubeContentType.VIDEO), eq("dQw4w9WgXcQ")))
                .thenReturn(Optional.of(new RegistryDuplicateChecker.ExistingMatch("existing-doc", "PENDING")));

        var req = new BulkPreviewRequest(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        var resp = svc.preview(req);

        assertEquals(RowStatus.DUPLICATE, resp.rows().get(0).status());
        assertEquals("existing-doc", resp.rows().get(0).duplicateOf());
        verifyNoInteractions(gateway);
    }

    @Test
    void duplicateRejected_marksDuplicateRejected() {
        when(batch.findExisting(any(), any()))
                .thenReturn(Optional.of(new RegistryDuplicateChecker.ExistingMatch("rejected-doc", "REJECTED")));
        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenReturn(PreviewFetchResult.ok(
                        new PreviewMetadata("dQw4w9WgXcQ", "Some Vid", "thumb.jpg", null, null, null, null, null, null),
                        VideoType.STANDARD));

        var req = new BulkPreviewRequest(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        var resp = svc.preview(req);

        assertEquals(RowStatus.DUPLICATE_REJECTED, resp.rows().get(0).status());
        assertEquals("rejected-doc", resp.rows().get(0).duplicateOf());
        assertNotNull(resp.rows().get(0).metadata(), "metadata must be present so admin sees what they'd re-submit");
    }

    @Test
    void newpipeError_passesThroughErrorCode() {
        when(batch.findExisting(any(), any())).thenReturn(Optional.empty());
        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenReturn(PreviewFetchResult.error(PreviewErrorCode.CONTENT_NOT_AVAILABLE));

        var req = new BulkPreviewRequest(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        var resp = svc.preview(req);

        assertEquals(RowStatus.ERROR, resp.rows().get(0).status());
        assertEquals(PreviewErrorCode.CONTENT_NOT_AVAILABLE, resp.rows().get(0).error().code());
    }

    @Test
    void mixedBatch_preservesOriginalOrder() {
        when(batch.findExisting(any(), any())).thenReturn(Optional.empty());
        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenReturn(PreviewFetchResult.ok(
                        new PreviewMetadata("x", "x", null, null, null, null, null, null, null),
                        VideoType.STANDARD));

        var req = new BulkPreviewRequest(
                List.of(
                        "https://www.youtube.com/watch?v=AAAAAAAAAAA",   // index 0, OK
                        "https://www.youtube.com/shorts/BBBBBBBBBBB",     // index 1, ERROR
                        "https://www.youtube.com/watch?v=CCCCCCCCCCC"     // index 2, OK
                ));
        var resp = svc.preview(req);

        assertEquals(3, resp.rows().size());
        assertEquals(0, resp.rows().get(0).rowIndex());
        assertEquals(1, resp.rows().get(1).rowIndex());
        assertEquals(2, resp.rows().get(2).rowIndex());
        assertEquals(RowStatus.OK,    resp.rows().get(0).status());
        assertEquals(RowStatus.ERROR, resp.rows().get(1).status());
        assertEquals(RowStatus.OK,    resp.rows().get(2).status());
    }

    @Test
    void buildRow_unexpectedExceptionInGateway_returnsErrorRow() {
        when(batch.findExisting(any(), any())).thenReturn(Optional.empty());
        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenThrow(new RuntimeException("unexpected bug"));

        var req = new BulkPreviewRequest(List.of("https://www.youtube.com/watch?v=AAAAAAAAAAA"));
        var resp = svc.preview(req);

        assertEquals(RowStatus.ERROR, resp.rows().get(0).status());
        assertEquals(PreviewErrorCode.NEWPIPE_PARSING_ERROR, resp.rows().get(0).error().code());
    }
}
