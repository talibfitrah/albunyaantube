package com.albunyaan.tube.controller;

import com.albunyaan.tube.exception.ContentGoneException;
import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.service.PublicContentService;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Share pages render only server-known text. Tests labelled "Compatibility:" pass on the pre-fix code too: they pin
 * behaviour the fix must keep. Every other share-page test below fails if the fix is reverted.
 */
@WebMvcTest(WatchPageController.class)
@AutoConfigureMockMvc(addFilters = false)
class WatchPageControllerTest {

    private static final String GENERIC_IMAGE = "property=\"og:image\" content=\"https://fitrahtube.com/og.png\"";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicContentService contentService;

    @MockBean
    private FirebaseAuth firebaseAuth;

    @MockBean
    private com.albunyaan.tube.repository.UserRepository userRepository;

    private Video video;

    @BeforeEach
    void setUp() {
        video = new Video();
        video.setId("doc-id");
        video.setYoutubeId("EnfgPg0Ey3I");
        video.setTitle("Test Video");
        video.setDescription("Test description");
        video.setStatus("APPROVED");
        video.setValidationStatus(ValidationStatus.VALID);
    }

    // --- Catalog pages (unchanged behaviour) ---

    @Test
    @DisplayName("GET /watch/{videoId} emits stored thumbnail metadata")
    void watchPage_usesStoredThumbnailForLinkPreview() throws Exception {
        video.setThumbnailUrl("https://example.com/thumb.jpg");
        when(contentService.getVideoDetails("EnfgPg0Ey3I")).thenReturn(video);

        mockMvc.perform(get("/watch/{videoId}", "EnfgPg0Ey3I"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("property=\"og:image\" content=\"https://example.com/thumb.jpg\"")))
                .andExpect(content().string(containsString("name=\"twitter:image\" content=\"https://example.com/thumb.jpg\"")));
    }

    @Test
    @DisplayName("GET /watch/{videoId} falls back to YouTube thumbnail metadata")
    void watchPage_buildsThumbnailFallbackFromYoutubeId() throws Exception {
        video.setThumbnailUrl("");
        when(contentService.getVideoDetails("EnfgPg0Ey3I")).thenReturn(video);

        mockMvc.perform(get("/watch/{videoId}", "EnfgPg0Ey3I"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("property=\"og:image\" content=\"https://i.ytimg.com/vi/EnfgPg0Ey3I/hqdefault.jpg\"")))
                .andExpect(content().string(containsString("property=\"og:image:secure_url\" content=\"https://i.ytimg.com/vi/EnfgPg0Ey3I/hqdefault.jpg\"")))
                .andExpect(content().string(containsString("property=\"og:image:type\" content=\"image/jpeg\"")))
                .andExpect(content().string(containsString("name=\"twitter:image\" content=\"https://i.ytimg.com/vi/EnfgPg0Ey3I/hqdefault.jpg\"")));
    }

