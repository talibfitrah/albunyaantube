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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
}
