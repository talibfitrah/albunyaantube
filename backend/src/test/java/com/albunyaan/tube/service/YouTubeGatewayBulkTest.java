package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.PreviewErrorCode;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class YouTubeGatewayBulkTest {

    // YouTubeGateway has a required-arg @Autowired constructor; use CALLS_REAL_METHODS
    // so fetchByDetectedType runs real code while the underlying fetch methods are stubbed.

    @Test
    void contentNotAvailableException_mapsToCode() throws Exception {
        YouTubeGateway gw = mock(YouTubeGateway.class, CALLS_REAL_METHODS);
        doThrow(new ContentNotAvailableException("gone")).when(gw).fetchStreamInfo(anyString());

        var r = gw.fetchByDetectedType(YouTubeContentType.VIDEO, "abc",
                "https://www.youtube.com/watch?v=abc");

        assertEquals(PreviewErrorCode.CONTENT_NOT_AVAILABLE, r.errorCode());
        assertNull(r.metadata());
    }

    @Test
    void privateContentException_mapsToCode() throws Exception {
        YouTubeGateway gw = mock(YouTubeGateway.class, CALLS_REAL_METHODS);
        doThrow(new PrivateContentException("private")).when(gw).fetchPlaylistInfo(anyString());

        var r = gw.fetchByDetectedType(YouTubeContentType.PLAYLIST, "PL1",
                "https://www.youtube.com/playlist?list=PL1");

        assertEquals(PreviewErrorCode.PRIVATE_CONTENT, r.errorCode());
    }

    @Test
    void playlistPreview_swapsExpiringPlcHeaderForFirstVideoThumbnail() throws Exception {
        // Registry bulk submit stores this preview thumbnail; the swap itself is covered by
        // ChannelOrchestratorTest's mapping cases (same ThumbnailUrls.playlistThumbnail helper).
        String playlistId = "PLlZazEh_c4nScNCvGBn8OEf6ujk-sDUpg";
        Image header = mock(Image.class);
        when(header.getUrl()).thenReturn("https://i.ytimg.com/pl_c/" + playlistId
                + "/studio_square_thumbnail.jpg?sqp=CJzKwdYG-oaymwEICKoDEPABSFqi85f_AwYI0sG4xAY=&rs=AOn4CL");
        PlaylistInfo info = mock(PlaylistInfo.class);
        when(info.getThumbnails()).thenReturn(List.of(header));
        when(info.getRelatedItems()).thenReturn(List.of(
                new StreamInfoItem(0, "https://www.youtube.com/watch?v=DW-00ckCAPI", "Part 1", StreamType.VIDEO_STREAM)));
        YouTubeGateway gw = mock(YouTubeGateway.class, CALLS_REAL_METHODS);
        doReturn(info).when(gw).fetchPlaylistInfo(playlistId);

        var r = gw.fetchByDetectedType(YouTubeContentType.PLAYLIST, playlistId,
                "https://www.youtube.com/playlist?list=" + playlistId);

        assertEquals("https://i.ytimg.com/vi/DW-00ckCAPI/hqdefault.jpg", r.metadata().thumbnailUrl());
    }

    @Test
    void accountTerminated_mapsToCode() throws Exception {
        YouTubeGateway gw = mock(YouTubeGateway.class, CALLS_REAL_METHODS);
        doThrow(new AccountTerminatedException("terminated")).when(gw).fetchChannelInfo(anyString());

        var r = gw.fetchByDetectedType(YouTubeContentType.CHANNEL, "UC1",
                "https://www.youtube.com/channel/UC1");

        assertEquals(PreviewErrorCode.CHANNEL_TERMINATED, r.errorCode());
    }

    @Test
    void ioException_mapsToNetworkError() throws Exception {
        YouTubeGateway gw = mock(YouTubeGateway.class, CALLS_REAL_METHODS);
        doThrow(new RuntimeException(new java.io.IOException("net")))
                .when(gw).fetchStreamInfo(anyString());

        var r = gw.fetchByDetectedType(YouTubeContentType.VIDEO, "abc",
                "https://www.youtube.com/watch?v=abc");

        assertEquals(PreviewErrorCode.NETWORK_ERROR, r.errorCode());
    }
}