    @Test
    @DisplayName("GET /watch/{videoId} normalizes signed YouTube thumbnail URLs for crawlers")
    void watchPage_normalizesSignedYoutubeThumbnailForLinkPreview() throws Exception {
        video.setThumbnailUrl("https://i.ytimg.com/vi/EnfgPg0Ey3I/hqdefault.jpg?sqp=abc&rs=def");
        when(contentService.getVideoDetails("EnfgPg0Ey3I")).thenReturn(video);

        mockMvc.perform(get("/watch/{videoId}", "EnfgPg0Ey3I"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:image\" content=\"https://i.ytimg.com/vi/EnfgPg0Ey3I/hqdefault.jpg\"")))
                .andExpect(content().string(not(containsString("sqp=abc"))));
    }

    @Test
    @DisplayName("GET /api/watch/{videoId} emits share metadata")
    void apiWatchPage_usesSameMetadataAsLegacyWatchRoute() throws Exception {
        video.setThumbnailUrl("https://example.com/thumb.jpg");
        when(contentService.getVideoDetails("EnfgPg0Ey3I")).thenReturn(video);

        mockMvc.perform(get("/api/watch/{videoId}", "EnfgPg0Ey3I"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("property=\"og:image\" content=\"https://example.com/thumb.jpg\"")))
                .andExpect(content().string(containsString("name=\"twitter:image\" content=\"https://example.com/thumb.jpg\"")));
    }

    @Test
    @DisplayName("GET /api/watch/{videoId} keeps HTTPS canonical URL behind production proxy")
    void apiWatchPage_buildsHttpsCanonicalUrlBehindProxy() throws Exception {
        video.setThumbnailUrl("https://example.com/thumb.jpg");
        when(contentService.getVideoDetails("EnfgPg0Ey3I")).thenReturn(video);

        mockMvc.perform(get("/api/watch/{videoId}", "EnfgPg0Ey3I")
                        .header("X-Forwarded-Proto", "http")
                        .header("X-Forwarded-Host", "app.fitrahtube.com"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:url\" content=\"https://app.fitrahtube.com/api/watch/EnfgPg0Ey3I\"")));
    }

    @Test
    @DisplayName("GET /api/channel/{channelId} emits channel share metadata")
    void channelSharePage_usesChannelThumbnailForLinkPreview() throws Exception {
        Channel channel = new Channel("UCabc123");
        channel.setStatus("APPROVED");
        channel.setName("Test Channel");
        channel.setDescription("Channel description");
        channel.setThumbnailUrl("https://yt3.googleusercontent.com/avatar=s160-c-k-c0x00ffffff-no-rj");
        when(contentService.getChannelDetails("UCabc123")).thenReturn(channel);

        mockMvc.perform(get("/api/channel/{channelId}", "UCabc123"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("property=\"og:type\" content=\"profile\"")))
                .andExpect(content().string(containsString("property=\"og:image\" content=\"https://yt3.googleusercontent.com/avatar=s512-c-k-c0x00ffffff-no-rj\"")))
                .andExpect(content().string(containsString("property=\"og:image:width\" content=\"512\"")))
                .andExpect(content().string(containsString("property=\"og:image:height\" content=\"512\"")))
                .andExpect(content().string(containsString("name=\"twitter:image\" content=\"https://yt3.googleusercontent.com/avatar=s512-c-k-c0x00ffffff-no-rj\"")));
    }

    @Test
    @DisplayName("GET /api/playlist/{playlistId} emits playlist share metadata")
    void playlistSharePage_usesPlaylistThumbnailForLinkPreview() throws Exception {
        Playlist playlist = new Playlist("PLabc123");
        playlist.setStatus("APPROVED");
        playlist.setTitle("Test Playlist");
        playlist.setDescription("Playlist description");
        playlist.setThumbnailUrl("https://i.ytimg.com/vi/EnfgPg0Ey3I/hqdefault.jpg?sqp=abc");
        playlist.setItemCount(12);
        when(contentService.getPlaylistDetails("PLabc123")).thenReturn(playlist);

        mockMvc.perform(get("/api/playlist/{playlistId}", "PLabc123"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("property=\"og:image\" content=\"https://i.ytimg.com/vi/EnfgPg0Ey3I/hqdefault.jpg\"")))
                .andExpect(content().string(not(containsString("sqp=abc"))));
    }

    @Test
    @DisplayName("GET /watch/{videoId} rejects malformed ids before lookup")
    void watchPage_rejectsMalformedVideoId() throws Exception {
        mockMvc.perform(get("/watch/{videoId}", "bad<script>"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("og:image"))));

        verify(contentService, never()).getVideoDetails(anyString());
    }

    // --- Compatibility: pass before and after the fix ---

    @Test
    @DisplayName("Compatibility: a catalog video keeps its own title, description and image whatever the query says")
    void watchPage_catalogTextWinsOverQueryMetadata() throws Exception {
        video.setThumbnailUrl("https://i.ytimg.com/vi/EnfgPg0Ey3I/hqdefault.jpg");
        when(contentService.getVideoDetails("EnfgPg0Ey3I")).thenReturn(video);

        mockMvc.perform(get("/watch/{videoId}", "EnfgPg0Ey3I")
                        .param("title", "Phishing Title")
                        .param("description", "Phishing description")
                        .param("image", "https://example.com/spoof.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:title\" content=\"Test Video\"")))
                .andExpect(content().string(containsString("property=\"og:description\" content=\"Test description\"")))
                .andExpect(content().string(containsString("property=\"og:image\" content=\"https://i.ytimg.com/vi/EnfgPg0Ey3I/hqdefault.jpg\"")))
                .andExpect(content().string(not(containsString("Phishing"))))
                .andExpect(content().string(not(containsString("example.com"))));
    }

    @Test
    @DisplayName("Compatibility: a video outside the catalog answers 200 titled FitrahTube Video")
    void apiWatchPage_nonCatalogVideoKeepsTheFitrahTubeVideoTitle() throws Exception {
        when(contentService.getVideoDetails("MissingVid1")).thenThrow(new ResourceNotFoundException("Video", "MissingVid1"));

        mockMvc.perform(get("/api/watch/{videoId}", "MissingVid1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:title\" content=\"FitrahTube Video\"")));
    }

    @Test
    @DisplayName("Compatibility: catalog text is HTML-escaped")
    void watchPage_catalogTextIsHtmlEscaped() throws Exception {
        video.setTitle("<img src=x onerror=alert(1)> \"quoted\" 'single'");
        video.setChannelTitle("<b>Chan</b>");
        when(contentService.getVideoDetails("EnfgPg0Ey3I")).thenReturn(video);

        mockMvc.perform(get("/watch/{videoId}", "EnfgPg0Ey3I"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "property=\"og:title\" content=\"&lt;img src=x onerror=alert(1)&gt; &quot;quoted&quot; &#39;single&#39;\"")))
                .andExpect(content().string(containsString("&lt;b&gt;Chan&lt;/b&gt;")))
                .andExpect(content().string(not(containsString("<img src=x"))))
                .andExpect(content().string(not(containsString("<b>Chan"))));
    }

    @Test
    @DisplayName("Compatibility: a blocked (REJECTED/ARCHIVED) channel or playlist keeps its 404 not-available page")
    void blockedChannelAndPlaylistKeepTheirNotAvailablePage() throws Exception {
        when(contentService.getChannelDetails("UCblocked")).thenThrow(new ContentGoneException("Channel", "UCblocked"));
        when(contentService.getPlaylistDetails("PLblocked")).thenThrow(new ContentGoneException("Playlist", "PLblocked"));

        mockMvc.perform(get("/api/channel/{channelId}", "UCblocked"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("This channel is not available.")))
                .andExpect(content().string(not(containsString("og:image"))));
        mockMvc.perform(get("/api/playlist/{playlistId}", "PLblocked"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("This playlist is not available.")))
                .andExpect(content().string(not(containsString("og:image"))));
    }

    // --- Outside the catalog: one generic, server-authored card (fail on revert) ---

    @Test
    @DisplayName("GET /api/watch/{videoId} outside the catalog is the generic FitrahTube card: logo, no YouTube thumbnail")
    void apiWatchPage_nonCatalogVideoGetsTheGenericCard() throws Exception {
        when(contentService.getVideoDetails("MissingVid1")).thenThrow(new ResourceNotFoundException("Video", "MissingVid1"));

        mockMvc.perform(get("/api/watch/{videoId}", "MissingVid1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString(GENERIC_IMAGE)))
                .andExpect(content().string(containsString("property=\"og:image:width\" content=\"1200\"")))
                .andExpect(content().string(containsString("property=\"og:image:height\" content=\"630\"")))
                .andExpect(content().string(containsString("href=\"albunyaantube://video/MissingVid1\"")))
                .andExpect(content().string(not(containsString("ytimg"))));
    }

    @Test
    @DisplayName("GET /api/watch/{videoId} never renders caller-supplied text or images")
    void apiWatchPage_ignoresQueryMetadata() throws Exception {
        when(contentService.getVideoDetails("MissingVid1")).thenThrow(new ResourceNotFoundException("Video", "MissingVid1"));

        mockMvc.perform(get("/api/watch/{videoId}", "MissingVid1")
                        .param("title", "Phishing Title")
                        .param("description", "Phishing description")
                        .param("image", "https://example.com/channel-video.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:title\" content=\"FitrahTube Video\"")))
                .andExpect(content().string(containsString(GENERIC_IMAGE)))
                .andExpect(content().string(not(containsString("Phishing"))))
                .andExpect(content().string(not(containsString("example.com"))))
                .andExpect(content().string(not(containsString("ytimg"))));
    }

    @Test
    @DisplayName("A blocked video keeps its 200 card, now the generic one with no YouTube thumbnail")
    void apiWatchPage_blockedVideoGetsTheGenericCard() throws Exception {
        when(contentService.getVideoDetails("BlockedVid1")).thenThrow(new ContentGoneException("Video", "BlockedVid1"));

        mockMvc.perform(get("/api/watch/{videoId}", "BlockedVid1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(GENERIC_IMAGE)))
                .andExpect(content().string(not(containsString("ytimg"))));
    }

    @Test
    @DisplayName("GET /api/channel/{channelId} outside the catalog is the generic card, whatever the query says")
    void channelSharePage_ignoresQueryMetadata() throws Exception {
        when(contentService.getChannelDetails("UCmissing")).thenThrow(new ResourceNotFoundException("Channel", "UCmissing"));

        mockMvc.perform(get("/api/channel/{channelId}", "UCmissing")
                        .param("title", "Phishing Channel")
                        .param("description", "Phishing description")
                        .param("image", "https://yt3.googleusercontent.com/spoof=s160"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:title\" content=\"FitrahTube Channel\"")))
                .andExpect(content().string(containsString(GENERIC_IMAGE)))
                .andExpect(content().string(containsString("href=\"albunyaantube://channel/UCmissing\"")))
                .andExpect(content().string(not(containsString("Phishing"))))
                .andExpect(content().string(not(containsString("spoof"))));
    }

    @Test
    @DisplayName("GET /api/playlist/{playlistId} outside the catalog is the generic card, whatever the query says")
    void playlistSharePage_ignoresQueryMetadata() throws Exception {
        when(contentService.getPlaylistDetails("PLmissing")).thenThrow(new ResourceNotFoundException("Playlist", "PLmissing"));

        mockMvc.perform(get("/api/playlist/{playlistId}", "PLmissing")
                        .param("title", "On-device playlist")
                        .param("description", "Phishing description")
                        .param("image", "https://example.com/playlist.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:title\" content=\"FitrahTube Playlist\"")))
                .andExpect(content().string(containsString(GENERIC_IMAGE)))
                .andExpect(content().string(containsString("href=\"albunyaantube://playlist/PLmissing\"")))
                .andExpect(content().string(not(containsString("On-device playlist"))))
                .andExpect(content().string(not(containsString("Phishing"))))
                .andExpect(content().string(not(containsString("example.com"))));
    }

    // --- POST /api/share-metadata is a no-op (fail on revert) ---

    @Test
    @DisplayName("POST /api/share-metadata is a no-op 204 and never reaches a channel page")
    void shareMetadataPostIsANoOpAndNeverReachesThePage() throws Exception {
        when(contentService.getChannelDetails("UCmissing")).thenThrow(new ResourceNotFoundException("Channel", "UCmissing"));

        mockMvc.perform(post("/api/share-metadata/{type}/{id}", "channel", "UCmissing")
                        .header("X-Device-Id", "test-device-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cached Channel\",\"image\":\"https://yt3.googleusercontent.com/avatar=s160\",\"description\":\"x\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/share-metadata/{type}/{id}", "nonsense", "bad<id>").content("not json"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/channel/{channelId}", "UCmissing"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Cached Channel"))))
                .andExpect(content().string(not(containsString("avatar=s"))));
    }

    @Test
    @DisplayName("A poisoned POST never changes a watch page outside the catalog")
    void poisonedPost_neverReachesTheWatchPage() throws Exception {
        when(contentService.getVideoDetails("PoisonVid01")).thenThrow(new ResourceNotFoundException("Video", "PoisonVid01"));

        mockMvc.perform(post("/api/share-metadata/{type}/{id}", "watch", "PoisonVid01")
                        .header("X-Device-Id", "test-device-poison")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Poisoned Title\",\"description\":\"Poisoned description\","
                                + "\"image\":\"https://i.ytimg.com/vi/PoisonVid01/maxresdefault.jpg\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/watch/{videoId}", "PoisonVid01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:title\" content=\"FitrahTube Video\"")))
                .andExpect(content().string(not(containsString("Poisoned"))))
                .andExpect(content().string(not(containsString("maxresdefault"))));
    }

    @Test
    @DisplayName("A poisoned POST never changes a playlist page outside the catalog")
    void poisonedPost_neverReachesThePlaylistPage() throws Exception {
        when(contentService.getPlaylistDetails("PLpoisoned")).thenThrow(new ResourceNotFoundException("Playlist", "PLpoisoned"));

        mockMvc.perform(post("/api/share-metadata/{type}/{id}", "playlist", "PLpoisoned")
                        .header("X-Device-Id", "test-device-poison")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Poisoned Playlist\",\"description\":\"Poisoned description\","
                                + "\"image\":\"https://i.ytimg.com/vi/EnfgPg0Ey3I/maxresdefault.jpg\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/playlist/{playlistId}", "PLpoisoned"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:title\" content=\"FitrahTube Playlist\"")))
                .andExpect(content().string(not(containsString("Poisoned"))))
                .andExpect(content().string(not(containsString("maxresdefault"))));
    }

    @Test
    @DisplayName("GET /api/channel/{channelId} never fills a catalog channel's blank fields from posted or query text")
    void channelSharePage_catalogBlankFieldsStayServerOnly() throws Exception {
        Channel channel = new Channel("UCblank1");
        channel.setStatus("APPROVED");
        channel.setName("Catalog Channel");
        when(contentService.getChannelDetails("UCblank1")).thenReturn(channel);

        mockMvc.perform(post("/api/share-metadata/{type}/{id}", "channel", "UCblank1")
                        .header("X-Device-Id", "test-device-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Posted Title\",\"image\":\"https://yt3.googleusercontent.com/posted=s160\",\"description\":\"Posted description\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/channel/{channelId}", "UCblank1")
                        .param("title", "Query Title")
                        .param("description", "Query description")
                        .param("image", "https://yt3.googleusercontent.com/query=s160"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:title\" content=\"Catalog Channel\"")))
                .andExpect(content().string(not(containsString("Posted"))))
                .andExpect(content().string(not(containsString("posted="))))
                .andExpect(content().string(not(containsString("Query"))))
                .andExpect(content().string(not(containsString("query="))));
    }

    @Test
    @DisplayName("GET /api/playlist/{playlistId} never fills a catalog playlist's blank fields from posted or query text")
    void playlistSharePage_catalogBlankFieldsStayServerOnly() throws Exception {
        Playlist playlist = new Playlist("PLblank1");
        playlist.setStatus("APPROVED");
        playlist.setTitle("Catalog Playlist");
        when(contentService.getPlaylistDetails("PLblank1")).thenReturn(playlist);

        mockMvc.perform(post("/api/share-metadata/{type}/{id}", "playlist", "PLblank1")
                        .header("X-Device-Id", "test-device-blank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Posted Title\",\"description\":\"Posted description\","
                                + "\"image\":\"https://i.ytimg.com/vi/EnfgPg0Ey3I/maxresdefault.jpg\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/playlist/{playlistId}", "PLblank1")
                        .param("title", "Query Title")
                        .param("description", "Query description")
                        .param("image", "https://example.com/query.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:title\" content=\"Catalog Playlist\"")))
                .andExpect(content().string(not(containsString("Posted"))))
                .andExpect(content().string(not(containsString("maxresdefault"))))
                .andExpect(content().string(not(containsString("Query"))))
                .andExpect(content().string(not(containsString("example.com"))));
    }

    // --- A transient registry error is a non-cacheable 503, never a fallback (fail on revert) ---

    @Test
    @DisplayName("A registry error on a catalog channel answers 503 no-store, not a cacheable 404")
    void channelSharePage_registryErrorIsANoStore503() throws Exception {
        when(contentService.getChannelDetails("UCabc123")).thenThrow(new ExecutionException("firestore", new RuntimeException("unavailable")));

        mockMvc.perform(get("/api/channel/{channelId}", "UCabc123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(not(containsString("og:image"))));
    }

    @Test
    @DisplayName("A registry error on a catalog playlist answers 503 no-store, not a cacheable 404")
    void playlistSharePage_registryErrorIsANoStore503() throws Exception {
        when(contentService.getPlaylistDetails("PLabc123")).thenThrow(new TimeoutException("deadline"));

        mockMvc.perform(get("/api/playlist/{playlistId}", "PLabc123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(not(containsString("og:image"))));
    }

    @Test
    @DisplayName("A registry error on a catalog video answers 503 no-store and never renders query text")
    void watchPage_registryErrorIsANoStore503EvenWithQueryMetadata() throws Exception {
        when(contentService.getVideoDetails("EnfgPg0Ey3I")).thenThrow(new TimeoutException("deadline"));

        mockMvc.perform(get("/watch/{videoId}", "EnfgPg0Ey3I")
                        .param("title", "Phishing Title")
                        .param("description", "Phishing description")
                        .param("image", "https://example.com/spoof.jpg"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(not(containsString("Phishing"))))
                .andExpect(content().string(not(containsString("example.com"))));
    }

    // --- og:url and canonical come from the trusted origin, never request headers (fail on revert) ---

    @Test
    @DisplayName("Hostile Host and X-Forwarded-Host never reach og:url or the canonical link of a catalog video")
    void watchPage_hostileHostHeadersNeverReachThePage() throws Exception {
        when(contentService.getVideoDetails("EnfgPg0Ey3I")).thenReturn(video);

        mockMvc.perform(get("/api/watch/{videoId}", "EnfgPg0Ey3I")
                        .header("Host", "evil.example")
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "http"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:url\" content=\"https://app.fitrahtube.com/api/watch/EnfgPg0Ey3I\"")))
                .andExpect(content().string(containsString("rel=\"canonical\" href=\"https://app.fitrahtube.com/api/watch/EnfgPg0Ey3I\"")))
                .andExpect(content().string(not(containsString("evil.example"))));
    }

    @Test
    @DisplayName("Hostile Host and X-Forwarded-Host never reach og:url of a generic card on the non-/api route")
    void channelSharePage_hostileHostHeadersNeverReachTheGenericCard() throws Exception {
        when(contentService.getChannelDetails("UCmissing")).thenThrow(new ResourceNotFoundException("Channel", "UCmissing"));

        mockMvc.perform(get("/channel/{channelId}", "UCmissing")
                        .header("Host", "evil.example:8443")
                        .header("X-Forwarded-Host", "evil.example"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:url\" content=\"https://app.fitrahtube.com/channel/UCmissing\"")))
                .andExpect(content().string(not(containsString("evil.example"))));
    }
}
