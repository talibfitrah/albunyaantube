package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.LiveChannelService;
import com.albunyaan.tube.service.LiveTabReader;
import com.albunyaan.tube.service.LiveTabResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec §12 IT1: one test per §7 write path through MockMvc, with the Live tab mocked (LiveTabReader). Each test uses
 * its own channel and video ids: the context, and with it LiveChannelService's 60 s memo, is shared.
 */
class LiveCategoryGuardIT extends BaseIntegrationTest {

    @MockBean FirebaseAuth firebaseAuth;
    @MockBean LiveTabReader reader;
    @MockBean com.albunyaan.tube.service.YouTubeGateway gateway;   // no NewPipe fetch ever leaves a test (row 10's submit)
    @Autowired ObjectMapper json;
    @Autowired ChannelRepository channels;
    @Autowired PlaylistRepository playlists;
    @Autowired VideoRepository videos;
    @Autowired LiveChannelService live;
    @Autowired com.albunyaan.tube.service.CategoryMappingService categoryMapping;

    static final String LIVE = "it1-live", PLAIN = "it1-plain";

    @Override
    protected String[] getCollectionsToClean() {
        return new String[]{"categories", "channels", "playlists", "videos", "users", "audit_logs",
                "category_content_order", "live_channel_state", "validation_runs"};
    }

    @BeforeEach
    void categories() throws Exception {
        doc("categories", LIVE, Map.of("name", "Live", "type", "LIVE", "topLevel", true));
        doc("categories", PLAIN, Map.of("name", "Plain", "topLevel", true));
        categoryMapping.refreshCategoryCache();          // the simple import maps names through an in-memory cache
    }

