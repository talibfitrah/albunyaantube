package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.LiveChannelService;
import com.albunyaan.tube.service.LiveTabReader;
import com.albunyaan.tube.service.LiveTabResult;
import com.albunyaan.tube.service.SortOrderService;
import com.albunyaan.tube.service.YouTubeGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §12 IT4: the prod plan against a seeded copy of the prod category (RS §2), with the Live tab mocked. */
class LiveChannelsMigrationIT extends BaseIntegrationTest {

    @MockBean FirebaseAuth firebaseAuth;
    @MockBean LiveTabReader reader;
    @MockBean YouTubeGateway gateway;          // spec §12: no request ever leaves a test
    @SpyBean SortOrderService sortOrder;
    @Autowired LiveChannelService live;
    @Autowired com.albunyaan.tube.scheduler.LiveChannelPoller poller;
    @Autowired com.albunyaan.tube.search.SearchIndex searchIndex;
    @Autowired ObjectMapper json;
    @Autowired VideoRepository videos;
    @Autowired AuditLogRepository auditLogs;

    static final String CAT = "btiLw1LiEqiUHQJiaJKX";
    static final String QURAN = "UCos52azQNBgW63_9uDJoPDA", SUNNAH = "UCROKYPep-UuODNwyipe6JMw",
            KIDS = "UC5vfWTTPnKdFp-8s0KbqJhw", ZAD = "UCOll3M-P7oKs5cSrQ9ytt6g";
    static final List<String[]> VIDEOS = List.of(new String[]{"PLkCnLrKN8Q", QURAN}, new String[]{"Rs7St51oDDc", SUNNAH},
            new String[]{"bpvNfqWTXhw", KIDS}, new String[]{"yee9gj7t8nQ", KIDS}, new String[]{"J3bDNstCnzE", KIDS},
            new String[]{"pqC-1yfFvu8", ZAD});

    @Override
    protected String[] getCollectionsToClean() {
        return new String[]{"categories", "channels", "playlists", "videos", "users", "audit_logs",
                "category_content_order", "live_channel_state"};
    }

    private void write(String collection, String id, Map<String, Object> fields) throws Exception {
        firestore.collection(collection).document(id).set(fields).get(5, TimeUnit.SECONDS);
    }

    private static LiveTabResult lives(String... ids) {
        return LiveTabResult.ok(Arrays.stream(ids).map(i -> new LiveTabResult.LiveItem(i, "live " + i)).toList(), 1);
    }

    @BeforeEach
    void seedProdShape() throws Exception {
        FirebaseToken token = Mockito.mock(FirebaseToken.class);
        when(token.getUid()).thenReturn("admin-uid");
        when(token.getEmail()).thenReturn("admin@test.com");
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", "admin");
        when(token.getClaims()).thenReturn(claims);
        when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);
        when(firebaseAuth.verifyIdToken(anyString(), anyBoolean())).thenReturn(token);
        live.loadStates(List.of());                      // the snapshot is a context singleton: no state leaks between tests
        live.publish(Map.of(), Map.of());

