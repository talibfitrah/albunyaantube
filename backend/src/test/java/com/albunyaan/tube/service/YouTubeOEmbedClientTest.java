package com.albunyaan.tube.service;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.SocketTimeoutException;
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

    @Test
    void parseAuthorUrlAcceptsAYouTubeHandleUrl() {
        assertEquals(Optional.of("https://www.youtube.com/@ZadTVchannel"),
                client.parseAuthorUrl("{\"author_name\":\"Zad\",\"author_url\":\"https://www.youtube.com/@ZadTVchannel\"}"));
    }

    @Test
    void parseAuthorUrlRejectsAForeignHostOrPlainHttp() {
        assertEquals(Optional.empty(), client.parseAuthorUrl("{\"author_url\":\"https://evil.example/@ZadTVchannel\"}"));
        assertEquals(Optional.empty(), client.parseAuthorUrl("{\"author_url\":\"http://www.youtube.com/@ZadTVchannel\"}"));
        assertEquals(Optional.empty(), client.parseAuthorUrl("{}"));
    }

    /** A client whose every oEmbed call goes through {@code interceptor} (no network). */
    static YouTubeOEmbedClient through(Interceptor interceptor) {
        YouTubeOEmbedClient c = new YouTubeOEmbedClient("test-agent");
        ReflectionTestUtils.setField(c, "httpClient", new OkHttpClient.Builder().addInterceptor(interceptor).build());
        return c;
    }

    /** A client whose every oEmbed call answers HTTP {@code code} with an empty JSON body. */
    static YouTubeOEmbedClient answering(int code) {
        return through(chain -> new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("").body(ResponseBody.create("{}", MediaType.get("application/json"))).build());
    }

    /** R7: a rate limit, a server error or a network failure is no signal; it reaches the caller, not "no author". */
    @Test
    void authorUrlThrowsWhenOEmbedCannotBeAsked() {
        assertThrows(IOException.class, () -> answering(429).authorUrl("J3bDNstCnzE"));
        assertThrows(IOException.class, () -> answering(503).authorUrl("J3bDNstCnzE"));
        assertThrows(IOException.class, () -> through(chain -> {
            throw new SocketTimeoutException("timeout");
        }).authorUrl("J3bDNstCnzE"));
    }

    /** 401 (embedding disabled) and 404 (private or deleted) are real answers: oEmbed can't name the author. */
    @Test
    void authorUrlIsEmptyWhenOEmbedAnswersItCannotNameTheAuthor() throws IOException {
        assertEquals(Optional.empty(), answering(401).authorUrl("J3bDNstCnzE"));
        assertEquals(Optional.empty(), answering(404).authorUrl("J3bDNstCnzE"));
    }
}
