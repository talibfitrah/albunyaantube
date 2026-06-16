package com.albunyaan.tube.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link YouTubeOEmbedClient} JSON parsing — verifies a real
 * oEmbed body yields the first video's stable {@code /vi/} thumbnail and that
 * unusable/expiring URLs are rejected, without any network round-trip.
 */
class YouTubeOEmbedClientTest {

    private final YouTubeOEmbedClient client = new YouTubeOEmbedClient("test-agent");

    @Test
    void parsesViThumbnailFromRealOembedBody() {
        // Trimmed real oEmbed response for the "O MESSENGER" playlist.
        String body = "{\"title\":\"O MESSENGER AI-Visualized Series\",\"type\":\"video\","
                + "\"thumbnail_url\":\"https://i.ytimg.com/vi/sQMC7fkjmOA/hqdefault.jpg\"}";

        Optional<String> result = client.parseUsableThumbnail(body);

        assertTrue(result.isPresent());
        assertEquals("https://i.ytimg.com/vi/sQMC7fkjmOA/hqdefault.jpg", result.get());
    }

    @Test
    void rejectsExpiringPlcThumbnail() {
        String body = "{\"thumbnail_url\":\"https://i.ytimg.com/pl_c/PLx/studio_square_thumbnail.jpg?days_since_epoch=20603\"}";
        assertTrue(client.parseUsableThumbnail(body).isEmpty());
    }

    @Test
    void rejectsOffPlatformHost_evenWhenShapedLikeAViThumbnail() {
        // A /vi/{11}/ image on a non-YouTube host must not be persisted.
        String body = "{\"thumbnail_url\":\"https://evil.example/vi/ABCDEFGHIJK/hqdefault.jpg\"}";
        assertTrue(client.parseUsableThumbnail(body).isEmpty());
    }

    @Test
    void rejectsHostSpoofViaPathOrUserinfo() {
        // ".ytimg.com/" appears in the PATH but the real host is evil.example.
        assertTrue(client.parseUsableThumbnail(
                "{\"thumbnail_url\":\"https://evil.example/.ytimg.com/vi/ABCDEFGHIJK/hqdefault.jpg\"}").isEmpty());
        // userinfo trick: real host is evil.example.
        assertTrue(client.parseUsableThumbnail(
                "{\"thumbnail_url\":\"https://i.ytimg.com@evil.example/vi/ABCDEFGHIJK/hqdefault.jpg\"}").isEmpty());
    }

    @Test
    void acceptsRealYtimgHostCaseInsensitively() {
        assertTrue(client.parseUsableThumbnail(
                "{\"thumbnail_url\":\"https://i.YTIMG.COM/vi/ABCDEFGHIJK/hqdefault.jpg\"}").isPresent());
    }

    @Test
    void rejectsLegacyPlaylistIdInViPath() {
        String body = "{\"thumbnail_url\":\"https://i.ytimg.com/vi/PLUitXL66pnO-yT8kCjZX7fIcx8ksPkJ47/mqdefault.jpg\"}";
        assertTrue(client.parseUsableThumbnail(body).isEmpty());
    }

    @Test
    void handlesMissingFieldAndMalformedJson() {
        assertTrue(client.parseUsableThumbnail("{\"title\":\"no thumbnail field\"}").isEmpty());
        assertTrue(client.parseUsableThumbnail("not json at all").isEmpty());
    }

    @Test
    void playlistThumbnailUrl_blankId_returnsEmptyWithoutNetwork() {
        assertTrue(client.playlistThumbnailUrl(null).isEmpty());
        assertTrue(client.playlistThumbnailUrl("  ").isEmpty());
    }
}
