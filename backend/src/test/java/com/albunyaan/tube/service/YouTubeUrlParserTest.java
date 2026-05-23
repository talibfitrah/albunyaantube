package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.PreviewErrorCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YouTubeUrlParserTest {

    private final YouTubeUrlParser parser = new YouTubeUrlParser();

    @Test
    void watchUrl_resolvesToVideo() {
        var r = parser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals(YouTubeContentType.VIDEO, r.type());
        assertEquals("dQw4w9WgXcQ", r.youtubeId());
        assertFalse(r.isShort());
        assertNull(r.errorCode());
    }

    @Test
    void youtuBeShort_resolvesToVideo() {
        var r = parser.parse("https://youtu.be/dQw4w9WgXcQ");
        assertEquals(YouTubeContentType.VIDEO, r.type());
        assertEquals("dQw4w9WgXcQ", r.youtubeId());
    }

    @Test
    void shortsUrl_isUnsupported() {
        var r = parser.parse("https://www.youtube.com/shorts/dQw4w9WgXcQ");
        assertNull(r.type());
        assertEquals(PreviewErrorCode.UNSUPPORTED_SHORTS, r.errorCode());
    }

    @Test
    void liveUrl_resolvesToVideo() {
        var r = parser.parse("https://www.youtube.com/live/dQw4w9WgXcQ");
        assertEquals(YouTubeContentType.VIDEO, r.type());
        assertEquals("dQw4w9WgXcQ", r.youtubeId());
    }

    @Test
    void playlistUrl_resolvesToPlaylist() {
        var r = parser.parse("https://www.youtube.com/playlist?list=PLxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        assertEquals(YouTubeContentType.PLAYLIST, r.type());
        assertEquals("PLxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", r.youtubeId());
    }

    @Test
    void channelIdUrl_resolvesToChannel() {
        var r = parser.parse("https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx");
        assertEquals(YouTubeContentType.CHANNEL, r.type());
        assertEquals("UCxxxxxxxxxxxxxxxxxxxxxx", r.youtubeId());
    }

    @Test
    void handleUrl_isRejected_asUnsupportedHandle() {
        // BULK-01 Group E: handle URLs need gateway-side resolution to UC... id;
        // until that lands they're rejected so dedupe doesn't silently miss.
        var r = parser.parse("https://www.youtube.com/@SomeHandle");
        assertEquals(com.albunyaan.tube.dto.registry.PreviewErrorCode.UNSUPPORTED_HANDLE, r.errorCode());
    }

    @Test
    void legacyCUrl_isRejected_asUnsupportedHandle() {
        var r = parser.parse("https://www.youtube.com/c/SomeChannel");
        assertEquals(com.albunyaan.tube.dto.registry.PreviewErrorCode.UNSUPPORTED_HANDLE, r.errorCode());
    }

    @Test
    void legacyUserUrl_isRejected_asUnsupportedHandle() {
        var r = parser.parse("https://www.youtube.com/user/SomeUser");
        assertEquals(com.albunyaan.tube.dto.registry.PreviewErrorCode.UNSUPPORTED_HANDLE, r.errorCode());
    }

    @Test
    void mobilePrefix_isStripped() {
        var r = parser.parse("https://m.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals(YouTubeContentType.VIDEO, r.type());
        assertEquals("dQw4w9WgXcQ", r.youtubeId());
    }

    @Test
    void musicSubdomain_isUnsupported() {
        var r = parser.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals(PreviewErrorCode.UNSUPPORTED_TYPE, r.errorCode());
    }

    @Test
    void postUrl_isUnsupported() {
        var r = parser.parse("https://www.youtube.com/post/UgkxIDxxxxx");
        assertEquals(PreviewErrorCode.UNSUPPORTED_TYPE, r.errorCode());
    }

    @Test
    void nonYoutubeHost_isRejected() {
        var r = parser.parse("https://example.com/watch?v=dQw4w9WgXcQ");
        assertEquals(PreviewErrorCode.NOT_YOUTUBE_URL, r.errorCode());
    }

    @Test
    void whitespaceAndBom_areTrimmed() {
        var r = parser.parse("  ﻿https://www.youtube.com/watch?v=dQw4w9WgXcQ  ");
        assertEquals(YouTubeContentType.VIDEO, r.type());
        assertEquals("dQw4w9WgXcQ", r.youtubeId());
    }

    @Test
    void zeroWidthChars_inUrl_areSanitized() {
        // U+200B zero-width space injected after host
        var r = parser.parse("https://www.youtube.com​/watch?v=dQw4w9WgXcQ");
        assertEquals(YouTubeContentType.VIDEO, r.type());
        assertEquals("dQw4w9WgXcQ", r.youtubeId());
    }

    @Test
    void emptyString_returnsNotYoutube() {
        var r = parser.parse("");
        assertEquals(PreviewErrorCode.NOT_YOUTUBE_URL, r.errorCode());
    }
}
