package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.EnrichedSearchResult;
import com.albunyaan.tube.dto.SearchPageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doThrow;

/**
 * Unit tests for SearchOrchestrator
 *
 * Tests verify:
 * - Search result conversion to DTOs
 * - Pagination handling
 * - Type filtering
 * - Error handling
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchOrchestratorTest {

    @Mock
    private YouTubeGateway gateway;

    @Mock
    private SearchExtractor searchExtractor;

    @Mock
    private ListExtractor.InfoItemsPage<InfoItem> infoItemsPage;

    private SearchOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new SearchOrchestrator(gateway);
    }

    @Nested
    @DisplayName("Search All Content")
    class SearchAllContentTests {

        @Test
        @DisplayName("Should return empty response for empty results")
        void searchAllEmpty_returnsEmptyResponse() throws Exception {
            // Arrange
            when(gateway.createSearchExtractor(anyString())).thenReturn(searchExtractor);
            when(searchExtractor.getInitialPage()).thenReturn(infoItemsPage);
            when(infoItemsPage.getItems()).thenReturn(Collections.emptyList());
            when(infoItemsPage.getNextPage()).thenReturn(null);

            // Act
            SearchPageResponse response = orchestrator.searchAllEnrichedPaged("test", null);

            // Assert
            assertNotNull(response);
            assertTrue(response.getItems().isEmpty());
            assertNull(response.getNextPageToken());
        }

        @Test
        @DisplayName("Should convert channel items to enriched results")
        void searchWithChannels_convertsCorrectly() throws Exception {
            // Arrange
            ChannelInfoItem channelItem = mock(ChannelInfoItem.class);
            when(channelItem.getInfoType()).thenReturn(InfoItem.InfoType.CHANNEL);
            when(channelItem.getName()).thenReturn("Test Channel");
            when(channelItem.getUrl()).thenReturn("https://youtube.com/channel/UC123");
            when(channelItem.getSubscriberCount()).thenReturn(10000L);
            when(channelItem.getThumbnails()).thenReturn(Collections.emptyList());

            when(gateway.createSearchExtractor(anyString())).thenReturn(searchExtractor);
            when(searchExtractor.getInitialPage()).thenReturn(infoItemsPage);
            when(infoItemsPage.getItems()).thenReturn(Collections.singletonList(channelItem));
            when(infoItemsPage.getNextPage()).thenReturn(null);

            // Act
            SearchPageResponse response = orchestrator.searchAllEnrichedPaged("test channel", null);

            // Assert
            assertNotNull(response);
            assertEquals(1, response.getItems().size());
            EnrichedSearchResult result = response.getItems().get(0);
            assertEquals("channel", result.getType());
            assertEquals("Test Channel", result.getTitle());
        }

        @Test
        @DisplayName("Should convert playlist items to enriched results")
        void searchWithPlaylists_convertsCorrectly() throws Exception {
            // Arrange
            PlaylistInfoItem playlistItem = mock(PlaylistInfoItem.class);
            when(playlistItem.getInfoType()).thenReturn(InfoItem.InfoType.PLAYLIST);
            when(playlistItem.getName()).thenReturn("Test Playlist");
            when(playlistItem.getUrl()).thenReturn("https://youtube.com/playlist?list=PL123");
            when(playlistItem.getStreamCount()).thenReturn(50L);
            when(playlistItem.getThumbnails()).thenReturn(Collections.emptyList());
            when(playlistItem.getUploaderName()).thenReturn("Test User");

            when(gateway.createSearchExtractor(anyString())).thenReturn(searchExtractor);
            when(searchExtractor.getInitialPage()).thenReturn(infoItemsPage);
            when(infoItemsPage.getItems()).thenReturn(Collections.singletonList(playlistItem));
            when(infoItemsPage.getNextPage()).thenReturn(null);

            // Act
            SearchPageResponse response = orchestrator.searchAllEnrichedPaged("test playlist", null);

            // Assert
            assertNotNull(response);
            assertEquals(1, response.getItems().size());
            EnrichedSearchResult result = response.getItems().get(0);
            assertEquals("playlist", result.getType());
            assertEquals("Test Playlist", result.getTitle());
        }

        @Test
        @DisplayName("Should convert video items to enriched results")
        void searchWithVideos_convertsCorrectly() throws Exception {
            // Arrange
            StreamInfoItem videoItem = mock(StreamInfoItem.class);
            when(videoItem.getInfoType()).thenReturn(InfoItem.InfoType.STREAM);
            when(videoItem.getName()).thenReturn("Test Video");
            when(videoItem.getUrl()).thenReturn("https://youtube.com/watch?v=abc123");
            when(videoItem.getViewCount()).thenReturn(100000L);
            when(videoItem.getDuration()).thenReturn(600L);
            when(videoItem.getThumbnails()).thenReturn(Collections.emptyList());
            when(videoItem.getUploaderName()).thenReturn("Test Channel");

            when(gateway.createSearchExtractor(anyString())).thenReturn(searchExtractor);
            when(searchExtractor.getInitialPage()).thenReturn(infoItemsPage);
            when(infoItemsPage.getItems()).thenReturn(Collections.singletonList(videoItem));
            when(infoItemsPage.getNextPage()).thenReturn(null);

            // Act
            SearchPageResponse response = orchestrator.searchAllEnrichedPaged("test video", null);

            // Assert
            assertNotNull(response);
            assertEquals(1, response.getItems().size());
            EnrichedSearchResult result = response.getItems().get(0);
            assertEquals("video", result.getType());
            assertEquals("Test Video", result.getTitle());
        }

        @Test
        @DisplayName("Should handle mixed content types")
        void searchMixedContent_returnsAllTypes() throws Exception {
            // Arrange
            ChannelInfoItem channelItem = mock(ChannelInfoItem.class);
            when(channelItem.getInfoType()).thenReturn(InfoItem.InfoType.CHANNEL);
            when(channelItem.getName()).thenReturn("Channel");
            when(channelItem.getUrl()).thenReturn("https://youtube.com/channel/UC1");
            when(channelItem.getThumbnails()).thenReturn(Collections.emptyList());

            StreamInfoItem videoItem = mock(StreamInfoItem.class);
            when(videoItem.getInfoType()).thenReturn(InfoItem.InfoType.STREAM);
            when(videoItem.getName()).thenReturn("Video");
            when(videoItem.getUrl()).thenReturn("https://youtube.com/watch?v=abc");
            when(videoItem.getThumbnails()).thenReturn(Collections.emptyList());
            when(videoItem.getUploaderName()).thenReturn("Test");

            when(gateway.createSearchExtractor(anyString())).thenReturn(searchExtractor);
            when(searchExtractor.getInitialPage()).thenReturn(infoItemsPage);
            when(infoItemsPage.getItems()).thenReturn(Arrays.asList(channelItem, videoItem));
            when(infoItemsPage.getNextPage()).thenReturn(null);

            // Act
            SearchPageResponse response = orchestrator.searchAllEnrichedPaged("test", null);

            // Assert
            assertEquals(2, response.getItems().size());
        }
    }

    @Nested
    @DisplayName("Pagination Handling")
    class PaginationTests {

        @Test
        @DisplayName("Should include next page token when more pages exist")
        void searchWithMorePages_includesNextToken() throws Exception {
            // Arrange
            Page nextPage = new Page("https://youtube.com/page2");

            when(gateway.createSearchExtractor(anyString())).thenReturn(searchExtractor);
            when(searchExtractor.getInitialPage()).thenReturn(infoItemsPage);
            when(infoItemsPage.getItems()).thenReturn(Collections.emptyList());
            when(infoItemsPage.getNextPage()).thenReturn(nextPage);
            when(gateway.encodePageToken(nextPage)).thenReturn("encoded_token");

            // Act
            SearchPageResponse response = orchestrator.searchAllEnrichedPaged("test", null);

            // Assert
            assertEquals("encoded_token", response.getNextPageToken());
        }

        @Test
        @DisplayName("Should fetch specific page when token provided")
        void searchWithPageToken_fetchesCorrectPage() throws Exception {
            // Arrange
            String pageToken = "test_token";
            Page decodedPage = new Page("https://youtube.com/page2");

            @SuppressWarnings("unchecked")
            ListExtractor.InfoItemsPage<InfoItem> secondPage = mock(ListExtractor.InfoItemsPage.class);

            when(gateway.createSearchExtractor(anyString())).thenReturn(searchExtractor);
            when(searchExtractor.getInitialPage()).thenReturn(infoItemsPage);
            when(gateway.decodePageToken(pageToken)).thenReturn(decodedPage);
            when(gateway.getSearchPage(searchExtractor, decodedPage)).thenReturn(secondPage);
            when(secondPage.getItems()).thenReturn(Collections.emptyList());
            when(secondPage.getNextPage()).thenReturn(null);

            // Act
            SearchPageResponse response = orchestrator.searchAllEnrichedPaged("test", pageToken);

            // Assert
            verify(gateway).getSearchPage(searchExtractor, decodedPage);
        }

        @Test
        @DisplayName("Should fall back to initial page for invalid token")
        void searchWithInvalidToken_fallsBackToInitial() throws Exception {
            // Arrange
            when(gateway.createSearchExtractor(anyString())).thenReturn(searchExtractor);
            when(searchExtractor.getInitialPage()).thenReturn(infoItemsPage);
            when(infoItemsPage.getItems()).thenReturn(Collections.emptyList());
            when(infoItemsPage.getNextPage()).thenReturn(null);
            when(gateway.decodePageToken("invalid")).thenReturn(null);

            // Act
            SearchPageResponse response = orchestrator.searchAllEnrichedPaged("test", "invalid");

            // Assert
            assertNotNull(response);
            assertTrue(response.getItems().isEmpty());
            // Should not throw and use initial page
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should wrap extraction exception in IOException")
        void searchExtractionError_throwsIOException() throws Exception {
            // Arrange
            when(gateway.createSearchExtractor(anyString()))
                    .thenThrow(new ExtractionException("Test error"));

            // Act & Assert
            IOException exception = assertThrows(IOException.class,
                    () -> orchestrator.searchAllEnrichedPaged("test", null));
            assertTrue(exception.getMessage().contains("Test error"));
        }

        @Test
        @DisplayName("Should handle fetch page exception")
        void searchFetchError_throwsIOException() throws Exception {
            // Arrange
            when(gateway.createSearchExtractor(anyString())).thenReturn(searchExtractor);
            doThrow(new IOException("Network error")).when(gateway).fetchSearchPage(searchExtractor);

            // Act & Assert
            assertThrows(IOException.class,
                    () -> orchestrator.searchAllEnrichedPaged("test", null));
        }
    }

    @Nested
    @DisplayName("Type-Filtered Search")
    class TypeFilteredSearchTests {

        @Test
        @DisplayName("Should filter channels only for channel search")
        void searchChannelsEnriched_filtersChannelsOnly() throws Exception {
            // Arrange
            ChannelInfoItem channelItem = mock(ChannelInfoItem.class);
            when(channelItem.getInfoType()).thenReturn(InfoItem.InfoType.CHANNEL);
            when(channelItem.getName()).thenReturn("Channel");
            when(channelItem.getUrl()).thenReturn("https://youtube.com/channel/UC1");
            when(channelItem.getThumbnails()).thenReturn(Collections.emptyList());

            StreamInfoItem videoItem = mock(StreamInfoItem.class);
            when(videoItem.getInfoType()).thenReturn(InfoItem.InfoType.STREAM);

            when(gateway.createSearchExtractor(anyString())).thenReturn(searchExtractor);
            when(searchExtractor.getInitialPage()).thenReturn(infoItemsPage);
            when(infoItemsPage.getItems()).thenReturn(Arrays.asList(channelItem, videoItem));

            // Act
            List<EnrichedSearchResult> results = orchestrator.searchChannelsEnriched("test");

            // Assert
            assertEquals(1, results.size());
            assertEquals("channel", results.get(0).getType());
        }

        @Test
        @DisplayName("Should filter videos only for video search")
        void searchVideosEnriched_filtersVideosOnly() throws Exception {
            // Arrange
            StreamInfoItem videoItem = mock(StreamInfoItem.class);
            when(videoItem.getInfoType()).thenReturn(InfoItem.InfoType.STREAM);
            when(videoItem.getName()).thenReturn("Video");
            when(videoItem.getUrl()).thenReturn("https://youtube.com/watch?v=abc");
            when(videoItem.getThumbnails()).thenReturn(Collections.emptyList());
            when(videoItem.getUploaderName()).thenReturn("Test");

            ChannelInfoItem channelItem = mock(ChannelInfoItem.class);
            when(channelItem.getInfoType()).thenReturn(InfoItem.InfoType.CHANNEL);

            when(gateway.createSearchExtractor(anyString())).thenReturn(searchExtractor);
            when(searchExtractor.getInitialPage()).thenReturn(infoItemsPage);
            when(infoItemsPage.getItems()).thenReturn(Arrays.asList(videoItem, channelItem));

            // Act
            List<EnrichedSearchResult> results = orchestrator.searchVideosEnriched("test");

            // Assert
            assertEquals(1, results.size());
            assertEquals("video", results.get(0).getType());
        }

        @Test
        @DisplayName("Should filter playlists only for playlist search")
        void searchPlaylistsEnriched_filtersPlaylistsOnly() throws Exception {
            // Arrange
            PlaylistInfoItem playlistItem = mock(PlaylistInfoItem.class);
            when(playlistItem.getInfoType()).thenReturn(InfoItem.InfoType.PLAYLIST);
            when(playlistItem.getName()).thenReturn("Playlist");
            when(playlistItem.getUrl()).thenReturn("https://youtube.com/playlist?list=PL1");
            when(playlistItem.getThumbnails()).thenReturn(Collections.emptyList());
            when(playlistItem.getUploaderName()).thenReturn("Test");

            when(gateway.createSearchExtractor(anyString())).thenReturn(searchExtractor);
            when(searchExtractor.getInitialPage()).thenReturn(infoItemsPage);
            when(infoItemsPage.getItems()).thenReturn(Collections.singletonList(playlistItem));

            // Act
            List<EnrichedSearchResult> results = orchestrator.searchPlaylistsEnriched("test");

            // Assert
            assertEquals(1, results.size());
            assertEquals("playlist", results.get(0).getType());
        }
    }
}
