package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.PreviewErrorCode;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;

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
