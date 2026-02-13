package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExclusionsWorkspaceController.
 * Covers GET (pagination, filters, search, cursor validation, reason mapping),
 * POST (channel/playlist, reason routing), DELETE (synthetic ID parsing).
 */
@ExtendWith(MockitoExtension.class)
class ExclusionsWorkspaceControllerTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private Cache<String, Object> workspaceExclusionsCache;

    private ExclusionsWorkspaceController controller;

    private Channel testChannel;
    private Playlist testPlaylist;

    @BeforeEach
    void setUp() {
        controller = new ExclusionsWorkspaceController(
                channelRepository, playlistRepository, workspaceExclusionsCache
        );

        // Build a channel with exclusions across multiple storage types
        testChannel = new Channel("UC_test_channel");
        testChannel.setId("ch-doc-1");
        testChannel.setName("Test Islamic Channel");
        testChannel.setStatus("APPROVED");

        Channel.ExcludedItems items = new Channel.ExcludedItems();
        items.setVideos(new ArrayList<>(List.of("vid1", "vid2")));
        items.setPlaylists(new ArrayList<>(List.of("pl1")));
        items.setLiveStreams(new ArrayList<>(List.of("live1")));
        items.setShorts(new ArrayList<>(List.of("short1")));
        testChannel.setExcludedItems(items);

        // Build a playlist with exclusions
        testPlaylist = new Playlist();
        testPlaylist.setId("pl-doc-1");
        testPlaylist.setYoutubeId("PL_test_playlist");
        testPlaylist.setTitle("Test Quran Playlist");
        testPlaylist.setStatus("APPROVED");
        testPlaylist.setExcludedVideoIds(new ArrayList<>(List.of("vidA", "vidB")));
    }

    // ======================== GET /api/admin/exclusions ========================

    @Test
    @DisplayName("GET - returns all exclusions across channels and playlists")
    void getExclusions_returnsAll() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, null, null, null);

        assertEquals(200, response.getStatusCode().value());
        CursorPageDto<ExclusionsWorkspaceController.ExclusionDto> body = response.getBody();
        assertNotNull(body);

        // 2 videos + 1 playlist + 1 livestream + 1 short + 2 playlist videos = 7
        assertEquals(7, body.getData().size());
        assertEquals(7, body.getPageInfo().getTotalCount());
        assertFalse(body.getPageInfo().isHasNext());
    }

    @Test
    @DisplayName("GET - pagination with cursor and limit")
    void getExclusions_pagination() throws Exception {
        stubRepositoriesWithTestData();

        // First page: limit 3
        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> page1 =
                controller.getExclusions(null, 3, null, null, null);

        assertEquals(200, page1.getStatusCode().value());
        assertEquals(3, page1.getBody().getData().size());
        assertTrue(page1.getBody().getPageInfo().isHasNext());
        assertNotNull(page1.getBody().getPageInfo().getNextCursor());

        // Second page
        String cursor = page1.getBody().getPageInfo().getNextCursor();
        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> page2 =
                controller.getExclusions(cursor, 3, null, null, null);

        assertEquals(200, page2.getStatusCode().value());
        assertEquals(3, page2.getBody().getData().size());
        assertTrue(page2.getBody().getPageInfo().isHasNext());

        // Third page: last item
        cursor = page2.getBody().getPageInfo().getNextCursor();
        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> page3 =
                controller.getExclusions(cursor, 3, null, null, null);

        assertEquals(200, page3.getStatusCode().value());
        assertEquals(1, page3.getBody().getData().size());
        assertFalse(page3.getBody().getPageInfo().isHasNext());
    }

    @Test
    @DisplayName("GET - filter by parentType=CHANNEL")
    void getExclusions_filterByParentTypeChannel() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, "CHANNEL", null, null);

        assertEquals(200, response.getStatusCode().value());
        // 2 videos + 1 playlist + 1 livestream + 1 short = 5
        assertEquals(5, response.getBody().getData().size());
        response.getBody().getData().forEach(dto ->
                assertEquals("CHANNEL", dto.parentType)
        );
    }

    @Test
    @DisplayName("GET - filter by parentType=PLAYLIST")
    void getExclusions_filterByParentTypePlaylist() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, "PLAYLIST", null, null);

        assertEquals(200, response.getStatusCode().value());
        // 2 video exclusions from playlist
        assertEquals(2, response.getBody().getData().size());
        response.getBody().getData().forEach(dto ->
                assertEquals("PLAYLIST", dto.parentType)
        );
    }

    @Test
    @DisplayName("GET - filter by excludeType=VIDEO returns videos, livestreams, shorts, posts")
    void getExclusions_filterByExcludeTypeVideo() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, null, "VIDEO", null);

        assertEquals(200, response.getStatusCode().value());
        // 2 channel videos + 1 livestream + 1 short + 2 playlist videos = 6
        // All have wireExcludeType=VIDEO
        assertEquals(6, response.getBody().getData().size());
        response.getBody().getData().forEach(dto ->
                assertEquals("VIDEO", dto.excludeType)
        );
    }

    @Test
    @DisplayName("GET - filter by excludeType=PLAYLIST returns only playlist exclusions")
    void getExclusions_filterByExcludeTypePlaylist() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, null, "PLAYLIST", null);

        assertEquals(200, response.getStatusCode().value());
        // 1 channel playlist exclusion
        assertEquals(1, response.getBody().getData().size());
        assertEquals("PLAYLIST", response.getBody().getData().get(0).excludeType);
    }

    @Test
    @DisplayName("GET - search by parent name")
    void getExclusions_searchByParentName() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, null, null, "Quran");

        assertEquals(200, response.getStatusCode().value());
        // Only playlist exclusions match "Quran" in parentName
        assertEquals(2, response.getBody().getData().size());
        response.getBody().getData().forEach(dto ->
                assertTrue(dto.parentName.toLowerCase().contains("quran"))
        );
    }

    @Test
    @DisplayName("GET - search by excludeId")
    void getExclusions_searchByExcludeId() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, null, null, "vid1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().getData().size());
        assertEquals("vid1", response.getBody().getData().get(0).excludeId);
    }

    @Test
    @DisplayName("GET - combined filters: parentType + excludeType")
    void getExclusions_combinedFilters() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, "CHANNEL", "PLAYLIST", null);

        assertEquals(200, response.getStatusCode().value());
        // Only 1 playlist exclusion on the channel
        assertEquals(1, response.getBody().getData().size());
        assertEquals("CHANNEL", response.getBody().getData().get(0).parentType);
        assertEquals("PLAYLIST", response.getBody().getData().get(0).excludeType);
    }

    @Test
    @DisplayName("GET - empty results when no exclusions exist")
    void getExclusions_empty() throws Exception {
        when(channelRepository.findAllWithExclusions(anyInt())).thenReturn(List.of());
        when(playlistRepository.findAllWithExclusions()).thenReturn(List.of());

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().getData().isEmpty());
        assertEquals(0, response.getBody().getPageInfo().getTotalCount());
    }

    @Test
    @DisplayName("GET - limit is capped at 200")
    void getExclusions_limitCapped() throws Exception {
        stubRepositoriesWithTestData();

        // Request limit > 200 should still work (capped internally)
        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 999, null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("GET - invalid cursor returns 400 with empty page body")
    void getExclusions_invalidCursor() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<?> response =
                controller.getExclusions("not-a-number", 50, null, null, null);

        assertEquals(400, response.getStatusCode().value());
        // Body should be a valid CursorPageDto (not null) for consistent client handling
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof CursorPageDto);
        @SuppressWarnings("unchecked")
        CursorPageDto<ExclusionsWorkspaceController.ExclusionDto> body =
                (CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>) response.getBody();
        assertTrue(body.getData().isEmpty());
    }

    @Test
    @DisplayName("GET - negative cursor returns 400 with empty page body")
    void getExclusions_negativeCursor() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<?> response =
                controller.getExclusions("-5", 50, null, null, null);

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof CursorPageDto);
    }

    @Test
    @DisplayName("GET - cursor beyond data returns empty page")
    void getExclusions_cursorBeyondData() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions("1000", 50, null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().getData().isEmpty());
        assertFalse(response.getBody().getPageInfo().isHasNext());
    }

    @Test
    @DisplayName("GET - uses cache on second call")
    void getExclusions_usesCache() throws Exception {
        // First call: cache miss, populates cache
        when(workspaceExclusionsCache.getIfPresent("all")).thenReturn(null);
        when(channelRepository.findAllWithExclusions(anyInt())).thenReturn(List.of(testChannel));
        when(playlistRepository.findAllWithExclusions()).thenReturn(List.of(testPlaylist));

        controller.getExclusions(null, 50, null, null, null);

        // Capture the cached value
        var cacheCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(workspaceExclusionsCache, atLeastOnce()).put(eq("all"), cacheCaptor.capture());
        Object cachedValue = cacheCaptor.getValue();

        // Second call: simulate cache hit
        when(workspaceExclusionsCache.getIfPresent("all")).thenReturn(cachedValue);

        controller.getExclusions(null, 50, null, null, null);

        // Repositories should only have been called once (during cache miss)
        verify(channelRepository, times(1)).findAllWithExclusions(anyInt());
        verify(playlistRepository, times(1)).findAllWithExclusions();
    }

    @Test
    @DisplayName("GET - synthetic IDs have correct format with storage type in 3rd segment")
    void getExclusions_syntheticIdFormat() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, null, null, null);

        for (ExclusionsWorkspaceController.ExclusionDto dto : response.getBody().getData()) {
            String[] parts = dto.id.split(":", 4);
            assertEquals(4, parts.length, "Synthetic ID should have 4 colon-separated parts: " + dto.id);
            assertEquals(dto.parentType, parts[0]);
            assertEquals(dto.parentId, parts[1]);
            assertEquals(dto.excludeId, parts[3]);
            // 3rd segment is storage type (may differ from wire excludeType for LIVESTREAM/SHORT/POST)
        }
    }

    // ======================== Reason/content-type mapping ========================

    @Test
    @DisplayName("GET - livestreams emitted as excludeType=VIDEO with reason=LIVESTREAM")
    void getExclusions_livestreamReasonMapping() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, null, null, "live1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().getData().size());
        ExclusionsWorkspaceController.ExclusionDto dto = response.getBody().getData().get(0);
        assertEquals("VIDEO", dto.excludeType);
        assertEquals("LIVESTREAM", dto.reason);
        assertEquals("live1", dto.excludeId);
        assertTrue(dto.id.contains(":LIVESTREAM:"), "Synthetic ID 3rd segment should be LIVESTREAM");
    }

    @Test
    @DisplayName("GET - shorts emitted as excludeType=VIDEO with reason=SHORT")
    void getExclusions_shortReasonMapping() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, null, null, "short1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().getData().size());
        ExclusionsWorkspaceController.ExclusionDto dto = response.getBody().getData().get(0);
        assertEquals("VIDEO", dto.excludeType);
        assertEquals("SHORT", dto.reason);
        assertEquals("short1", dto.excludeId);
        assertTrue(dto.id.contains(":SHORT:"), "Synthetic ID 3rd segment should be SHORT");
    }

    @Test
    @DisplayName("GET - plain videos emitted as excludeType=VIDEO with reason=null")
    void getExclusions_plainVideoNoReason() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, null, null, "vid1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().getData().size());
        ExclusionsWorkspaceController.ExclusionDto dto = response.getBody().getData().get(0);
        assertEquals("VIDEO", dto.excludeType);
        assertNull(dto.reason, "Plain videos should have null reason");
    }

    @Test
    @DisplayName("GET - playlist exclusions emitted as excludeType=PLAYLIST with reason=null")
    void getExclusions_playlistExclusionNoReason() throws Exception {
        stubRepositoriesWithTestData();

        ResponseEntity<CursorPageDto<ExclusionsWorkspaceController.ExclusionDto>> response =
                controller.getExclusions(null, 50, null, null, "pl1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().getData().size());
        ExclusionsWorkspaceController.ExclusionDto dto = response.getBody().getData().get(0);
        assertEquals("PLAYLIST", dto.excludeType);
        assertNull(dto.reason, "Playlist exclusions should have null reason");
    }

    // ======================== POST /api/admin/exclusions ========================

    @Test
    @DisplayName("POST - create channel VIDEO exclusion")
    void createExclusion_channelVideo() throws Exception {
        when(channelRepository.findById("ch-doc-1")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "CHANNEL";
        request.parentId = "ch-doc-1";
        request.excludeType = "VIDEO";
        request.excludeId = "newVid";

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(201, response.getStatusCode().value());
        ExclusionsWorkspaceController.ExclusionDto dto = (ExclusionsWorkspaceController.ExclusionDto) response.getBody();
        assertNotNull(dto);
        assertEquals("CHANNEL:ch-doc-1:VIDEO:newVid", dto.id);
        assertEquals("VIDEO", dto.excludeType);
        assertNull(dto.reason, "No reason specified, should be null");
        assertEquals("Test Islamic Channel", dto.parentName);

        verify(channelRepository).save(any(Channel.class));
        verify(workspaceExclusionsCache).invalidateAll();
    }

    @Test
    @DisplayName("POST - create channel VIDEO with reason=LIVESTREAM routes to liveStreams list")
    void createExclusion_channelVideoWithLivestreamReason() throws Exception {
        when(channelRepository.findById("ch-doc-1")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "CHANNEL";
        request.parentId = "ch-doc-1";
        request.excludeType = "VIDEO";
        request.excludeId = "newLive";
        request.reason = "LIVESTREAM";

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(201, response.getStatusCode().value());
        ExclusionsWorkspaceController.ExclusionDto dto = (ExclusionsWorkspaceController.ExclusionDto) response.getBody();
        assertNotNull(dto);
        assertEquals("CHANNEL:ch-doc-1:LIVESTREAM:newLive", dto.id);
        assertEquals("VIDEO", dto.excludeType);
        assertEquals("LIVESTREAM", dto.reason);

        // Verify it was added to the liveStreams list, not videos
        assertTrue(testChannel.getExcludedItems().getLiveStreams().contains("newLive"));
        assertFalse(testChannel.getExcludedItems().getVideos().contains("newLive"));
    }

    @Test
    @DisplayName("POST - create channel VIDEO with reason=SHORT routes to shorts list")
    void createExclusion_channelVideoWithShortReason() throws Exception {
        when(channelRepository.findById("ch-doc-1")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "CHANNEL";
        request.parentId = "ch-doc-1";
        request.excludeType = "VIDEO";
        request.excludeId = "newShort";
        request.reason = "SHORT";

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(201, response.getStatusCode().value());
        ExclusionsWorkspaceController.ExclusionDto dto = (ExclusionsWorkspaceController.ExclusionDto) response.getBody();
        assertNotNull(dto);
        assertEquals("CHANNEL:ch-doc-1:SHORT:newShort", dto.id);
        assertEquals("VIDEO", dto.excludeType);
        assertEquals("SHORT", dto.reason);

        assertTrue(testChannel.getExcludedItems().getShorts().contains("newShort"));
        assertFalse(testChannel.getExcludedItems().getVideos().contains("newShort"));
    }

    @Test
    @DisplayName("POST - create channel VIDEO with reason=POST routes to posts list")
    void createExclusion_channelVideoWithPostReason() throws Exception {
        when(channelRepository.findById("ch-doc-1")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "CHANNEL";
        request.parentId = "ch-doc-1";
        request.excludeType = "VIDEO";
        request.excludeId = "newPost";
        request.reason = "POST";

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(201, response.getStatusCode().value());
        ExclusionsWorkspaceController.ExclusionDto dto = (ExclusionsWorkspaceController.ExclusionDto) response.getBody();
        assertNotNull(dto);
        assertEquals("CHANNEL:ch-doc-1:POST:newPost", dto.id);
        assertEquals("VIDEO", dto.excludeType);
        assertEquals("POST", dto.reason);

        assertTrue(testChannel.getExcludedItems().getPosts().contains("newPost"));
    }

    @Test
    @DisplayName("POST - unknown reason is ignored (treated as null)")
    void createExclusion_unknownReasonIgnored() throws Exception {
        when(channelRepository.findById("ch-doc-1")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "CHANNEL";
        request.parentId = "ch-doc-1";
        request.excludeType = "VIDEO";
        request.excludeId = "newVid2";
        request.reason = "UNKNOWN_REASON";

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(201, response.getStatusCode().value());
        ExclusionsWorkspaceController.ExclusionDto dto = (ExclusionsWorkspaceController.ExclusionDto) response.getBody();
        assertNull(dto.reason, "Unknown reason should be normalized to null");
        // Should route to videos list (default)
        assertTrue(testChannel.getExcludedItems().getVideos().contains("newVid2"));
    }

    @Test
    @DisplayName("POST - excludeType=LIVESTREAM rejected (must use VIDEO + reason)")
    void createExclusion_rejectsLivestreamExcludeType() throws Exception {
        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "CHANNEL";
        request.parentId = "ch-doc-1";
        request.excludeType = "LIVESTREAM";
        request.excludeId = "live1";

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    @DisplayName("POST - create playlist VIDEO exclusion")
    void createExclusion_playlistVideo() throws Exception {
        when(playlistRepository.findById("pl-doc-1")).thenReturn(Optional.of(testPlaylist));
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(inv -> inv.getArgument(0));

        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "PLAYLIST";
        request.parentId = "pl-doc-1";
        request.excludeType = "VIDEO";
        request.excludeId = "newVid";

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(201, response.getStatusCode().value());
        ExclusionsWorkspaceController.ExclusionDto dto = (ExclusionsWorkspaceController.ExclusionDto) response.getBody();
        assertNotNull(dto);
        assertEquals("PLAYLIST:pl-doc-1:VIDEO:newVid", dto.id);

        verify(playlistRepository).save(any(Playlist.class));
        verify(workspaceExclusionsCache).invalidateAll();
    }

    @Test
    @DisplayName("POST - playlist exclusion ignores reason (always null)")
    void createExclusion_playlistIgnoresReason() throws Exception {
        when(playlistRepository.findById("pl-doc-1")).thenReturn(Optional.of(testPlaylist));
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(inv -> inv.getArgument(0));

        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "PLAYLIST";
        request.parentId = "pl-doc-1";
        request.excludeType = "VIDEO";
        request.excludeId = "newVid";
        request.reason = "LIVESTREAM";

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(201, response.getStatusCode().value());
        ExclusionsWorkspaceController.ExclusionDto dto = (ExclusionsWorkspaceController.ExclusionDto) response.getBody();
        assertNull(dto.reason, "Playlist exclusions should always have null reason");
    }

    @Test
    @DisplayName("POST - duplicate exclusion does not re-save")
    void createExclusion_duplicateNoOp() throws Exception {
        when(channelRepository.findById("ch-doc-1")).thenReturn(Optional.of(testChannel));

        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "CHANNEL";
        request.parentId = "ch-doc-1";
        request.excludeType = "VIDEO";
        request.excludeId = "vid1"; // Already excluded

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(201, response.getStatusCode().value());
        verify(channelRepository, never()).save(any());
        verify(workspaceExclusionsCache, never()).invalidateAll();
    }

    @Test
    @DisplayName("POST - playlist only supports VIDEO excludeType")
    void createExclusion_playlistRejectsNonVideo() throws Exception {
        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "PLAYLIST";
        request.parentId = "pl-doc-1";
        request.excludeType = "PLAYLIST";
        request.excludeId = "pl2";

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    @DisplayName("POST - missing required fields returns 400")
    void createExclusion_missingFields() throws Exception {
        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "CHANNEL";
        // parentId, excludeType, excludeId missing

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    @DisplayName("POST - channel not found returns 404")
    void createExclusion_channelNotFound() throws Exception {
        when(channelRepository.findById("nonexistent")).thenReturn(Optional.empty());

        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "CHANNEL";
        request.parentId = "nonexistent";
        request.excludeType = "VIDEO";
        request.excludeId = "vid1";

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    @DisplayName("POST - invalid parentType returns 400")
    void createExclusion_invalidParentType() throws Exception {
        ExclusionsWorkspaceController.CreateExclusionRequest request = new ExclusionsWorkspaceController.CreateExclusionRequest();
        request.parentType = "INVALID";
        request.parentId = "some-id";
        request.excludeType = "VIDEO";
        request.excludeId = "vid1";

        ResponseEntity<?> response = controller.createExclusion(request);

        assertEquals(400, response.getStatusCode().value());
    }

    // ======================== DELETE /api/admin/exclusions/{id} ========================

    @Test
    @DisplayName("DELETE - remove channel VIDEO exclusion")
    void removeExclusion_channelVideo() throws Exception {
        when(channelRepository.findById("ch-doc-1")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> response = controller.removeExclusion("CHANNEL:ch-doc-1:VIDEO:vid1");

        assertEquals(204, response.getStatusCode().value());
        assertFalse(testChannel.getExcludedItems().getVideos().contains("vid1"));
        verify(channelRepository).save(any(Channel.class));
        verify(workspaceExclusionsCache).invalidateAll();
    }

    @Test
    @DisplayName("DELETE - remove channel LIVESTREAM exclusion via storage type in ID")
    void removeExclusion_channelLivestream() throws Exception {
        when(channelRepository.findById("ch-doc-1")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> response = controller.removeExclusion("CHANNEL:ch-doc-1:LIVESTREAM:live1");

        assertEquals(204, response.getStatusCode().value());
        assertFalse(testChannel.getExcludedItems().getLiveStreams().contains("live1"));
        verify(channelRepository).save(any(Channel.class));
    }

    @Test
    @DisplayName("DELETE - remove channel SHORT exclusion via storage type in ID")
    void removeExclusion_channelShort() throws Exception {
        when(channelRepository.findById("ch-doc-1")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> response = controller.removeExclusion("CHANNEL:ch-doc-1:SHORT:short1");

        assertEquals(204, response.getStatusCode().value());
        assertFalse(testChannel.getExcludedItems().getShorts().contains("short1"));
        verify(channelRepository).save(any(Channel.class));
    }

    @Test
    @DisplayName("DELETE - remove playlist VIDEO exclusion")
    void removeExclusion_playlistVideo() throws Exception {
        when(playlistRepository.findById("pl-doc-1")).thenReturn(Optional.of(testPlaylist));
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> response = controller.removeExclusion("PLAYLIST:pl-doc-1:VIDEO:vidA");

        assertEquals(204, response.getStatusCode().value());
        assertFalse(testPlaylist.getExcludedVideoIds().contains("vidA"));
        verify(playlistRepository).save(any(Playlist.class));
        verify(workspaceExclusionsCache).invalidateAll();
    }

    @Test
    @DisplayName("DELETE - nonexistent exclusion returns 204 (idempotent)")
    void removeExclusion_nonexistent() throws Exception {
        when(channelRepository.findById("ch-doc-1")).thenReturn(Optional.of(testChannel));

        ResponseEntity<?> response = controller.removeExclusion("CHANNEL:ch-doc-1:VIDEO:doesNotExist");

        assertEquals(204, response.getStatusCode().value());
        verify(channelRepository, never()).save(any());
    }

    @Test
    @DisplayName("DELETE - invalid ID format returns 400")
    void removeExclusion_invalidIdFormat() throws Exception {
        ResponseEntity<?> response = controller.removeExclusion("bad-id");

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    @DisplayName("DELETE - channel not found returns 404")
    void removeExclusion_channelNotFound() throws Exception {
        when(channelRepository.findById("nonexistent")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.removeExclusion("CHANNEL:nonexistent:VIDEO:vid1");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    @DisplayName("DELETE - channel with empty excludedItems returns 204 (idempotent)")
    void removeExclusion_channelEmptyExcludedItems() throws Exception {
        Channel emptyChannel = new Channel("UC_empty");
        emptyChannel.setId("ch-empty");
        // setExcludedItems(null) defaults to new ExcludedItems() per Channel model

        when(channelRepository.findById("ch-empty")).thenReturn(Optional.of(emptyChannel));

        ResponseEntity<?> response = controller.removeExclusion("CHANNEL:ch-empty:VIDEO:vid1");

        // No item to remove, so no save occurs, but still 204 (idempotent)
        assertEquals(204, response.getStatusCode().value());
        verify(channelRepository, never()).save(any());
    }

    @Test
    @DisplayName("DELETE - invalid parentType in ID returns 400")
    void removeExclusion_invalidParentType() throws Exception {
        ResponseEntity<?> response = controller.removeExclusion("INVALID:id:VIDEO:vid1");

        assertEquals(400, response.getStatusCode().value());
    }

    // ======================== Helpers ========================

    private void stubRepositoriesWithTestData() throws Exception {
        when(workspaceExclusionsCache.getIfPresent("all")).thenReturn(null);
        when(channelRepository.findAllWithExclusions(anyInt())).thenReturn(List.of(testChannel));
        when(playlistRepository.findAllWithExclusions()).thenReturn(List.of(testPlaylist));
    }
}
