package com.albunyaan.tube.util;

import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Image;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ThumbnailUrls}. These encode the two production bugs
 * directly: a fabricated {@code yt3.ggpht.com/ytc/{channelId}} avatar and an
 * expired {@code /pl_c/} playlist thumbnail must both be classified as broken,
 * while real avatars and usable {@code /vi/} (or {@code /vi_webp/}) images must
 * not be.
 */
class ThumbnailUrlsTest {

    private static final String ZAD_ID = "UCBoe29aQT-zMECFyyyO7H4Q";

    @Test
    void isBrokenChannelAvatar_fabricatedStub_isBroken() {
        // The exact production stub: host + /ytc/ + the channel's own id, no token.
        assertTrue(ThumbnailUrls.isBrokenChannelAvatar(
                "https://yt3.ggpht.com/ytc/" + ZAD_ID, ZAD_ID));
    }

    @Test
    void isBrokenChannelAvatar_nullOrBlank_isBroken() {
        assertTrue(ThumbnailUrls.isBrokenChannelAvatar(null, ZAD_ID));
        assertTrue(ThumbnailUrls.isBrokenChannelAvatar("   ", ZAD_ID));
    }

    @Test
    void isBrokenChannelAvatar_realAvatar_isNotBroken() {
        // Real avatar: opaque token + sizing suffix after /ytc/; never ends with the UC id.
        String real = "https://yt3.googleusercontent.com/ytc/AIdro_n06gVsYQ=s72-c-k-c0x00ffffff-no-rj";
        assertFalse(ThumbnailUrls.isBrokenChannelAvatar(real, ZAD_ID));
    }

    @Test
    void isBrokenChannelAvatar_idAppearingMidPathButNotAsYtcSegment_isNotBroken() {
        // A URL that merely contains the id but is not the …/ytc/{id} stub must not match.
        String notStub = "https://yt3.googleusercontent.com/" + ZAD_ID + "/photo=s88-c-k";
        assertFalse(ThumbnailUrls.isBrokenChannelAvatar(notStub, ZAD_ID));
    }

    @Test
    void isBrokenPlaylistThumbnail_plcAndNoThumbnailAndBlank_areBroken() {
        assertTrue(ThumbnailUrls.isBrokenPlaylistThumbnail(null));
        assertTrue(ThumbnailUrls.isBrokenPlaylistThumbnail(""));
        assertTrue(ThumbnailUrls.isBrokenPlaylistThumbnail(
                "https://i.ytimg.com/pl_c/PLlZazEh_c4n/studio_square_thumbnail.jpg?sqp=x&days_since_epoch=20603"));
        assertTrue(ThumbnailUrls.isBrokenPlaylistThumbnail("https://i.ytimg.com/img/no_thumbnail.jpg"));
    }

    @Test
    void isBrokenPlaylistThumbnail_viAndViWebpImages_areNotBroken() {
        assertFalse(ThumbnailUrls.isBrokenPlaylistThumbnail(
                "https://i.ytimg.com/vi/ABCDEFGHIJK/hqdefault.jpg"));
        // Signed /vi/ variant is still usable — the client strips/ladders it.
        assertFalse(ThumbnailUrls.isBrokenPlaylistThumbnail(
                "https://i.ytimg.com/vi/ABCDEFGHIJK/hqdefault.jpg?sqp=x&rs=y"));
        // webp variant lives under /vi_webp/ — also usable.
        assertFalse(ThumbnailUrls.isBrokenPlaylistThumbnail(
                "https://i.ytimg.com/vi_webp/ABCDEFGHIJK/hqdefault.webp"));
        // img.youtube.com is also a legitimate YouTube thumbnail host.
        assertFalse(ThumbnailUrls.isBrokenPlaylistThumbnail(
                "https://img.youtube.com/vi/ABCDEFGHIJK/hqdefault.jpg"));
    }

    @Test
    void isBrokenPlaylistThumbnail_offPlatformHost_isBroken() {
        // A /vi/{11}/ image on a non-YouTube host (or via path/userinfo spoof) must
        // be classified broken so it gets healed, never accepted as usable.
        assertTrue(ThumbnailUrls.isBrokenPlaylistThumbnail(
                "https://evil.example/vi/ABCDEFGHIJK/hqdefault.jpg"));
        assertTrue(ThumbnailUrls.isBrokenPlaylistThumbnail(
                "https://i.ytimg.com@evil.example/vi/ABCDEFGHIJK/hqdefault.jpg"));
    }

    @Test
    void isBrokenPlaylistThumbnail_legacyPlaylistIdInViPath_isBroken() {
        // Legacy seeder shape: a 34-char playlist id sits in the /vi/ path — there
        // is no such video, so it renders blank and must be classified broken even
        // though it contains the "/vi/" substring.
        assertTrue(ThumbnailUrls.isBrokenPlaylistThumbnail(
                "https://i.ytimg.com/vi/PLUitXL66pnO-yT8kCjZX7fIcx8ksPkJ47/mqdefault.jpg"));
    }

    @Test
    void bestAvatarUrl_picksHighestResolution() {
        Image small = mock(Image.class);
        when(small.getUrl()).thenReturn("small");
        when(small.getHeight()).thenReturn(48);
        Image large = mock(Image.class);
        when(large.getUrl()).thenReturn("large");
        when(large.getHeight()).thenReturn(800);

        assertEquals("large", ThumbnailUrls.bestAvatarUrl(List.of(small, large)));
    }

    @Test
    void bestAvatarUrl_skipsBlankUrls_andHandlesEmpty() {
        assertNull(ThumbnailUrls.bestAvatarUrl(null));
        assertNull(ThumbnailUrls.bestAvatarUrl(List.of()));

        Image blank = mock(Image.class);
        when(blank.getUrl()).thenReturn("");
        Image good = mock(Image.class);
        when(good.getUrl()).thenReturn("good");
        when(good.getHeight()).thenReturn(-1); // unknown height
        assertEquals("good", ThumbnailUrls.bestAvatarUrl(List.of(blank, good)));
    }
}
