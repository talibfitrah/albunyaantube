package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.search.SearchIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec §4.4 / §4.7: routing of /search and /content?q= into the index. Matching itself is SearchIndexTest. */
@ExtendWith(MockitoExtension.class)
class PublicContentServiceSearchTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryContentOrderRepository orderRepository;
    @Mock private SearchIndex searchIndex;

    private PublicContentService service;

    @BeforeEach
    void setUp() {
        service = new PublicContentService(channelRepository, playlistRepository, videoRepository,
                categoryRepository, orderRepository, Runnable::run, searchIndex);
    }

    private static List<ContentItemDto> items(int n) {
        List<ContentItemDto> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(ContentItemDto.video("v" + i, "Video " + i, null, 60, 1, null, null, null, null, null));
        return out;
    }

    @Test
    void anElevenLetterWordThatParsesAsAVideoIdFallsThroughToText() throws Exception {
        // Review Focus 1: "subhanallah" is 11 [A-Za-z] chars, so parseDirectId reads it as a video id.
        SearchIndex.Hits text = new SearchIndex.Hits(items(1), "exact");
        when(searchIndex.byId("subhanallah", null)).thenReturn(SearchIndex.Hits.NONE);
        when(searchIndex.search("subhanallah", null, false, 20, null)).thenReturn(text);

        assertSame(text, service.search("subhanallah", "ALL", 20));
    }

    @Test
    void aBareVideoIdThatHitsIsAnsweredById() throws Exception {
        SearchIndex.Hits byId = new SearchIndex.Hits(items(1), "exact");
        when(searchIndex.byId("abcdefghijk", null)).thenReturn(byId);

        assertSame(byId, service.search("abcdefghijk", null, 20));
        verify(searchIndex, never()).search(anyString(), any(), anyBoolean(), anyInt(), any());
    }

    @Test
    void aPastedVideoUrlIsAnsweredById() throws Exception {
        SearchIndex.Hits byId = new SearchIndex.Hits(items(1), "exact");
        when(searchIndex.byId("abcdefghijk", "VIDEO")).thenReturn(byId);

        assertSame(byId, service.search("https://www.youtube.com/watch?v=abcdefghijk", "VIDEOS", 20));
        verify(searchIndex, never()).search(anyString(), any(), anyBoolean(), anyInt(), any());
    }

    @Test
    void aPastedVideoUrlWhoseIdMissesAnswersNoneAndIsNotSearchedAsText() throws Exception {
        // Ruling F6 / spec §4.4: an unknown pasted id is "none", not a fuzzy search over the URL's words.
        when(searchIndex.byId("abcdefghijk", null)).thenReturn(SearchIndex.Hits.NONE);

        SearchIndex.Hits hits = service.search("https://youtu.be/abcdefghijk", null, 20);

        assertEquals("none", hits.match());
        assertTrue(hits.items().isEmpty());
        verify(searchIndex, never()).search(anyString(), any(), anyBoolean(), anyInt(), any());
    }

    @Test
    void aBareChannelIdThatMissesAnswersNoneAndIsNotSearchedAsText() throws Exception {
        // Only a bare 11-character token can be an ordinary word; a 24-character UC… token cannot.
        String channelId = "UC" + "a".repeat(22);
        when(searchIndex.byId(channelId, null)).thenReturn(SearchIndex.Hits.NONE);

        assertEquals("none", service.search(channelId, null, 20).match());
        verify(searchIndex, never()).search(anyString(), any(), anyBoolean(), anyInt(), any());
    }

    @Test
    void theQueryIsTrimmedAndCutTo128CharactersBeforeTheIndexSeesIt() throws Exception {
        SearchIndex.Hits text = new SearchIndex.Hits(items(1), "exact");
        when(searchIndex.search("a".repeat(128), null, false, 20, null)).thenReturn(text);

        assertSame(text, service.search("  " + "a".repeat(300) + "  ", null, 20));
    }

    @Test
    void underTwoCharactersIsNoneWithoutTouchingTheIndex() throws Exception {
        assertEquals("none", service.search(" a ", null, 20).match());
        verifyNoInteractions(searchIndex);
    }

    @Test
    void inTabSearchPagesWithTheBase64OffsetCursor() throws Exception {
        when(searchIndex.search("quran", "CHANNEL", true, 21, null)).thenReturn(new SearchIndex.Hits(items(25), "exact"));
        PublicContentService.SearchPage first = service.searchContent("CHANNELS", null, 20, null, null, null, "quran");
        assertEquals(20, first.page().getData().size());
        assertEquals("exact", first.match());
        String cursor = first.page().getPageInfo().getNextCursor();
        assertEquals(Base64.getEncoder().encodeToString("20".getBytes()), cursor);

        when(searchIndex.search("quran", "CHANNEL", true, 41, null)).thenReturn(new SearchIndex.Hits(items(25), "exact"));
        PublicContentService.SearchPage second = service.searchContent("CHANNELS", cursor, 20, null, null, null, "quran");
        assertEquals(5, second.page().getData().size());
        assertNull(second.page().getPageInfo().getNextCursor());
    }

    @Test
    void inTabSearchDoesNotApplyToHomeOrToOneCharacter() throws Exception {
        assertNull(service.searchContent("HOME", null, 20, null, null, null, "quran"));
        assertNull(service.searchContent("CHANNELS", null, 20, null, null, null, "q"));
        verifyNoInteractions(searchIndex);
    }

    @Test
    void aLengthFilterRestrictsInTabSearchToTheMatchingVideos() throws Exception {
        Video shortOne = new Video("vShort");
        shortOne.setStatus("APPROVED");
        shortOne.setDurationSeconds(120);
        Video longOne = new Video("vLong");
        longOne.setStatus("APPROVED");
        longOne.setDurationSeconds(3600);
        when(videoRepository.findAllByOrderByUploadedAtDesc(anyInt())).thenReturn(List.of(shortOne, longOne));
        when(searchIndex.search(eq("quran"), eq("VIDEO"), eq(true), eq(21), any())).thenReturn(SearchIndex.Hits.NONE);

        service.searchContent("VIDEOS", null, 20, null, "SHORT", null, "quran");

        verify(searchIndex).search("quran", "VIDEO", true, 21, Set.of("vShort"));
    }
}
