package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.PreviewErrorCode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BULK-01 (T2) — regex-only YouTube URL classifier. No network calls.
 * NewPipe handle/c/user → UC... resolution happens later in {@link YouTubeGateway}.
 */
@Service
public class YouTubeUrlParser {

    private static final java.util.Set<String> YOUTUBE_HOSTS = java.util.Set.of(
            "www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be"
    );

    private static final Pattern WATCH_V_PARAM = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})");
    private static final Pattern SHORTS_PATH = Pattern.compile("^/shorts/([a-zA-Z0-9_-]{11})/?$");
    private static final Pattern LIVE_PATH = Pattern.compile("^/live/([a-zA-Z0-9_-]{11})/?$");
    private static final Pattern PLAYLIST_LIST_PARAM = Pattern.compile("[?&]list=(PL[a-zA-Z0-9_-]+|LL[a-zA-Z0-9_-]+|UU[a-zA-Z0-9_-]+|RD[a-zA-Z0-9_-]+|OL[a-zA-Z0-9_-]+)");
    private static final Pattern CHANNEL_UC_PATH = Pattern.compile("^/channel/(UC[a-zA-Z0-9_-]{22})/?.*$");
    private static final Pattern HANDLE_PATH = Pattern.compile("^/(@[A-Za-z0-9._-]{1,30})/?.*$");
    private static final Pattern LEGACY_C_PATH = Pattern.compile("^/c/([A-Za-z0-9._-]+)/?.*$");
    private static final Pattern LEGACY_USER_PATH = Pattern.compile("^/user/([A-Za-z0-9._-]+)/?.*$");
    private static final Pattern POST_PATH = Pattern.compile("^/post/.+");
    private static final Pattern RESULTS_PATH = Pattern.compile("^/results/?$");
    private static final Pattern PLAYLIST_PATH = Pattern.compile("^/playlist/?$");

    /** Trim, BOM-strip, and remove zero-width and bidi-override characters from a URL string. */
    private static String sanitize(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]", "")
                .trim();
    }

    public YouTubeUrlParseResult parse(String rawUrl) {
        String url = sanitize(rawUrl);
        if (url.isEmpty()) return YouTubeUrlParseResult.error(PreviewErrorCode.NOT_YOUTUBE_URL);

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return YouTubeUrlParseResult.error(PreviewErrorCode.NOT_YOUTUBE_URL);
        }

        String host = uri.getHost();
        if (host == null) return YouTubeUrlParseResult.error(PreviewErrorCode.NOT_YOUTUBE_URL);
        host = host.toLowerCase();

        if (host.equals("music.youtube.com")) return YouTubeUrlParseResult.error(PreviewErrorCode.UNSUPPORTED_TYPE);
        if (!YOUTUBE_HOSTS.contains(host)) return YouTubeUrlParseResult.error(PreviewErrorCode.NOT_YOUTUBE_URL);

        String path = uri.getPath() == null ? "/" : uri.getPath();
        String rawQuery = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();

        // youtu.be/{id}
        if (host.equals("youtu.be")) {
            String id = path.replaceFirst("^/", "").replaceFirst("/.*$", "");
            if (id.matches("[a-zA-Z0-9_-]{11}")) {
                return YouTubeUrlParseResult.ok(YouTubeContentType.VIDEO, id, canonicalWatchUrl(id), false);
            }
            return YouTubeUrlParseResult.error(PreviewErrorCode.NOT_YOUTUBE_URL);
        }

        Matcher shortsM = SHORTS_PATH.matcher(path);
        if (shortsM.matches()) return YouTubeUrlParseResult.error(PreviewErrorCode.UNSUPPORTED_SHORTS);

        Matcher liveM = LIVE_PATH.matcher(path);
        if (liveM.matches()) {
            String id = liveM.group(1);
            return YouTubeUrlParseResult.ok(YouTubeContentType.VIDEO, id, canonicalWatchUrl(id), false);
        }

        if (path.equals("/watch") || path.equals("/watch/")) {
            Matcher vM = WATCH_V_PARAM.matcher(rawQuery);
            if (vM.find()) {
                String id = vM.group(1);
                return YouTubeUrlParseResult.ok(YouTubeContentType.VIDEO, id, canonicalWatchUrl(id), false);
            }
            return YouTubeUrlParseResult.error(PreviewErrorCode.NOT_YOUTUBE_URL);
        }

        if (PLAYLIST_PATH.matcher(path).matches()) {
            Matcher pM = PLAYLIST_LIST_PARAM.matcher(rawQuery);
            if (pM.find()) {
                String id = pM.group(1);
                return YouTubeUrlParseResult.ok(YouTubeContentType.PLAYLIST, id, canonicalPlaylistUrl(id), false);
            }
            return YouTubeUrlParseResult.error(PreviewErrorCode.UNSUPPORTED_TYPE);
        }

        Matcher chM = CHANNEL_UC_PATH.matcher(path);
        if (chM.matches()) {
            String id = chM.group(1);
            return YouTubeUrlParseResult.ok(YouTubeContentType.CHANNEL, id, canonicalChannelUrl(id), false);
        }

        Matcher hM = HANDLE_PATH.matcher(path);
        if (hM.matches()) {
            String handle = hM.group(1);
            return YouTubeUrlParseResult.ok(YouTubeContentType.CHANNEL, handle, "https://www.youtube.com/" + handle, false);
        }

        Matcher cM = LEGACY_C_PATH.matcher(path);
        if (cM.matches()) {
            String name = cM.group(1);
            return YouTubeUrlParseResult.ok(YouTubeContentType.CHANNEL, name, "https://www.youtube.com/c/" + name, false);
        }

        Matcher uM = LEGACY_USER_PATH.matcher(path);
        if (uM.matches()) {
            String name = uM.group(1);
            return YouTubeUrlParseResult.ok(YouTubeContentType.CHANNEL, name, "https://www.youtube.com/user/" + name, false);
        }

        if (POST_PATH.matcher(path).matches()) return YouTubeUrlParseResult.error(PreviewErrorCode.UNSUPPORTED_TYPE);
        if (RESULTS_PATH.matcher(path).matches()) return YouTubeUrlParseResult.error(PreviewErrorCode.UNSUPPORTED_TYPE);

        return YouTubeUrlParseResult.error(PreviewErrorCode.UNSUPPORTED_TYPE);
    }

    private static String canonicalWatchUrl(String id) { return "https://www.youtube.com/watch?v=" + id; }
    private static String canonicalPlaylistUrl(String id) { return "https://www.youtube.com/playlist?list=" + id; }
    private static String canonicalChannelUrl(String id) { return "https://www.youtube.com/channel/" + id; }
}
