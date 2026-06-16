package com.albunyaan.tube.util;

import org.schabi.newpipe.extractor.Image;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Helpers for detecting and selecting YouTube channel-avatar / playlist
 * thumbnail URLs.
 *
 * <p>Two failure modes are handled here, both rooted in the same problem: a
 * thumbnail URL is captured once at import time and served forever, with no
 * refresh.
 *
 * <ul>
 *   <li><b>Channel stubs</b> — a legacy seeder wrote fabricated avatar URLs of
 *       the form {@code https://yt3.ggpht.com/ytc/{channelId}}. Real YouTube
 *       avatars carry an opaque image token plus a {@code =sNNN} sizing suffix
 *       after {@code /ytc/}, so a URL whose {@code /ytc/} segment is exactly the
 *       channel id is the fabricated stub (and returns HTTP 400).</li>
 *   <li><b>Volatile playlist thumbnails</b> — YouTube's playlist
 *       {@code /pl_c/{id}/studio_square_thumbnail.jpg} URLs are signed and
 *       carry a {@code days_since_epoch} stamp, so they expire and 404. A stable
 *       thumbnail is an {@code i.ytimg.com/vi/{videoId}/…} (or {@code /vi_webp/})
 *       image, which the client can fallback-ladder.</li>
 * </ul>
 */
public final class ThumbnailUrls {

    /**
     * A usable video thumbnail points at a real 11-char YouTube video id under
     * {@code /vi/} or {@code /vi_webp/}. Legacy seeder rows put a 34-char
     * playlist id (or 24-char channel id) in the {@code /vi/} path, which renders
     * blank — those must not pass as usable.
     */
    private static final Pattern VI_VIDEO_ID = Pattern.compile("/vi(?:_webp)?/[A-Za-z0-9_-]{11}/");

    private ThumbnailUrls() {}

    /**
     * True when a stored channel avatar URL is the fabricated seeder stub or is
     * missing. The stub's {@code /ytc/} segment is exactly the channel id
     * ({@code …/ytc/{channelId}}); a genuine avatar has an opaque token plus a
     * {@code =sNNN} suffix there, so it never ends with {@code /ytc/{channelId}}.
     */
    public static boolean isBrokenChannelAvatar(String url, String youtubeId) {
        if (url == null || url.isBlank()) {
            return true;
        }
        return youtubeId != null && !youtubeId.isBlank() && url.endsWith("/ytc/" + youtubeId);
    }

    /**
     * True when a stored playlist thumbnail URL is unrenderable: blank, the
     * {@code no_thumbnail.jpg} placeholder, or the expiring
     * {@code /pl_c/…/studio_square_thumbnail.jpg} custom thumbnail. A usable
     * thumbnail is an {@code i.ytimg.com/vi/…} or {@code /vi_webp/…} image.
     */
    public static boolean isBrokenPlaylistThumbnail(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        if (url.contains("/img/no_thumbnail")) {
            return true;
        }
        if (url.contains("/pl_c/")) {
            return true;
        }
        // Usable only when it resolves to a real 11-char video id. This also
        // rejects the legacy /vi/{playlistId}/ seeder shape (a 34-char id), which
        // a plain "/vi/" substring check would wrongly accept.
        return !VI_VIDEO_ID.matcher(url).find();
    }

    /**
     * Highest-resolution avatar URL from a NewPipe avatar list, or the first
     * non-blank URL when heights are unknown, or {@code null} when the list has
     * no usable URL.
     */
    public static String bestAvatarUrl(List<Image> avatars) {
        if (avatars == null || avatars.isEmpty()) {
            return null;
        }
        Image best = null;
        for (Image img : avatars) {
            if (img == null || img.getUrl() == null || img.getUrl().isBlank()) {
                continue;
            }
            if (best == null || img.getHeight() > best.getHeight()) {
                best = img;
            }
        }
        return best != null ? best.getUrl() : null;
    }
}
