package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.scheduler.LiveChannelPoller;
import com.albunyaan.tube.service.LiveChannelService;
import com.albunyaan.tube.service.LiveTabReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §12 IT2 (and IT5 in Task 6): the public lists of a seeded LIVE category. */
class LiveCategoryServingIT extends BaseIntegrationTest {

    @MockBean LiveTabReader reader;          // no YouTube; the snapshot is set directly
    @MockBean com.google.firebase.auth.FirebaseAuth firebaseAuth;   // the moderation routes (stage 9 B13)
    @Autowired LiveChannelService live;
    @Autowired LiveChannelPoller poller;

    static final String CAT = "it2-live";
    static final String SUNNAH = "UCit2sunnah0000000000000", KIDS = "UCit2kids000000000000000";

    @Override
    protected String[] getCollectionsToClean() {
        return new String[]{"categories", "channels", "playlists", "videos", "users", "audit_logs",
                "category_content_order", "live_channel_state"};
    }

    private void put(String collection, String id, Map<String, Object> fields) throws Exception {
        firestore.collection(collection).document(id).set(fields).get(5, TimeUnit.SECONDS);
    }

    private static LiveChannelState liveState(String uc, String... ids) {
        LiveChannelState s = LiveChannelState.unknown(uc);
        s.setStatus(LiveChannelState.LIVE);
        for (String id : ids) s.getLives().add(new LiveChannelState.Live(id, "title " + id));
        return s;
    }

    @BeforeEach
    void seed() throws Exception {
        put("categories", CAT, Map.of("name", "Live", "type", "LIVE", "topLevel", true, "displayOrder", 1));
        // "subscribers": the CHANNELS list orders by it, and Firestore leaves out documents without the field
        put("channels", "channel_" + SUNNAH, Map.of("youtubeId", SUNNAH, "name", "Sunnah", "status", "APPROVED", "categoryIds", List.of(CAT), "subscribers", 2L));
        put("channels", "channel_" + KIDS, Map.of("youtubeId", KIDS, "name", "Kids", "status", "APPROVED", "categoryIds", List.of(CAT), "subscribers", 1L));
        put("category_content_order", CAT + "_channel_channel_" + SUNNAH, Map.of("categoryId", CAT, "contentId", "channel_" + SUNNAH, "contentType", "channel", "position", 0));
        put("category_content_order", CAT + "_channel_channel_" + KIDS, Map.of("categoryId", CAT, "contentId", "channel_" + KIDS, "contentType", "channel", "position", 1));
        put("videos", "doc-it2blocked", Map.of("youtubeId", "it2blocked1", "status", "REJECTED", "title", "x"));
        live.loadStates(List.of(liveState(SUNNAH, "it2sunnah01"), liveState(KIDS, "it2kids0001", "it2blocked1")));
        poller.refreshView();
    }

    @Test
    void theCategoryListServesTheCurrentLivesWithTheirChannel() throws Exception {
        for (String type : new String[]{"HOME", "VIDEOS"}) {
            mvc.perform(get("/api/v1/content").param("type", type).param("category", CAT).param("limit", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", contains("it2sunnah01", "it2kids0001")))   // R11 dropped the REJECTED doc
                    .andExpect(jsonPath("$.data[0].liveChannelId").value(SUNNAH))
                    .andExpect(jsonPath("$.data[0].type").value("VIDEO"));
        }
    }

    @Test
    void channelsStillListTheChannelCards() throws Exception {
        mvc.perform(get("/api/v1/content").param("type", "CHANNELS").param("category", CAT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("CHANNEL"))
                .andExpect(jsonPath("$.data[0].liveChannelId").doesNotExist());   // NON_NULL omits it (F18)
    }

    @Test
    void theHomeSectionCountsTheExpandedItems() throws Exception {
        mvc.perform(get("/api/v1/home").param("category", CAT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].items[*].id", contains("it2sunnah01", "it2kids0001")))
                .andExpect(jsonPath("$.data[0].totalContentCount").value(2));
    }

    // ---- IT5: the live endpoint the 1.0.4 apps use for switching ----

    @Test
    void theLiveEndpointServesTheFilteredLivesFromMemory() throws Exception {
        mvc.perform(get("/api/v1/live/channels/" + KIDS))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=15")))
                .andExpect(jsonPath("$.channelId").value(KIDS))
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.lives[*].id", contains("it2kids0001")));        // R11: the REJECTED doc is gone

        // No Firestore read per call: the answer survives the channel docs disappearing.
        com.albunyaan.tube.util.FirestoreTestHelper.clearCollections(firestore, "channels");
        mvc.perform(get("/api/v1/live/channels/" + KIDS)).andExpect(status().isOk());
    }

    @Test
    void anOfflineEntryAnswersOfflineWithNoLives() throws Exception {
        LiveChannelState off = LiveChannelState.unknown(SUNNAH);
        off.setStatus(LiveChannelState.OFFLINE);
        live.loadStates(List.of(off, liveState(KIDS, "it2kids0001")));
        poller.refreshView();

        mvc.perform(get("/api/v1/live/channels/" + SUNNAH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.lives").isEmpty());
    }

    @Test
    void aChannelThatIsNoLiveEntryIs404() throws Exception {
        mvc.perform(get("/api/v1/live/channels/UCnotAnEntry000000000000")).andExpect(status().isNotFound());
    }

    // ---- Stage 9 B13 (P2): moderation reaches the live endpoint at the poller's next tick, not its next cycle ----

    private void asAdmin() throws Exception {
        com.google.firebase.auth.FirebaseToken token = org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseToken.class);
        org.mockito.Mockito.when(token.getUid()).thenReturn("admin-uid");
        org.mockito.Mockito.when(token.getEmail()).thenReturn("admin-uid@test.com");
        org.mockito.Mockito.when(token.getClaims()).thenReturn(Map.of("role", "admin"));
        org.mockito.Mockito.when(firebaseAuth.verifyIdToken(org.mockito.ArgumentMatchers.anyString())).thenReturn(token);
        org.mockito.Mockito.when(firebaseAuth.verifyIdToken(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(token);
    }

    @Test
    void anExclusionARemovalAndACategoryDeleteReachTheLiveEndpointAtTheNextTick() throws Exception {
        asAdmin();
        // An exclusion (the workspace writes the channel doc and evicts nothing)
        mvc.perform(post("/api/admin/exclusions").header("Authorization", "Bearer fake-admin-token")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"parentType\":\"CHANNEL\",\"parentId\":\"channel_" + KIDS
                                + "\",\"excludeType\":\"VIDEO\",\"excludeId\":\"it2kids0001\",\"reason\":\"LIVESTREAM\"}"))
                .andExpect(status().isCreated());
        poller.refreshIfStale();
        mvc.perform(get("/api/v1/live/channels/" + KIDS)).andExpect(status().isOk()).andExpect(jsonPath("$.lives").isEmpty());

        // The channel removed from the LIVE category
        mvc.perform(delete("/api/admin/sort/categories/" + CAT + "/content/channel/channel_" + SUNNAH)
                .header("Authorization", "Bearer fake-admin-token")).andExpect(status().isOk());
        poller.refreshIfStale();
        mvc.perform(get("/api/v1/live/channels/" + SUNNAH)).andExpect(status().isNotFound());

        // The LIVE category deleted
        mvc.perform(delete("/api/admin/categories/" + CAT).header("Authorization", "Bearer fake-admin-token"))
                .andExpect(status().isNoContent());
        poller.refreshIfStale();
        mvc.perform(get("/api/v1/live/channels/" + KIDS)).andExpect(status().isNotFound());
    }
}