    void as(String role) throws Exception {
        FirebaseToken token = Mockito.mock(FirebaseToken.class);
        when(token.getUid()).thenReturn(role + "-uid");
        when(token.getEmail()).thenReturn(role + "@test.com");
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        when(token.getClaims()).thenReturn(claims);
        when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);
        when(firebaseAuth.verifyIdToken(anyString(), anyBoolean())).thenReturn(token);
    }

    static String uc(String tag) {
        return ("UC" + tag + "0000000000000000000000").substring(0, 24);
    }

    void liveOn(String uc, String... ids) {
        when(reader.read(uc)).thenReturn(LiveTabResult.ok(
                Arrays.stream(ids).map(i -> new LiveTabResult.LiveItem(i, "t")).toList(), 1));
    }

    ResultActions send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer t").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    void doc(String collection, String id, Map<String, Object> fields) throws Exception {
        firestore.collection(collection).document(id).set(fields).get(5, TimeUnit.SECONDS);
    }

    // ---- Row 1: single add channel; resubmit ----

    @Test
    void row1_aModeratorAddingAChannelToLiveIs403() throws Exception {
        as("moderator");
        send(post("/api/admin/registry/channels"), Map.of("youtubeId", uc("r1m"), "name", "C", "categoryIds", List.of(LIVE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_ADMIN_ONLY"));
        assertThat(countDocuments("channels")).isZero();
    }

    @Test
    void row1_anAdminAddingAChannelSeedsItsState() throws Exception {
        as("admin");
        liveOn(uc("r1a"), "it1r1alive1");
        send(post("/api/admin/registry/channels"),
                Map.of("youtubeId", uc("r1a"), "name", "C", "categoryIds", List.of(LIVE), "status", "APPROVED"))
                .andExpect(status().isCreated());
        assertThat(live.state(uc("r1a")).getStatus()).isEqualTo("LIVE");
    }

    @Test
    void row1_aModeratorResubmitAddingLiveIs403() throws Exception {
        doc("channels", "ch-r1r", Map.of("youtubeId", uc("r1r"), "name", "C", "status", "REQUEST_CHANGES",
                "submittedBy", "moderator-uid", "categoryIds", List.of(PLAIN)));
        as("moderator");
        send(post("/api/admin/registry/channels"), Map.of("youtubeId", uc("r1r"), "name", "C", "categoryIds", List.of(LIVE)))
                .andExpect(status().isForbidden());
        assertThat(channels.findById("ch-r1r").orElseThrow().getStatus()).isEqualTo("REQUEST_CHANGES");
    }

    @Test
    void row1_anOverLongResubmitNoteIs400BeforeAnyYouTubeRead() throws Exception {
        doc("channels", "ch-r1n", Map.of("youtubeId", uc("r1n"), "name", "C", "status", "REQUEST_CHANGES",
                "submittedBy", "admin-uid", "categoryIds", List.of(PLAIN)));
        as("admin");
        liveOn(uc("r1n"), "it1r1nlive1");
        send(post("/api/admin/registry/channels"), Map.of("youtubeId", uc("r1n"), "name", "C",
                "categoryIds", List.of(LIVE), "submitterNote", "x".repeat(1001)))
                .andExpect(status().isBadRequest());
        verify(reader, never()).read(uc("r1n"));                     // no seed, no Live-tab read
        assertThat(channels.findById("ch-r1n").orElseThrow().getStatus()).isEqualTo("REQUEST_CHANGES");
    }

    /** R7: the cap counts member docs, not only order rows (admin PUTs and imports write no order row). */
    @Test
    void row1_thirtyMemberDocsWithoutOrderRowsRefuseThe31stAsFull() throws Exception {
        for (int i = 0; i < 30; i++) {
            doc("channels", "ch-cap" + i, Map.of("youtubeId", uc(String.format("c%02d", i)), "name", "C",
                    "status", "APPROVED", "categoryIds", List.of(LIVE), "createdAt", Timestamp.now()));
        }
        as("admin");
        liveOn(uc("c30"), "it1c30live1");
        send(post("/api/admin/registry/channels"),
                Map.of("youtubeId", uc("c30"), "name", "C", "categoryIds", List.of(LIVE), "status", "APPROVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_FULL"));
        assertThat(countDocuments("channels")).isEqualTo(30);
    }

    // ---- Row 2: admin edit channel (ids and status) ----

    @Test
    void row2_approvingAPendingChannelHoldingLiveSeedsIt() throws Exception {
        doc("channels", "ch-r2", Map.of("youtubeId", uc("r2"), "name", "C", "status", "PENDING", "categoryIds", List.of(LIVE)));
        as("admin");
        liveOn(uc("r2"), "it1r2live01");
        send(put("/api/admin/registry/channels/ch-r2"), Map.of("name", "C", "status", "APPROVED", "categoryIds", List.of(LIVE)))
                .andExpect(status().isOk());
        assertThat(live.state(uc("r2"))).isNotNull();
    }

    // ---- Row 3: toggle channel -> APPROVED ----

    @Test
    void row3_togglingAChannelInLiveToApprovedSeedsIt() throws Exception {
        doc("channels", "ch-r3", Map.of("youtubeId", uc("r3"), "name", "C", "status", "PENDING", "categoryIds", List.of(LIVE)));
        as("admin");
        liveOn(uc("r3"), "it1r3live01");
        send(patch("/api/admin/registry/channels/ch-r3/toggle"), Map.of()).andExpect(status().isOk());
        assertThat(live.state(uc("r3"))).isNotNull();
    }

    // ---- Row 4: single add playlist ----

    @Test
    void row4_anAdminAddingAPlaylistToLiveIs400() throws Exception {
        as("admin");
        send(post("/api/admin/registry/playlists"), Map.of("youtubeId", "PLit1r400000000000000000000000000",
                "title", "P", "categoryIds", List.of(LIVE), "status", "APPROVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_TYPE_NOT_ALLOWED"));
        assertThat(countDocuments("playlists")).isZero();
    }

    @Test
    void row4_aModeratorResubmitAddingLiveIs403() throws Exception {
        doc("playlists", "pl-r4r", Map.of("youtubeId", "PLit1r4r0000000000000000000000000", "title", "P",
                "status", "REQUEST_CHANGES", "submittedBy", "moderator-uid", "categoryIds", List.of(PLAIN)));
        as("moderator");
        send(post("/api/admin/registry/playlists"),
                Map.of("youtubeId", "PLit1r4r0000000000000000000000000", "title", "P", "categoryIds", List.of(LIVE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_ADMIN_ONLY"));
        assertThat(playlists.findById("pl-r4r").orElseThrow().getStatus()).isEqualTo("REQUEST_CHANGES");
    }

    // ---- Row 5: admin edit playlist ----

    @Test
    void row5_approvingAPendingPlaylistHoldingLiveIs400() throws Exception {
        doc("playlists", "pl-r5", Map.of("youtubeId", "PLit1r500000000000000000000000000", "title", "P",
                "status", "PENDING", "categoryIds", List.of(LIVE)));
        as("admin");
        send(put("/api/admin/registry/playlists/pl-r5"), Map.of("title", "P", "status", "APPROVED", "categoryIds", List.of(LIVE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_TYPE_NOT_ALLOWED"));
        assertThat(playlists.findById("pl-r5").orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    // ---- Row 6: toggle playlist -> APPROVED ----

    @Test
    void row6_togglingAPlaylistInLiveToApprovedIs400() throws Exception {
        doc("playlists", "pl-r6", Map.of("youtubeId", "PLit1r600000000000000000000000000", "title", "P",
                "status", "PENDING", "categoryIds", List.of(LIVE)));
        as("admin");
        send(patch("/api/admin/registry/playlists/pl-r6/toggle"), Map.of()).andExpect(status().isBadRequest());
        assertThat(playlists.findById("pl-r6").orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    // ---- Row 7: single add video ----

    @Test
    void row7_anAdminAddingALiveVideoPersistsTheVerifiedChannel() throws Exception {
        as("admin");
        liveOn(uc("r7a"), "it1r7avid01");
        send(post("/api/admin/registry/videos"), Map.of("youtubeId", "it1r7avid01", "title", "V",
                "categoryIds", List.of(LIVE), "status", "APPROVED", "channelId", uc("r7a")))
                .andExpect(status().isCreated());
        assertThat(videos.findByYoutubeId("it1r7avid01").orElseThrow().getChannelId()).isEqualTo(uc("r7a"));
    }

    @Test
    void row7_anAdminAddingANonLiveVideoIs400NotLive() throws Exception {
        as("admin");
        liveOn(uc("r7b"), "it1r7bother");
        send(post("/api/admin/registry/videos"), Map.of("youtubeId", "it1r7bvid01", "title", "V",
                "categoryIds", List.of(LIVE), "status", "APPROVED", "channelId", uc("r7b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_LINK_NOT_LIVE"))
                .andExpect(jsonPath("$.details.channelId").value(uc("r7b")));
        assertThat(countDocuments("videos")).isZero();
    }

    @Test
    void row7_aNonUcBodyChannelIsResolvedAndTheVerifiedOneStored() throws Exception {
        as("admin");
        when(reader.resolveChannelId("it1r7cvid01")).thenReturn(Optional.of(uc("r7c")));
        liveOn(uc("r7c"), "it1r7cvid01");
        send(post("/api/admin/registry/videos"), Map.of("youtubeId", "it1r7cvid01", "title", "V",
                "categoryIds", List.of(LIVE), "status", "APPROVED", "channelId", "@it1r7c"))
                .andExpect(status().isCreated());
        assertThat(videos.findByYoutubeId("it1r7cvid01").orElseThrow().getChannelId()).isEqualTo(uc("r7c"));
    }

    @Test
    void row7_aModeratorResubmitAddingLiveIs403() throws Exception {
        doc("videos", "doc-r7r", Map.of("youtubeId", "it1r7rvid01", "title", "V", "status", "REQUEST_CHANGES",
                "submittedBy", "moderator-uid", "channelId", uc("r7r"), "categoryIds", List.of(PLAIN)));
        as("moderator");
        send(post("/api/admin/registry/videos"), Map.of("youtubeId", "it1r7rvid01", "title", "V", "categoryIds", List.of(LIVE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_ADMIN_ONLY"));
        assertThat(videos.findById("doc-r7r").orElseThrow().getStatus()).isEqualTo("REQUEST_CHANGES");
    }

    /** D10: a blank (or non-UC) body channel on a resubmit falls back to the stored doc's UC id. */
    @Test
    void row7_aBlankBodyChannelOnAResubmitUsesTheStoredOne() throws Exception {
        doc("videos", "doc-r7d", Map.of("youtubeId", "it1r7dvid01", "title", "V", "status", "REQUEST_CHANGES",
                "submittedBy", "admin-uid", "channelId", uc("r7d"), "categoryIds", List.of(PLAIN)));
        as("admin");
        liveOn(uc("r7d"), "it1r7dvid01");
        send(post("/api/admin/registry/videos"), Map.of("youtubeId", "it1r7dvid01", "title", "V",
                "categoryIds", List.of(LIVE), "channelId", ""))
                .andExpect(status().isOk());
        Video stored = videos.findById("doc-r7d").orElseThrow();
        assertThat(stored.getChannelId()).isEqualTo(uc("r7d"));
        assertThat(stored.getStatus()).isEqualTo("PENDING");
    }

    // ---- Row 8: admin edit video (ids and status) ----

    @Test
    void row8_aStoredDocWithNoChannelIsResolvedAndAdded() throws Exception {
        doc("videos", "doc-r8a", Map.of("youtubeId", "it1r8avid01", "title", "V", "status", "APPROVED", "categoryIds", List.of()));
        as("admin");
        when(reader.resolveChannelId("it1r8avid01")).thenReturn(Optional.of(uc("r8a")));
        liveOn(uc("r8a"), "it1r8avid01");
        send(put("/api/admin/registry/videos/doc-r8a"), Map.of("categoryIds", List.of(LIVE))).andExpect(status().isOk());
        assertThat(videos.findById("doc-r8a").orElseThrow().getChannelId()).isEqualTo(uc("r8a"));
    }

    @Test
    void row8_approvingAPendingNonLiveVideoHoldingLiveIs400() throws Exception {
        doc("videos", "doc-r8b", Map.of("youtubeId", "it1r8bvid01", "title", "V", "status", "PENDING",
                "channelId", uc("r8b"), "categoryIds", List.of(LIVE)));
        as("admin");
        liveOn(uc("r8b"), "it1r8bother");
        send(put("/api/admin/registry/videos/doc-r8b"), Map.of("status", "APPROVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_LINK_NOT_LIVE"));
        assertThat(videos.findById("doc-r8b").orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    // ---- Row 9: toggle video -> APPROVED ----

    @Test
    void row9_togglingANonLiveVideoInLiveToApprovedIs400() throws Exception {
        doc("videos", "doc-r9", Map.of("youtubeId", "it1r9vid001", "title", "V", "status", "PENDING",
                "channelId", uc("r9"), "categoryIds", List.of(LIVE)));
        as("admin");
        liveOn(uc("r9"), "it1r9other1");
        send(patch("/api/admin/registry/videos/doc-r9/toggle"), Map.of()).andExpect(status().isBadRequest());
        assertThat(videos.findById("doc-r9").orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void row9_togglingALiveVideoToApprovedStoresTheVerifiedChannel() throws Exception {
        doc("videos", "doc-r9b", Map.of("youtubeId", "it1r9bvid01", "title", "V", "status", "PENDING",
                "categoryIds", List.of(LIVE)));
        as("admin");
        when(reader.resolveChannelId("it1r9bvid01")).thenReturn(Optional.of(uc("r9b")));
        liveOn(uc("r9b"), "it1r9bvid01");
        send(patch("/api/admin/registry/videos/doc-r9b/toggle"), Map.of()).andExpect(status().isOk());
        Video stored = videos.findById("doc-r9b").orElseThrow();
        assertThat(stored.getChannelId()).isEqualTo(uc("r9b"));
        assertThat(stored.getStatus()).isEqualTo("APPROVED");
    }

    // ---- Row 10: bulk submit (whole request, D9) ----

    @Test
    void row10_oneBadRowRefusesTheWholeBulkSubmitAndListsIt() throws Exception {
        as("admin");
        liveOn(uc("r10"), "it1r10live1");
        String playlistId = "PLit1r1000000000000000000000000000";
        Map<String, Object> channelRow = Map.of("rowIndex", 0,
                "originalUrl", "https://www.youtube.com/channel/" + uc("r10"), "detectedType", "CHANNEL",
                "metadata", Map.of("youtubeId", uc("r10"), "title", "C"), "categoryIds", List.of(LIVE));
        Map<String, Object> playlistRow = Map.of("rowIndex", 1,
                "originalUrl", "https://www.youtube.com/playlist?list=" + playlistId, "detectedType", "PLAYLIST",
                "metadata", Map.of("youtubeId", playlistId, "title", "P"), "categoryIds", List.of(LIVE));
        send(post("/api/admin/registry/bulk/submit"), Map.of("rows", List.of(channelRow, playlistRow), "status", "APPROVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_TYPE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.details.offenders[0].ref").value("1"));
        assertThat(countDocuments("channels")).isZero();
        assertThat(countDocuments("playlists")).isZero();
    }

    // ---- Row 11: approve with categoryOverride ----

    @Test
    void row11_approvingAPlaylistIntoLiveIs400AndItStaysPending() throws Exception {
        doc("playlists", "pl-r11", Map.of("youtubeId", "PLit1r1100000000000000000000000000", "title", "P",
                "status", "PENDING", "submittedBy", "moderator-uid", "categoryIds", List.of(PLAIN)));
        as("admin");
        send(post("/api/admin/approvals/pl-r11/approve"), Map.of("categoryOverride", LIVE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_TYPE_NOT_ALLOWED"));
        assertThat(playlists.findById("pl-r11").orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    // ---- Row 12: library bulk assign (whole request) ----

    @Test
    void row12_aNonLiveVideoRefusesTheWholeAssign() throws Exception {
        doc("channels", "ch-r12", Map.of("youtubeId", uc("r12c"), "name", "C", "status", "APPROVED", "categoryIds", List.of(PLAIN)));
        doc("videos", "doc-r12", Map.of("youtubeId", "it1r12vid01", "title", "V", "status", "APPROVED",
                "channelId", uc("r12v"), "categoryIds", List.of(PLAIN)));
        as("admin");
        liveOn(uc("r12c"), "it1r12live1");
        liveOn(uc("r12v"), "it1r12other");
        send(post("/api/admin/content/bulk/assign-categories"), Map.of(
                "items", List.of(Map.of("type", "channel", "id", "ch-r12"), Map.of("type", "video", "id", "doc-r12")),
                "categoryIds", List.of(LIVE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_LINK_NOT_LIVE"));
        assertThat(channels.findById("ch-r12").orElseThrow().getCategoryIds()).containsExactly(PLAIN);
    }

    // ---- Row 13: library bulk approve (raw status batch) ----

    @Test
    void row13_bulkApprovingAPendingPlaylistInLiveIs400AndNothingIsApproved() throws Exception {
        doc("playlists", "pl-r13", Map.of("youtubeId", "PLit1r1300000000000000000000000000", "title", "P",
                "status", "PENDING", "categoryIds", List.of(LIVE)));
        as("admin");
        send(post("/api/admin/content/bulk/approve"), Map.of("items", List.of(Map.of("type", "playlist", "id", "pl-r13"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_TYPE_NOT_ALLOWED"));
        assertThat(playlists.findById("pl-r13").orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    // ---- Row 14: sort screen "add to category" ----

    @Test
    void row14_addingAPlaylistToLiveOnTheSortScreenIs400WithItsCode() throws Exception {
        doc("playlists", "pl-r14", Map.of("youtubeId", "PLit1r1400000000000000000000000000", "title", "P",
                "status", "APPROVED", "categoryIds", List.of()));
        as("admin");
        send(post("/api/admin/sort/categories/" + LIVE + "/content/add"),
                Map.of("items", List.of(Map.of("contentId", "pl-r14", "contentType", "playlist"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_TYPE_NOT_ALLOWED"));
        assertThat(playlists.findById("pl-r14").orElseThrow().getCategoryIds()).isEmpty();
    }

    // ---- Fix round 1 ----

    /** Row 10 is the only row here a moderator reaches: a LIVE row refuses the whole submit, and nothing is written. */
    @Test
    void row10_aModeratorBulkSubmitWithALiveRowIs403AndWritesNothing() throws Exception {
        as("moderator");
        Map<String, Object> row = Map.of("rowIndex", 0,
                "originalUrl", "https://www.youtube.com/channel/" + uc("r10m"), "detectedType", "CHANNEL",
                "metadata", Map.of("youtubeId", uc("r10m"), "title", "C"), "categoryIds", List.of(LIVE));
        send(post("/api/admin/registry/bulk/submit"), Map.of("rows", List.of(row)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_ADMIN_ONLY"));
        assertThat(countDocuments("channels")).isZero();
    }

    /** A row that fails the URL round-trip is never written, so the guard neither seeds nor counts it. */
    @Test
    void row10_aHandleUrlRowSeedsNoState() throws Exception {
        as("admin");
        liveOn(uc("r10h"), "it1r10hlive");
        Map<String, Object> row = Map.of("rowIndex", 0,
                "originalUrl", "https://www.youtube.com/@it1r10h", "detectedType", "CHANNEL",
                "metadata", Map.of("youtubeId", uc("r10h"), "title", "C"), "categoryIds", List.of(LIVE));
        send(post("/api/admin/registry/bulk/submit"), Map.of("rows", List.of(row), "status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].errorCode").value("INVALID_URL"));
        verify(reader, never()).read(uc("r10h"));
        assertThat(live.state(uc("r10h"))).isNull();
    }

    /** The guard's offender refs and verified channels are keyed by rowIndex, so it must be unique. */
    @Test
    void row10_aDuplicateRowIndexIs400() throws Exception {
        as("admin");
        Map<String, Object> first = Map.of("rowIndex", 0,
                "originalUrl", "https://www.youtube.com/channel/" + uc("r10d1"), "detectedType", "CHANNEL",
                "metadata", Map.of("youtubeId", uc("r10d1"), "title", "C"), "categoryIds", List.of(PLAIN));
        Map<String, Object> second = Map.of("rowIndex", 0,
                "originalUrl", "https://www.youtube.com/channel/" + uc("r10d2"), "detectedType", "CHANNEL",
                "metadata", Map.of("youtubeId", uc("r10d2"), "title", "C"), "categoryIds", List.of(PLAIN));
        send(post("/api/admin/registry/bulk/submit"), Map.of("rows", List.of(first, second)))
                .andExpect(status().isBadRequest());
        assertThat(countDocuments("channels")).isZero();
    }

    /** D13: approving a live video stores the verified channel. */
    @Test
    void row11_approvingALiveVideoStoresTheVerifiedChannel() throws Exception {
        doc("videos", "doc-r11v", Map.of("youtubeId", "it1r11vid01", "title", "V", "status", "PENDING",
                "submittedBy", "moderator-uid", "categoryIds", List.of(LIVE)));
        as("admin");
        when(reader.resolveChannelId("it1r11vid01")).thenReturn(Optional.of(uc("r11v")));
        liveOn(uc("r11v"), "it1r11vid01");
        send(post("/api/admin/approvals/doc-r11v/approve"), Map.of()).andExpect(status().isOk());
        Video stored = videos.findById("doc-r11v").orElseThrow();
        assertThat(stored.getChannelId()).isEqualTo(uc("r11v"));
        assertThat(stored.getStatus()).isEqualTo("APPROVED");
    }

    /** D13: assigning LIVE to a live stored video with no channel stores the verified one. */
    @Test
    void row12_assigningALiveStoredVideoWithNoChannelStoresTheVerifiedOne() throws Exception {
        doc("videos", "doc-r12b", Map.of("youtubeId", "it1r12bvid1", "title", "V", "status", "APPROVED",
                "categoryIds", List.of(PLAIN)));
        as("admin");
        when(reader.resolveChannelId("it1r12bvid1")).thenReturn(Optional.of(uc("r12b")));
        liveOn(uc("r12b"), "it1r12bvid1");
        send(post("/api/admin/content/bulk/assign-categories"), Map.of(
                "items", List.of(Map.of("type", "video", "id", "doc-r12b")), "categoryIds", List.of(LIVE)))
                .andExpect(status().isOk());
        Video stored = videos.findById("doc-r12b").orElseThrow();
        assertThat(stored.getChannelId()).isEqualTo(uc("r12b"));
        assertThat(stored.getCategoryIds()).containsExactly(LIVE);
    }

    /** Bulk approve forces PUBLIC, so an APPROVED but PERSONAL doc holding LIVE is a publish. */
    @Test
    void row13_bulkApprovingAPersonalPlaylistInLiveIs400() throws Exception {
        doc("playlists", "pl-r13p", Map.of("youtubeId", "PLit1r13p000000000000000000000000", "title", "P",
                "status", "APPROVED", "visibility", "PERSONAL", "categoryIds", List.of(LIVE)));
        as("admin");
        send(post("/api/admin/content/bulk/approve"), Map.of("items", List.of(Map.of("type", "playlist", "id", "pl-r13p"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_TYPE_NOT_ALLOWED"));
        assertThat(playlists.findById("pl-r13p").orElseThrow().getVisibility()).isEqualTo("PERSONAL");
    }

    /** D13: a sort-screen add of a live video stores the verified channel. */
    @Test
    void row14_aSortAddOfALiveVideoStoresTheVerifiedChannel() throws Exception {
        doc("videos", "doc-r14v", Map.of("youtubeId", "it1r14vid01", "title", "V", "status", "APPROVED",
                "categoryIds", List.of()));
        as("admin");
        when(reader.resolveChannelId("it1r14vid01")).thenReturn(Optional.of(uc("r14v")));
        liveOn(uc("r14v"), "it1r14vid01");
        send(post("/api/admin/sort/categories/" + LIVE + "/content/add"),
                Map.of("items", List.of(Map.of("contentId", "doc-r14v", "contentType", "video"))))
                .andExpect(status().isOk());
        Video stored = videos.findById("doc-r14v").orElseThrow();
        assertThat(stored.getChannelId()).isEqualTo(uc("r14v"));
        assertThat(stored.getCategoryIds()).containsExactly(LIVE);
    }

    // ---- Row 15: JSON import (ImportExportService.importAll) ----

    private ResultActions importFile(String path, String jsonBody, String param, String value) throws Exception {
        return mvc.perform(multipart(path)
                .file(new MockMultipartFile("file", "import.json", "application/json", jsonBody.getBytes(StandardCharsets.UTF_8)))
                .param(param, value)
                .header("Authorization", "Bearer t"));
    }

    @Test
    void row15_aJsonImportOfAPlaylistIntoLiveIsRefusedWhole() throws Exception {
        as("admin");
        String body = json.writeValueAsString(Map.of("playlists", List.of(Map.of("id", "pl-r15",
                "youtubeId", "PLit1r1500000000000000000000000000", "title", "P", "status", "APPROVED", "categoryIds", List.of(LIVE)))));
        importFile("/api/admin/import-export/import", body, "mergeStrategy", "OVERWRITE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_TYPE_NOT_ALLOWED"));
        assertThat(countDocuments("playlists")).isZero();
    }

    @Test
    void row15_aJsonImportNeverSetsOrClearsTheLiveType() throws Exception {
        as("admin");
        String body = "{\"categories\":[{\"id\":\"" + LIVE + "\",\"name\":\"Live\",\"type\":null},"
                + "{\"id\":\"it1-new\",\"name\":\"New\",\"type\":\"LIVE\"}]}";
        importFile("/api/admin/import-export/import", body, "mergeStrategy", "OVERWRITE")
                .andExpect(status().isOk());
        assertThat(firestore.collection("categories").document(LIVE).get().get().getString("type")).isEqualTo("LIVE");
        assertThat(firestore.collection("categories").document("it1-new").get().get().getString("type")).isNull();
    }

    // ---- Row 16: simple import (sync refuses the request; async fails its run before the first write) ----

    @Test
    void row16_aSimpleImportRowPuttingAPlaylistIntoLiveIsRefusedWhole() throws Exception {
        as("admin");
        String body = "[{}, {\"PLit1r1600000000000000000000000000\": \"P|Live\"}, {}]";
        importFile("/api/admin/import-export/import/simple", body, "defaultStatus", "APPROVED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_TYPE_NOT_ALLOWED"));
        assertThat(countDocuments("playlists")).isZero();
    }

    /** D9 + K11 (pre-flight M8): the async import never checks on the request thread; its run fails before any write. */
    @Test
    void row16_theAsyncSimpleImportFailsItsRunBeforeAnyWrite() throws Exception {
        as("admin");
        String body = "[{}, {\"PLit1r16a000000000000000000000000\": \"P|Live\"}, {}]";
        String runId = json.readTree(importFile("/api/admin/import-export/import/simple/async", body, "defaultStatus", "APPROVED")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).get("runId").asText();
        var run = firestore.collection("validation_runs").document(runId);
        for (int i = 0; i < 100 && !"FAILED".equals(run.get().get().getString("status")); i++) Thread.sleep(100);

        var doc = run.get().get();
        assertThat(doc.getString("status")).isEqualTo("FAILED");
        assertThat(doc.getString("currentPhase")).isEqualTo("LIVE_CATEGORY_REFUSED");
        assertThat(doc.get("details.code")).isEqualTo("LIVE_CATEGORY_TYPE_NOT_ALLOWED");
        assertThat(countDocuments("playlists")).isZero();
    }

    // ---- Row 17: category create/update (shape and members) ----

    @Test
    void row17_aLiveCategoryWithAParentIsRefusedShape() throws Exception {
        as("admin");
        send(post("/api/admin/categories"), Map.of("name", "Live kids", "parentCategoryId", PLAIN, "type", "LIVE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_SHAPE"));
    }

    @Test
    void row17_settingLiveOnACategoryHoldingAPlaylistIsRefusedMembersInvalid() throws Exception {
        doc("playlists", "pl-r17", Map.of("youtubeId", "PLit1r1700000000000000000000000000", "title", "P",
                "status", "APPROVED", "categoryIds", List.of(PLAIN), "createdAt", Timestamp.now()));   // members() orders by createdAt
        as("admin");
        send(put("/api/admin/categories/" + PLAIN), Map.of("name", "Plain", "type", "LIVE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_MEMBERS_INVALID"))
                .andExpect(jsonPath("$.details.offenders[0].ref").value("pl-r17"));
        assertThat(firestore.collection("categories").document(PLAIN).get().get().getString("type")).isNull();
    }

    // ---- Task 10 fix round 1: row 15 (identity, D8, SKIP and MERGE, the verified channel) ----

    private ResultActions importJson(Object file, String mergeStrategy) throws Exception {
        return importFile("/api/admin/import-export/import", json.writeValueAsString(file), "mergeStrategy", mergeStrategy);
    }

    /** A file that keeps a LIVE doc's id but swaps in another youtubeId adds a new link, so it is checked as one. */
    @Test
    void row15_aJsonImportSwappingTheYoutubeIdOfALiveDocIsChecked() throws Exception {
        doc("videos", "doc-r15s", Map.of("youtubeId", "it1r15svid1", "title", "V", "status", "APPROVED",
                "channelId", uc("r15s"), "categoryIds", List.of(LIVE), "createdAt", Timestamp.now()));
        as("admin");
        liveOn(uc("r15s"), "it1r15svid1");
        importJson(Map.of("videos", List.of(Map.of("id", "doc-r15s", "youtubeId", "it1r15sother", "title", "V",
                "status", "APPROVED", "channelId", uc("r15s"), "categoryIds", List.of(LIVE)))), "OVERWRITE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_LINK_NOT_LIVE"));
        assertThat(videos.findById("doc-r15s").orElseThrow().getYoutubeId()).isEqualTo("it1r15svid1");
    }

    /** D13: a JSON-imported live video is stored with the verified channel, not the file's. */
    @Test
    void row15_aJsonImportedLiveVideoStoresTheVerifiedChannel() throws Exception {
        as("admin");
        when(reader.resolveChannelId("it1r15cvid1")).thenReturn(Optional.of(uc("r15c")));
        liveOn(uc("r15c"), "it1r15cvid1");
        importJson(Map.of("videos", List.of(Map.of("id", "doc-r15c", "youtubeId", "it1r15cvid1", "title", "V",
                "status", "APPROVED", "channelId", "@it1r15c", "categoryIds", List.of(LIVE)))), "OVERWRITE")
                .andExpect(status().isOk());
        assertThat(videos.findById("doc-r15c").orElseThrow().getChannelId()).isEqualTo(uc("r15c"));
    }

    /** SKIP never writes a stored doc, so a file row naming LIVE for it is no add and is not refused. */
    @Test
    void row15_skipLeavesAStoredDocAloneAndIsNotRefused() throws Exception {
        doc("playlists", "pl-r15k", Map.of("youtubeId", "PLit1r15k000000000000000000000000", "title", "P",
                "status", "APPROVED", "categoryIds", List.of(PLAIN), "createdAt", Timestamp.now()));
        as("admin");
        importJson(Map.of("playlists", List.of(Map.of("id", "pl-r15k", "youtubeId", "PLit1r15k000000000000000000000000",
                "title", "P", "status", "APPROVED", "categoryIds", List.of(LIVE)))), "SKIP")
                .andExpect(status().isOk());
        assertThat(playlists.findById("pl-r15k").orElseThrow().getCategoryIds()).containsExactly(PLAIN);
    }

    /** MERGE of a doc already in LIVE (same youtubeId) adds nothing: no Live-tab read, and the edit is saved. */
    @Test
    void row15_mergeOfADocAlreadyInLiveIsNoAdd() throws Exception {
        doc("videos", "doc-r15m", Map.of("youtubeId", "it1r15mvid1", "title", "V", "status", "APPROVED",
                "channelId", uc("r15m"), "categoryIds", List.of(LIVE), "createdAt", Timestamp.now()));
        as("admin");
        importJson(Map.of("videos", List.of(Map.of("id", "doc-r15m", "youtubeId", "it1r15mvid1", "title", "V2",
                "status", "APPROVED", "channelId", uc("r15m"), "categoryIds", List.of(LIVE)))), "MERGE")
                .andExpect(status().isOk());
        verify(reader, never()).read(uc("r15m"));
        assertThat(videos.findById("doc-r15m").orElseThrow().getTitle()).isEqualTo("V2");
    }

    /** D8: a file can't give a stored LIVE category a parent. */
    @Test
    void row15_aJsonImportGivingTheLiveCategoryAParentIsRefusedShape() throws Exception {
        as("admin");
        importJson(Map.of("categories", List.of(Map.of("id", LIVE, "name", "Live", "parentCategoryId", PLAIN))), "OVERWRITE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_SHAPE"));
        assertThat(firestore.collection("categories").document(LIVE).get().get().getString("parentCategoryId")).isNull();
    }

    /** D8: nothing nests under a LIVE category, through a file either. */
    @Test
    void row15_aJsonImportNestingACategoryUnderLiveIsRefusedShape() throws Exception {
        as("admin");
        importJson(Map.of("categories", List.of(Map.of("id", "it1-kid", "name", "Kid", "parentCategoryId", LIVE))), "OVERWRITE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIVE_CATEGORY_SHAPE"));
        assertThat(firestore.collection("categories").document("it1-kid").get().get().exists()).isFalse();
    }

    /** D8 binds only what is written: SKIP leaves a stored category alone, so its file row is not refused. */
    @Test
    void row15_skipExemptsAStoredCategoryFromTheShapeRule() throws Exception {
        as("admin");
        importJson(Map.of("categories", List.of(Map.of("id", LIVE, "name", "Live", "parentCategoryId", PLAIN))), "SKIP")
                .andExpect(status().isOk());
        var stored = firestore.collection("categories").document(LIVE).get().get();
        assertThat(stored.getString("parentCategoryId")).isNull();
        assertThat(stored.getString("type")).isEqualTo("LIVE");
    }

    @Test
    void theAdminStatusAnswers401ToAnInvalidToken() throws Exception {
        com.google.firebase.auth.FirebaseAuthException bad = Mockito.mock(com.google.firebase.auth.FirebaseAuthException.class);
        when(firebaseAuth.verifyIdToken(anyString())).thenThrow(bad);
        when(firebaseAuth.verifyIdToken(anyString(), anyBoolean())).thenThrow(bad);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/live-channels/status")
                .header("Authorization", "Bearer expired"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theAdminStatusIsAdminOnly() throws Exception {
        as("moderator");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/live-channels/status")
                .header("Authorization", "Bearer t")).andExpect(status().isForbidden());
        as("admin");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/live-channels/status")
                .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].id").value(LIVE));
    }
}
