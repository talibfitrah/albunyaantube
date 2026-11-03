package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.firebase.ErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PERSONAL exclusions end to end: grants read from the Firestore emulator, through the real security chain
 * (/api/v1/** permitAll, FirebaseAuthFilter skipped). A grantee's Bearer gets 200; anyone else the missing-id 404.
 */
class PersonalDetailsIT extends BaseIntegrationTest {

    @MockBean FirebaseAuth firebaseAuth;
    @Autowired ChannelRepository channels;
    @Autowired PlaylistRepository playlists;
    @Autowired ObjectMapper json;

    @BeforeEach
    void seed() throws Exception {
        token("grantee-token", "uid-it-grantee");
        token("other-token", "uid-it-other");
        when(firebaseAuth.verifyIdToken("bad-token")).thenThrow(
                new FirebaseAuthException(ErrorCode.UNAUTHENTICATED, "expired", null, null, null));

        Channel c = new Channel("UCitPersonal");
        c.setStatus("APPROVED");
        c.setVisibility("PERSONAL");
        c.setPersonalGrants(List.of("uid-it-grantee"));
        Channel.ExcludedItems excluded = new Channel.ExcludedItems();
        excluded.setVideos(List.of("itvideo0001"));
        excluded.setPlaylists(List.of("PLitExcluded"));
        c.setExcludedItems(excluded);
        channels.save(c);

        Playlist p = new Playlist("PLitPersonal");
        p.setStatus("APPROVED");
        p.setVisibility("PERSONAL");
        p.setPersonalGrants(List.of("uid-it-grantee"));
        p.setExcludedVideoIds(List.of("itvideo0002"));
        playlists.save(p);
    }

    private void token(String value, String uid) throws Exception {
        FirebaseToken decoded = Mockito.mock(FirebaseToken.class);
        when(decoded.getUid()).thenReturn(uid);
        when(firebaseAuth.verifyIdToken(value)).thenReturn(decoded);
    }

    private static MockHttpServletRequestBuilder as(String path, String bearer) {
        MockHttpServletRequestBuilder request = get(path).accept(MediaType.APPLICATION_JSON);
        return bearer == null ? request : request.header("Authorization", "Bearer " + bearer);
    }

    @Test
    void granteeGetsThePersonalExclusions() throws Exception {
        mvc.perform(as("/api/v1/channels/UCitPersonal", "grantee-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.excludedVideoIds", contains("itvideo0001")))
                .andExpect(jsonPath("$.excludedPlaylistIds", contains("PLitExcluded")))
                .andExpect(jsonPath("$.personalGrants").doesNotExist());
        mvc.perform(as("/api/v1/playlists/PLitPersonal", "grantee-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.excludedVideoIds", contains("itvideo0002")))
                .andExpect(jsonPath("$.personalGrants").doesNotExist());
    }

    @Test
    void everyoneElseGetsTheMissingIdAnswer() throws Exception {
        for (String bearer : new String[]{null, "other-token", "bad-token"}) {
            assertEquals(body404("/api/v1/channels/UCitMissing0", null).replace("UCitMissing0", "UCitPersonal"),
                    body404("/api/v1/channels/UCitPersonal", bearer), "channel, caller " + bearer);
            assertEquals(body404("/api/v1/playlists/PLitMissing0", null).replace("PLitMissing0", "PLitPersonal"),
                    body404("/api/v1/playlists/PLitPersonal", bearer), "playlist, caller " + bearer);
        }
    }

    /** The 404 body without its timestamp, the one field that differs between any two calls. */
    private String body404(String path, String bearer) throws Exception {
        String body = mvc.perform(as(path, bearer)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        ObjectNode node = (ObjectNode) json.readTree(body);
        node.remove("timestamp");
        return node.toString();
    }
}
