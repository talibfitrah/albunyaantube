package com.albunyaan.tube.controller;

import com.albunyaan.tube.exception.ContentGoneException;
import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.service.PublicContentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ANDROID-MULTI-01 Issue 4: Public share landing pages that link unfurlers
 * (WhatsApp, Telegram, Slack, Skype) can crawl to render rich previews with
 * thumbnail + title + description for videos, channels and playlists.
 *
 * <p>This endpoint exists purely to serve OpenGraph meta tags in a static HTML
 * response — link preview crawlers do not execute JavaScript, so a client-side
 * rendered Vue/React page cannot provide og:image. A minimal server-rendered
 * page is the simplest path.
 *
 * <p>When a human visits the URL on mobile, a JS hop deep-links them into the
 * native FitrahTube app (scheme {@code albunyaantube://video/{id}}). On desktop
 * they see the preview with a Play-in-app CTA.
 */
@Controller
public class WatchPageController {

    private static final Logger log = LoggerFactory.getLogger(WatchPageController.class);

    private static final int DESCRIPTION_MAX_CHARS = 300;
    /** The FitrahTube logo card the landing site already serves as its own og:image (landing/build.py), 1200x630. */
    private static final String GENERIC_CARD_IMAGE = "https://fitrahtube.com/og.png";

    /**
     * Allowed shape for the {@code videoId} path variable. Firestore document IDs and
     * YouTube IDs both fit inside this charset; anything outside it is guaranteed to be
     * a malformed or attacker-crafted value and is rejected before any rendering occurs.
     */
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern YOUTUBE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern YOUTUBE_THUMBNAIL_PATTERN = Pattern.compile(
            "^https?://(?:i\\.ytimg\\.com|img\\.youtube\\.com)/(?:vi|vi_webp)/([A-Za-z0-9_-]{11})/.*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YOUTUBE_AVATAR_SIZE_PATTERN = Pattern.compile("=s\\d+(?=-|$)");
    private static final Pattern HTTPS_URL_PATTERN = Pattern.compile("^https://[^\\s\"'<>]+$", Pattern.CASE_INSENSITIVE);

    private final PublicContentService contentService;
    private final String publicBaseUrl;

    /**
     * {@code publicBaseUrl} is the only origin og:url and canonical links ever use; request Host and
     * X-Forwarded-* headers never reach the page. Its default lives here, not in application.yml, because prod's
     * application-prod.yml replaces the classpath yml.
     */
    public WatchPageController(PublicContentService contentService,
                               @Value("${app.public-base-url:https://app.fitrahtube.com}") String publicBaseUrl) {
        this.contentService = contentService;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    /**
     * Retired: share pages render only server-known text (registry rows, else one generic card), so what
     * a caller posts here is never shown. The route stays authenticated and answers 204, so installed Android apps,
     * which post on every share and only log a non-2xx, see no change. The body is not read.
     */
    @PostMapping("/api/share-metadata/{type}/{id}")
    @ResponseBody
    public ResponseEntity<Void> shareMetadata() {
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = {"/watch/{videoId}", "/api/watch/{videoId}"}, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> watch(@PathVariable String videoId, HttpServletRequest request) {
        // Reject malformed IDs outright. Defense-in-depth: even though the response
        // escapes the value, keeping obvious attack payloads out of the rendering path
        // eliminates a whole class of bugs (XSS via </script> injection, log spoofing,
        // etc.) before any handler runs.
        if (videoId == null || !VIDEO_ID_PATTERN.matcher(videoId).matches()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(buildUnavailableHtml("", "", "", "FitrahTube", "This video is not available.", ""));
        }
        String canonicalUrl = canonicalUrl(request, "watch", videoId);
        try {
            Video video = contentService.getVideoDetails(videoId);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(buildHtml(video, canonicalUrl));
        } catch (Exception e) {
            return fallback(e, "video", videoId, canonicalUrl);
        }
    }

    @GetMapping(value = {"/channel/{channelId}", "/api/channel/{channelId}"}, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> channel(@PathVariable String channelId, HttpServletRequest request) {
        if (channelId == null || !VIDEO_ID_PATTERN.matcher(channelId).matches()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(buildUnavailableHtml("", "", "", "FitrahTube", "This channel is not available.", ""));
        }
        String canonicalUrl = canonicalUrl(request, "channel", channelId);
        try {
            Channel channel = contentService.getChannelDetails(channelId);
            String html = buildShareHtml(
                    firstNonBlank(channel.getName(), "FitrahTube Channel"),
                    "FitrahTube channel",
                    truncate(nullSafe(channel.getDescription(), ""), DESCRIPTION_MAX_CHARS),
                    resolveChannelImage(channel.getThumbnailUrl()),
                    canonicalUrl,
                    "albunyaantube://channel/" + channelId,
                    "Open channel in FitrahTube",
                    "profile");
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (Exception e) {
            return fallback(e, "channel", channelId, canonicalUrl);
        }
    }

    @GetMapping(value = {"/playlist/{playlistId}", "/api/playlist/{playlistId}"}, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> playlist(@PathVariable String playlistId, HttpServletRequest request) {
        if (playlistId == null || !VIDEO_ID_PATTERN.matcher(playlistId).matches()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(buildUnavailableHtml("", "", "", "FitrahTube", "This playlist is not available.", ""));
        }
        String canonicalUrl = canonicalUrl(request, "playlist", playlistId);
        try {
            Playlist playlist = contentService.getPlaylistDetails(playlistId);
            String itemCount = playlist.getItemCount() == null ? "FitrahTube playlist" : playlist.getItemCount() + " videos";
            String html = buildShareHtml(
                    firstNonBlank(playlist.getTitle(), "FitrahTube Playlist"),
                    itemCount,
                    truncate(nullSafe(playlist.getDescription(), ""), DESCRIPTION_MAX_CHARS),
                    resolvePreviewImage(playlist.getThumbnailUrl(), ""),
                    canonicalUrl,
                    "albunyaantube://playlist/" + playlistId,
                    "Open playlist in FitrahTube",
                    "website");
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (Exception e) {
            return fallback(e, "playlist", playlistId, canonicalUrl);
        }
    }

    private String buildHtml(Video video, String canonicalUrl) {
        String youtubeId = nullSafe(video.getYoutubeId(), video.getId());
        return buildShareHtml(
                nullSafe(video.getTitle(), "FitrahTube"),
                nullSafe(video.getChannelTitle(), ""),
                truncate(nullSafe(video.getDescription(), ""), DESCRIPTION_MAX_CHARS),
                resolveThumbnailUrl(video),
                canonicalUrl,
                "albunyaantube://video/" + youtubeId,
                "Open in FitrahTube",
                "video.other"
        );
    }

    /**
     * Why the registry lookup failed decides the page:
     * - not in the catalog, or not public yet (the service's 404): one generic, server-authored card. It never carries
     *   a per-id title or thumbnail, so a fitrahtube.com link can't be dressed up as arbitrary content;
     * - blocked (the service's 410): what it answered before the fix, a video's 200 card, a channel's or playlist's 404;
     * - anything else is a registry error: 503 no-store, so an unfurler never caches a stand-in for a catalog item.
     */
    private ResponseEntity<String> fallback(Exception e, String kind, String id, String canonicalUrl) {
        String deepLink = "albunyaantube://" + kind + "/" + id;
        String label = kind.substring(0, 1).toUpperCase(Locale.ROOT) + kind.substring(1);
        if (!(e instanceof ResourceNotFoundException) && !(e instanceof ContentGoneException)) {
            log.warn("Share page registry error for {} {}: {}", kind, id, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                    .contentType(MediaType.TEXT_HTML)
                    .body(buildUnavailableHtml(id, canonicalUrl, deepLink, "FitrahTube",
                            "This " + kind + " is temporarily unavailable. Please try again shortly.", "Temporarily unavailable"));
        }
        boolean card = "video".equals(kind) ? YOUTUBE_ID_PATTERN.matcher(id).matches() : e instanceof ResourceNotFoundException;
        if (!card) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML)
                    .body(buildUnavailableHtml(id, canonicalUrl, deepLink, "FitrahTube",
                            "This " + kind + " is not available.", label + " not found"));
        }
        return ResponseEntity.ok().header("X-Robots-Tag", "noindex").contentType(MediaType.TEXT_HTML).body(buildShareHtml(
                "FitrahTube " + label,
                "",
                "Open this " + kind + " in the FitrahTube app.",
                GENERIC_CARD_IMAGE,
                canonicalUrl,
                deepLink,
                "Open in FitrahTube",
                "website"));
    }

    private String buildShareHtml(
            String rawTitle,
            String rawSubtitle,
            String rawDescription,
            String rawThumbnail,
            String canonicalUrl,
            String deepLink,
            String ctaText,
            String ogType
    ) {
        String title = escapeHtml(nullSafe(rawTitle, "FitrahTube"));
        String subtitle = escapeHtml(nullSafe(rawSubtitle, ""));
        String description = escapeHtml(truncate(nullSafe(rawDescription, ""), DESCRIPTION_MAX_CHARS));
        String thumbnail = escapeAttr(nullSafe(rawThumbnail, ""));
        boolean profileImage = "profile".equals(ogType);
        boolean genericCard = GENERIC_CARD_IMAGE.equals(rawThumbnail);
        int imageWidth = genericCard ? 1200 : profileImage ? 512 : 480;
        int imageHeight = genericCard ? 630 : profileImage ? 512 : 360;

        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n<html lang=\"en\"><head>\n");
        html.append("<meta charset=\"utf-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n");
        // The generic card answers for any made-up id: keep it out of search indexes. Catalog pages stay indexable.
        html.append("<meta name=\"robots\" content=\"")
                .append(genericCard ? "noindex" : "index,follow,max-image-preview:large").append("\">\n");
        html.append("<title>").append(title).append(" — FitrahTube</title>\n");
        html.append("<meta name=\"description\" content=\"").append(description).append("\">\n");
        html.append("<link rel=\"canonical\" href=\"").append(escapeAttr(canonicalUrl)).append("\">\n");
        if (!thumbnail.isEmpty()) {
            html.append("<link rel=\"image_src\" href=\"").append(thumbnail).append("\">\n");
        }
        // OpenGraph
        html.append("<meta property=\"og:type\" content=\"").append(escapeAttr(nullSafe(ogType, "website"))).append("\">\n");
        html.append("<meta property=\"og:title\" content=\"").append(title).append("\">\n");
        html.append("<meta property=\"og:description\" content=\"").append(description).append("\">\n");
        html.append("<meta property=\"og:url\" content=\"").append(escapeAttr(canonicalUrl)).append("\">\n");
        html.append("<meta property=\"og:site_name\" content=\"FitrahTube\">\n");
        if (!thumbnail.isEmpty()) {
            html.append("<meta property=\"og:image\" content=\"").append(thumbnail).append("\">\n");
            if (thumbnail.startsWith("https://")) {
                html.append("<meta property=\"og:image:secure_url\" content=\"").append(thumbnail).append("\">\n");
            }
            if (isStableYoutubeJpeg(rawThumbnail)) {
                html.append("<meta property=\"og:image:type\" content=\"image/jpeg\">\n");
            }
            html.append("<meta property=\"og:image:width\" content=\"").append(imageWidth).append("\">\n");
            html.append("<meta property=\"og:image:height\" content=\"").append(imageHeight).append("\">\n");
            html.append("<meta property=\"og:image:alt\" content=\"").append(title).append("\">\n");
        }
        // Twitter cards — used by Slack, Skype and a few others in addition to OG
        html.append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n");
        html.append("<meta name=\"twitter:title\" content=\"").append(title).append("\">\n");
        html.append("<meta name=\"twitter:description\" content=\"").append(description).append("\">\n");
        if (!thumbnail.isEmpty()) {
            html.append("<meta name=\"twitter:image\" content=\"").append(thumbnail).append("\">\n");
            html.append("<meta name=\"twitter:image:alt\" content=\"").append(title).append("\">\n");
        }
        html.append("<style>\n");
        html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;");
        html.append("margin:0;background:#0b0d12;color:#e4e7eb;min-height:100vh;");
        html.append("display:flex;flex-direction:column;align-items:center;justify-content:center;padding:24px}\n");
        html.append(".card{max-width:640px;width:100%;background:#13161d;border-radius:12px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.4)}\n");
        html.append(".thumb{width:100%;aspect-ratio:")
                .append(genericCard ? "1200/630" : profileImage ? "1/1" : "16/9")
                .append(";object-fit:cover;background:#000;display:block}\n");
        html.append(".body{padding:20px}\n");
        html.append("h1{margin:0 0 8px;font-size:20px;line-height:1.3}\n");
        html.append(".subtitle{color:#9ca3af;font-size:14px;margin:0 0 16px}\n");
        html.append(".desc{color:#cbd5e1;font-size:14px;line-height:1.5;margin:0 0 20px;white-space:pre-line}\n");
        html.append(".cta{display:inline-block;padding:12px 24px;background:#2563eb;color:#fff;");
        html.append("text-decoration:none;border-radius:8px;font-weight:600;font-size:15px}\n");
        html.append("</style>\n");
        html.append("</head><body>\n");
        html.append("<div class=\"card\">\n");
        if (!thumbnail.isEmpty()) {
            html.append("<img class=\"thumb\" src=\"").append(thumbnail).append("\" alt=\"").append(title).append("\">\n");
        }
        html.append("<div class=\"body\">\n");
        html.append("<h1>").append(title).append("</h1>\n");
        if (!subtitle.isEmpty()) {
            html.append("<p class=\"subtitle\">").append(subtitle).append("</p>\n");
        }
        if (!description.isEmpty()) {
            html.append("<p class=\"desc\">").append(description).append("</p>\n");
        }
        html.append("<a id=\"openAppLink\" class=\"cta\" data-href=\"")
                .append(escapeAttr(deepLink))
                .append("\" href=\"")
                .append(escapeAttr(deepLink))
                .append("\">")
                .append(escapeHtml(ctaText))
                .append("</a>\n");
        html.append("</div>\n</div>\n");
        // Mobile hop: read the deep link from the anchor's data attribute at runtime —
        // never interpolate the value directly into the script body (that is how a
        // crafted </script> payload would break out of the JS string literal). HTML
        // attributes are the safe context; escapeAttr already guards them.
        html.append("<script>(function(){\n");
        html.append("var ua=navigator.userAgent||'';\n");
        html.append("var isMobile=/Android|iPhone|iPad|iPod/i.test(ua);\n");
        html.append("if(!isMobile)return;\n");
        html.append("var el=document.getElementById('openAppLink');\n");
        html.append("if(!el)return;\n");
        html.append("var href=el.getAttribute('data-href');\n");
        html.append("if(href)setTimeout(function(){window.location.href=href;},50);\n");
        html.append("})();</script>\n");
        html.append("</body></html>");
        return html.toString();
    }

    private String buildUnavailableHtml(
            String id,
            String canonicalUrl,
            String deepLink,
            String title,
            String description,
            String heading
    ) {
        String safeHeading = firstNonBlank(heading, "Content not found");
        String safeDeepLink = firstNonBlank(deepLink, "albunyaantube://video/" + id);
        return "<!DOCTYPE html><html lang=\"en\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escapeHtml(title) + "</title>"
                + "<meta property=\"og:title\" content=\"" + escapeHtml(title) + "\">"
                + "<meta property=\"og:description\" content=\"" + escapeHtml(description) + "\">"
                + "<meta property=\"og:url\" content=\"" + escapeAttr(canonicalUrl) + "\">"
                + "<style>body{font-family:sans-serif;padding:40px;background:#0b0d12;color:#e4e7eb;text-align:center}"
                + "a{color:#3b82f6}</style>"
                + "</head><body><h1>" + escapeHtml(safeHeading) + "</h1>"
                + "<p>" + escapeHtml(description) + "</p>"
                + "<p><a href=\"" + escapeAttr(safeDeepLink) + "\">Open FitrahTube</a></p>"
                + "</body></html>";
    }

    private static String resolveThumbnailUrl(Video video) {
        String storedThumbnail = nullSafe(video.getThumbnailUrl(), "").trim();
        String youtubeId = nullSafe(video.getYoutubeId(), "").trim();

        return resolvePreviewImage(storedThumbnail, youtubeId);
    }

    private static String resolvePreviewImage(String rawImageUrl, String youtubeId) {
        String storedThumbnail = nullSafe(rawImageUrl, "").trim();
        String fallbackYoutubeId = nullSafe(youtubeId, "").trim();

        Matcher thumbnailMatcher = YOUTUBE_THUMBNAIL_PATTERN.matcher(storedThumbnail);
        if (thumbnailMatcher.matches()) {
            if (YOUTUBE_ID_PATTERN.matcher(fallbackYoutubeId).matches()) {
                return stableYoutubeThumbnail(fallbackYoutubeId);
            }
            return stableYoutubeThumbnail(thumbnailMatcher.group(1));
        }

        if (HTTPS_URL_PATTERN.matcher(storedThumbnail).matches()) {
            return storedThumbnail;
        }

        if (YOUTUBE_ID_PATTERN.matcher(fallbackYoutubeId).matches()) {
            return stableYoutubeThumbnail(fallbackYoutubeId);
        }

        return "";
    }

    private static String resolveChannelImage(String rawImageUrl) {
        String imageUrl = nullSafe(rawImageUrl, "").trim();
        if (!HTTPS_URL_PATTERN.matcher(imageUrl).matches()) {
            return "";
        }
        if (imageUrl.contains("yt3.googleusercontent.com") || imageUrl.contains("yt3.ggpht.com")) {
            return YOUTUBE_AVATAR_SIZE_PATTERN.matcher(imageUrl).replaceFirst("=s512");
        }
        return imageUrl;
    }

    private static boolean isStableYoutubeJpeg(String thumbnailUrl) {
        return thumbnailUrl != null
                && thumbnailUrl.startsWith("https://i.ytimg.com/vi/")
                && thumbnailUrl.endsWith("/hqdefault.jpg");
    }

    private static String stableYoutubeThumbnail(String youtubeId) {
        return "https://i.ytimg.com/vi/" + youtubeId + "/hqdefault.jpg";
    }

    /** The configured origin, the matched route ("/api" alias kept) and the validated id: never request headers. */
    private String canonicalUrl(HttpServletRequest request, String route, String id) {
        return publicBaseUrl + (request.getRequestURI().startsWith("/api/") ? "/api/" : "/") + route + "/" + id;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String escapeAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : s.substring(0, maxChars - 1) + "…";
    }

    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static String nullSafe(String s, String fallback) {
        return (s == null || s.isEmpty()) ? fallback : s;
    }
}
