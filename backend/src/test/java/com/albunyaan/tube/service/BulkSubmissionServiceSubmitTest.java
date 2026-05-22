package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.*;
import com.albunyaan.tube.model.VideoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BulkSubmissionServiceSubmitTest {

    private RegistrySubmissionWriter writer;
    private BulkSubmissionService svc;

    @BeforeEach
    void setUp() {
        writer = mock(RegistrySubmissionWriter.class);
        svc = new BulkSubmissionService(
                mock(YouTubeUrlParser.class),
                mock(YouTubeGateway.class),
                mock(RegistryDuplicateChecker.class),
                writer,
                Executors.newFixedThreadPool(2));
    }

    @Test
    void moderator_submitChannel_alwaysPending() throws Exception {
        when(writer.writeChannel(any(), any(), eq("PENDING"), eq("mod-uid"), eq(false))).thenReturn("doc-c-1");

        var row = new SubmitRow(0, "https://www.youtube.com/channel/UC1",
                YouTubeContentType.CHANNEL, null,
                new PreviewMetadata("UC1", "Ch", "t.jpg", null, null, 100L, null, null, null),
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

        var row = new SubmitRow(0, "https://www.youtube.com/live/abc",
                YouTubeContentType.VIDEO, VideoType.LIVE,
                new PreviewMetadata("abc", "Live Vid", "t.jpg", "Ch", "UC1", null, null, null, 50L),
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
                new SubmitRow(0, "url-c", YouTubeContentType.CHANNEL, null,
                        new PreviewMetadata("c", "c", null, null, null, null, null, null, null), List.of("cat-1")),
                new SubmitRow(1, "url-p", YouTubeContentType.PLAYLIST, null,
                        new PreviewMetadata("p", "p", null, null, null, null, null, null, null), List.of("cat-1")),
                new SubmitRow(2, "url-v", YouTubeContentType.VIDEO, VideoType.STANDARD,
                        new PreviewMetadata("v", "v", null, null, null, null, null, null, null), List.of("cat-1"))
        );
        var req = new BulkSubmitRequest(rows, "PENDING");

        var resp = svc.submit(req, "uid", true);

        assertEquals(3, resp.totalSubmitted());
        assertEquals(2, resp.added());
        assertEquals(1, resp.failed());
        assertEquals(SubmitStatus.FAILED, resp.results().get(1).status());
        assertNotNull(resp.results().get(1).errorCode());
    }
}
