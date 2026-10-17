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
        when(searchIndex.search("subhanallah", null, 20)).thenReturn(text);

        assertSame(text, service.search("subhanallah", "ALL", 20));
    }

    @Test
    void aBareVideoIdThatHitsIsAnsweredById() throws Exception {
        SearchIndex.Hits byId = new SearchIndex.Hits(items(1), "exact");
        when(searchIndex.byId("abcdefghijk", null)).thenReturn(byId);

        assertSame(byId, service.search("abcdefghijk", null, 20));
        verify(searchIndex, never()).search(anyString(), any(), anyInt());
    }

    @Test
    void aPastedVideoUrlIsAnsweredById() throws Exception {
        SearchIndex.Hits byId = new SearchIndex.Hits(items(1), "exact");
        when(searchIndex.byId("abcdefghijk", "VIDEO")).thenReturn(byId);

        assertSame(byId, service.search("https://www.youtube.com/watch?v=abcdefghijk", "VIDEOS", 20));
        verify(searchIndex, never()).search(anyString(), any(), anyInt());
    }

    @Test
    void aPastedVideoUrlWhoseIdMissesAnswersNoneAndIsNotSearchedAsText() throws Exception {
        // Ruling F6 / spec §4.4: an unknown pasted id is "none", not a fuzzy search over the URL's words.
        when(searchIndex.byId("abcdefghijk", null)).thenReturn(SearchIndex.Hits.NONE);

        SearchIndex.Hits hits = service.search("https://youtu.be/abcdefghijk", null, 20);

        assertEquals("none", hits.match());
        assertTrue(hits.items().isEmpty());
        verify(searchIndex, never()).search(anyString(), any(), anyInt());
    }

    @Test
    void shortsLiveAndEmbedUrlsAreAnsweredById() throws Exception {
        SearchIndex.Hits byId = new SearchIndex.Hits(items(1), "exact");
        when(searchIndex.byId("abcdefghijk", null)).thenReturn(byId);
        for (String url : List.of("https://www.youtube.com/shorts/abcdefghijk", "https://youtube.com/live/abcdefghijk?feature=share",
                "https://www.youtube.com/embed/abcdefghijk")) {
            assertSame(byId, service.search(url, null, 20), url);
        }
        verify(searchIndex, never()).search(anyString(), any(), anyInt());
    }

    @Test
    void anEmbedVideoseriesUrlIsAnsweredAsItsPlaylist() throws Exception {
        // N1: "videoseries" is 11 valid id characters, so the /embed/ check must not run before list=.
        String playlistId = "PL" + "a".repeat(14);
        SearchIndex.Hits byId = new SearchIndex.Hits(items(1), "exact");
        lenient().when(searchIndex.byId(any(), any())).thenReturn(SearchIndex.Hits.NONE);
        when(searchIndex.byId(playlistId, null)).thenReturn(byId);

        assertSame(byId, service.search("https://www.youtube.com/embed/videoseries?list=" + playlistId, null, 20));
    }

    @Test
    void anEmbedUrlWithAnElevenCharacterIdIsAnsweredAsThatVideo() throws Exception {
        // N1 guard: moving the /embed/ check below list= must not stop it reading a plain embed URL.
        SearchIndex.Hits byId = new SearchIndex.Hits(items(1), "exact");
        when(searchIndex.byId("abcdefghijk", null)).thenReturn(byId);

        assertSame(byId, service.search("https://www.youtube.com/embed/abcdefghijk", null, 20));
    }

    @Test
    void aYouTubeUrlTheParserCannotReadAnswersNoneAndIsNotSearchedAsText() throws Exception {
        // F11: a pasted /@handle (or any other unread YouTube URL) is never fuzzy-searched as words.
        for (String url : List.of("https://www.youtube.com/@SomeHandle", "https://www.youtube.com/feed/subscriptions",
                "https://youtu.be/")) {
            SearchIndex.Hits hits = service.search(url, null, 20);
            assertEquals("none", hits.match(), url);
            assertTrue(hits.items().isEmpty(), url);
        }
        verifyNoInteractions(searchIndex);
    }

    @Test
    void aBareChannelIdThatMissesAnswersNoneAndIsNotSearchedAsText() throws Exception {
        // Only a bare 11-character token can be an ordinary word; a 24-character UC… token cannot.
        String channelId = "UC" + "a".repeat(22);
        when(searchIndex.byId(channelId, null)).thenReturn(SearchIndex.Hits.NONE);

        assertEquals("none", service.search(channelId, null, 20).match());
        verify(searchIndex, never()).search(anyString(), any(), anyInt());
    }

    @Test
    void anUnknownTypeAnswersNoneInsteadOfEveryType() throws Exception {
        // F10 / Codex P3: /search validates nothing, and the legacy search answered [] for any type but ALL/CHANNELS/PLAYLISTS/VIDEOS.
        lenient().when(searchIndex.search(any(), any(), anyInt()))
                .thenReturn(new SearchIndex.Hits(items(1), "exact"));
        lenient().when(searchIndex.byId(any(), any())).thenReturn(new SearchIndex.Hits(items(1), "exact"));
        for (String type : List.of("VIDEO", "FOO", "HOME", "", "https://youtu.be/abcdefghijk")) {
            for (String q : List.of("quran", "https://youtu.be/abcdefghijk")) {
                SearchIndex.Hits hits = service.search(q, type, 20);
                assertEquals("none", hits.match(), type + " / " + q);
                assertTrue(hits.items().isEmpty(), type + " / " + q);
            }
        }
        verifyNoInteractions(searchIndex);
    }

    @Test
    void allAndNoTypeStillSearchEveryType() throws Exception {
        SearchIndex.Hits text = new SearchIndex.Hits(items(1), "exact");
        when(searchIndex.search("quran", null, 20)).thenReturn(text);
        assertSame(text, service.search("quran", null, 20));
        assertSame(text, service.search("quran", "all", 20));
    }

    @Test
    void theQueryIsTrimmedAndCutTo128CharactersBeforeTheIndexSeesIt() throws Exception {
        SearchIndex.Hits text = new SearchIndex.Hits(items(1), "exact");
        when(searchIndex.search("a".repeat(128), null, 20)).thenReturn(text);

        assertSame(text, service.search("  " + "a".repeat(300) + "  ", null, 20));
    }

    @Test
    void underTwoCharactersIsNoneWithoutTouchingTheIndex() throws Exception {
        assertEquals("none", service.search(" a ", null, 20).match());
        verifyNoInteractions(searchIndex);
    }

    @Test
    void inTabSearchPagesWithABase64CursorOfTheOffsetAndTheIndexSnapshot() throws Exception {
        when(searchIndex.searchPage("quran", "CHANNEL", 21, null, -1)).thenReturn(new SearchIndex.Hits(items(25), "exact", 7));
        PublicContentService.SearchPage first = service.searchContent("CHANNELS", null, 20, null, null, null, "quran");
        assertEquals(20, first.page().getData().size());
        assertEquals("exact", first.match());
        String cursor = first.page().getPageInfo().getNextCursor();
        assertEquals(Base64.getEncoder().encodeToString("20:7".getBytes()), cursor);

        // The next page asks for the first page's snapshot (Cubic P3, 1.0.4).
        when(searchIndex.searchPage("quran", "CHANNEL", 41, null, 7)).thenReturn(new SearchIndex.Hits(items(25), "exact", 7));
        PublicContentService.SearchPage second = service.searchContent("CHANNELS", cursor, 20, null, null, null, "quran");
        assertEquals(5, second.page().getData().size());
        assertNull(second.page().getPageInfo().getNextCursor());
    }

    private static String cursor(String offset) {
        return Base64.getEncoder().encodeToString(offset.getBytes());
    }

    @Test
    void aCursorPastTheCapAnswersAnEmptyPageWithoutSearching() throws Exception {
        // F2 / stage 4 H1: a crafted offset made the index re-run the query once per 10 hits, or overflowed.
        lenient().when(searchIndex.searchPage(any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(new SearchIndex.Hits(items(25), "exact", 7));
        for (String offset : List.of("1001", "2000000000", String.valueOf(Integer.MAX_VALUE), "3000000000")) {
            PublicContentService.SearchPage page = assertDoesNotThrow(
                    () -> service.searchContent("CHANNELS", cursor(offset), 20, null, null, null, "quran"), offset);
            assertTrue(page.page().getData().isEmpty(), offset);
            assertNull(page.page().getPageInfo().getNextCursor(), offset);
        }
        verify(searchIndex, never()).searchPage(any(), any(), anyInt(), any(), anyLong());
    }

    @Test
    void theLastCursorUnderTheCapStillSearches() throws Exception {
        when(searchIndex.searchPage("quran", "CHANNEL", 1021, null, -1)).thenReturn(new SearchIndex.Hits(items(1005), "exact", 7));

        PublicContentService.SearchPage page = service.searchContent("CHANNELS", cursor("1000"), 20, null, null, null, "quran");

        assertEquals(List.of("v1000", "v1001", "v1002", "v1003", "v1004"),
                page.page().getData().stream().map(ContentItemDto::getId).toList());
        assertNull(page.page().getPageInfo().getNextCursor());
    }

    @Test
    void inTabSearchDoesNotApplyToHomeOrToOneCharacter() throws Exception {
        assertNull(service.searchContent("HOME", null, 20, null, null, null, "quran"));
        assertNull(service.searchContent("CHANNELS", null, 20, null, null, null, "q"));
        verifyNoInteractions(searchIndex);
    }

    @Test
    void aFilteredInTabSearchChecksReadinessBeforeReadingFirestore() throws Exception {
        // F12: during the boot window each filtered keystroke paid the membership reads, then answered 503 anyway.
        doThrow(new SearchIndex.NotReadyException()).when(searchIndex).awaitReady();

        assertThrows(SearchIndex.NotReadyException.class,
                () -> service.searchContent("VIDEOS", null, 20, "some-category", "SHORT", null, "quran"));

        verifyNoInteractions(channelRepository, playlistRepository, videoRepository, categoryRepository, orderRepository);
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
        when(searchIndex.searchPage(eq("quran"), eq("VIDEO"), eq(21), any(), eq(-1L))).thenReturn(SearchIndex.Hits.NONE);

        service.searchContent("VIDEOS", null, 20, null, "SHORT", null, "quran");

        verify(searchIndex).searchPage("quran", "VIDEO", 21, Set.of("vShort"), -1);
    }
}
