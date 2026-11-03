package com.albunyaan.tube.controller;

import com.albunyaan.tube.exception.GlobalExceptionHandler;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.LiveCategoryService;
import com.albunyaan.tube.service.PublicContentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.firebase.ErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Grantees of a PERSONAL channel or playlist read its exclusions on the same public routes, by sending their Firebase
 * Bearer. Everyone else (anonymous, another user, a bad token) must not be able to tell the item from a missing one.
 * Real controller and service; only the repositories and Firebase are stubbed.
 */
class PersonalDetailsAccessTest {

    private final ChannelRepository channels = mock(ChannelRepository.class);
    private final PlaylistRepository playlists = mock(PlaylistRepository.class);
    private final FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        PublicContentService service = new PublicContentService(channels, playlists, mock(VideoRepository.class),
                mock(CategoryRepository.class), mock(CategoryContentOrderRepository.class), Runnable::run,
                mock(com.albunyaan.tube.search.SearchIndex.class), mock(LiveCategoryService.class));
        mvc = MockMvcBuilders.standaloneSetup(new PublicContentController(service, firebaseAuth))
                .addPlaceholderValue("app.security.cors.allowed-origins", "*")
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        token("grantee-token", "uid-grantee");
        token("other-token", "uid-other");
        when(firebaseAuth.verifyIdToken("bad-token")).thenThrow(
                new FirebaseAuthException(ErrorCode.UNAUTHENTICATED, "expired", null, null, null));
    }

    private void token(String value, String uid) throws Exception {
        FirebaseToken decoded = mock(FirebaseToken.class);
        when(decoded.getUid()).thenReturn(uid);
        when(firebaseAuth.verifyIdToken(value)).thenReturn(decoded);
    }

    private static Channel personalChannel() {
        Channel c = new Channel("UCpersonal1");
        c.setStatus("APPROVED");
        c.setVisibility("PERSONAL");
        c.setPersonalGrants(List.of("uid-grantee"));
        Channel.ExcludedItems excluded = new Channel.ExcludedItems();
        excluded.setVideos(List.of("video000001"));
        excluded.setPlaylists(List.of("PLexcluded1"));
        c.setExcludedItems(excluded);
        return c;
    }

    private static Playlist personalPlaylist() {
        Playlist p = new Playlist("PLpersonal1");
        p.setStatus("APPROVED");
        p.setVisibility("PERSONAL");
        p.setPersonalGrants(List.of("uid-grantee"));
        p.setExcludedVideoIds(List.of("video000002"));
        return p;
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String bearer) {
        request.accept(org.springframework.http.MediaType.APPLICATION_JSON);
        return bearer == null ? request : request.header("Authorization", "Bearer " + bearer);
    }

    @Test
    void granteeGetsThePersonalChannelsExclusions() throws Exception {
        when(channels.findByYoutubeId("UCpersonal1")).thenReturn(Optional.of(personalChannel()));

        mvc.perform(as(get("/api/v1/channels/UCpersonal1"), "grantee-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.youtubeId").value("UCpersonal1"))
                .andExpect(jsonPath("$.excludedVideoIds", contains("video000001")))
                .andExpect(jsonPath("$.excludedPlaylistIds", contains("PLexcluded1")))
                // The PublicDetails shape: no grantee list, no visibility, no staff fields.
                .andExpect(jsonPath("$.personalGrants").doesNotExist())
                .andExpect(jsonPath("$.visibility").doesNotExist())
                .andExpect(jsonPath("$.submittedBy").doesNotExist());
    }

    @Test
    void granteeGetsThePersonalPlaylistsExclusions() throws Exception {
        when(playlists.findByYoutubeId("PLpersonal1")).thenReturn(Optional.of(personalPlaylist()));

        mvc.perform(as(get("/api/v1/playlists/PLpersonal1"), "grantee-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.excludedVideoIds", contains("video000002")))
                .andExpect(jsonPath("$.personalGrants").doesNotExist());
    }

    /** Anonymous, another user and a rejected token each get exactly the body a missing id gets. */
    @Test
    void nobodyElseCanTellAPersonalChannelFromAMissingOne() throws Exception {
        String missing = body404(null, "/api/v1/channels/UCpersonal1");
        when(channels.findByYoutubeId("UCpersonal1")).thenReturn(Optional.of(personalChannel()));
        for (String bearer : new String[]{null, "other-token", "bad-token"}) {
            assertEquals(missing, body404(bearer, "/api/v1/channels/UCpersonal1"), "caller " + bearer);
        }
    }

    @Test
    void nobodyElseCanTellAPersonalPlaylistFromAMissingOne() throws Exception {
        String missing = body404(null, "/api/v1/playlists/PLpersonal1");
        when(playlists.findByYoutubeId("PLpersonal1")).thenReturn(Optional.of(personalPlaylist()));
        for (String bearer : new String[]{null, "other-token", "bad-token"}) {
            assertEquals(missing, body404(bearer, "/api/v1/playlists/PLpersonal1"), "caller " + bearer);
        }
    }

    /** A grant opens only the item it names: the grantee still gets 404 on someone else's PERSONAL channel. */
    @Test
    void aGrantDoesNotOpenAnotherUsersPersonalChannel() throws Exception {
        Channel others = personalChannel();
        others.setPersonalGrants(List.of("uid-other"));
        when(channels.findByYoutubeId("UCpersonal1")).thenReturn(Optional.of(others));

        mvc.perform(as(get("/api/v1/channels/UCpersonal1"), "grantee-token")).andExpect(status().isNotFound());
    }

    /** A token that fails to verify never breaks a PUBLIC channel: it is read as anonymous, never a 401. */
    @Test
    void aBadTokenStillGetsAPublicChannel() throws Exception {
        Channel open = personalChannel();
        open.setVisibility("PUBLIC");
        when(channels.findByYoutubeId("UCpersonal1")).thenReturn(Optional.of(open));

        mvc.perform(as(get("/api/v1/channels/UCpersonal1"), "bad-token")).andExpect(status().isOk());
    }

    /** An admin's REJECT blocks every caller, grantee or not, as before: the gone check runs before the grant check. */
    @Test
    void aRejectedPersonalChannelIsGoneForEveryone() throws Exception {
        Channel rejected = personalChannel();
        rejected.setStatus("REJECTED");
        when(channels.findByYoutubeId("UCpersonal1")).thenReturn(Optional.of(rejected));

        for (String bearer : new String[]{null, "other-token", "grantee-token"}) {
            mvc.perform(as(get("/api/v1/channels/UCpersonal1"), bearer)).andExpect(status().isGone());
        }
    }

    /** The 404 body without its timestamp, the one field that differs between any two calls. */
    private String body404(String bearer, String path) throws Exception {
        MvcResult result = mvc.perform(as(get(path), bearer)).andExpect(status().isNotFound()).andReturn();
        ObjectNode body = (ObjectNode) json.readTree(result.getResponse().getContentAsString());
        body.remove("timestamp");
        return body.toString();
    }
}
