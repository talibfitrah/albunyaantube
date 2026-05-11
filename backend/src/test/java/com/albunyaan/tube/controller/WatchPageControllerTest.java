package com.albunyaan.tube.controller;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WatchPageController.class)
@AutoConfigureMockMvc(addFilters = false)
class WatchPageControllerTest {

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
    @DisplayName("GET /api/watch/{videoId} keeps previews for on-device only videos")
    void apiWatchPage_missingBackendVideoStillEmitsYoutubeThumbnail() throws Exception {
        when(contentService.getVideoDetails("MissingVid1")).thenThrow(new RuntimeException("not found"));

        mockMvc.perform(get("/api/watch/{videoId}", "MissingVid1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("property=\"og:image\" content=\"https://i.ytimg.com/vi/MissingVid1/hqdefault.jpg\"")))
                .andExpect(content().string(containsString("name=\"twitter:image\" content=\"https://i.ytimg.com/vi/MissingVid1/hqdefault.jpg\"")));
    }

    @Test
    @DisplayName("GET /api/watch/{videoId} can fall back to app-supplied title and image")
    void apiWatchPage_missingBackendVideoUsesQueryMetadata() throws Exception {
        when(contentService.getVideoDetails("MissingVid1")).thenThrow(new RuntimeException("not found"));

        mockMvc.perform(get("/api/watch/{videoId}", "MissingVid1")
                        .param("title", "Channel video")
                        .param("image", "https://example.com/channel-video.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:title\" content=\"Channel video\"")))
                .andExpect(content().string(containsString("property=\"og:image\" content=\"https://example.com/channel-video.jpg\"")))
                .andExpect(content().string(containsString("name=\"twitter:image\" content=\"https://example.com/channel-video.jpg\"")));
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
    @DisplayName("GET /api/channel/{channelId} can use cached app metadata without query params")
    void channelSharePage_usesCachedMetadataForCleanUrl() throws Exception {
        when(contentService.getChannelDetails("UCmissing")).thenThrow(new RuntimeException("not found"));

        mockMvc.perform(post("/api/share-metadata/{type}/{id}", "channel", "UCmissing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Cached Channel",
                                  "image": "https://yt3.googleusercontent.com/avatar=s160-c-k-c0x00ffffff-no-rj",
                                  "description": "Cached description"
                                }
                                """))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/channel/{channelId}", "UCmissing"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:title\" content=\"Cached Channel\"")))
                .andExpect(content().string(containsString("property=\"og:image\" content=\"https://yt3.googleusercontent.com/avatar=s512-c-k-c0x00ffffff-no-rj\"")))
                .andExpect(content().string(containsString("property=\"og:url\" content=\"http://localhost/api/channel/UCmissing\"")))
                .andExpect(content().string(not(containsString("?title="))));
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
    @DisplayName("GET /api/playlist/{playlistId} can fall back to app-supplied metadata")
    void playlistSharePage_usesQueryMetadataWhenBackendDoesNotHavePlaylist() throws Exception {
        when(contentService.getPlaylistDetails("PLmissing")).thenThrow(new RuntimeException("not found"));

        mockMvc.perform(get("/api/playlist/{playlistId}", "PLmissing")
                        .param("title", "On-device playlist")
                        .param("image", "https://example.com/playlist.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("property=\"og:title\" content=\"On-device playlist\"")))
                .andExpect(content().string(containsString("property=\"og:image\" content=\"https://example.com/playlist.jpg\"")));
    }

    @Test
    @DisplayName("GET /watch/{videoId} rejects malformed ids before lookup")
    void watchPage_rejectsMalformedVideoId() throws Exception {
        mockMvc.perform(get("/watch/{videoId}", "bad<script>"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("og:image"))));

        verify(contentService, never()).getVideoDetails(anyString());
    }
}
