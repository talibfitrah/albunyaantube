package com.albunyaan.tube.controller;

import com.albunyaan.tube.service.LiveChannelService;
import com.albunyaan.tube.service.LiveEndpointService;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A-M5: 404 is the apps' definitive "not live" stop, so it is never sent before a view of the loaded states is published. */
@WebMvcTest(LiveChannelController.class)
@Import(LiveEndpointService.class)   // the real answers; their collaborators are the mocks below
@AutoConfigureMockMvc(addFilters = false)
class LiveChannelControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean LiveChannelService live;
    @MockBean com.albunyaan.tube.scheduler.LiveChannelPoller poller;
    @MockBean FirebaseAuth firebaseAuth;
    @MockBean com.albunyaan.tube.repository.UserRepository userRepository;
    @MockBean com.albunyaan.tube.repository.VideoRepository videos;

    static final String UC = "UCaaaaaaaaaaaaaaaaaaaaaa";
    static final String A = "aaaaaaaaaaa", B = "bbbbbbbbbbb";

    /** A listed channel serving {@code served}, with no video docs unless a test stubs them. */
    private com.albunyaan.tube.model.Channel listed(String... served) throws Exception {
        var c = new com.albunyaan.tube.model.Channel(UC);
        when(live.published()).thenReturn(true);
        when(live.entries()).thenReturn(Map.of(UC, c));
        when(live.served()).thenReturn(Map.of(UC, java.util.Arrays.stream(served)
                .map(id -> new com.albunyaan.tube.service.LiveTabResult.LiveItem(id, "t-" + id)).toList()));
        when(videos.findByYoutubeIds(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of());
        // Cubic r2 #3: the verdict cache is the real service's (LiveChannelPollerTest); here every call judges
        when(live.candidateVerdict(anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i -> i.<java.util.function.Supplier<Boolean>>getArgument(2).get());
        return c;
    }

    /** A candidate the snapshot does not list is judged without a YouTube read; the poller is asked to catch up (B2). */
    @Test
    void anAllowedCandidateIsAnsweredWithoutAnyYouTubeRead() throws Exception {
        listed();
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"channelId\":\"" + UC + "\",\"status\":\"LIVE\",\"lives\":[{\"id\":\"" + B + "\",\"title\":\"\"}]}", true));
        verify(poller).refreshStale(UC);                                 // throttled in the poller (Cubic r9)
        verify(live, never()).livesNow(anyString());
    }

    @Test
    void aCandidateTakesTheTitleTheLastReadGaveIt() throws Exception {
        listed();
        var s = com.albunyaan.tube.model.LiveChannelState.unknown(UC);
        s.setLives(new java.util.ArrayList<>(java.util.List.of(new com.albunyaan.tube.model.LiveChannelState.Live(B, "Tb"))));
        when(live.state(UC)).thenReturn(s);
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B))
                .andExpect(jsonPath("$.lives[0].title").value("Tb"));
    }

    /**
     * B1: the answer is the candidate's verdict only. After a rotation the snapshot still lists the ended id A for up to a
     * day; listing it next to B would keep the apps on A (LiveChannelRecovery never switches while A is listed).
     */
    @Test
    void aCandidateAnswersOnlyItselfNeverTheSnapshotsOtherLives() throws Exception {
        listed(A);
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B))
                .andExpect(content().json("{\"channelId\":\"" + UC + "\",\"status\":\"LIVE\",\"lives\":[{\"id\":\"" + B + "\",\"title\":\"\"}]}", true));
    }

    /** A candidate the snapshot serves is answered from it: its title, no registry read, no on-demand read. */
    @Test
    void aServedCandidateIsAnsweredFromTheSnapshotAlone() throws Exception {
        listed(A, B);
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", A))
                .andExpect(content().json("{\"channelId\":\"" + UC + "\",\"status\":\"LIVE\",\"lives\":[{\"id\":\"" + A + "\",\"title\":\"t-" + A + "\"}]}", true));
        verify(videos, never()).findByYoutubeIds(org.mockito.ArgumentMatchers.any());
        verify(poller, never()).refreshOnDemand(anyString());
        verify(live, never()).candidateVerdict(anyString(), anyString(), org.mockito.ArgumentMatchers.any());   // no budget
    }

    /** Cubic r6 K2: ENDED is never a removal: the phone's page lists the id live, so an ended candidate is served. */
    @Test
    void anExcludedOrBlockedCandidateIsNotServedAnEndedOneIs() throws Exception {
        var c = listed(A);
        var ex = new com.albunyaan.tube.model.Channel.ExcludedItems();
        ex.setLiveStreams(java.util.List.of(B));
        c.setExcludedItems(ex);
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B))
                .andExpect(jsonPath("$.status").value("OFFLINE")).andExpect(jsonPath("$.lives").isEmpty());

        c.setExcludedItems(null);
        var doc = new com.albunyaan.tube.model.Video(B);
        doc.setStatus("REJECTED");
        when(videos.findByYoutubeIds(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of(B, doc));
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B)).andExpect(jsonPath("$.lives").isEmpty());

        when(videos.findByYoutubeIds(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of());
        var s = com.albunyaan.tube.model.LiveChannelState.unknown(UC);
        s.getEnded().add(B);
        when(live.state(UC)).thenReturn(s);
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B))
                .andExpect(jsonPath("$.status").value("LIVE")).andExpect(jsonPath("$.lives[0].id").value(B));
        // B2: each answer still asks for a read, so a rotation reaches the snapshot and an ended id live again clears;
        // a judged one through the poller's throttle (Cubic r9)
        verify(poller, times(1)).refreshOnDemand(UC);
        verify(poller, times(2)).refreshStale(UC);
    }

    /**
     * Codex r6 C1: an exclusion on the channel doc, or a live the last view filtered out as blocked, is known locally: []
     * with no lookup and no budget, so a spent budget never keeps a removed stream playing (503).
     */
    @Test
    void aKnownLocalDenialIsOfflineEvenWithTheBudgetSpent() throws Exception {
        var c = listed();
        org.mockito.Mockito.doReturn(null).when(live).candidateVerdict(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
        var ex = new com.albunyaan.tube.model.Channel.ExcludedItems();
        ex.setLiveStreams(java.util.List.of(B));
        c.setExcludedItems(ex);
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lives").isEmpty());

        c.setExcludedItems(null);
        when(live.blocked(B)).thenReturn(true);
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lives").isEmpty());

        verify(live, never()).candidateVerdict(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
        verify(videos, never()).findByYoutubeIds(org.mockito.ArgumentMatchers.any());
    }

    /**
     * Cubic r3 P1: a failed registry lookup is no verdict. A 200 OFFLINE would stop a playing live (and its cast) on one
     * Firestore blip, so the answer is 503 + Retry-After, no body, and no on-demand ask (it judged nothing).
     */
    @Test
    void aFailedRegistryLookupIs503NotOffline() throws Exception {
        listed();
        when(videos.findByYoutubeIds(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new java.util.concurrent.TimeoutException("firestore"));
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(content().string(""));
        verify(poller, never()).refreshOnDemand(anyString());
    }

    /** Cubic r3 P2: with the lookup budget spent the service judges nothing (null): 503, no lookup, no on-demand ask. */
    @Test
    void aSpentLookupBudgetIs503WithoutALookup() throws Exception {
        listed();
        org.mockito.Mockito.doReturn(null).when(live).candidateVerdict(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(content().string(""));
        verify(videos, never()).findByYoutubeIds(org.mockito.ArgumentMatchers.any());
        verify(poller, never()).refreshOnDemand(anyString());
    }

    /** B7b: a candidate never skips the 503s: states not published, or a channel just added. */
    @Test
    void aCandidateStillWaitsWhileStatesLoadOrAChannelWasJustAdded() throws Exception {
        when(live.published()).thenReturn(false);
        when(live.entries()).thenReturn(Map.of());
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "60"));

        when(live.published()).thenReturn(true);
        when(live.recentlyAdded(UC)).thenReturn(true);
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "60"));
        verify(poller, never()).refreshOnDemand(anyString());
        verify(videos, never()).findByYoutubeIds(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anUnlistedChannelIs404WithACandidate() throws Exception {
        when(live.published()).thenReturn(true);
        when(live.entries()).thenReturn(Map.of());
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B)).andExpect(status().isNotFound());
        verify(poller, never()).refreshOnDemand(anyString());
    }

    @Test
    void anInvalidCandidateIs400() throws Exception {
        listed();
        for (String bad : new String[] {"short", "bbbbbbbbbb$", "bbbbbbbbbbbb", ""}) {
            mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.error").value("Bad Request"));
        }
        verify(poller, never()).refreshOnDemand(anyString());
    }

    /** One client's evidence is never cached for another request: a candidate answer is no-store, the plain one public. */
    @Test
    void aCandidateAnswerIsNeverCached() throws Exception {
        listed();
        mockMvc.perform(get("/api/v1/live/channels/" + UC).param("live", B))
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get("/api/v1/live/channels/" + UC))
                .andExpect(header().string("Cache-Control", "max-age=15, public"));
    }

    @Test
    void whileStatesAreLoadingTheAnswerIs503WithRetryAfterNever404() throws Exception {
        when(live.published()).thenReturn(false);
        when(live.entries()).thenReturn(Map.of());
        mockMvc.perform(get("/api/v1/live/channels/UCaaaaaaaaaaaaaaaaaaaaaa"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void onceAViewIsPublishedAnUnknownChannelIsStill404() throws Exception {
        when(live.published()).thenReturn(true);
        when(live.entries()).thenReturn(Map.of());
        mockMvc.perform(get("/api/v1/live/channels/UCaaaaaaaaaaaaaaaaaaaaaa")).andExpect(status().isNotFound());
        verify(poller, never()).refreshOnDemand(anyString());       // I8: an unlisted id never costs a read
    }

    /** Final model: a call for a listed channel answers from the snapshot and asks the poller for a read (async). */
    @Test
    void aListedChannelAsksThePollerForARead() throws Exception {
        when(live.published()).thenReturn(true);
        when(live.entries()).thenReturn(Map.of("UCaaaaaaaaaaaaaaaaaaaaaa", new com.albunyaan.tube.model.Channel("UCaaaaaaaaaaaaaaaaaaaaaa")));
        when(live.served()).thenReturn(Map.of());
        mockMvc.perform(get("/api/v1/live/channels/UCaaaaaaaaaaaaaaaaaaaaaa")).andExpect(status().isOk());
        verify(poller).refreshOnDemand("UCaaaaaaaaaaaaaaaaaaaaaa");
    }

    // ---- Exact live links: GET /api/v1/live/links/{videoId}, from the poller's in-memory view ----

    @Test
    void aServedLinkIs204NoStore() throws Exception {
        when(live.published()).thenReturn(true);
        when(live.linkChannel(A)).thenReturn(UC);
        mockMvc.perform(get("/api/v1/live/links/" + A))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
        verify(videos, never()).findByYoutubeIds(org.mockito.ArgumentMatchers.any());
        verify(poller, never()).refreshOnDemand(anyString());
    }

    /**
     * Cubic r6 K2: ENDED is never a removal: an ended link still in its LIVE category is 204, and (K1 b) asks for a read
     * of its channel, so a stream that came back live is shown again within minutes.
     */
    @Test
    void anEndedLinkIs204AndAsksForARead() throws Exception {
        when(live.published()).thenReturn(true);
        when(live.linkChannel(A)).thenReturn(UC);
        var s = com.albunyaan.tube.model.LiveChannelState.unknown(UC);
        s.getEnded().add(A);
        when(live.state(UC)).thenReturn(s);
        mockMvc.perform(get("/api/v1/live/links/" + A)).andExpect(status().isNoContent());
        verify(poller).refreshStale(UC);                 // throttled in the poller (Cubic r8)
    }

    @Test
    void aLinkNoLongerServedIs404() throws Exception {
        when(live.published()).thenReturn(true);
        when(live.linkChannel(A)).thenReturn(null);
        mockMvc.perform(get("/api/v1/live/links/" + A))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    /** Cubic r4 R4-1: a link the view lacks while a refresh is pending (or a cycle runs) may have just been added. */
    @Test
    void aLinkTheViewLacksWhileTheViewIsPendingIs503() throws Exception {
        when(live.published()).thenReturn(true);
        when(live.linkChannel(A)).thenReturn(null);
        when(poller.viewPending()).thenReturn(true);
        mockMvc.perform(get("/api/v1/live/links/" + A))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "60"))
                .andExpect(content().string(""));
    }

    @Test
    void aLinkWhileStatesAreLoadingIs503WithRetryAfterNever404() throws Exception {
        when(live.published()).thenReturn(false);
        mockMvc.perform(get("/api/v1/live/links/" + A))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "60"));
        verify(live, never()).linkChannel(anyString());
    }

    @Test
    void anInvalidLinkIdIs400() throws Exception {
        when(live.published()).thenReturn(true);
        for (String bad : new String[] {"short", "bbbbbbbbbb$", "bbbbbbbbbbbb"}) {
            mockMvc.perform(get("/api/v1/live/links/" + bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.error").value("Bad Request"));
        }
        verify(live, never()).linkChannel(anyString());
    }
}
