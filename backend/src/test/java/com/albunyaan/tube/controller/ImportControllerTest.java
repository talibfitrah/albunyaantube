package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.importflow.ImportDisposition;
import com.albunyaan.tube.dto.importflow.ImportItem;
import com.albunyaan.tube.dto.importflow.ImportResolveRequest;
import com.albunyaan.tube.exception.GlobalExceptionHandler;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.SubmissionRateLimiter;
import com.albunyaan.tube.service.UserImportSubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ImportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ImportControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    ChannelRepository channelRepository;

    @MockBean
    PlaylistRepository playlistRepository;

    @MockBean
    VideoRepository videoRepository;

    @MockBean
    UserImportSubmissionService submissions;

    @MockBean
    SubmissionRateLimiter rateLimiter;

    @MockBean
    FirebaseAuth firebaseAuth;

    @MockBean
    UserRepository userRepository;

    ObjectMapper json = new ObjectMapper();

    private static final String TEST_UID   = "uid-test-1";
    private static final String TEST_EMAIL = "user@test.com";

    @BeforeEach
    void setUp() {
        FirebaseUserDetails principal = new FirebaseUserDetails(TEST_UID, TEST_EMAIL, "user", true);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        // Default: rate limiter allows all requests (null = no retry-after)
        when(rateLimiter.tryAcquireImport(any(), anyInt())).thenReturn(null);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── Test 1: four dispositions (APPROVED, PENDING, REJECTED, PENDING for UNKNOWN) ──

    /**
     * POST /api/account/import/resolve with four items:
     *   1. CHANNEL whose registry status is APPROVED  → disposition APPROVED + content non-null
     *   2. PLAYLIST whose registry status is PENDING  → disposition PENDING, content null
     *   3. VIDEO whose registry status is REJECTED    → disposition REJECTED, content null
     *   4. CHANNEL that is UNKNOWN (not in registry)  → triggers submit() → disposition PENDING
     */
    @Test
    void resolveReturnsFourDispositions() throws Exception {
        // item 1: APPROVED channel already in registry
        Channel approvedChannel = new Channel();
        approvedChannel.setYoutubeId("ch-approved");
        approvedChannel.setStatus("APPROVED");
        approvedChannel.setName("Approved Channel");
        when(channelRepository.findByYoutubeId("ch-approved"))
                .thenReturn(Optional.of(approvedChannel));

        // item 2: PENDING playlist already in registry
        Playlist pendingPlaylist = new Playlist();
        pendingPlaylist.setYoutubeId("pl-pending");
        pendingPlaylist.setStatus("PENDING");
        pendingPlaylist.setTitle("Pending Playlist");
        when(playlistRepository.findByYoutubeId("pl-pending"))
                .thenReturn(Optional.of(pendingPlaylist));

        // item 3: REJECTED video already in registry
        Video rejectedVideo = new Video();
        rejectedVideo.setYoutubeId("vid-rejected");
        rejectedVideo.setStatus("REJECTED");
        rejectedVideo.setTitle("Rejected Video");
        when(videoRepository.findByYoutubeId("vid-rejected"))
                .thenReturn(Optional.of(rejectedVideo));

        // item 4: UNKNOWN channel → not in registry → submit() returns PENDING
        when(channelRepository.findByYoutubeId("ch-unknown"))
                .thenReturn(Optional.empty());
        when(submissions.submit(any(ImportItem.class), eq(TEST_UID)))
                .thenReturn(ImportDisposition.PENDING);

        ImportResolveRequest req = new ImportResolveRequest(List.of(
                new ImportItem(YouTubeContentType.CHANNEL,  "ch-approved", "Approved Channel", null, null),
                new ImportItem(YouTubeContentType.PLAYLIST, "pl-pending",  "Pending Playlist",  null, null),
                new ImportItem(YouTubeContentType.VIDEO,    "vid-rejected","Rejected Video",    null, null),
                new ImportItem(YouTubeContentType.CHANNEL,  "ch-unknown",  "Unknown Channel",   null, null)
        ));

        mvc.perform(post("/api/account/import/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                // item 0: APPROVED with content
                .andExpect(jsonPath("$.results[0].disposition").value("APPROVED"))
                .andExpect(jsonPath("$.results[0].content").isNotEmpty())
                .andExpect(jsonPath("$.results[0].content.type").value("CHANNEL"))
                // item 1: PENDING, no content
                .andExpect(jsonPath("$.results[1].disposition").value("PENDING"))
                .andExpect(jsonPath("$.results[1].content").doesNotExist())
                // item 2: REJECTED, no content
                .andExpect(jsonPath("$.results[2].disposition").value("REJECTED"))
                .andExpect(jsonPath("$.results[2].content").doesNotExist())
                // item 3: UNKNOWN → submit() → PENDING, no content
                .andExpect(jsonPath("$.results[3].disposition").value("PENDING"))
                .andExpect(jsonPath("$.results[3].content").doesNotExist());

        // verify submit was called exactly once (only for the UNKNOWN item)
        verify(submissions, times(1)).submit(any(ImportItem.class), eq(TEST_UID));
    }

    // ── Test 2: submit() is called for an UNKNOWN item ────────────────────

    @Test
    void unknownItemTriggersSubmit() throws Exception {
        when(channelRepository.findByYoutubeId("ch-new")).thenReturn(Optional.empty());
        when(submissions.submit(any(ImportItem.class), eq(TEST_UID)))
                .thenReturn(ImportDisposition.PENDING);

        ImportResolveRequest req = new ImportResolveRequest(List.of(
                new ImportItem(YouTubeContentType.CHANNEL, "ch-new", "New Channel", null, null)
        ));

        mvc.perform(post("/api/account/import/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].disposition").value("PENDING"))
                .andExpect(jsonPath("$.results[0].youtubeId").value("ch-new"));

        verify(submissions).submit(any(ImportItem.class), eq(TEST_UID));
    }

    // ── Finding 3: existing PERSONAL item is grant-gated on the resolve short-circuit ──

    @Test
    void resolveExistingPersonalItem_nonGrantee_returnsPendingNoContent() throws Exception {
        Video v = new Video();
        v.setYoutubeId("vid-personal");
        v.setStatus("APPROVED");
        v.setVisibility("PERSONAL");
        v.setPersonalGrants(List.of("someone-else"));
        v.setTitle("Personal Video");
        when(videoRepository.findByYoutubeId("vid-personal")).thenReturn(Optional.of(v));

        ImportResolveRequest req = new ImportResolveRequest(List.of(
                new ImportItem(YouTubeContentType.VIDEO, "vid-personal", "Personal Video", null, null)
        ));

        mvc.perform(post("/api/account/import/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].disposition").value("PENDING"))
                .andExpect(jsonPath("$.results[0].content").doesNotExist());
        // existing doc → never creates a new submission
        verify(submissions, never()).submit(any(ImportItem.class), eq(TEST_UID));
    }

    @Test
    void resolveExistingPersonalItem_grantee_returnsApprovedWithContent() throws Exception {
        Video v = new Video();
        v.setYoutubeId("vid-personal");
        v.setStatus("APPROVED");
        v.setVisibility("PERSONAL");
        v.setPersonalGrants(List.of(TEST_UID));
        v.setTitle("Personal Video");
        when(videoRepository.findByYoutubeId("vid-personal")).thenReturn(Optional.of(v));

        ImportResolveRequest req = new ImportResolveRequest(List.of(
                new ImportItem(YouTubeContentType.VIDEO, "vid-personal", "Personal Video", null, null)
        ));

        mvc.perform(post("/api/account/import/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].disposition").value("APPROVED"))
                .andExpect(jsonPath("$.results[0].content").isNotEmpty());
    }

    // ── Test 3: unauthenticated request → 401 ────────────────────────────

    @Test
    void unauthenticatedReturns401() throws Exception {
        SecurityContextHolder.clearContext();
        // One valid item so @NotEmpty passes; principal is null because context was cleared.
        ImportResolveRequest req = new ImportResolveRequest(List.of(
                new ImportItem(YouTubeContentType.CHANNEL, "ch-anon", "Anon Channel", null, null)
        ));
        mvc.perform(post("/api/account/import/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // ── Test A6-a: daily import budget exceeded → 429 with remaining=0 ──────

    /**
     * When tryAcquireImport signals rejection (returns non-null retryAfterSec),
     * the controller must return HTTP 429 with a JSON body containing "message"
     * and "remaining" fields, and must NOT consume the budget or call submit().
     */
    @Test
    void budgetExceededReturns429WithRemaining() throws Exception {
        // Limiter signals the budget is exhausted: 86 400 seconds until reset
        when(rateLimiter.tryAcquireImport(eq(TEST_UID), anyInt())).thenReturn(86_400L);

        ImportResolveRequest req = new ImportResolveRequest(List.of(
                new ImportItem(YouTubeContentType.CHANNEL, "ch-over", "Over Budget", null, null)
        ));

        mvc.perform(post("/api/account/import/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.remaining").value(0));

        // Budget must NOT be consumed on rejection; submit must NOT be called
        verify(rateLimiter, times(1)).tryAcquireImport(eq(TEST_UID), anyInt());
        verify(submissions, never()).submit(any(), any());
    }

    // ── Test A6-b: request within budget → 200 and budget consumed ─────────

    /**
     * When tryAcquireImport returns null (allowed), the request succeeds (200)
     * and tryAcquireImport is called exactly once with the correct item count.
     */
    @Test
    void requestUnderBudgetSucceedsAndConsumesBudget() throws Exception {
        // Limiter allows (null = no retry-after)
        when(rateLimiter.tryAcquireImport(eq(TEST_UID), eq(1))).thenReturn(null);

        when(channelRepository.findByYoutubeId("ch-ok")).thenReturn(Optional.empty());
        when(submissions.submit(any(ImportItem.class), eq(TEST_UID)))
                .thenReturn(ImportDisposition.PENDING);

        ImportResolveRequest req = new ImportResolveRequest(List.of(
                new ImportItem(YouTubeContentType.CHANNEL, "ch-ok", "OK Channel", null, null)
        ));

        mvc.perform(post("/api/account/import/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].disposition").value("PENDING"));

        // Budget must have been checked (and consumed) exactly once with count=1
        verify(rateLimiter, times(1)).tryAcquireImport(eq(TEST_UID), eq(1));
    }

    // ── Test 4: service exception on one item → ERROR, others unaffected ─

    @Test
    void serviceExceptionOnOneItemYieldsErrorOthersUnaffected() throws Exception {
        // First item: lookup throws
        when(channelRepository.findByYoutubeId("ch-boom"))
                .thenThrow(new ExecutionException("Firestore timeout", new RuntimeException()));

        // Second item: normal APPROVED channel
        Channel approvedChannel = new Channel();
        approvedChannel.setYoutubeId("ch-ok");
        approvedChannel.setStatus("APPROVED");
        approvedChannel.setName("Good Channel");
        when(channelRepository.findByYoutubeId("ch-ok"))
                .thenReturn(Optional.of(approvedChannel));

        ImportResolveRequest req = new ImportResolveRequest(List.of(
                new ImportItem(YouTubeContentType.CHANNEL, "ch-boom", "Boom Channel", null, null),
                new ImportItem(YouTubeContentType.CHANNEL, "ch-ok",   "Good Channel", null, null)
        ));

        mvc.perform(post("/api/account/import/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                // failing item → ERROR, no content
                .andExpect(jsonPath("$.results[0].disposition").value("ERROR"))
                .andExpect(jsonPath("$.results[0].content").doesNotExist())
                // passing item → APPROVED with content
                .andExpect(jsonPath("$.results[1].disposition").value("APPROVED"))
                .andExpect(jsonPath("$.results[1].content").isNotEmpty());
    }
}
