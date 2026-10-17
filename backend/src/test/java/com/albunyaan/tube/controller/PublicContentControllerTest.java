package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.exception.GlobalExceptionHandler;
import com.albunyaan.tube.exception.ContentGoneException;
import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.model.ApprovalMetadata;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.search.SearchIndex;
import com.albunyaan.tube.service.PublicContentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for PublicContentController.
 * Tests edge cases and error handling for video endpoints.
 * FIXED: Import GlobalExceptionHandler to handle exceptions properly in tests
 */
@WebMvcTest(PublicContentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
public class PublicContentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicContentService contentService;

    @MockBean
    private FirebaseAuth firebaseAuth;

    @MockBean
    private com.albunyaan.tube.repository.UserRepository userRepository;

    private Video testVideo;

    @BeforeEach
    void setUp() {
        testVideo = new Video();
        testVideo.setId("test-video-id");
        testVideo.setYoutubeId("EnfgPg0Ey3I");
        testVideo.setTitle("Test Video");
        testVideo.setDescription("Test description");
        testVideo.setThumbnailUrl("https://example.com/thumb.jpg");
        testVideo.setDurationSeconds(240);
        testVideo.setStatus("APPROVED");
        testVideo.setValidationStatus(ValidationStatus.VALID);
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - Success")
    void testGetVideoDetails_Success() throws Exception {
        // Given
        when(contentService.getVideoDetails(anyString()))
                .thenReturn(testVideo);

        // When & Then
        mockMvc.perform(get("/api/v1/videos/{videoId}", "EnfgPg0Ey3I")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.youtubeId").value("EnfgPg0Ey3I"))
                .andExpect(jsonPath("$.title").value("Test Video"));
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - Video Not Found (404)")
    void testGetVideoDetails_NotFound() throws Exception {
        // Given
        when(contentService.getVideoDetails(anyString()))
                .thenThrow(new ResourceNotFoundException("Video", "nonexistent-id"));

        // When & Then
        mockMvc.perform(get("/api/v1/videos/{videoId}", "nonexistent-id")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - Invalid Video ID Format")
    void testGetVideoDetails_InvalidIdFormat() throws Exception {
        // Given - use invalid YouTube ID format (contains invalid chars)
        String invalidId = "invalid@#$%";
        when(contentService.getVideoDetails(invalidId))
                .thenThrow(new ResourceNotFoundException("Video", invalidId));

        // When & Then
        mockMvc.perform(get("/api/v1/videos/{videoId}", invalidId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - Pending Video → 410 (videos are admin-submitted to registry; block until approved, unlike channels/playlists which may be browse-discovered)")
    void testGetVideoDetails_PendingVideo() throws Exception {
        when(contentService.getVideoDetails(anyString()))
                .thenThrow(new ContentGoneException("Video", "pending-video-id"));

        mockMvc.perform(get("/api/v1/videos/{videoId}", "pending-video-id")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.error").value("Gone"));
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - Unavailable/Archived Video → 410")
    void testGetVideoDetails_UnavailableVideo() throws Exception {
        when(contentService.getVideoDetails(anyString()))
                .thenThrow(new ContentGoneException("Video", "unavailable-video-id"));

        mockMvc.perform(get("/api/v1/videos/{videoId}", "unavailable-video-id")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.error").value("Gone"));
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - offlineAllowed serialized when set (iOS Phase 3 gate)")
    void testGetVideoDetails_OfflineAllowedSerialized() throws Exception {
        testVideo.setOfflineAllowed(true);
        when(contentService.getVideoDetails(anyString())).thenReturn(testVideo);

        mockMvc.perform(get("/api/v1/videos/{videoId}", "EnfgPg0Ey3I")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offlineAllowed").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - never-toggled offlineAllowed is served as the effective true")
    void testGetVideoDetails_OfflineAllowedAbsentOnLegacyDoc() throws Exception {
        // testVideo never had the field set — mirrors every production document (0 of 249 toggled).
        // Owner ruling 2026-09-27: only an explicit admin false blocks saving, so the public API
        // serves the effective value, never the raw null.
        when(contentService.getVideoDetails(anyString())).thenReturn(testVideo);

        mockMvc.perform(get("/api/v1/videos/{videoId}", "EnfgPg0Ey3I")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offlineAllowed").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - an explicit admin false stays false")
    void testGetVideoDetails_OfflineAllowedExplicitFalseStaysFalse() throws Exception {
        testVideo.setOfflineAllowed(false);
        when(contentService.getVideoDetails(anyString())).thenReturn(testVideo);

        mockMvc.perform(get("/api/v1/videos/{videoId}", "EnfgPg0Ey3I")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offlineAllowed").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - Special Characters in Video ID")
    void testGetVideoDetails_SpecialCharacters() throws Exception {
        // Given
        String specialVideoId = "test-id-with-dashes_and_underscores";
        testVideo.setYoutubeId(specialVideoId);
        when(contentService.getVideoDetails(specialVideoId))
                .thenReturn(testVideo);

        // When & Then
        mockMvc.perform(get("/api/v1/videos/{videoId}", specialVideoId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.youtubeId").value(specialVideoId));
    }

    @Test
    @DisplayName("GET /api/v1/content - Success with default parameters")
    void testGetContent_DefaultParameters() throws Exception {
        // Given
        CursorPageDto page = new CursorPageDto(Collections.emptyList(), null);
        when(contentService.getContent(
                anyString(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/v1/content")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/content - With category filter")
    void testGetContent_WithCategoryFilter() throws Exception {
        // Given
        CursorPageDto page = new CursorPageDto(Collections.emptyList(), null);
        when(contentService.getContent(
                anyString(), any(), anyInt(), eq("islamic-lectures"), any(), any(), any()))
                .thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/v1/content")
                        .param("category", "islamic-lectures")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/content - Limit validation (max 50)")
    void testGetContent_LimitValidation() throws Exception {
        // Given
        CursorPageDto page = new CursorPageDto(Collections.emptyList(), null);
        when(contentService.getContent(
                anyString(), any(), eq(50), any(), any(), any(), any()))
                .thenReturn(page);

        // When & Then - Request 100, should be capped at 50
        mockMvc.perform(get("/api/v1/content")
                        .param("limit", "100")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/content - Limit validation (min 1)")
    void testGetContent_LimitValidation_Minimum() throws Exception {
        // Given
        CursorPageDto page = new CursorPageDto(Collections.emptyList(), null);
        when(contentService.getContent(
                anyString(), any(), eq(1), any(), any(), any(), any()))
                .thenReturn(page);

        // When & Then - Request 0, should be set to 1
        mockMvc.perform(get("/api/v1/content")
                        .param("limit", "0")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/categories - Success")
    void testGetCategories_Success() throws Exception {
        // Given
        when(contentService.getCategories())
                .thenReturn(Collections.emptyList());

        // When & Then
        mockMvc.perform(get("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/channels/{channelId} - Channel Not Found → 404")
    void testGetChannelDetails_NotFound() throws Exception {
        when(contentService.getChannelDetails(anyString()))
                .thenThrow(new ResourceNotFoundException("Channel", "nonexistent"));

        mockMvc.perform(get("/api/v1/channels/{channelId}", "nonexistent")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/channels/{channelId} - REJECTED/ARCHIVED channel → 410")
    void testGetChannelDetails_Gone() throws Exception {
        when(contentService.getChannelDetails(anyString()))
                .thenThrow(new ContentGoneException("Channel", "UCblocked"));

        mockMvc.perform(get("/api/v1/channels/{channelId}", "UCblocked")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.error").value("Gone"));
    }

    @Test
    @DisplayName("GET /api/v1/playlists/{playlistId} - Playlist Not Found → 404")
    void testGetPlaylistDetails_NotFound() throws Exception {
        when(contentService.getPlaylistDetails(anyString()))
                .thenThrow(new ResourceNotFoundException("Playlist", "nonexistent"));

        mockMvc.perform(get("/api/v1/playlists/{playlistId}", "nonexistent")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/playlists/{playlistId} - REJECTED/ARCHIVED playlist → 410")
    void testGetPlaylistDetails_Gone() throws Exception {
        when(contentService.getPlaylistDetails(anyString()))
                .thenThrow(new ContentGoneException("Playlist", "PLblocked"));

        mockMvc.perform(get("/api/v1/playlists/{playlistId}", "PLblocked")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.error").value("Gone"));
    }

    @Test
    @DisplayName("GET /api/v1/search - Success with query")
    void testSearch_Success() throws Exception {
        // Given
        when(contentService.search(anyString(), any(), anyInt()))
                .thenReturn(SearchIndex.Hits.NONE);

        // When & Then
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "islamic lectures")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/search - Empty query")
    void testSearch_EmptyQuery() throws Exception {
        // Given
        when(contentService.search(eq(""), any(), anyInt()))
                .thenReturn(SearchIndex.Hits.NONE);

        // When & Then
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/search - With type filter")
    void testSearch_WithTypeFilter() throws Exception {
        // Given
        when(contentService.search(anyString(), eq("VIDEOS"), anyInt()))
                .thenReturn(SearchIndex.Hits.NONE);

        // When & Then
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "test")
                        .param("type", "VIDEOS")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/search - Limit validation")
    void testSearch_LimitValidation() throws Exception {
        // Given
        when(contentService.search(anyString(), any(), eq(50)))
                .thenReturn(SearchIndex.Hits.NONE);

        // When & Then - Request 100, should be capped at 50
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "test")
                        .param("limit", "100")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/search - X-Search-Match header, body still a bare array of the same keys")
    void testSearch_SetsMatchHeaderAndKeepsTheArrayBody() throws Exception {
        ContentItemDto channel = ContentItemDto.channel("UCzakir", "Dr Zakir Naik", null, null, null, null, null, List.of("kw"));
        when(contentService.search(eq("zakit"), any(), anyInt())).thenReturn(new SearchIndex.Hits(List.of(channel), "approximate"));

        mockMvc.perform(get("/api/v1/search").param("q", "zakit"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Search-Match", "approximate"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value("UCzakir"))
                .andExpect(jsonPath("$[0].type").value("CHANNEL"))
                .andExpect(jsonPath("$[0].name").value("Dr Zakir Naik"))
                .andExpect(jsonPath("$[0].keywords").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/search - stream and inner hits carry sourceType/sourceId; catalog hits carry neither (R28)")
    void testSearch_ProvenanceOnlyOnStreamAndInnerHits() throws Exception {
        ContentItemDto inner = ContentItemDto.video("ilgiwqpcOC8", "Lezing", null, 2712, null, null,
                "https://i.ytimg.com/vi/ilgiwqpcOC8/hqdefault.jpg", null, "Dawah Nederland", null);
        inner.setSourceType("CHANNEL");
        inner.setSourceId("UCfassTU6mGWIj-yb26_ADjQ");
        ContentItemDto channel = ContentItemDto.channel("UCzakir", "Dr Zakir Naik", null, null, null, null, null, List.of());
        when(contentService.search(eq("lezing"), any(), anyInt())).thenReturn(new SearchIndex.Hits(List.of(inner, channel), "exact"));

        mockMvc.perform(get("/api/v1/search").param("q", "lezing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].type").value("VIDEO"))
                .andExpect(jsonPath("$[0].sourceType").value("CHANNEL"))
                .andExpect(jsonPath("$[0].sourceId").value("UCfassTU6mGWIj-yb26_ADjQ"))
                .andExpect(jsonPath("$[1].sourceType").doesNotExist())
                .andExpect(jsonPath("$[1].sourceId").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/search - 503 + Retry-After while the index builds")
    void testSearch_NotReadyIs503() throws Exception {
        when(contentService.search(anyString(), any(), anyInt())).thenThrow(new SearchIndex.NotReadyException());

        mockMvc.perform(get("/api/v1/search").param("q", "quran"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
    }

    @Test
    @DisplayName("GET /api/v1/titles - known ids only, an hour of public caching; bad input 400; a cold index 503 (Task 24b)")
    void testTitles() throws Exception {
        when(contentService.titles(List.of("ilgiwqpcOC8", "cBjnzYHSV4M")))
                .thenReturn(new SearchIndex.Titles(Map.of("ilgiwqpcOC8", "Lezing: 'De rechtvaardige kalief'"), true));
        mockMvc.perform(get("/api/v1/titles").param("ids", "ilgiwqpcOC8,cBjnzYHSV4M,ilgiwqpcOC8")) // duplicates collapse
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=3600")))
                .andExpect(header().string("Cache-Control", containsString("public")))
                .andExpect(jsonPath("$.ilgiwqpcOC8").value("Lezing: 'De rechtvaardige kalief'"))
                .andExpect(jsonPath("$.cBjnzYHSV4M").doesNotExist());

        String fiftyOne = java.util.stream.IntStream.range(0, 51).mapToObj(i -> String.format("lesson%05d", i))
                .collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(get("/api/v1/titles").param("ids", fiftyOne)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/titles").param("ids", "not-an-id")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/titles")).andExpect(status().isBadRequest());
        verify(contentService, times(1)).titles(any());

        when(contentService.titles(any())).thenThrow(new SearchIndex.NotReadyException());
        mockMvc.perform(get("/api/v1/titles").param("ids", "ilgiwqpcOC8")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
    }

    @Test
    @DisplayName("GET /api/v1/titles - a partial answer (inner budget, failure, not serving yet) is shared-cached for a minute only")
    void testTitles_PartialAnswerIsCachedForAMinute() throws Exception {
        when(contentService.titles(List.of("ilgiwqpcOC8")))
                .thenReturn(new SearchIndex.Titles(Map.of(), false));
        mockMvc.perform(get("/api/v1/titles").param("ids", "ilgiwqpcOC8"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"));
        when(contentService.titles(List.of("cBjnzYHSV4M")))
                .thenReturn(new SearchIndex.Titles(Map.of(), true));
        mockMvc.perform(get("/api/v1/titles").param("ids", "cBjnzYHSV4M"))
                .andExpect(header().string("Cache-Control", "max-age=3600, public"));
    }

    @Test
    @DisplayName("GET /api/v1/content?q= - in-tab search sets X-Search-Match")
    void testContent_InTabSearchSetsMatchHeader() throws Exception {
        when(contentService.searchContent(eq("CHANNELS"), any(), anyInt(), any(), any(), any(), eq("zakit")))
                .thenReturn(new PublicContentService.SearchPage(new CursorPageDto<>(List.of(), null), "approximate"));

        mockMvc.perform(get("/api/v1/content").param("type", "CHANNELS").param("q", "zakit"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Search-Match", "approximate"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/content?q= - relevance overrides the tab's sort, which never reaches the plain list")
    void testContent_InTabSearchIgnoresTheTabSort() throws Exception {
        when(contentService.searchContent(eq("VIDEOS"), any(), anyInt(), any(), any(), any(), eq("zakit")))
                .thenReturn(new PublicContentService.SearchPage(new CursorPageDto<>(List.of(), null), "exact"));

        mockMvc.perform(get("/api/v1/content").param("type", "VIDEOS").param("q", "zakit").param("sort", "NEWEST"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Search-Match", "exact"));

        verify(contentService, never()).getContent(any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/v1/content?q=<2 chars - plain list, no X-Search-Match header (ruling F14)")
    void testContent_OneCharacterQueryServesThePlainList() throws Exception {
        when(contentService.getContent(eq("CHANNELS"), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new CursorPageDto<>(List.of(), null));

        mockMvc.perform(get("/api/v1/content").param("type", "CHANNELS").param("q", "z"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Search-Match"));
    }

    @Test
    @DisplayName("GET /api/v1/content?q= - 503 + Retry-After while the index builds")
    void testContent_InTabSearchNotReadyIs503() throws Exception {
        when(contentService.searchContent(any(), any(), anyInt(), any(), any(), any(), anyString()))
                .thenThrow(new SearchIndex.NotReadyException());

        mockMvc.perform(get("/api/v1/content").param("type", "CHANNELS").param("q", "zakit"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
    }

    // Privacy (1.0.4): the public by-id routes served the raw Firestore entity, staff uids and the
    // reviewer's email included. Each route must serve exactly the public field set: what a client
    // reads (iOS OfflineGateClient: youtubeId + offlineAllowed; PublicHeaders: channel youtubeId/name/
    // thumbnailUrl, playlist title/thumbnailUrl/itemCount) plus the content metadata ContentItemDto serves.

    private static final String STAFF_UID = "staffUid00000000000000000001";
    private static final String STAFF_EMAIL = "reviewer@staff.example";
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    @Autowired
    private ObjectMapper objectMapper;

    private static ApprovalMetadata staffReview() {
        ApprovalMetadata review = new ApprovalMetadata(STAFF_UID, STAFF_EMAIL, "internal review note");
        review.setRejectionReason("internal reason");
        return review;
    }

    private JsonNode publicDetail(String path, Set<String> expectedKeys) throws Exception {
        String body = mockMvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        Set<String> keys = new java.util.TreeSet<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertEquals(new java.util.TreeSet<>(expectedKeys), keys, path + " must serve exactly the public fields");
        assertFalse(body.contains(STAFF_UID), path + " leaks a staff uid");
        assertFalse(EMAIL.matcher(body).find(), path + " leaks an email address");
        return json;
    }

    @Test
    @DisplayName("Privacy: GET /api/v1/videos/{id} serves only public fields (no staff uid/email, review or registry data)")
    void videoDetailServesOnlyPublicFields() throws Exception {
        testVideo.setSubmittedBy(STAFF_UID);
        testVideo.setApprovedBy(STAFF_UID);
        testVideo.setApprovalMetadata(staffReview());
        testVideo.setSubmitterNote("context for the admin");
        testVideo.setSource("MODERATOR");
        testVideo.setKeywords(List.of("tafsir"));
        testVideo.setDisplayOrder(3);
        testVideo.setLastValidatedAt(Timestamp.now());
        testVideo.setChannelTitle("Test Channel");
        testVideo.setViewCount(42L);
        when(contentService.getVideoDetails("EnfgPg0Ey3I")).thenReturn(testVideo);

        JsonNode json = publicDetail("/api/v1/videos/EnfgPg0Ey3I", Set.of(
                "youtubeId", "title", "description", "thumbnailUrl", "durationSeconds",
                "viewCount", "channelTitle", "offlineAllowed"));
        assertEquals("EnfgPg0Ey3I", json.get("youtubeId").asText());
        assertTrue(json.get("offlineAllowed").isBoolean());
        assertTrue(json.get("offlineAllowed").asBoolean());
        assertEquals(240, json.get("durationSeconds").asInt());
    }

    @Test
    @DisplayName("Privacy: GET /api/v1/channels/{id} serves only public fields (no staff uid/email, review or registry data)")
    void channelDetailServesOnlyPublicFields() throws Exception {
        Channel channel = new Channel();
        channel.setId("firestore-doc-id");
        channel.setYoutubeId("UCabc123");
        channel.setName("Test Channel");
        channel.setDescription("Lectures");
        channel.setThumbnailUrl("https://example.com/avatar.jpg");
        channel.setSubscribers(1200L);
        channel.setVideoCount(80);
        channel.setStatus("APPROVED");
        channel.setSubmittedBy(STAFF_UID);
        channel.setApprovedBy(STAFF_UID);
        channel.setApprovalMetadata(staffReview());
        channel.setSubmitterNote("context for the admin");
        channel.setKeywords(List.of("seerah"));
        channel.setDisplayOrder(1);
        channel.setLastValidatedAt(Timestamp.now());
        when(contentService.getChannelDetails("UCabc123")).thenReturn(channel);

        JsonNode json = publicDetail("/api/v1/channels/UCabc123", Set.of(
                "youtubeId", "name", "description", "thumbnailUrl", "subscribers", "videoCount"));
        assertEquals("UCabc123", json.get("youtubeId").asText());
        assertEquals("Test Channel", json.get("name").asText());
        assertEquals("https://example.com/avatar.jpg", json.get("thumbnailUrl").asText());
    }

    @Test
    @DisplayName("Privacy: GET /api/v1/playlists/{id} serves only public fields (no staff uid/email, review or registry data)")
    void playlistDetailServesOnlyPublicFields() throws Exception {
        Playlist playlist = new Playlist();
        playlist.setId("firestore-doc-id");
        playlist.setYoutubeId("PLabc123");
        playlist.setTitle("Test Playlist");
        playlist.setDescription("Series");
        playlist.setThumbnailUrl("https://example.com/cover.jpg");
        playlist.setItemCount(12);
        playlist.setStatus("APPROVED");
        playlist.setSubmittedBy(STAFF_UID);
        playlist.setApprovedBy(STAFF_UID);
        playlist.setApprovalMetadata(staffReview());
        playlist.setSubmitterNote("context for the admin");
        playlist.setExcludedVideoIds(List.of("sCQwxFEMlZ4"));
        playlist.setKeywords(List.of("fiqh"));
        playlist.setDisplayOrder(2);
        playlist.setLastValidatedAt(Timestamp.now());
        when(contentService.getPlaylistDetails("PLabc123")).thenReturn(playlist);

        JsonNode json = publicDetail("/api/v1/playlists/PLabc123", Set.of(
                "youtubeId", "title", "description", "thumbnailUrl", "itemCount"));
        assertEquals("Test Playlist", json.get("title").asText());
        assertEquals("https://example.com/cover.jpg", json.get("thumbnailUrl").asText());
        assertEquals(12, json.get("itemCount").asInt());
    }
}