        write("categories", CAT, Map.of("name", "Channels Live", "topLevel", true, "displayOrder", 17));
        for (String uc : List.of(QURAN, SUNNAH, KIDS, ZAD)) {
            write("channels", "channel_" + uc, Map.of("youtubeId", uc, "name", uc, "status", "APPROVED",
                    "validationStatus", "VALID", "categoryIds", List.of()));
        }
        for (int i = 0; i < VIDEOS.size(); i++) {
            String id = VIDEOS.get(i)[0];
            write("videos", "doc-" + id, Map.of("youtubeId", id, "title", "stored " + id, "status", "APPROVED",
                    "validationStatus", "ERROR", "categoryIds", List.of(CAT)));
            write("category_content_order", CAT + "_video_doc-" + id,
                    Map.of("categoryId", CAT, "contentId", "doc-" + id, "contentType", "video", "position", i));
        }
        when(reader.read(QURAN)).thenReturn(lives("eC4LfEVxvKg"));
        when(reader.read(SUNNAH)).thenReturn(lives("Rs7St51oDDc"));
        when(reader.read(KIDS)).thenReturn(lives("J3bDNstCnzE", "yee9gj7t8nQ", "bpvNfqWTXhw"));
        when(reader.read(ZAD)).thenReturn(lives("eaOtcUNHDB8"));
        when(reader.resolveChannelId("PLkCnLrKN8Q")).thenReturn(Optional.of(QURAN));
    }

    private Map<String, Object> plan(String mode) {
        return Map.of("mode", mode, "categoryId", CAT,
                "channels", List.of(Map.of("channelId", QURAN, "position", 0), Map.of("channelId", SUNNAH, "position", 1),
                        Map.of("channelId", KIDS, "position", 2), Map.of("channelId", ZAD, "position", 3)),
                "removeVideos", VIDEOS.stream().map(v -> Map.of("youtubeId", v[0], "channelId", v[1])).toList());
    }

    private ResultActions run(String mode) throws Exception {
        return run(plan(mode));
    }

    private ResultActions run(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/admin/migrations/live-channels").header("Authorization", "Bearer t")
                .header("X-Confirm-Migration", "run-live-channels").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    @Test
    void withoutTheConfirmHeaderItIs428() throws Exception {
        mvc.perform(post("/api/admin/migrations/live-channels").header("Authorization", "Bearer t")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(plan("DRY_RUN"))))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("MIGRATION_CONFIRM_REQUIRED"));
        verifyNoInteractions(reader);
    }

    @Test
    void aDryRunPredictsTheSpecTableAndWritesNothing() throws Exception {
        run("DRY_RUN").andExpect(status().isOk())
                .andExpect(jsonPath("$.proven").value(true))
                .andExpect(jsonPath("$.blockers").isEmpty())
                .andExpect(jsonPath("$.videos[?(@.youtubeId=='PLkCnLrKN8Q')].action").value(contains("ARCHIVE")))
                .andExpect(jsonPath("$.videos[?(@.youtubeId=='pqC-1yfFvu8')].action").value(contains("ARCHIVE")))
                .andExpect(jsonPath("$.videos[?(@.youtubeId=='Rs7St51oDDc')].action").value(contains("WRITE_CHANNEL_ID")))
                .andExpect(jsonPath("$.videos[0].categoryIds", contains(CAT)))    // R12: the controller sees them first
                .andExpect(jsonPath("$.resolverCheck.match").value(true))
                .andExpect(jsonPath("$.servedAfter[*].id", contains("eC4LfEVxvKg", "Rs7St51oDDc", "J3bDNstCnzE",
                        "yee9gj7t8nQ", "bpvNfqWTXhw", "eaOtcUNHDB8")));
        assertThat(firestore.collection("categories").document(CAT).get().get().getString("type")).isNull();
        assertThat(videos.findByYoutubeId("PLkCnLrKN8Q").orElseThrow().getCategoryIds()).containsExactly(CAT);
        assertThat(videos.findByYoutubeId("Rs7St51oDDc").orElseThrow().getChannelId()).isNull();
        assertThat(countDocuments("live_channel_state")).isZero();
        assertThat(countDocuments("audit_logs")).isZero();
    }

    @Test
    void aChannelListedTwiceIsABlockerAndIsPolledOnce() throws Exception {
        Map<String, Object> body = new HashMap<>(plan("APPLY"));
        body.put("channels", List.of(Map.of("channelId", QURAN, "position", 0), Map.of("channelId", SUNNAH, "position", 1),
                Map.of("channelId", KIDS, "position", 2), Map.of("channelId", ZAD, "position", 3),
                Map.of("channelId", SUNNAH, "position", 4)));

        run(body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers", hasItem("channel " + SUNNAH + " is listed twice")));

        verify(reader).read(SUNNAH);
        assertThat(countDocuments("audit_logs")).isZero();
    }

    /** Review I1: a crash between the doc write and the row delete leaves the row; the re-run must still delete it. */
    @Test
    void aRemovalCutShortBetweenItsTwoWritesLeavesNoOrphanRow() throws Exception {
        write("videos", "doc-PLkCnLrKN8Q", Map.of("youtubeId", "PLkCnLrKN8Q", "title", "stored PLkCnLrKN8Q",
                "status", "APPROVED", "validationStatus", "ERROR", "categoryIds", List.of()));   // its row stays

        run("APPLY").andExpect(status().isOk());

        assertThat(documentExists("category_content_order", CAT + "_video_doc-PLkCnLrKN8Q")).isFalse();
        assertThat(countDocuments("category_content_order")).isEqualTo(4);
    }

    /** Review I2, R12: an ended doc that also sits in another category is kept, never archived, and named. */
    @Test
    void anEndedDocInAnotherCategoryIsKeptWithAWarning() throws Exception {
        write("videos", "doc-pqC-1yfFvu8", Map.of("youtubeId", "pqC-1yfFvu8", "title", "stored pqC-1yfFvu8",
                "status", "APPROVED", "validationStatus", "ERROR", "categoryIds", List.of(CAT, "other-cat")));

        run("APPLY").andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasItem("kept: pqC-1yfFvu8 holds categories [other-cat]")));

        var doc = videos.findByYoutubeId("pqC-1yfFvu8").orElseThrow();
        assertThat(doc.getValidationStatus()).isEqualTo(ValidationStatus.ERROR);
        assertThat(doc.getCategoryIds()).containsExactly("other-cat");
        assertThat(live.state(ZAD).getArchived()).doesNotContainKey("pqC-1yfFvu8");
    }

    /** Review I3, R12: the migration's archive leaves the poller's restore record, so a reappearing id heals. */
    @Test
    void anArchivedDocIsRestoredWhenItsIdReappearsOnItsChannel() throws Exception {
        run("APPLY").andExpect(status().isOk());

        assertThat(live.state(QURAN).getArchived()).containsEntry("PLkCnLrKN8Q", "ERROR");
        assertThat(live.state(ZAD).getArchived()).containsEntry("pqC-1yfFvu8", "ERROR");
        assertThat(firestore.collection("live_channel_state").document(QURAN).get().get().get("archived"))
                .isEqualTo(Map.of("PLkCnLrKN8Q", "ERROR"));                 // persisted: survives a restart

        when(reader.read(QURAN)).thenReturn(lives("eC4LfEVxvKg", "PLkCnLrKN8Q"));
        live.readFresh(QURAN);                                              // the cycle below reuses this read (memo)
        poller.cycle(System.currentTimeMillis());

        assertThat(videos.findByYoutubeId("PLkCnLrKN8Q").orElseThrow().getValidationStatus()).isEqualTo(ValidationStatus.ERROR);
        assertThat(live.state(QURAN).getArchived()).doesNotContainKey("PLkCnLrKN8Q");
    }

    @Test
    void applyServesTheSixLivesArchivesTheTwoEndedDocsAndKeepsTheFour() throws Exception {
        run("APPLY").andExpect(status().isOk())
                .andExpect(jsonPath("$.resumed").value(false))
                .andExpect(jsonPath("$.youtubeRequests").value(4))
                .andExpect(jsonPath("$.servedAfter[*].id", contains("eC4LfEVxvKg", "Rs7St51oDDc", "J3bDNstCnzE",
                        "yee9gj7t8nQ", "bpvNfqWTXhw", "eaOtcUNHDB8")));
        // Spec §11 "apply ≈ 6": one Live-tab read per plan channel plus G2's one resolution; step 5's guard sees an
        // ordinary category, so it reads nothing, and nothing reaches the gateway.
        for (String uc : List.of(QURAN, SUNNAH, KIDS, ZAD)) verify(reader).read(uc);
        verify(reader).resolveChannelId("PLkCnLrKN8Q");
        verifyNoMoreInteractions(reader);
        verifyNoInteractions(gateway);

        assertThat(firestore.collection("categories").document(CAT).get().get().getString("type")).isEqualTo("LIVE");
        for (String ended : List.of("PLkCnLrKN8Q", "pqC-1yfFvu8")) {
            assertThat(videos.findByYoutubeId(ended).orElseThrow().getValidationStatus()).isEqualTo(ValidationStatus.ARCHIVED);
            mvc.perform(get("/api/v1/videos/" + ended)).andExpect(status().isGone());   // gone from search, lists and the gate (F27)
        }
        for (String kept : List.of("Rs7St51oDDc", "bpvNfqWTXhw", "yee9gj7t8nQ", "J3bDNstCnzE")) {
            var doc = videos.findByYoutubeId(kept).orElseThrow();
            assertThat(doc.getValidationStatus()).isEqualTo(ValidationStatus.ERROR);
            assertThat(doc.getChannelId()).isNotNull();
            assertThat(doc.getCategoryIds()).doesNotContain(CAT);
        }
        mvc.perform(get("/api/v1/content").param("category", CAT).param("limit", "50"))
                .andExpect(jsonPath("$.data[*].id", contains("eC4LfEVxvKg", "Rs7St51oDDc", "J3bDNstCnzE",
                        "yee9gj7t8nQ", "bpvNfqWTXhw", "eaOtcUNHDB8")));
        assertThat(auditLogs.findByAction("live_channels_migration_backup", 10)).hasSize(1);
        assertThat(countDocuments("live_channel_state")).isEqualTo(4);
        // Spec §10 verify (pre-flight D14): search no longer returns the 2 ended ids once the index catches up.
        searchIndex.reconcile();
        Object index = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(searchIndex);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(index, "awaitWrites");
        for (String ended : List.of("PLkCnLrKN8Q", "pqC-1yfFvu8")) {
            mvc.perform(get("/api/v1/search").param("q", ended))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(ended))));
        }
    }

    /** R18: a run that dies after step 7's first removal completes on re-run, with exactly one backup. */
    @Test
    void aFailedApplyResumesToCompletionWithOneBackup() throws Exception {
        doCallRealMethod().doThrow(new IllegalStateException("simulated crash")).doCallRealMethod()
                .when(sortOrder).removeContentFromCategoryAndUpdate(anyString(), anyString(), anyString());

        run("APPLY").andExpect(status().isInternalServerError());        // the crash is a 500, never 409 (pre-flight M1)
        run("APPLY").andExpect(status().isOk()).andExpect(jsonPath("$.resumed").value(true));

        assertThat(auditLogs.findByAction("live_channels_migration_backup", 10)).hasSize(1);
        mvc.perform(get("/api/v1/content").param("category", CAT).param("limit", "50"))
                .andExpect(jsonPath("$.data.length()").value(6));
    }

    /**
     * Stage 9 B23 (Cubic r11 #17): APPLY is idempotent, so a re-run (a 524 retry) is a no-op for a doc an earlier run
     * handled. Run 1 kept the live Sunnah link public (its channel id written, out of the category); that stream ending
     * before the retry must not make the retry archive it.
     */
    @Test
    void aReRunNeverArchivesALinkAnEarlierRunKeptPublic() throws Exception {
        run("APPLY").andExpect(status().isOk());
        when(reader.read(SUNNAH)).thenReturn(lives());                       // Rs7St51oDDc has ended by the retry

        run("APPLY").andExpect(status().isOk())
                .andExpect(jsonPath("$.resumed").value(true))
                .andExpect(jsonPath("$.videos[?(@.youtubeId=='Rs7St51oDDc')].action").value(contains("WRITE_CHANNEL_ID")));

        var doc = videos.findByYoutubeId("Rs7St51oDDc").orElseThrow();
        assertThat(doc.getValidationStatus()).isEqualTo(ValidationStatus.ERROR);
        assertThat(doc.getChannelId()).isEqualTo(SUNNAH);
        assertThat(live.state(SUNNAH).getArchived()).doesNotContainKey("Rs7St51oDDc");
        assertThat(videos.findByYoutubeId("PLkCnLrKN8Q").orElseThrow().getValidationStatus()).isEqualTo(ValidationStatus.ARCHIVED);
    }

    /** R18: the kill switch serves the four entries as channel cards: valid and never empty. */
    @Test
    void clearingTheTypeServesTheFourChannelCards() throws Exception {
        run("APPLY").andExpect(status().isOk());

        mvc.perform(put("/api/admin/categories/" + CAT).header("Authorization", "Bearer t").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Channels Live\",\"displayOrder\":17,\"parentCategoryId\":null,\"type\":null}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/content").param("category", CAT).param("limit", "50"))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[*].type", contains("CHANNEL", "CHANNEL", "CHANNEL", "CHANNEL")));
    }

    @Test
    void anUnprovenPollBlocksApplyAndWritesNothing() throws Exception {
        for (String uc : List.of(QURAN, SUNNAH, KIDS, ZAD)) when(reader.read(uc)).thenReturn(lives());

        run("APPLY").andExpect(status().isConflict()).andExpect(jsonPath("$.blockers").isNotEmpty());

        assertThat(firestore.collection("categories").document(CAT).get().get().getString("type")).isNull();
        assertThat(countDocuments("audit_logs")).isZero();
    }

    /** Spec §10, controller ruling 2: a G2 failure (oEmbed 429/5xx, breaker) is a warning, never a blocker. */
    @Test
    void aFailedResolutionIsAWarningAndNeverStopsTheRun() throws Exception {
        when(reader.resolveChannelId("PLkCnLrKN8Q")).thenThrow(new IOException("oEmbed 429"));

        run("DRY_RUN").andExpect(status().isOk())
                .andExpect(jsonPath("$.blockers").isEmpty())
                .andExpect(jsonPath("$.resolverCheck.youtubeId").value("PLkCnLrKN8Q"))
                .andExpect(jsonPath("$.resolverCheck.resolvedChannelId").doesNotExist())   // null
                .andExpect(jsonPath("$.resolverCheck.match").value(false))
                .andExpect(jsonPath("$.warnings", hasItem("G2: resolution of PLkCnLrKN8Q unavailable: oEmbed 429")));
        run("APPLY").andExpect(status().isOk())
                .andExpect(jsonPath("$.resolverCheck.match").value(false));

        assertThat(firestore.collection("categories").document(CAT).get().get().getString("type")).isEqualTo("LIVE");
    }
}
