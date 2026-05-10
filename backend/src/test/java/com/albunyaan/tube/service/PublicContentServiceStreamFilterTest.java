package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.model.SearchableStream;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SearchableStreamRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PublicContentService search behaviour.
 * Uses the same constructor/mock pattern as PublicContentServiceHomeFeedTest.
 */
@ExtendWith(MockitoExtension.class)
class PublicContentServiceStreamFilterTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryContentOrderRepository orderRepository;
    @Mock private SearchableStreamRepository searchableStreamRepository;
    @Mock private SearchTokenizer searchTokenizer;

    private PublicContentService service;

    @BeforeEach
    void setUp() {
        service = new PublicContentService(
                channelRepository, playlistRepository, videoRepository,
                categoryRepository, orderRepository,
                Runnable::run,   // Direct executor — synchronous test execution
                searchableStreamRepository,
                searchTokenizer
        );
    }

    // -------------------------------------------------------------------------
    // searchStreams — archive filter
    // -------------------------------------------------------------------------

    /**
     * Verifies that searchStreams(), reached via search(type="VIDEOS"), excludes
     * any video whose ValidationStatus is ARCHIVED while keeping VALID ones.
     *
     * Route: search("halal", "VIDEOS", 10)
     *   → searchByText("halal", "VIDEOS", 10)
     *     → searchVideosByText (returns empty — all videoRepository calls stubbed empty)
     *     → searchStreams("halal", 5)   ← the method under test
     */
    @Test
    void searchStreams_excludesArchivedVideos() throws Exception {
        // --- Two candidates from the searchable_streams index ---
        SearchableStream s1 = new SearchableStream();
        s1.setStreamId("video-a");
        s1.setTitle("Halal Cooking");
        s1.setTitleNorm("halal cooking");
        s1.setSearchTokens(List.of("halal"));

        SearchableStream s2 = new SearchableStream();
        s2.setStreamId("video-b");
        s2.setTitle("Halal Cooking 2");
        s2.setTitleNorm("halal cooking 2");
        s2.setSearchTokens(List.of("halal"));

        when(searchTokenizer.tokenize("halal", null)).thenReturn(List.of("halal"));
        when(searchableStreamRepository.searchByToken(eq("halal"), anyInt()))
                .thenReturn(new ArrayList<>(List.of(s1, s2)));

        // --- Video documents: video-a is VALID+APPROVED, video-b is ARCHIVED ---
        Video valid = new Video();
        valid.setYoutubeId("video-a");
        valid.setStatus("APPROVED");
        valid.setValidationStatus(ValidationStatus.VALID);

        Video archived = new Video();
        archived.setYoutubeId("video-b");
        archived.setStatus("APPROVED");
        archived.setValidationStatus(ValidationStatus.ARCHIVED);

        // findByYoutubeIds returns Map<String, Video>
        Map<String, Video> videoMap = new HashMap<>();
        videoMap.put("video-a", valid);
        videoMap.put("video-b", archived);
        when(videoRepository.findByYoutubeIds(argThat(c ->
                c != null && c.contains("video-a") && c.contains("video-b"))))
                .thenReturn(videoMap);

        // --- Stub searchVideosByText path to return empty (no interference) ---
        when(videoRepository.searchByTitleLower(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        // searchByTitle and searchByKeyword are called in try/catch blocks inside
        // searchVideosByText; leaving them unstubbed lets Mockito return empty lists
        // for their default return type, which is fine.

        // --- Invoke via public search() entry point ---
        List<ContentItemDto> results = service.search("halal", "VIDEOS", 10);

        assertEquals(1, results.size(), "Only the VALID video should be returned");
        assertEquals("video-a", results.get(0).getId(),
                "The ARCHIVED video-b must be excluded");
    }

    /**
     * Verifies that a video with ValidationStatus.UNAVAILABLE is also excluded
     * from stream search results.
     */
    @Test
    void searchStreams_excludesUnavailableVideos() throws Exception {
        SearchableStream s1 = new SearchableStream();
        s1.setStreamId("video-ok");
        s1.setTitle("Good Video");
        s1.setTitleNorm("good video");
        s1.setSearchTokens(List.of("good"));

        SearchableStream s2 = new SearchableStream();
        s2.setStreamId("video-gone");
        s2.setTitle("Gone Video");
        s2.setTitleNorm("gone video");
        s2.setSearchTokens(List.of("good"));

        when(searchTokenizer.tokenize("good", null)).thenReturn(List.of("good"));
        when(searchableStreamRepository.searchByToken(eq("good"), anyInt()))
                .thenReturn(new ArrayList<>(List.of(s1, s2)));

        Video okVideo = new Video();
        okVideo.setYoutubeId("video-ok");
        okVideo.setStatus("APPROVED");
        okVideo.setValidationStatus(ValidationStatus.VALID);

        Video unavailable = new Video();
        unavailable.setYoutubeId("video-gone");
        unavailable.setStatus("APPROVED");
        unavailable.setValidationStatus(ValidationStatus.UNAVAILABLE);

        Map<String, Video> videoMap = new HashMap<>();
        videoMap.put("video-ok", okVideo);
        videoMap.put("video-gone", unavailable);
        when(videoRepository.findByYoutubeIds(argThat(c ->
                c != null && c.contains("video-ok") && c.contains("video-gone"))))
                .thenReturn(videoMap);

        when(videoRepository.searchByTitleLower(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());

        List<ContentItemDto> results = service.search("good", "VIDEOS", 10);

        assertEquals(1, results.size(), "Only the VALID video should be returned");
        assertEquals("video-ok", results.get(0).getId(),
                "The UNAVAILABLE video must be excluded");
    }

    /**
     * Verifies that a video with status=REJECTED (but ValidationStatus.VALID) is
     * excluded from stream search results. This closes the spec gap: isAvailable()
     * only checks ARCHIVED/UNAVAILABLE; isApproved() is required to block REJECTED.
     */
    @Test
    void searchStreams_excludesRejectedVideos() throws Exception {
        SearchableStream s1 = new SearchableStream();
        s1.setStreamId("video-a");
        s1.setTitle("Halal Cooking");
        s1.setTitleNorm("halal cooking");
        s1.setSearchTokens(List.of("halal"));

        SearchableStream s2 = new SearchableStream();
        s2.setStreamId("video-b");
        s2.setTitle("Halal Cooking Rejected");
        s2.setTitleNorm("halal cooking rejected");
        s2.setSearchTokens(List.of("halal"));

        when(searchTokenizer.tokenize("halal", null)).thenReturn(List.of("halal"));
        when(searchableStreamRepository.searchByToken(eq("halal"), anyInt()))
                .thenReturn(new ArrayList<>(List.of(s1, s2)));

        // video-a: VALID + APPROVED — must pass through
        Video valid = new Video();
        valid.setYoutubeId("video-a");
        valid.setStatus("APPROVED");
        valid.setValidationStatus(ValidationStatus.VALID);

        // video-b: VALID validationStatus but REJECTED status — must be blocked
        Video rejected = new Video();
        rejected.setYoutubeId("video-b");
        rejected.setStatus("REJECTED");
        rejected.setValidationStatus(ValidationStatus.VALID);

        Map<String, Video> videoMap = new HashMap<>();
        videoMap.put("video-a", valid);
        videoMap.put("video-b", rejected);
        when(videoRepository.findByYoutubeIds(argThat(c ->
                c != null && c.contains("video-a") && c.contains("video-b"))))
                .thenReturn(videoMap);

        when(videoRepository.searchByTitleLower(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());

        List<ContentItemDto> results = service.search("halal", "VIDEOS", 10);

        assertEquals(1, results.size(), "Only the APPROVED video should be returned");
        assertEquals("video-a", results.get(0).getId(),
                "The REJECTED video-b must be excluded");
    }

    /**
     * Verifies that streams with no corresponding Video document are NOT filtered
     * (they are protected by other mechanisms and should still appear in search).
     */
    @Test
    void searchStreams_keepsStreamsWithNoVideoDocument() throws Exception {
        SearchableStream s1 = new SearchableStream();
        s1.setStreamId("orphan-stream");
        s1.setTitle("Orphan Video");
        s1.setTitleNorm("orphan video");
        s1.setSearchTokens(List.of("orphan"));

        when(searchTokenizer.tokenize("orphan", null)).thenReturn(List.of("orphan"));
        when(searchableStreamRepository.searchByToken(eq("orphan"), anyInt()))
                .thenReturn(new ArrayList<>(List.of(s1)));

        // No Video document found for this stream ID.
        // lenient: this stub is only exercised after the filter is implemented (Step 4).
        lenient().when(videoRepository.findByYoutubeIds(argThat(c ->
                c != null && c.contains("orphan-stream"))))
                .thenReturn(Collections.emptyMap());

        // searchVideosByText returns empty by default (Mockito default for List return type)

        List<ContentItemDto> results = service.search("orphan", "VIDEOS", 10);

        assertEquals(1, results.size(),
                "Streams with no Video document must not be filtered out");
        assertEquals("orphan-stream", results.get(0).getId());
    }
}
