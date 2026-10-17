package com.albunyaan.tube.service;

import com.albunyaan.tube.config.NewPipeConfiguration;
import com.albunyaan.tube.config.ValidationProperties;
import com.albunyaan.tube.dto.BatchValidationResult;
import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.dto.PlaylistItemDto;
import com.albunyaan.tube.dto.StreamItemDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.ResponseBody;
import org.mockito.junit.jupiter.MockitoExtension;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChannelOrchestrator
 *
 * Tests verify:
 * - Channel/playlist/video fetching
 * - DTO mapping
 * - Validation operations
 * - Batch operations
 * - Error handling
 */
@ExtendWith(MockitoExtension.class)
class ChannelOrchestratorTest {

    @Mock
    private YouTubeGateway gateway;

    private ChannelOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        // No throttler or breaker here; ArchiveDecisionTests runs the real breaker through the real gateway
        orchestrator = new ChannelOrchestrator(gateway, null, null, null);
    }

    @Nested
    @DisplayName("Channel Operations")
    class ChannelOperationTests {

        @Test
        @DisplayName("Should fetch channel details successfully")
        void getChannelDetails_success() throws Exception {
            // Arrange
            String channelId = "UC123";
            ChannelInfo channelInfo = mock(ChannelInfo.class);
            when(channelInfo.getName()).thenReturn("Test Channel");
            when(channelInfo.getSubscriberCount()).thenReturn(10000L);

            when(gateway.fetchChannelInfo(channelId)).thenReturn(channelInfo);

            // Act
            ChannelInfo result = orchestrator.getChannelDetails(channelId);

            // Assert
            assertNotNull(result);
            assertEquals("Test Channel", result.getName());
            assertEquals(10000L, result.getSubscriberCount());
        }

        @Test
        @DisplayName("Should wrap extraction exception in IOException")
        void getChannelDetails_extractionError() throws Exception {
            // Arrange
            when(gateway.fetchChannelInfo(anyString()))
                    .thenThrow(new ExtractionException("Channel not found"));

            // Act & Assert
            IOException exception = assertThrows(IOException.class,
                    () -> orchestrator.getChannelDetails("invalid"));
            assertTrue(exception.getMessage().contains("Channel not found"));
        }
    }

    @Nested
    @DisplayName("Playlist Operations")
    class PlaylistOperationTests {

        @Test
        @DisplayName("Should fetch playlist details successfully")
        void getPlaylistDetails_success() throws Exception {
            // Arrange
            String playlistId = "PL123";
            PlaylistInfo playlistInfo = mock(PlaylistInfo.class);
            when(playlistInfo.getName()).thenReturn("Test Playlist");
            when(playlistInfo.getStreamCount()).thenReturn(50L);

            when(gateway.fetchPlaylistInfo(playlistId)).thenReturn(playlistInfo);

            // Act
            PlaylistInfo result = orchestrator.getPlaylistDetails(playlistId);

            // Assert
            assertNotNull(result);
            assertEquals("Test Playlist", result.getName());
            assertEquals(50L, result.getStreamCount());
        }

        @Test
        @DisplayName("Should wrap extraction exception in IOException")
        void getPlaylistDetails_extractionError() throws Exception {
            // Arrange
            when(gateway.fetchPlaylistInfo(anyString()))
                    .thenThrow(new ExtractionException("Playlist not found"));

            // Act & Assert
            IOException exception = assertThrows(IOException.class,
                    () -> orchestrator.getPlaylistDetails("invalid"));
            assertTrue(exception.getMessage().contains("Playlist not found"));
        }

        @Test
        @DisplayName("validateAndFetchPlaylistDto resolves a stable /vi/ thumbnail via oEmbed when NewPipe yields a volatile /pl_c/ URL")
        void validateAndFetchPlaylistDto_hardensVolatileThumbnailViaOEmbed() throws Exception {
            String playlistId = "PLlZazEh_c4nScNCvGBn8OEf6ujk-sDUpg";
            Image plc = mock(Image.class);
            when(plc.getUrl()).thenReturn(
                    "https://i.ytimg.com/pl_c/" + playlistId + "/studio_square_thumbnail.jpg?days_since_epoch=20603");
            PlaylistInfo playlistInfo = mock(PlaylistInfo.class);
            when(playlistInfo.getUrl()).thenReturn("https://www.youtube.com/playlist?list=" + playlistId);
            when(playlistInfo.getThumbnails()).thenReturn(List.of(plc));
            when(gateway.fetchPlaylistInfo(playlistId)).thenReturn(playlistInfo);

            YouTubeOEmbedClient oEmbed = mock(YouTubeOEmbedClient.class);
            when(oEmbed.playlistThumbnailUrl(playlistId))
                    .thenReturn(Optional.of("https://i.ytimg.com/vi/sQMC7fkjmOA/hqdefault.jpg"));
            ChannelOrchestrator hardened = new ChannelOrchestrator(gateway, null, null, oEmbed);

            PlaylistDetailsDto dto = hardened.validateAndFetchPlaylistDto(playlistId);

            assertNotNull(dto);
            assertEquals("https://i.ytimg.com/vi/sQMC7fkjmOA/hqdefault.jpg", dto.getThumbnailUrl());
        }

        private PlaylistInfo playlistWithHeader(String playlistId, String headerUrl) {
            Image header = mock(Image.class);
            when(header.getUrl()).thenReturn(headerUrl);
            PlaylistInfo playlistInfo = mock(PlaylistInfo.class);
            when(playlistInfo.getUrl()).thenReturn("https://www.youtube.com/playlist?list=" + playlistId);
            when(playlistInfo.getThumbnails()).thenReturn(List.of(header));
            return playlistInfo;
        }

        @Test
        @DisplayName("Mapping swaps an expiring /pl_c/ header for the first video's /vi/ thumbnail, with no extra request")
        void mapToPlaylistDetailsDto_brokenHeader_usesFirstVideoThumbnail() {
            String playlistId = "PLlZazEh_c4nScNCvGBn8OEf6ujk-sDUpg";
            PlaylistInfo playlistInfo = playlistWithHeader(playlistId, "https://i.ytimg.com/pl_c/" + playlistId
                    + "/studio_square_thumbnail.jpg?sqp=CJzKwdYG-oaymwEICKoDEPABSFqi85f_AwYI0sG4xAY=&rs=AOn4CL");
            when(playlistInfo.getRelatedItems()).thenReturn(List.of(
                    new StreamInfoItem(0, "https://www.youtube.com/watch?v=DW-00ckCAPI", "Part 1", StreamType.VIDEO_STREAM),
                    new StreamInfoItem(0, "https://www.youtube.com/watch?v=uIwfpuLw24E", "Part 2", StreamType.VIDEO_STREAM)));
            YouTubeOEmbedClient oEmbed = mock(YouTubeOEmbedClient.class);

            PlaylistDetailsDto dto = new ChannelOrchestrator(gateway, null, null, oEmbed).mapToPlaylistDetailsDto(playlistInfo);

            assertEquals("https://i.ytimg.com/vi/DW-00ckCAPI/hqdefault.jpg", dto.getThumbnailUrl());
            verifyNoInteractions(oEmbed);
        }

        @Test
        @DisplayName("Mapping leaves a usable /vi/ header alone")
        void mapToPlaylistDetailsDto_usableHeader_isKept() {
            String header = "https://i.ytimg.com/vi/uIwfpuLw24E/hqdefault.jpg?sqp=-oaymwEWCKgBEF5IWvKriqkDCQgBFQAAiEIYAQ==";
            PlaylistInfo playlistInfo = playlistWithHeader("PLmO7YhDrrq7CEwJKpdMVEpjUS--rtfzcy", header);
            when(playlistInfo.getRelatedItems()).thenReturn(List.of(
                    new StreamInfoItem(0, "https://www.youtube.com/watch?v=DW-00ckCAPI", "Part 1", StreamType.VIDEO_STREAM)));

            assertEquals(header, orchestrator.mapToPlaylistDetailsDto(playlistInfo).getThumbnailUrl());
        }

        @Test
        @DisplayName("Batch validation of an empty playlist with a broken header never asks oEmbed")
        void batchValidatePlaylistsDto_brokenHeaderNoItems_neverCallsOEmbed() throws Exception {
            String playlistId = "PLmO7YhDrrq7CEwJKpdMVEpjUS--rtfzcy";
            PlaylistInfo playlistInfo = playlistWithHeader(playlistId, "https://i.ytimg.com/img/no_thumbnail.jpg");
            when(playlistInfo.getRelatedItems()).thenReturn(List.of());
            when(gateway.fetchPlaylistInfo(playlistId)).thenReturn(playlistInfo);
            YouTubeOEmbedClient oEmbed = mock(YouTubeOEmbedClient.class);

            BatchValidationResult<PlaylistDetailsDto> result = new ChannelOrchestrator(gateway, null, null, oEmbed)
                    .batchValidatePlaylistsDtoWithDetails(List.of(playlistId));

            // The broken header stays on the DTO; the validator's refresh then keeps the stored thumbnail.
            assertEquals("https://i.ytimg.com/img/no_thumbnail.jpg", result.getContent(playlistId).getThumbnailUrl());
            verifyNoInteractions(oEmbed);
        }

        @Test
        @DisplayName("A first item with an unusable id leaves the broken header and never asks oEmbed")
        void mapToPlaylistDetailsDto_unusableFirstItemId_leavesHeader() {
            String header = "https://i.ytimg.com/pl_c/PLlZazEh_c4nScNCvGBn8OEf6ujk-sDUpg/studio_square_thumbnail.jpg?sqp=x";
            PlaylistInfo playlistInfo = playlistWithHeader("PLlZazEh_c4nScNCvGBn8OEf6ujk-sDUpg", header);
            when(playlistInfo.getRelatedItems()).thenReturn(List.of(
                    new StreamInfoItem(0, "https://www.youtube.com/watch?v=tooShort", "Odd", StreamType.VIDEO_STREAM),
                    new StreamInfoItem(0, "https://www.youtube.com/watch?v=DW-00ckCAPI", "Part 1", StreamType.VIDEO_STREAM)));
            YouTubeOEmbedClient oEmbed = mock(YouTubeOEmbedClient.class);

            PlaylistDetailsDto dto = new ChannelOrchestrator(gateway, null, null, oEmbed).mapToPlaylistDetailsDto(playlistInfo);

            assertEquals(header, dto.getThumbnailUrl());
            verifyNoInteractions(oEmbed);
        }
    }

    @Nested
    @DisplayName("Video Operations")
    class VideoOperationTests {

        @Test
        @DisplayName("Should fetch video details successfully")
        void getVideoDetails_success() throws Exception {
            // Arrange
            String videoId = "abc123";
            StreamInfo streamInfo = mock(StreamInfo.class);
            when(streamInfo.getName()).thenReturn("Test Video");
            when(streamInfo.getViewCount()).thenReturn(100000L);
            when(streamInfo.getDuration()).thenReturn(600L);

            when(gateway.fetchStreamInfo(videoId)).thenReturn(streamInfo);

            // Act
            StreamInfo result = orchestrator.getVideoDetails(videoId);

            // Assert
            assertNotNull(result);
            assertEquals("Test Video", result.getName());
            assertEquals(100000L, result.getViewCount());
        }

        @Test
        @DisplayName("Should wrap extraction exception in IOException")
        void getVideoDetails_extractionError() throws Exception {
            // Arrange
            when(gateway.fetchStreamInfo(anyString()))
                    .thenThrow(new ExtractionException("Video not found"));

            // Act & Assert
            IOException exception = assertThrows(IOException.class,
                    () -> orchestrator.getVideoDetails("invalid"));
            assertTrue(exception.getMessage().contains("Video not found"));
        }
    }

    @Nested
    @DisplayName("Validation Operations")
    class ValidationTests {

        @Test
        @DisplayName("Should return channel info on successful validation")
        void validateAndFetchChannel_success() throws Exception {
            // Arrange
            ChannelInfo channelInfo = mock(ChannelInfo.class);
            when(gateway.fetchChannelInfo(anyString())).thenReturn(channelInfo);

            // Act
            ChannelInfo result = orchestrator.validateAndFetchChannel("UC123");

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should return null on channel validation failure")
        void validateAndFetchChannel_failure() throws Exception {
            // Arrange
            when(gateway.fetchChannelInfo(anyString()))
                    .thenThrow(new ExtractionException("Not found"));

            // Act
            ChannelInfo result = orchestrator.validateAndFetchChannel("invalid");

            // Assert
            assertNull(result);
        }

        @Test
        @DisplayName("Should return playlist info on successful validation")
        void validateAndFetchPlaylist_success() throws Exception {
            // Arrange
            PlaylistInfo playlistInfo = mock(PlaylistInfo.class);
            when(gateway.fetchPlaylistInfo(anyString())).thenReturn(playlistInfo);

            // Act
            PlaylistInfo result = orchestrator.validateAndFetchPlaylist("PL123");

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should return null on playlist validation failure")
        void validateAndFetchPlaylist_failure() throws Exception {
            // Arrange
            when(gateway.fetchPlaylistInfo(anyString()))
                    .thenThrow(new ExtractionException("Not found"));

            // Act
            PlaylistInfo result = orchestrator.validateAndFetchPlaylist("invalid");

            // Assert
            assertNull(result);
        }

        @Test
        @DisplayName("Should return video info on successful validation")
        void validateAndFetchVideo_success() throws Exception {
            // Arrange
            StreamInfo streamInfo = mock(StreamInfo.class);
            when(gateway.fetchStreamInfo(anyString())).thenReturn(streamInfo);

            // Act
            StreamInfo result = orchestrator.validateAndFetchVideo("abc123");

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should return null on video validation failure")
        void validateAndFetchVideo_failure() throws Exception {
            // Arrange
            when(gateway.fetchStreamInfo(anyString()))
                    .thenThrow(new ExtractionException("Not found"));

            // Act
            StreamInfo result = orchestrator.validateAndFetchVideo("invalid");

            // Assert
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Batch Validation")
    class BatchValidationTests {

        @Test
        @DisplayName("Should return empty map for null input")
        void batchValidateChannels_nullInput() {
            Map<String, ChannelInfo> result = orchestrator.batchValidateChannels(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for empty input")
        void batchValidateChannels_emptyInput() {
            Map<String, ChannelInfo> result = orchestrator.batchValidateChannels(Collections.emptyList());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for null playlist input")
        void batchValidatePlaylists_nullInput() {
            Map<String, PlaylistInfo> result = orchestrator.batchValidatePlaylists(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for null video input")
        void batchValidateVideos_nullInput() {
            Map<String, StreamInfo> result = orchestrator.batchValidateVideos(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Deprecated batch method should still use gateway executor")
        void batchValidateChannels_usesExecutor() throws Exception {
            // Arrange - deprecated methods still use concurrent execution
            when(gateway.runAsync(any())).thenAnswer(invocation -> {
                Runnable runnable = invocation.getArgument(0);
                runnable.run();
                return CompletableFuture.completedFuture(null);
            });

            ChannelInfo channelInfo = mock(ChannelInfo.class);
            when(gateway.fetchChannelInfo("UC1")).thenReturn(channelInfo);

            // Act
            Map<String, ChannelInfo> result = orchestrator.batchValidateChannels(List.of("UC1"));

            // Assert
            assertEquals(1, result.size());
            verify(gateway).runAsync(any());
        }

        @Test
        @DisplayName("WithDetails batch validation should process sequentially")
        void batchValidateChannelsWithDetails_processesSequentially() throws Exception {
            // Arrange
            ChannelInfo channelInfo = mock(ChannelInfo.class);
            when(channelInfo.getName()).thenReturn("Test Channel");
            when(gateway.fetchChannelInfo(anyString())).thenReturn(channelInfo);

            // Act
            var result = orchestrator.batchValidateChannelsWithDetails(List.of("UC1", "UC2"));

            // Assert - should call fetchChannelInfo directly (not via runAsync)
            verify(gateway, times(2)).fetchChannelInfo(anyString());
            verify(gateway, never()).runAsync(any()); // Should NOT use async
            assertEquals(2, result.getValidCount());
        }
    }

    @Nested
    @DisplayName("Free-text messages (mocked gateway)")
    class FreeTextMessageTests {

        @Test
        @DisplayName("A channel is archived only on NewPipe's own gone signals, never on free-text messages")
        void channelFreeTextGoneMessage_isErrorNotArchived() throws Exception {
            // NewPipe 0.26.5 never throws a plain ExtractionException with these texts for a channel; if its
            // wording ever moves here, the safe outcome is ERROR (retry), not an archive.
            for (String text : new String[] {"this channel does not exist", "account has been terminated"}) {
                doThrow(new ExtractionException(text)).when(gateway).fetchChannelInfo("UC_text");

                var result = orchestrator.batchValidateChannelsDtoWithDetails(List.of("UC_text"));

                assertTrue(result.isError("UC_text"), text);
                assertFalse(result.isNotFound("UC_text"), text);
            }
        }

        @Test
        @DisplayName("R5 channel: NewPipe's \"doesn't exist\" text archives with either apostrophe")
        void channelDoesNotExist_eitherApostrophe_archives() throws Exception {
            for (String text : new String[] {"This channel doesn't exist.", "This channel doesn\u2019t exist."}) {
                doThrow(new ContentNotAvailableException(text)).when(gateway).fetchChannelInfo("UC_gone");

                var result = orchestrator.batchValidateChannelsDtoWithDetails(List.of("UC_gone"));

                assertTrue(result.isNotFound("UC_gone"), text);
            }
        }

        @Test
        @DisplayName("Playlists and videos are archived only on NewPipe's own signals, never on free-text messages")
        void playlistAndVideoFreeTextGoneMessage_isErrorNotArchived() throws Exception {
            when(gateway.fetchPlaylistInfo("PL_text")).thenThrow(new ExtractionException("this playlist does not exist"));
            when(gateway.fetchStreamInfo("video_text")).thenThrow(new ExtractionException("this video is unavailable"));

            var playlists = orchestrator.batchValidatePlaylistsDtoWithDetails(List.of("PL_text"));
            var videos = orchestrator.batchValidateVideosDtoWithDetails(List.of("video_text"));

            assertTrue(playlists.isError("PL_text"));
            assertFalse(playlists.isNotFound("PL_text"));
            assertTrue(videos.isError("video_text"));
            assertFalse(videos.isNotFound("video_text"));
        }

        @Test
        @DisplayName("Should NOT classify generic errors as notFound")
        void genericError_isError() throws Exception {
            // Arrange - generic network error should be treated as transient
            when(gateway.fetchChannelInfo("UC_network"))
                    .thenThrow(new ExtractionException("Connection timed out"));

            // Act
            var result = orchestrator.batchValidateChannelsDtoWithDetails(List.of("UC_network"));

            // Assert - should be marked as error, not notFound
            assertTrue(result.isError("UC_network"), "Network error should be marked as error for retry");
            assertFalse(result.isNotFound("UC_network"), "Network error should NOT be marked as notFound");
        }

        @Test
        @DisplayName("Should NOT classify parser errors as notFound")
        void parserError_isError() throws Exception {
            // Arrange - parser error could be temporary due to YouTube changes
            when(gateway.fetchChannelInfo("UC_parser"))
                    .thenThrow(new ExtractionException("Could not parse JSON response"));

            // Act
            var result = orchestrator.batchValidateChannelsDtoWithDetails(List.of("UC_parser"));

            // Assert
            assertTrue(result.isError("UC_parser"), "Parser error should be marked as error for retry");
            assertFalse(result.isNotFound("UC_parser"), "Parser error should NOT be marked as notFound");
        }
    }

    /**
     * The archive decision through real NewPipe 0.26.5 (no network): YouTube's answer goes in at the downloader,
     * the validator's VALID/notFound/ERROR comes out. notFound is what ContentValidationService archives.
     */
    @Nested
    @DisplayName("Archive decision (real NewPipe, fixture downloader)")
    class ArchiveDecisionTests {

        private static final String ID = "UC27fWY0RSTpQv8GIsp64log";
        private static final String PLAYLIST = "PLvalidatorTestPlaylist00000000000";
        private static final String VIDEO = "validatorT1";
        private static final String BROWSE = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false";
        private static final String PLAYER = "https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch";

        /** Answers NewPipe's client-version and visitor-id lookups, then each InnerTube POST from the queue. */
        private final Deque<Response> replies = new ArrayDeque<>();
        private YouTubeGateway realGateway;
        private ChannelOrchestrator realOrchestrator;

        /** The real oEmbed client over a fake OkHttp answer; by default the validator must not ask oEmbed at all. */
        private YouTubeOEmbedClient oEmbed;
        private final List<String> oEmbedUrls = new ArrayList<>();
        private Interceptor oEmbedAnswer = chain -> {
            throw new AssertionError("oEmbed must not be asked: " + chain.request().url());
        };

        @BeforeEach
        void setUp() {
            NewPipe.init(new Downloader() {
                @Override
                public Response execute(Request request) {
                    if (request.url().equals("https://www.youtube.com/sw.js")) {
                        return new Response(200, "OK", Map.of(),
                                "\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"2.20260120.01.00\"", request.url());
                    }
                    if (request.url().contains("/youtubei/v1/visitor_id")) {
                        return new Response(200, "OK", Map.of(),
                                "{\"responseContext\":{\"visitorData\":\"CgtWYWxpZGF0b3JUZXN0\"}}", request.url());
                    }
                    assertTrue(request.url().contains("/youtubei/v1/"), "unexpected request " + request.url());
                    return replies.remove();
                }
            });
            oEmbed = new YouTubeOEmbedClient("test-agent");
            ReflectionTestUtils.setField(oEmbed, "httpClient", new OkHttpClient.Builder().addInterceptor(chain -> {
                oEmbedUrls.add(chain.request().url().toString());
                return oEmbedAnswer.intercept(chain);
            }).build());
            realGateway = new YouTubeGateway(ServiceList.YouTube, 1, null, null);
            realOrchestrator = new ChannelOrchestrator(realGateway, null, null, oEmbed);
        }

        private static Interceptor oEmbedStatus(int code) {
            return chain -> new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("").body(ResponseBody.create("{}", MediaType.get("application/json"))).build();
        }

        @AfterEach
        void tearDown() {
            realGateway.shutdown();
        }

        /** HTTP/2 carries no reason phrase, hence "". */
        private BatchValidationResult<ChannelInfo> validate(int code, String body, String landedOn) {
            replies.add(new Response(code, "", Map.of(), body, landedOn));
            return realOrchestrator.batchValidateChannelsWithDetails(List.of(ID));
        }

        private BatchValidationResult<PlaylistInfo> playlist(int code, String body, String landedOn) {
            replies.add(new Response(code, "", Map.of(), body, landedOn));
            return realOrchestrator.batchValidatePlaylistsWithDetails(List.of(PLAYLIST));
        }

        private BatchValidationResult<StreamInfo> video(int code, String body, String landedOn) {
            replies.add(new Response(code, "", Map.of(), body, landedOn));
            return realOrchestrator.batchValidateVideosWithDetails(List.of(VIDEO));
        }

        /** The Android reel player answer that NewPipe's checkPlayabilityStatus reads. */
        private BatchValidationResult<StreamInfo> playability(String status, String reason) {
            return video(200, "{\"playerResponse\":{\"playabilityStatus\":{\"status\":\"" + status
                    + "\",\"reason\":\"" + reason + "\"}}}", PLAYER);
        }

        private static String alert(String text) {
            return "{\"alerts\":[{\"alertRenderer\":{\"type\":\"ERROR\",\"text\":{\"simpleText\":\"" + text + "\"}}}]}";
        }

        private void assertErrorNotArchived(BatchValidationResult<ChannelInfo> result, String newPipeMessage) {
            assertError(result, ID, newPipeMessage);
        }

        private static void assertError(BatchValidationResult<?> result, String id, String newPipeMessage) {
            assertFalse(result.isNotFound(id), "must not archive: " + newPipeMessage);
            assertTrue(result.isError(id), "must be ERROR (retry): " + newPipeMessage);
            assertTrue(result.getErrorMessage(id).contains(newPipeMessage), result.getErrorMessage(id));
        }

        private static void assertArchived(BatchValidationResult<?> result, String id, String signal) {
            assertTrue(result.isNotFound(id), "must archive: " + signal + " / got " + result.getErrorMessage(id));
            assertFalse(result.isError(id), signal);
        }

        @Test
        @DisplayName("(a) a JSON error 500 is ERROR, not archived")
        void json500_isError() {
            var result = validate(500, "{\"error\":{\"code\":500,\"message\":\"Internal error encountered.\","
                    + "\"status\":\"INTERNAL\"}}", BROWSE);

            assertErrorNotArchived(result, "INTERNAL");
        }

        @Test
        @DisplayName("(b) a JSON 429 RESOURCE_EXHAUSTED is ERROR, not archived")
        void json429_isError() {
            var result = validate(429, "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted "
                    + "(e.g. check quota).\",\"status\":\"RESOURCE_EXHAUSTED\"}}", BROWSE);

            assertErrorNotArchived(result, "RESOURCE_EXHAUSTED");
        }

        @Test
        @DisplayName("(c) an ERROR alert that is not a gone signal is ERROR, not archived")
        void otherErrorAlert_isError() {
            var result = validate(200, "{\"alerts\":[{\"alertRenderer\":{\"type\":\"ERROR\",\"text\":"
                    + "{\"simpleText\":\"This channel is not available.\"}}}]}", BROWSE);

            assertErrorNotArchived(result, "This channel is not available.");
        }

        @Test
        @DisplayName("an /oops redirect is ERROR, not archived")
        void oopsRedirect_isError() {
            var result = validate(200, "{\"responseContext\":{},\"padding\":\"0123456789012345678901234567890123\"}",
                    "https://www.youtube.com/oops");

            assertErrorNotArchived(result, "Content unavailable");
        }

        @Test
        @DisplayName("(d) a terminated account is archived")
        void terminatedAlert_archives() {
            var result = validate(200, "{\"alerts\":[{\"alertRenderer\":{\"type\":\"ERROR\",\"text\":{\"simpleText\":"
                    + "\"This account has been terminated for a violation of YouTube's Terms of Service.\"}}}]}", BROWSE);

            assertTrue(result.isNotFound(ID));
            assertFalse(result.isError(ID));
        }

        @Test
        @DisplayName("(e) \"This channel doesn't exist.\" (JSON error 404) is archived")
        void json404DoesNotExist_archives() {
            var result = validate(200, "{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\","
                    + "\"status\":\"NOT_FOUND\"}}", BROWSE);

            assertTrue(result.isNotFound(ID));
            assertFalse(result.isError(ID));
        }

        @Test
        @DisplayName("R4 channel: a bare HTTP 404 is ERROR (NewPipe checks 404 before the final URL)")
        void http404_isError() {
            assertErrorNotArchived(validate(404, "{}", BROWSE), "Not found");
        }

        @Test
        @DisplayName("R4 channel: a request that ends at /oops with a 404 is ERROR, not archived (Codex repro)")
        void oopsWith404_isError() {
            assertErrorNotArchived(validate(404, "{}", "https://www.youtube.com/oops"), "Not found");
        }

        // ---- Round 2: channels ----

        @Test
        @DisplayName("R2 channel: the ERROR alert reading exactly \"This channel does not exist.\" is archived")
        void channelDoesNotExistAlert_archives() {
            assertArchived(validate(200, alert("This channel does not exist."), BROWSE), ID, "does-not-exist alert");
        }

        @Test
        @DisplayName("R2 channel: a near-miss of that alert is ERROR (exact wording only)")
        void channelDoesNotExistAlertNearMiss_isError() {
            String text = "This channel does not exist. Learn more";
            assertErrorNotArchived(validate(200, alert(text), BROWSE), text);
        }

        // ---- Round 2: playlists ----

        @Test
        @DisplayName("R3 playlist: YouTube's exact alert \"The playlist does not exist.\" is archived")
        void playlistDoesNotExistAlert_archives() {
            assertArchived(playlist(200, alert("The playlist does not exist."), BROWSE), PLAYLIST, "does-not-exist");
        }

        @Test
        @DisplayName("R3 playlist: any other ERROR alert, or a near-miss of that wording, is ERROR")
        void playlistOtherAlert_isError() {
            for (String text : new String[] {"Something went wrong", "The playlist does not exist. Learn more"}) {
                assertError(playlist(200, alert(text), BROWSE), PLAYLIST, text);
            }
        }

        @Test
        @DisplayName("R2 playlist: an /oops redirect is ERROR, not archived")
        void playlistOops_isError() {
            assertError(playlist(200, "{\"responseContext\":{},\"padding\":\"0123456789012345678901234567890123\"}",
                    "https://www.youtube.com/oops"), PLAYLIST, "Content unavailable");
        }

        @Test
        @DisplayName("R2 playlist: an HTTP 404 is ERROR (NewPipe cannot say which of its two requests got it)")
        void playlistHttp404_isError() {
            assertError(playlist(404, "{}", BROWSE), PLAYLIST, "Not found");
        }

        @Test
        @DisplayName("R2 playlist: a terminated owner account is archived")
        void playlistTerminatedAlert_archives() {
            assertArchived(playlist(200, alert("This account has been terminated for a violation of YouTube's "
                    + "Terms of Service."), BROWSE), PLAYLIST, "terminated");
        }

        // ---- Round 2: videos ----

        @Test
        @DisplayName("R2 video: unrecognised non-OK playability statuses are ERROR, not archived")
        void videoUnrecognisedStatus_isError() {
            String[][] cases = {
                    {"UNPLAYABLE", "This content isn't available, try again later."},
                    {"LIVE_STREAM_OFFLINE", "This live event will begin in a few moments."},
                    {"LOGIN_REQUIRED", "Please sign in to continue."},
                    {"ERROR", "Something went wrong"},
            };
            for (String[] c : cases) {
                assertError(playability(c[0], c[1]), VIDEO, c[1]);
            }
        }

        @Test
        @DisplayName("R2 video: a geo-block in the server's region is ERROR, not archived")
        void videoGeoBlock_isError() {
            assertError(playability("UNPLAYABLE", "The uploader has not made this video available in your country"),
                    VIDEO, "GeographicRestrictionException");
        }

        @Test
        @DisplayName("R2 video: an /oops redirect is ERROR, not archived")
        void videoOops_isError() {
            assertError(video(200, "{\"responseContext\":{},\"padding\":\"0123456789012345678901234567890123\"}",
                    "https://www.youtube.com/oops"), VIDEO, "Content unavailable");
        }

        @Test
        @DisplayName("R2 video: an HTTP 404 is ERROR (NewPipe cannot say which of its requests got it)")
        void videoHttp404_isError() {
            assertError(video(404, "{}", PLAYER), VIDEO, "Not found");
        }

        @Test
        @DisplayName("R2 video: removed, private, terminated, age-gated and paid videos are archived")
        void videoGoneOrGated_archives() {
            String[][] cases = {
                    {"ERROR", "This video has been removed by the uploader"},
                    {"UNPLAYABLE", "This video is no longer available due to a copyright claim by Example Rights"},
                    {"LOGIN_REQUIRED", "This video is private"},
                    {"UNPLAYABLE", "This video is no longer available because the YouTube account associated with "
                            + "this video has been terminated."},
                    {"LOGIN_REQUIRED", "Sign in to confirm your age. This video may be inappropriate for some users."},
                    {"UNPLAYABLE", "This video requires payment to watch."},
                    {"UNPLAYABLE", "Join this channel to get access to members-only content like this video."},
                    {"UNPLAYABLE", "This video is only available to Music Premium members"},
            };
            for (String[] c : cases) {
                assertArchived(playability(c[0], c[1]), VIDEO, c[0] + " " + c[1]);
            }
            assertTrue(oEmbedUrls.isEmpty(), "specific reasons archive without oEmbed: " + oEmbedUrls);
        }

        // ---- Round 3: videos ----

        /** A generic reason whose real cause (a geo-block) sits only in errorScreen, which NewPipe's message omits. */
        private static final String GENERIC_WITH_GEO_SUBREASON = "{\"playerResponse\":{\"playabilityStatus\":{"
                + "\"status\":\"UNPLAYABLE\",\"reason\":\"Video unavailable\",\"errorScreen\":{"
                + "\"playerErrorMessageRenderer\":{\"reason\":{\"simpleText\":\"Video unavailable\"},"
                + "\"subreason\":{\"runs\":[{\"text\":\"The uploader has not made this video available in your "
                + "country\"}]}}}}}}";

        /** A breaker that is on, with no persistence, shared by the gateway and the validator as in prod. */
        private ChannelOrchestrator withBreaker(YouTubeCircuitBreaker breaker) {
            YouTubeGateway gatewayWithBreaker = new YouTubeGateway(ServiceList.YouTube, 1, null, breaker);
            return new ChannelOrchestrator(gatewayWithBreaker, null, breaker, oEmbed);
        }

        private static YouTubeCircuitBreaker enabledBreaker() {
            ValidationProperties props = new ValidationProperties();
            props.getYoutube().getCircuitBreaker().setEnabled(true);
            return new YouTubeCircuitBreaker(props, null);
        }

        @Test
        @DisplayName("R3 video: the short age gate archives and is never recorded as a rate limit")
        void videoShortAgeGate_archivesAndIsNotARateLimit() {
            YouTubeCircuitBreaker breaker = enabledBreaker();
            replies.add(new Response(200, "", Map.of(), "{\"playerResponse\":{\"playabilityStatus\":{\"status\":"
                    + "\"LOGIN_REQUIRED\",\"reason\":\"Sign in to confirm your age\"}}}", PLAYER));

            var result = withBreaker(breaker).batchValidateVideosWithDetails(List.of(VIDEO));

            assertArchived(result, VIDEO, "age gate");
            assertEquals(0, breaker.getStatusSnapshot().getTotalRateLimitErrors());
            assertNull(breaker.getStatusSnapshot().getLastErrorType());
            assertEquals(YouTubeCircuitBreaker.State.CLOSED, breaker.getCurrentState());
        }

        @Test
        @DisplayName("R3/R4 video: the bot check (both apostrophes) stays a rate limit, recorded once by the breaker")
        void videoBotCheck_staysARateLimit() {
            for (String reason : new String[] {"Sign in to confirm you're not a bot",
                    "Sign in to confirm you\u2019re not a bot"}) {
                YouTubeCircuitBreaker breaker = enabledBreaker();
                replies.add(new Response(200, "", Map.of(), "{\"playerResponse\":{\"playabilityStatus\":{\"status\":"
                        + "\"LOGIN_REQUIRED\",\"reason\":\"" + reason + "\"}}}", PLAYER));

                var result = withBreaker(breaker).batchValidateVideosWithDetails(List.of(VIDEO));

                assertError(result, VIDEO, "Rate limit");
                assertEquals(1, breaker.getStatusSnapshot().getTotalRateLimitErrors(), reason);
            }
        }

        // ---- Round 4: only the bot check is a sign-in rate limit (breaker on, through the gateway) ----

        private BatchValidationResult<StreamInfo> loginRequired(YouTubeCircuitBreaker breaker, String reason) {
            replies.add(new Response(200, "", Map.of(), "{\"playerResponse\":{\"playabilityStatus\":{\"status\":"
                    + "\"LOGIN_REQUIRED\",\"reason\":\"" + reason + "\"}}}", PLAYER));
            return withBreaker(breaker).batchValidateVideosWithDetails(List.of(VIDEO));
        }

        @Test
        @DisplayName("R4 video: a private video archives and is never recorded as a rate limit")
        void videoPrivate_archivesAndIsNotARateLimit() {
            for (String reason : new String[] {"Private video", "This video is private"}) {
                YouTubeCircuitBreaker breaker = enabledBreaker();

                assertArchived(loginRequired(breaker, reason), VIDEO, reason);
                assertEquals(0, breaker.getStatusSnapshot().getTotalRateLimitErrors(), reason);
            }
        }

        @Test
        @DisplayName("R4 video: any other LOGIN_REQUIRED reason is ERROR and never recorded as a rate limit")
        void videoOtherLoginRequired_isErrorNotARateLimit() {
            YouTubeCircuitBreaker breaker = enabledBreaker();

            assertError(loginRequired(breaker, "Please sign in to continue."), VIDEO, "Please sign in to continue.");
            assertEquals(0, breaker.getStatusSnapshot().getTotalRateLimitErrors());
        }

        // ---- Round 5: no oEmbed corroboration; a generic reason is ERROR with no extra request ----

        @Test
        @DisplayName("R5 video: a generic unavailable reason is ERROR and oEmbed is never asked")
        void videoGenericUnavailable_isErrorWithNoOEmbedCall() {
            oEmbedAnswer = oEmbedStatus(404);   // the old corroboration would have archived on this

            assertError(video(200, GENERIC_WITH_GEO_SUBREASON, PLAYER), VIDEO, "Video unavailable");
            String[][] cases = {{"ERROR", "Video unavailable"}, {"ERROR", "This video is unavailable"},
                    {"UNPLAYABLE", "This video isn't available anymore"}};
            for (String[] c : cases) {
                assertError(playability(c[0], c[1]), VIDEO, c[1]);
            }
            assertTrue(oEmbedUrls.isEmpty(), "no extra request: " + oEmbedUrls);
        }

        @Test
        @DisplayName("R5 video: the generic path leaves the breaker's rate-limit window untouched")
        void videoGenericUnavailable_leavesTheBreakerWindowUntouched() {
            YouTubeCircuitBreaker breaker = enabledBreaker();
            breaker.recordRateLimitError(new IOException("HTTP 429 earlier"));
            breaker.recordRateLimitError(new IOException("HTTP 429 earlier"));
            oEmbedAnswer = oEmbedStatus(401);   // the old corroboration recorded a success here, clearing the window
            List<String> ids = List.of("validatorT1", "validatorT2", "validatorT3");
            for (String reason : new String[] {"Video unavailable", "This video is unavailable",
                    "This video isn't available anymore"}) {
                replies.add(new Response(200, "", Map.of(), "{\"playerResponse\":{\"playabilityStatus\":{\"status\":"
                        + "\"ERROR\",\"reason\":\"" + reason + "\"}}}", PLAYER));
            }

            var result = withBreaker(breaker).batchValidateVideosWithDetails(ids);

            ids.forEach(id -> assertTrue(result.isError(id), id));
            assertEquals(2, breaker.getStatusSnapshot().getTotalRateLimitErrors(), "the validator recorded nothing");
            breaker.recordRateLimitError(new IOException("HTTP 429 later"));
            assertEquals(YouTubeCircuitBreaker.State.OPEN, breaker.getCurrentState(),
                    "the two earlier errors are still in the window, so the third opens the breaker");
        }

        /** The shared prod downloader over an OkHttp fake: version and visitor-id lookups answer 200, every other
         *  InnerTube request answers HTTP 429 with YouTube's RESOURCE_EXHAUSTED JSON body. */
        private static Downloader prodDownloaderAnswering429() {
            OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
                String url = chain.request().url().toString();
                int code = 429;
                String body = "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted (e.g. check quota).\","
                        + "\"status\":\"RESOURCE_EXHAUSTED\"}}";
                if (url.equals("https://www.youtube.com/sw.js")) {
                    code = 200;
                    body = "\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"2.20260120.01.00\"";
                } else if (url.contains("/youtubei/v1/visitor_id")) {
                    code = 200;
                    body = "{\"responseContext\":{\"visitorData\":\"CgtWYWxpZGF0b3JUZXN0\"}}";
                }
                return new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_2).code(code)
                        .message("").body(ResponseBody.create(body, MediaType.get("application/json; charset=utf-8")))
                        .build();
            }).build();
            NewPipeConfiguration config = new NewPipeConfiguration();
            ReflectionTestUtils.setField(config, "userAgent", "test-agent");
            return config.newPipeDownloader(http);
        }

        @Test
        @DisplayName("R5: an HTTP 429 through the prod downloader is ERROR plus one rate limit for every type, never archive")
        void http429ThroughTheProdDownloader_isARateLimitNeverAnArchive() {
            NewPipe.init(prodDownloaderAnswering429());
            YouTubeCircuitBreaker channelBreaker = enabledBreaker();
            YouTubeCircuitBreaker playlistBreaker = enabledBreaker();
            YouTubeCircuitBreaker videoBreaker = enabledBreaker();

            assertError(withBreaker(channelBreaker).batchValidateChannelsWithDetails(List.of(ID)), ID, "Rate limit");
            assertError(withBreaker(playlistBreaker).batchValidatePlaylistsWithDetails(List.of(PLAYLIST)), PLAYLIST,
                    "Rate limit");
            assertError(withBreaker(videoBreaker).batchValidateVideosWithDetails(List.of(VIDEO)), VIDEO, "Rate limit");
            for (YouTubeCircuitBreaker breaker : List.of(channelBreaker, playlistBreaker, videoBreaker)) {
                assertEquals(1, breaker.getStatusSnapshot().getTotalRateLimitErrors());
            }
        }

        @Test
        @DisplayName("R4 channel: a sign-in alert that is not the bot check is ERROR and never a rate limit")
        void channelSignInAlert_isErrorNotARateLimit() {
            YouTubeCircuitBreaker breaker = enabledBreaker();
            replies.add(new Response(200, "", Map.of(), alert("Sign in to confirm your age"), BROWSE));

            var result = withBreaker(breaker).batchValidateChannelsWithDetails(List.of(ID));

            assertErrorNotArchived(result, "Sign in to confirm your age");
            assertEquals(0, breaker.getStatusSnapshot().getTotalRateLimitErrors());
        }
    }

    @Nested
    @DisplayName("DTO Mapping")
    class DtoMappingTests {

        @Test
        @DisplayName("Should map null channel to null DTO")
        void mapToChannelDetailsDto_nullInput() {
            ChannelDetailsDto result = orchestrator.mapToChannelDetailsDto(null);
            assertNull(result);
        }

        @Test
        @DisplayName("Should map channel info to DTO correctly")
        void mapToChannelDetailsDto_success() {
            // Arrange
            ChannelInfo channelInfo = mock(ChannelInfo.class);
            when(channelInfo.getUrl()).thenReturn("https://youtube.com/channel/UC123");
            when(channelInfo.getName()).thenReturn("Test Channel");
            when(channelInfo.getDescription()).thenReturn("Test Description");
            when(channelInfo.getSubscriberCount()).thenReturn(50000L);
            when(channelInfo.getTags()).thenReturn(List.of("tag1", "tag2"));
            when(channelInfo.getAvatars()).thenReturn(Collections.emptyList());
            when(channelInfo.getBanners()).thenReturn(Collections.emptyList());

            // Act
            ChannelDetailsDto result = orchestrator.mapToChannelDetailsDto(channelInfo);

            // Assert
            assertNotNull(result);
            assertEquals("Test Channel", result.getName());
            assertEquals("Test Description", result.getDescription());
            assertEquals(50000L, result.getSubscriberCount());
        }

        @Test
        @DisplayName("Should map channel with avatars correctly")
        void mapToChannelDetailsDto_withAvatars() {
            // Arrange
            ChannelInfo channelInfo = mock(ChannelInfo.class);
            when(channelInfo.getUrl()).thenReturn("https://youtube.com/channel/UC123");
            when(channelInfo.getName()).thenReturn("Test");
            when(channelInfo.getTags()).thenReturn(Collections.emptyList());

            Image avatar = mock(Image.class);
            when(avatar.getUrl()).thenReturn("https://example.com/avatar.jpg");
            when(channelInfo.getAvatars()).thenReturn(List.of(avatar));
            when(channelInfo.getBanners()).thenReturn(Collections.emptyList());

            // Act
            ChannelDetailsDto result = orchestrator.mapToChannelDetailsDto(channelInfo);

            // Assert
            assertEquals("https://example.com/avatar.jpg", result.getThumbnailUrl());
        }

        @Test
        @DisplayName("Should map null stream item to null DTO")
        void mapToStreamItemDto_nullInput() {
            StreamItemDto result = orchestrator.mapToStreamItemDto(null);
            assertNull(result);
        }

        @Test
        @DisplayName("Should map stream info item to DTO correctly")
        void mapToStreamItemDto_success() {
            // Arrange
            StreamInfoItem streamItem = mock(StreamInfoItem.class);
            when(streamItem.getUrl()).thenReturn("https://youtube.com/watch?v=abc123");
            when(streamItem.getName()).thenReturn("Test Video");
            when(streamItem.getUploaderName()).thenReturn("Test Channel");
            when(streamItem.getUploaderUrl()).thenReturn("https://youtube.com/channel/UC1");
            when(streamItem.getViewCount()).thenReturn(10000L);
            when(streamItem.getDuration()).thenReturn(300L);
            when(streamItem.getThumbnails()).thenReturn(Collections.emptyList());
            when(streamItem.getUploadDate()).thenReturn(null);

            // Act
            StreamItemDto result = orchestrator.mapToStreamItemDto(streamItem);

            // Assert
            assertNotNull(result);
            assertEquals("Test Video", result.getName());
            assertEquals("Test Channel", result.getUploaderName());
            assertEquals(10000L, result.getViewCount());
            assertEquals(300L, result.getDuration());
        }
    }
}
