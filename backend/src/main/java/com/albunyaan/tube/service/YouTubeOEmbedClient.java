package com.albunyaan.tube.service;

import com.albunyaan.tube.util.ThumbnailUrls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Resolves a stable playlist thumbnail via YouTube's public oEmbed endpoint.
 *
 * <p>NewPipe v0.25.2 cannot parse current YouTube playlist item lists
 * ({@code lockupViewModel}), so {@code PlaylistInfo.getRelatedItems()} comes
 * back empty and the only thumbnail it exposes is the signed, dated
 * {@code /pl_c/…/studio_square_thumbnail.jpg} URL that expires (HTTP 404).
 * oEmbed sidesteps that: for a playlist it returns the first video's
 * {@code i.ytimg.com/vi/{videoId}/hqdefault.jpg} image — stable, never-expiring,
 * and fallback-ladderable by the client. This avoids a NewPipe major bump that
 * would also disturb the live dub-poToken extraction path.
 */
@Service
public class YouTubeOEmbedClient {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeOEmbedClient.class);
    private static final String OEMBED_BASE = "https://www.youtube.com/oembed";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String userAgent;

    public YouTubeOEmbedClient(
            @Value("${app.newpipe.http.user-agent:Mozilla/5.0 (Windows NT 10.0; rv:127.0) Gecko/20100101 Firefox/127.0}")
            String userAgent) {
        this.userAgent = userAgent;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Returns a stable {@code /vi/} thumbnail for the playlist, or empty if
     * oEmbed is unavailable (private/deleted playlist, network error) or returns
     * an unusable URL.
     */
    public Optional<String> playlistThumbnailUrl(String playlistId) {
        if (playlistId == null || playlistId.isBlank()) {
            return Optional.empty();
        }
        String pageUrl = "https://www.youtube.com/playlist?list=" + playlistId;
        String requestUrl = OEMBED_BASE + "?url="
                + URLEncoder.encode(pageUrl, StandardCharsets.UTF_8) + "&format=json";

        Request request = new Request.Builder()
                .url(requestUrl)
                .header("User-Agent", userAgent)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                logger.warn("oEmbed lookup for playlist {} returned HTTP {}", playlistId, response.code());
                return Optional.empty();
            }
            return parseUsableThumbnail(response.body().string());
        } catch (IOException e) {
            logger.warn("oEmbed lookup for playlist {} failed: {}", playlistId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses the {@code thumbnail_url} from an oEmbed JSON body and returns it
     * only if it is a usable (non-expiring {@code /vi/}) image. Package-private
     * for unit testing without a network round-trip.
     */
    Optional<String> parseUsableThumbnail(String jsonBody) {
        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            String thumbnailUrl = root.path("thumbnail_url").asText(null);
            // Pin to YouTube's image CDN over HTTPS: the value is only ever stored
            // and rendered as an image src, but a host allowlist keeps a surprising
            // oEmbed response from persisting an off-platform URL.
            if (thumbnailUrl == null
                    || !thumbnailUrl.startsWith("https://")
                    || !thumbnailUrl.contains(".ytimg.com/")
                    || ThumbnailUrls.isBrokenPlaylistThumbnail(thumbnailUrl)) {
                return Optional.empty();
            }
            return Optional.of(thumbnailUrl);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
