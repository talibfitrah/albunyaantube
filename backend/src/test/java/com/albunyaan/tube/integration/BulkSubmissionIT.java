package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.*;
import com.albunyaan.tube.model.VideoType;
import com.albunyaan.tube.service.PreviewFetchResult;
import com.albunyaan.tube.service.YouTubeGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for bulk preview + submit endpoints.
 *
 * Tests the full controller → service → Firestore-writer wiring.
 * YouTubeGateway is mocked via @MockBean so NewPipe is never called over the network.
 * Auth uses the same FirebaseAuth stub pattern as the rest of the IT suite.
 */
class BulkSubmissionIT extends BaseIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockBean
    private FirebaseAuth firebaseAuth;

    /**
     * Mock the gateway so the test doesn't hit YouTube —
     * we're testing controller + service + Firestore wiring only.
     */
    @MockBean
    private YouTubeGateway gateway;

    @Autowired
    private com.albunyaan.tube.repository.VideoRepository videoRepository;

    @Autowired
    private com.albunyaan.tube.service.ContentReportService reports;

    @Autowired
    private com.albunyaan.tube.service.ContentValidationService validation;

    @Autowired
    private com.albunyaan.tube.service.ApprovalService approvals;

    @Override
    protected String[] getCollectionsToClean() {
        return new String[]{"categories", "channels", "playlists", "videos", "users", "audit_logs", "searchable_streams",
                "contentReports", "adminNotifications"};
    }

    /**
     * Seed the "cat-1" category so the per-row categoryIds existence
     * check in {@code BulkSubmissionService.submit} (added 2026-05-23 to
     * close H2 unbounded-fan-out finding) finds a real category and
     * rows are not rejected with INVALID_CATEGORY. Runs after the
     * inherited {@link BaseIntegrationTest#setUpFirestore} clears the
     * categories collection.
     */
    @org.junit.jupiter.api.BeforeEach
    void seedCategory() throws Exception {
        firestore.collection("categories").document("cat-1")
                .set(Map.of("name", "Test Category", "slug", "test-category"))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Auth helper (copied from BulkUserActionIT pattern)
    // -------------------------------------------------------------------------

    private void stubAuthAs(String uid, String role) throws Exception {
        FirebaseToken token = Mockito.mock(FirebaseToken.class);
        Mockito.when(token.getUid()).thenReturn(uid);
        Mockito.when(token.getEmail()).thenReturn(uid + "@test.com");
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        Mockito.when(token.getClaims()).thenReturn(claims);
        Mockito.when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);
        Mockito.when(firebaseAuth.verifyIdToken(anyString(), anyBoolean())).thenReturn(token);
    }

    // -------------------------------------------------------------------------
    // Test cases
    // -------------------------------------------------------------------------

    /**
     * §7.1 — Moderator can submit a batch; all rows land PENDING regardless of
     * the requested status in the body (role normalization enforced in service).
     */
    @Test
    void moderator_canSubmitBatch_allRowsLandPending() throws Exception {
        stubAuthAs("mod-uid", "moderator");

        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenAnswer(inv -> PreviewFetchResult.ok(
                        new PreviewMetadata(
                                "UCxxxxxxxxxxxxxxxxxxxxxx",
                                "Test Channel",
                                "thumb.jpg",
                                null, null, 100L, null, null, null),
                        VideoType.STANDARD));

        // Preview step: 2 URLs (channel + playlist)
        var previewReq = new BulkPreviewRequest(
                List.of(
                        "https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx",
                        "https://www.youtube.com/playlist?list=PLxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"));

        mvc.perform(post("/api/admin/registry/bulk/preview")
                        .header("Authorization", "Bearer fake-mod-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(previewReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2));

        // Submit one channel; request status=APPROVED but moderator → must be downgraded to PENDING
        var submitReq = new BulkSubmitRequest(
                List.of(new SubmitRow(
                        0,
                        "https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx",
                        YouTubeContentType.CHANNEL,
                        null,
                        new PreviewMetadata(
                                "UCxxxxxxxxxxxxxxxxxxxxxx", "Test Channel", "thumb.jpg",
                                null, null, 100L, null, null, null),
                        List.of("cat-1"))),
                "APPROVED");   // moderator — must be downgraded to PENDING by service

        mvc.perform(post("/api/admin/registry/bulk/submit")
                        .header("Authorization", "Bearer fake-mod-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.results[0].status").value("ADDED"));
    }

    /**
     * §7.1 — Admin can submit with status=APPROVED, bypassing the PENDING default.
     */
    @Test
    void admin_canSubmitApproved_bypassesPending() throws Exception {
        stubAuthAs("admin-uid", "admin");

        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenReturn(PreviewFetchResult.ok(
                        new PreviewMetadata(
                                "AAAAAAAAAAA", "Test Video", "thumb.jpg",
                                "Channel", "UCxxxxxxxxxxxxxxxxxxxxxx",
                                null, null, 213L, 1000L),
                        VideoType.STANDARD));

        var submitReq = new BulkSubmitRequest(
                List.of(new SubmitRow(
                        0,
                        "https://www.youtube.com/watch?v=AAAAAAAAAAA",
                        YouTubeContentType.VIDEO,
                        VideoType.STANDARD,
                        new PreviewMetadata(
                                "AAAAAAAAAAA", "Test Video", "thumb.jpg",
                                "Channel", "UCxxxxxxxxxxxxxxxxxxxxxx",
                                null, null, 213L, 1000L),
                        List.of("cat-1"))),
                "APPROVED");

        mvc.perform(post("/api/admin/registry/bulk/submit")
                        .header("Authorization", "Bearer fake-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));
    }

    private void seedVideo(String docId, String youtubeId, String status, String source,
                           com.albunyaan.tube.model.ValidationStatus validation) throws Exception {
        var v = new com.albunyaan.tube.model.Video(youtubeId);
        v.setId(docId);
        v.setStatus(status);
        v.setSource(source);
        v.setValidationStatus(validation);
        firestore.collection("videos").document(docId).set(v).get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Stage 9 B11: a bulk re-add of a taken-down video reuses its take-down record (one doc), and the app serves it. */
    @Test
    void bulkReAddOfATakenDownVideoReusesItsRecordAndIsServed() throws Exception {
        stubAuthAs("admin-uid", "admin");
        seedVideo("aaaa-takedown", "TakeDown001", "REJECTED", "REPORT", com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
        var meta = new PreviewMetadata("TakeDown001", "Lesson", "https://i.ytimg.com/vi/TakeDown001/hq.jpg",
                "Channel", "UCxxxxxxxxxxxxxxxxxxxxxx", null, null, 213L, 1000L);
        when(gateway.fetchByDetectedType(any(), any(), any())).thenReturn(PreviewFetchResult.ok(meta, VideoType.STANDARD));
        var submitReq = new BulkSubmitRequest(List.of(new SubmitRow(0, "https://www.youtube.com/watch?v=TakeDown001",
                YouTubeContentType.VIDEO, VideoType.STANDARD, meta, List.of("cat-1"))), "APPROVED");

        mvc.perform(post("/api/admin/registry/bulk/submit")
                        .header("Authorization", "Bearer fake-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.results[0].registryId").value("aaaa-takedown"));

        var docs = firestore.collection("videos").whereEqualTo("youtubeId", "TakeDown001").get()
                .get(5, java.util.concurrent.TimeUnit.SECONDS).getDocuments();
        org.junit.jupiter.api.Assertions.assertEquals(1, docs.size(), "no second doc");
        org.junit.jupiter.api.Assertions.assertEquals("APPROVED", docs.get(0).getString("status"));
        org.junit.jupiter.api.Assertions.assertNull(docs.get(0).get("validationStatus"), "the take-down is lifted");
        mvc.perform(get("/api/v1/videos/TakeDown001")).andExpect(status().isOk());
    }

    /** Stage 9 B11: a stale REJECTED second doc (a bulk re-add before B11) never blocks the approved one at the gate. */
    @Test
    void aStaleRejectedDuplicateNeverBlocksTheApprovedVideo() throws Exception {
        seedVideo("aaaa-stale", "TwoDocs0001", "REJECTED", null, null);   // sorts first: limit(1) used to answer it
        seedVideo("zzzz-approved", "TwoDocs0001", "APPROVED", null, null);

        mvc.perform(get("/api/v1/videos/TwoDocs0001")).andExpect(status().isOk());
    }

    /** Stage 9 B13: Restore lifts a take-down whole: the exclusion it added, its stream's archive, then its record. */
    @Test
    void restoreOfATakeDownLiftsItsExclusionAndItsStreamThenDeletesTheRecord() throws Exception {
        stubAuthAs("admin-uid", "admin");
        var record = new com.albunyaan.tube.model.Video("TakeDown002");
        record.setId("aaaa-restore");
        record.setStatus("REJECTED");
        record.setSource("REPORT");
        record.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
        record.setTakeDownExclusions(List.of("channel:UCtakedown0000000000000a"));
        firestore.collection("videos").document("aaaa-restore").set(record).get(5, java.util.concurrent.TimeUnit.SECONDS);
        firestore.collection("channels").document("ch-takedown").set(Map.of("youtubeId", "UCtakedown0000000000000a",
                "name", "C", "status", "APPROVED", "excludedItems", Map.of("videos", List.of("TakeDown002", "KeptExcl001"))))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        firestore.collection("searchable_streams").document("TakeDown002").set(Map.of(   // streamId is the doc id
                "title", "Lesson", "sourceKeys", List.of("channel:UCtakedown0000000000000a"), "visible", false))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);

        mvc.perform(post("/api/admin/content-validation/action")
                        .header("Authorization", "Bearer fake-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RESTORE\",\"type\":\"VIDEO\",\"ids\":[\"aaaa-restore\"]}"))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertFalse(firestore.collection("videos").document("aaaa-restore").get()
                .get(5, java.util.concurrent.TimeUnit.SECONDS).exists(), "the record is gone");
        org.junit.jupiter.api.Assertions.assertEquals(List.of("KeptExcl001"), firestore.collection("channels").document("ch-takedown")
                .get().get(5, java.util.concurrent.TimeUnit.SECONDS).get("excludedItems.videos"), "only the take-down's exclusion is lifted");
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, firestore.collection("searchable_streams").document("TakeDown002")
                .get().get(5, java.util.concurrent.TimeUnit.SECONDS).getBoolean("visible"), "the stream's archive is undone");
    }

    /** Stage 9 B13: through the real Firestore client, a conditional write's own refusal reaches its caller as itself. */
    @Test
    void aConditionalWritesOwnRefusalIsThrownAsItself() throws Exception {
        seedVideo("aaaa-approved", "ReAdded0001", "APPROVED", null, null);
        var doc = videoRepository.findById("aaaa-approved").orElseThrow();
        var gone = new com.albunyaan.tube.model.Video("Restored001");
        gone.setId("aaaa-gone");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> videoRepository.saveIfStatus(doc, "REJECTED"), "another re-add landed: 409");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> videoRepository.saveIfStatus(gone, "REJECTED"), "a Restore deleted the record");
    }

    /**
     * Stage 9 B14 (re-review P3-1, Cubic r5 #2): a take-down's sources are written as that one field, and only while the
     * doc is still the take-down record. A re-add that landed meanwhile wins: the write is refused, the doc untouched.
     */
    @Test
    void aTakeDownsSourcesAreWrittenOnlyWhileTheDocIsStillTheRecord() throws Exception {
        seedVideo("aaaa-record", "TakeDown005", "REJECTED", "REPORT", com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
        seedVideo("aaaa-readded", "ReAdded0002", "APPROVED", null, null);
        List<String> sources = List.of("channel:UCtakedown0000000000000a");

        videoRepository.updateIfStatusAndSource("aaaa-record", "REJECTED", "REPORT", "takeDownExclusions", sources);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> videoRepository
                .updateIfStatusAndSource("aaaa-readded", "REJECTED", "REPORT", "takeDownExclusions", sources), "re-added: refused");

        var record = videoRepository.findById("aaaa-record").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(sources, record.getTakeDownExclusions());
        org.junit.jupiter.api.Assertions.assertEquals(com.albunyaan.tube.model.ValidationStatus.ARCHIVED, record.getValidationStatus());
        var reAdded = videoRepository.findById("aaaa-readded").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("APPROVED", reAdded.getStatus(), "the re-add stands");
        org.junit.jupiter.api.Assertions.assertNull(reAdded.getTakeDownExclusions());
    }

    /**
     * Stage 9 B14 (Cubic r5 #3): a re-add, single or bulk, that writes into a take-down record also lifts the exclusions
     * its report added, as Restore does, so the re-added video is not left hidden on those sources.
     */
    @Test
    void aReAddOverATakeDownLiftsTheExclusionsItsReportAdded() throws Exception {
        stubAuthAs("admin-uid", "admin");
        for (String id : List.of("TakeDown003", "TakeDown004")) {
            var record = new com.albunyaan.tube.model.Video(id);
            record.setId("aaaa-" + id);
            record.setStatus("REJECTED");
            record.setSource("REPORT");
            record.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
            record.setTakeDownExclusions(List.of("channel:UCtakedown0000000000000a"));
            firestore.collection("videos").document(record.getId()).set(record).get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        firestore.collection("channels").document("ch-takedown").set(Map.of("youtubeId", "UCtakedown0000000000000a",
                "name", "C", "status", "APPROVED",
                "excludedItems", Map.of("videos", List.of("TakeDown003", "TakeDown004", "KeptExcl001"))))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);

        mvc.perform(post("/api/admin/registry/videos")
                        .header("Authorization", "Bearer fake-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("youtubeId", "TakeDown003", "title", "Lesson",
                                "categoryIds", List.of("cat-1"), "status", "APPROVED"))))
                .andExpect(status().isCreated());
        var meta = new PreviewMetadata("TakeDown004", "Lesson", "https://i.ytimg.com/vi/TakeDown004/hq.jpg",
                "Channel", "UCxxxxxxxxxxxxxxxxxxxxxx", null, null, 213L, 1000L);
        when(gateway.fetchByDetectedType(any(), any(), any())).thenReturn(PreviewFetchResult.ok(meta, VideoType.STANDARD));
        mvc.perform(post("/api/admin/registry/bulk/submit")
                        .header("Authorization", "Bearer fake-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new BulkSubmitRequest(List.of(new SubmitRow(0,
                                "https://www.youtube.com/watch?v=TakeDown004", YouTubeContentType.VIDEO, VideoType.STANDARD,
                                meta, List.of("cat-1"))), "PENDING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));

        org.junit.jupiter.api.Assertions.assertEquals(List.of("KeptExcl001"), firestore.collection("channels")
                .document("ch-takedown").get().get(5, java.util.concurrent.TimeUnit.SECONDS).get("excludedItems.videos"),
                "only the reports' exclusions are lifted");
    }

    /**
     * Stage 9 B16 (Cubic r6 P1): a moderator cannot undo a take-down. The add, single (409) or bulk (DUPLICATE), leaves
     * the record and its report's exclusions as they were, the gate stays 410, and the record is not theirs to delete.
     */
    @Test
    void aModeratorCannotUndoATakeDown() throws Exception {
        stubAuthAs("mod-uid", "moderator");
        var record = new com.albunyaan.tube.model.Video("TakeDown006");
        record.setId("aaaa-TakeDown006");
        record.setStatus("REJECTED");
        record.setSource("REPORT");
        record.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
        record.setTakeDownExclusions(List.of("channel:UCtakedown0000000000000a"));
        firestore.collection("videos").document(record.getId()).set(record).get(5, java.util.concurrent.TimeUnit.SECONDS);
        firestore.collection("channels").document("ch-takedown").set(Map.of("youtubeId", "UCtakedown0000000000000a",
                "name", "C", "status", "APPROVED", "excludedItems", Map.of("videos", List.of("TakeDown006"))))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        var meta = new PreviewMetadata("TakeDown006", "Lesson", "https://i.ytimg.com/vi/TakeDown006/hq.jpg",
                "Channel", "UCxxxxxxxxxxxxxxxxxxxxxx", null, null, 213L, 1000L);
        when(gateway.fetchByDetectedType(any(), any(), any())).thenReturn(PreviewFetchResult.ok(meta, VideoType.STANDARD));

        mvc.perform(post("/api/admin/registry/videos")
                        .header("Authorization", "Bearer fake-mod-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("youtubeId", "TakeDown006", "title", "Lesson",
                                "categoryIds", List.of("cat-1")))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/registry/bulk/submit")
                        .header("Authorization", "Bearer fake-mod-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new BulkSubmitRequest(List.of(new SubmitRow(0,
                                "https://www.youtube.com/watch?v=TakeDown006", YouTubeContentType.VIDEO, VideoType.STANDARD,
                                meta, List.of("cat-1"))), "PENDING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.results[0].errorCode").value("DUPLICATE"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/admin/registry/videos/aaaa-TakeDown006/submission")
                        .header("Authorization", "Bearer fake-mod-token"))
                .andExpect(status().is4xxClientError());

        var docs = firestore.collection("videos").whereEqualTo("youtubeId", "TakeDown006").get()
                .get(5, java.util.concurrent.TimeUnit.SECONDS).getDocuments();
        org.junit.jupiter.api.Assertions.assertEquals(1, docs.size(), "no second doc");
        var kept = docs.get(0).toObject(com.albunyaan.tube.model.Video.class);
        org.junit.jupiter.api.Assertions.assertEquals("REJECTED", kept.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("REPORT", kept.getSource());
        org.junit.jupiter.api.Assertions.assertNull(kept.getSubmittedBy());
        org.junit.jupiter.api.Assertions.assertEquals(com.albunyaan.tube.model.ValidationStatus.ARCHIVED, kept.getValidationStatus());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("channel:UCtakedown0000000000000a"), kept.getTakeDownExclusions());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("TakeDown006"), firestore.collection("channels")
                .document("ch-takedown").get().get(5, java.util.concurrent.TimeUnit.SECONDS).get("excludedItems.videos"),
                "the report's exclusion stays");
        mvc.perform(get("/api/v1/videos/TakeDown006")).andExpect(status().isGone());
    }

    /** A parentless report on the id, resolved: the take-down (ContentReportService.archiveReportedContent). */
    private void takeDown(String youtubeId) throws Exception {
        var report = reports.submitReport(com.albunyaan.tube.model.ReportTargetType.VIDEO, youtubeId,
                List.of(com.albunyaan.tube.model.ReportReason.OTHER), "it", "it-" + youtubeId);
        reports.resolveReport(report.getId(), com.albunyaan.tube.model.ReportStatus.RESOLVED, "admin", null);
    }

    private com.albunyaan.tube.model.Video submission(String youtubeId, String source, String submittedBy) throws Exception {
        var video = new com.albunyaan.tube.model.Video(youtubeId);
        video.setId("aaaa-" + youtubeId);
        video.setTitle("Lesson");
        video.setStatus("PENDING");
        video.setSource(source);
        video.setSubmittedBy(submittedBy);
        video.setCategoryIds(List.of("cat-1"));
        firestore.collection("videos").document(video.getId()).set(video).get(5, java.util.concurrent.TimeUnit.SECONDS);
        return video;
    }

    private Map<String, Object> stored(String collection, String id) throws Exception {
        var snap = firestore.collection(collection).document(id).get().get(5, java.util.concurrent.TimeUnit.SECONDS);
        return snap.exists() ? snap.getData() : null;
    }

    /**
     * Stage 9 B23 (r10 re-review #3; B21's sibling 1): while a report's take-down archives a submission, its submitter
     * cannot withdraw (delete) it, whatever status an admin moves it to: the archive is the take-down's only hold, and
     * the withdraw's own transaction refuses it. Restore is the way out. Restore's CAS still deletes a take-down record,
     * and an unarchived submission is still its submitter's to withdraw.
     */
    @Test
    void aSubmissionAReportTookDownIsNotItsSubmittersToWithdraw() throws Exception {
        submission("TakeDown008", "MODERATOR", "mod-uid");
        takeDown("TakeDown008");
        stubAuthAs("admin-uid", "admin");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/registry/videos/aaaa-TakeDown008")
                        .header("Authorization", "Bearer fake-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))                  // "send it back to review"
                .andExpect(status().isOk());
        firestore.collection("channels").document("ch-td").set(Map.of("youtubeId", "UCtakedown0000000000000b", "name", "C",
                "status", "PENDING", "validationStatus", "ARCHIVED", "submittedBy", "mod-uid"))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        firestore.collection("playlists").document("pl-td").set(Map.of("youtubeId", "PLtakedown000000000000000000000000b",
                "title", "P", "status", "REQUEST_CHANGES", "validationStatus", "ARCHIVED", "submittedBy", "mod-uid"))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        submission("TakeDown010", "MODERATOR", "mod-uid");                       // control: not archived

        stubAuthAs("mod-uid", "moderator");
        for (String path : List.of("videos/aaaa-TakeDown008", "channels/ch-td", "playlists/pl-td")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/admin/registry/" + path + "/submission")
                            .header("Authorization", "Bearer fake-mod-token"))
                    .andExpect(status().isConflict());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/admin/registry/videos/aaaa-TakeDown010/submission")
                        .header("Authorization", "Bearer fake-mod-token"))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertEquals("ARCHIVED", stored("videos", "aaaa-TakeDown008").get("validationStatus"));
        org.junit.jupiter.api.Assertions.assertNotNull(stored("channels", "ch-td"));
        org.junit.jupiter.api.Assertions.assertNotNull(stored("playlists", "pl-td"));
        org.junit.jupiter.api.Assertions.assertNull(stored("videos", "aaaa-TakeDown010"));
        mvc.perform(get("/api/v1/videos/TakeDown008")).andExpect(status().isGone());

        var record = new com.albunyaan.tube.model.Video("TakeDown009");
        record.setId("aaaa-TakeDown009");
        record.setStatus("REJECTED");
        record.setSource("REPORT");
        record.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
        firestore.collection("videos").document(record.getId()).set(record).get(5, java.util.concurrent.TimeUnit.SECONDS);
        videoRepository.deleteByIdIfStatusIn(record.getId(), java.util.Set.of("REJECTED"));
        org.junit.jupiter.api.Assertions.assertNull(stored("videos", "aaaa-TakeDown009"), "Restore's CAS lifts a record");
    }

    /**
     * Stage 9 B23 (Cubic r11 #1; r10 re-review #2): a take-down of a submission under review keeps its review state, so
     * Restore gives it back to the approvals queue, and its importers follow the review (AWAITING, then APPROVED on the
     * queue's approve) instead of being stranded as AWAITING on a REJECTED doc no decision can reach.
     */
    @Test
    void aTakeDownOfASubmissionUnderReviewIsUndoneByRestoreAndItsImportersFollowTheReview() throws Exception {
        submission("TakeDown011", "USER_IMPORT", "user-uid");
        var row = firestore.collection("users").document("user-uid").collection("favorites").document("TakeDown011");
        row.set(Map.of("youtubeId", "TakeDown011", "approvalStatus", "AWAITING", "deleted", false, "updatedAt", 1000L))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);

        takeDown("TakeDown011");
        org.junit.jupiter.api.Assertions.assertEquals("PENDING", stored("videos", "aaaa-TakeDown011").get("status"),
                "the take-down keeps the review state");
        org.junit.jupiter.api.Assertions.assertEquals("ARCHIVED", stored("videos", "aaaa-TakeDown011").get("validationStatus"));

        var restored = validation.restoreContent("video", List.of("aaaa-TakeDown011"), "admin-uid", "admin");
        org.junit.jupiter.api.Assertions.assertEquals(1, restored.getSuccessCount());
        org.junit.jupiter.api.Assertions.assertEquals("PENDING", stored("videos", "aaaa-TakeDown011").get("status"));
        org.junit.jupiter.api.Assertions.assertEquals("VALID", stored("videos", "aaaa-TakeDown011").get("validationStatus"));
        org.junit.jupiter.api.Assertions.assertEquals("AWAITING", row.get().get(5, java.util.concurrent.TimeUnit.SECONDS)
                .getString("approvalStatus"));

        approvals.approve("aaaa-TakeDown011", new com.albunyaan.tube.dto.ApprovalRequestDto("ok"), "admin-uid", "admin");

        var after = row.get().get(5, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertEquals("APPROVED", after.getString("approvalStatus"));
        org.junit.jupiter.api.Assertions.assertNotEquals(Boolean.TRUE, after.getBoolean("deleted"));
    }

    /**
     * Stage 9 B24 (Cubic r12 #1): an approve that read the submission before a report took it down writes back the doc it
     * read; the take-down's ARCHIVED survives (APPROVED, still not served, until Restore). A re-add over a take-down
     * record (REJECTED) still lifts it.
     */
    @Test
    void anApproveReadBeforeATakeDownKeepsTheArchiveAndAReAddOverARecordStillLiftsIt() throws Exception {
        var stale = submission("TakeDown012", "MODERATOR", "mod-uid");    // what the approve read
        takeDown("TakeDown012");
        org.junit.jupiter.api.Assertions.assertEquals("ARCHIVED", stored("videos", "aaaa-TakeDown012").get("validationStatus"));

        stale.setStatus("APPROVED");
        videoRepository.saveIfStatus(stale, "PENDING");                     // as ApprovalService.approveVideo does

        org.junit.jupiter.api.Assertions.assertEquals("APPROVED", stored("videos", "aaaa-TakeDown012").get("status"));
        org.junit.jupiter.api.Assertions.assertEquals("ARCHIVED", stored("videos", "aaaa-TakeDown012").get("validationStatus"));
        mvc.perform(get("/api/v1/videos/TakeDown012")).andExpect(status().isGone());

        takeDown("TakeDown013");                                             // no doc: its take-down record
        var record = videoRepository.findByYoutubeId("TakeDown013").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("REJECTED", record.getStatus());
        var readd = new com.albunyaan.tube.model.Video("TakeDown013");
        readd.setId(record.getId());
        readd.setStatus("APPROVED");
        readd.setTitle("Lesson");
        reports.reAdd(readd);

        org.junit.jupiter.api.Assertions.assertNull(stored("videos", record.getId()).get("validationStatus"), "lifted");
        mvc.perform(get("/api/v1/videos/TakeDown013")).andExpect(status().isOk());
    }

    /**
     * §7.1 — Non-admin, non-moderator (plain USER role) must receive 403 Forbidden.
     */
    @Test
    void nonAdminNonModerator_isForbidden() throws Exception {
        stubAuthAs("user-uid", "user");

        var req = new BulkPreviewRequest(
                List.of("https://www.youtube.com/watch?v=AAAAAAAAAAA"));

        mvc.perform(post("/api/admin/registry/bulk/preview")
                        .header("Authorization", "Bearer fake-user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    /**
     * §7.1 — Preview endpoint rejects a request with 26 URLs (max is 25) with 400 Bad Request.
     */
    @Test
    void preview_rejects26urls_withValidationError() throws Exception {
        stubAuthAs("admin-uid", "admin");

        var twentySix = new ArrayList<String>();
        for (int i = 0; i < 26; i++) {
            twentySix.add("https://www.youtube.com/watch?v=AAAAAAAAAAA");
        }
        var req = new BulkPreviewRequest(twentySix);

        mvc.perform(post("/api/admin/registry/bulk/preview")
                        .header("Authorization", "Bearer fake-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
