package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.service.PublicContentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final int TITLE_MAX_CHARS = 160;
    private static final int SHARE_IMAGE_MAX_CHARS = 500;
    private static final long SHARE_METADATA_TTL_MILLIS = 10 * 60 * 1000L;
    private static final long SHARE_METADATA_RATE_LIMIT_TTL_MILLIS = 60_000L;
    private static final int SHARE_METADATA_RATE_LIMIT_PER_MINUTE = 30;
    private static final String PUBLIC_SHARE_HOST = "app.fitrahtube.com";
    private static final String HEADER_DEVICE_ID = "X-Device-Id";

    /**
     * Closed allow-list of hosts permitted to appear as {@code og:image} when the
     * Android client seeds the share-metadata cache. Anything outside this set is
     * dropped (silently coerced to empty) — the previous behaviour of accepting
     * any HTTPS URL turned the POST /api/share-metadata endpoint into a
     * phishing-grade Open Graph spoofing primitive: an unauthenticated caller
     * could write an arbitrary {@code og:image} for any well-formed video,
     * channel or playlist ID and have it served to link unfurlers (WhatsApp,
     * Telegram, Slack, Skype, etc.) for ten minutes per cache entry.
     */
    private static final Set<String> APPROVED_IMAGE_HOSTS = Set.of(
            "i.ytimg.com",
            "img.youtube.com",
            "yt3.googleusercontent.com",
            "yt3.ggpht.com"
    );

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
    private static final Pattern SAFE_HOST_PATTERN = Pattern.compile("^[A-Za-z0-9.-]+(?::[0-9]{1,5})?$");

    private final PublicContentService contentService;
    private final Cache<String, CachedShareMetadata> shareMetadataCache = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(SHARE_METADATA_TTL_MILLIS, TimeUnit.MILLISECONDS)
            .build();
    /**
     * Per-X-Device-Id rate-limit bucket for POST /api/share-metadata. Each entry
     * holds the request count within the trailing rate-limit window; entries
     * expire automatically so the table can never grow unbounded. The bucket
     * is per-device rather than per-IP so a misbehaving NAT/proxy doesn't lock
     * legitimate clients out, and per-device requires a header that the Android
     * app always sends — anonymous browser callers (who never sign-share-flow)
     * can be turned away by header presence alone.
     */
    private final Cache<String, AtomicInteger> shareMetadataRateLimit = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(SHARE_METADATA_RATE_LIMIT_TTL_MILLIS, TimeUnit.MILLISECONDS)
            .build();

    public WatchPageController(PublicContentService contentService) {
        this.contentService = contentService;
    }

    @PostMapping(value = "/api/share-metadata/{type}/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Void> shareMetadata(
            @PathVariable String type,
            @PathVariable String id,
            @RequestBody(required = false) ShareMetadataRequest metadata,
            HttpServletRequest request
    ) {
        if (!isSupportedShareType(type) || id == null || !VIDEO_ID_PATTERN.matcher(id).matches()) {
            return ResponseEntity.badRequest().build();
        }
        if (metadata == null) {
            return ResponseEntity.badRequest().build();
        }

        // Require X-Device-Id so the rate-limit bucket can't be bypassed by stripping
        // a single header. Falling back to remote address (the previous behaviour for
        // /api/v1/reports) would let a misconfigured proxy collapse every anonymous
        // caller into one bucket — either DoSing real clients out, or letting an
        // attacker churn random IDs to bypass the limit. The Android share publisher
        // always sets the header in NetworkModule.
        String deviceId = request.getHeader(HEADER_DEVICE_ID);
        if (deviceId == null || deviceId.isBlank()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).build();
        }
        AtomicInteger counter = shareMetadataRateLimit.get(deviceId, k -> new AtomicInteger(0));
        if (counter.incrementAndGet() > SHARE_METADATA_RATE_LIMIT_PER_MINUTE) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).build();
        }

        // Image URL: only YouTube hosts on the closed allow-list survive. Anything
        // else is silently dropped (coerced to empty) so the title/description can
        // still seed the cache without giving an attacker control of og:image. The
        // 500-char cap also bounds the cache footprint (5,000 entries × 500 chars).
        String image = validateShareImage(metadata.image());
        String title = truncate(firstNonBlank(metadata.title(), ""), TITLE_MAX_CHARS);
        String description = truncate(firstNonBlank(metadata.description(), ""), DESCRIPTION_MAX_CHARS);
        if (title.isEmpty() && description.isEmpty() && image.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        shareMetadataCache.put(cacheKey(type, id), new CachedShareMetadata(title, description, image));
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns {@code rawImageUrl} only if it is an https URL within the configured
     * size limit and its host is in {@link #APPROVED_IMAGE_HOSTS}; otherwise
     * returns the empty string. The Android client may legitimately pass a YouTube
     * thumbnail URL when seeding the share cache; anything else is treated as
     * untrusted input and dropped without an error so the title/description path
     * still functions.
     */
    private static String validateShareImage(String rawImageUrl) {
        String url = nullSafe(rawImageUrl, "").trim();
        if (url.isEmpty()) return "";
        if (url.length() > SHARE_IMAGE_MAX_CHARS) return "";
        if (!HTTPS_URL_PATTERN.matcher(url).matches()) return "";
        try {
            java.net.URI parsed = java.net.URI.create(url);
            String host = parsed.getHost();
            if (host == null) return "";
            // Strip any user-info smuggling (e.g. "evil.com@i.ytimg.com" would
            // resolve to host=i.ytimg.com on some parsers; URI.getHost is the
            // authority host so this is already correct, but be explicit).
            String normalised = host.toLowerCase(Locale.ROOT);
            if (!APPROVED_IMAGE_HOSTS.contains(normalised)) return "";
            return url;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    @GetMapping(value = {"/watch/{videoId}", "/api/watch/{videoId}"}, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> watch(
            @PathVariable String videoId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String image,
            @RequestParam(required = false) String description,
            HttpServletRequest request
    ) {
        // Reject malformed IDs outright. Defense-in-depth: even though the response
        // escapes the value, keeping obvious attack payloads out of the rendering path
        // eliminates a whole class of bugs (XSS via </script> injection, log spoofing,
        // etc.) before any handler runs.
        if (videoId == null || !VIDEO_ID_PATTERN.matcher(videoId).matches()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(buildUnavailableHtml("", "", "", "FitrahTube", "This video is not available.", ""));
        }
        String canonicalUrl = buildCanonicalUrl(request);
        ShareMetadata metadata = getShareMetadata("watch", videoId);
        try {
            Video video = contentService.getVideoDetails(videoId);
            if (video == null) {
                return videoFallback(videoId, canonicalUrl, title, image, description, metadata);
            }
            String html = buildHtml(video, canonicalUrl);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (Exception e) {
            log.warn("Watch page fallback for videoId={}: {}", videoId, e.getMessage());
            return videoFallback(videoId, canonicalUrl, title, image, description, metadata);
        }
    }

    @GetMapping(value = {"/channel/{channelId}", "/api/channel/{channelId}"}, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> channel(
            @PathVariable String channelId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String image,
            @RequestParam(required = false) String description,
            HttpServletRequest request
    ) {
        if (channelId == null || !VIDEO_ID_PATTERN.matcher(channelId).matches()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(buildUnavailableHtml("", "", "", "FitrahTube", "This channel is not available.", ""));
        }
        String canonicalUrl = buildCanonicalUrl(request);
        ShareMetadata metadata = getShareMetadata("channel", channelId);
        try {
            Object details = contentService.getChannelDetails(channelId);
            if (details instanceof Channel channel) {
                String imageUrl = firstNonBlank(
                        resolveChannelImage(channel.getThumbnailUrl()),
                        metadata.image()
                );
                String html = buildShareHtml(
                        firstNonBlank(channel.getName(), metadata.title(), "FitrahTube Channel"),
                        "FitrahTube channel",
                        truncate(firstNonBlank(channel.getDescription(), metadata.description(), ""), DESCRIPTION_MAX_CHARS),
                        imageUrl,
                        canonicalUrl,
                        "albunyaantube://channel/" + channelId,
                        "Open channel in FitrahTube",
                        "profile"
                );
                return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
            }
        } catch (Exception e) {
            log.warn("Channel share page fallback for channelId={}: {}", channelId, e.getMessage());
        }
        return metadataFallback(
                channelId,
                canonicalUrl,
                "albunyaantube://channel/" + channelId,
                title,
                description,
                image,
                "Open channel in FitrahTube",
                "profile",
                "channel",
                canonicalUrl,
                metadata
        );
    }

    @GetMapping(value = {"/playlist/{playlistId}", "/api/playlist/{playlistId}"}, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> playlist(
            @PathVariable String playlistId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String image,
            @RequestParam(required = false) String description,
            HttpServletRequest request
    ) {
        if (playlistId == null || !VIDEO_ID_PATTERN.matcher(playlistId).matches()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(buildUnavailableHtml("", "", "", "FitrahTube", "This playlist is not available.", ""));
        }
        String canonicalUrl = buildCanonicalUrl(request);
        ShareMetadata metadata = getShareMetadata("playlist", playlistId);
        try {
            Object details = contentService.getPlaylistDetails(playlistId);
            if (details instanceof Playlist playlist) {
                String imageUrl = firstNonBlank(
                        resolvePreviewImage(playlist.getThumbnailUrl(), ""),
                        metadata.image()
                );
                String itemCount = playlist.getItemCount() == null
                        ? "FitrahTube playlist"
                        : playlist.getItemCount() + " videos";
                String html = buildShareHtml(
                        firstNonBlank(playlist.getTitle(), metadata.title(), "FitrahTube Playlist"),
                        itemCount,
                        truncate(firstNonBlank(playlist.getDescription(), metadata.description(), ""), DESCRIPTION_MAX_CHARS),
                        imageUrl,
                        canonicalUrl,
                        "albunyaantube://playlist/" + playlistId,
                        "Open playlist in FitrahTube",
                        "website"
                );
                return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
            }
        } catch (Exception e) {
            log.warn("Playlist share page fallback for playlistId={}: {}", playlistId, e.getMessage());
        }
        return metadataFallback(
                playlistId,
                canonicalUrl,
                "albunyaantube://playlist/" + playlistId,
                title,
                description,
                image,
                "Open playlist in FitrahTube",
                "website",
                "playlist",
                canonicalUrl,
                metadata
        );
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

    private ResponseEntity<String> videoFallback(
            String videoId,
            String canonicalUrl,
            String title,
            String image,
            String description,
            ShareMetadata metadata
    ) {
        if (YOUTUBE_ID_PATTERN.matcher(videoId).matches()) {
            String html = buildShareHtml(
                    firstNonBlank(title, metadata.title(), "FitrahTube Video"),
                    "",
                    firstNonBlank(description, metadata.description(), "Watch this video in FitrahTube."),
                    firstNonBlank(resolvePreviewImage(image, videoId), metadata.image()),
                    canonicalUrl,
                    "albunyaantube://video/" + videoId,
                    "Open in FitrahTube",
                    "video.other"
            );
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        }
        String html = buildUnavailableHtml(
                videoId,
                canonicalUrl,
                "albunyaantube://video/" + videoId,
                "FitrahTube",
                "This video is not available.",
                "Video not found"
        );
        return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(html);
    }

    private ResponseEntity<String> metadataFallback(
            String id,
            String canonicalUrl,
            String deepLink,
            String title,
            String description,
            String image,
            String ctaText,
            String ogType,
            String itemType,
            String fallbackUrl,
            ShareMetadata metadata
    ) {
        String previewImage = "profile".equals(ogType)
                ? resolveChannelImage(firstNonBlank(image, metadata.image()))
                : resolvePreviewImage(firstNonBlank(image, metadata.image()), "");
        if (!previewImage.isEmpty()) {
            String html = buildShareHtml(
                    firstNonBlank(title, metadata.title(), "FitrahTube"),
                    "",
                    truncate(firstNonBlank(description, metadata.description(), ""), DESCRIPTION_MAX_CHARS),
                    previewImage,
                    firstNonBlank(fallbackUrl, canonicalUrl),
                    deepLink,
                    ctaText,
                    ogType
            );
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        }
        String html = buildUnavailableHtml(id, canonicalUrl, deepLink, title, "This " + itemType + " is not available.", title);
        return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(html);
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
        int imageWidth = profileImage ? 512 : 480;
        int imageHeight = profileImage ? 512 : 360;

        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n<html lang=\"en\"><head>\n");
        html.append("<meta charset=\"utf-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n");
        html.append("<meta name=\"robots\" content=\"index,follow,max-image-preview:large\">\n");
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
                .append(profileImage ? "1/1" : "16/9")
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

    private static String buildCanonicalUrl(HttpServletRequest request) {
        return buildCanonicalUrl(request, false);
    }

    private static String buildCanonicalUrl(HttpServletRequest request, boolean includeQueryString) {
        String host = firstHeaderValue(request, "X-Forwarded-Host");
        if (host.isEmpty()) {
            host = firstHeaderValue(request, "Host");
        }
        if (host.isEmpty()) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (port > 0 && port != 80 && port != 443) {
                host += ":" + port;
            }
        }
        host = sanitizeHost(host, request.getServerName());

        String scheme = firstHeaderValue(request, "X-Forwarded-Proto");
        String cfScheme = extractCloudflareScheme(request.getHeader("CF-Visitor"));
        if ("https".equals(cfScheme)) {
            scheme = "https";
        }
        if ("https".equalsIgnoreCase(scheme)) {
            scheme = "https";
        } else if ("http".equalsIgnoreCase(scheme)) {
            scheme = "http";
        } else {
            scheme = request.isSecure() ? "https" : nullSafe(request.getScheme(), "http");
        }
        if ("http".equals(scheme) && hostWithoutPort(host).equalsIgnoreCase(PUBLIC_SHARE_HOST)) {
            scheme = "https";
        }

        String url = scheme + "://" + host + request.getRequestURI();
        String query = request.getQueryString();
        if (includeQueryString && query != null && !query.isBlank()) {
            url += "?" + query;
        }
        return url;
    }

    private static String firstHeaderValue(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            return "";
        }
        int comma = value.indexOf(',');
        return (comma >= 0 ? value.substring(0, comma) : value).trim();
    }

    private static String sanitizeHost(String host, String fallback) {
        String value = host == null ? "" : host.trim();
        if (SAFE_HOST_PATTERN.matcher(value).matches()) {
            return value;
        }
        return (fallback == null || fallback.isBlank()) ? PUBLIC_SHARE_HOST : fallback;
    }

    private static String hostWithoutPort(String host) {
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    private static String extractCloudflareScheme(String cfVisitor) {
        if (cfVisitor == null) {
            return "";
        }
        String compact = cfVisitor.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (compact.contains("\"scheme\":\"https\"")) {
            return "https";
        }
        if (compact.contains("\"scheme\":\"http\"")) {
            return "http";
        }
        return "";
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

    private static boolean isSupportedShareType(String type) {
        return "watch".equals(type) || "channel".equals(type) || "playlist".equals(type);
    }

    private ShareMetadata getShareMetadata(String type, String id) {
        CachedShareMetadata cached = shareMetadataCache.getIfPresent(cacheKey(type, id));
        if (cached == null) return ShareMetadata.EMPTY;
        return new ShareMetadata(cached.title(), cached.description(), cached.image());
    }

    private static String cacheKey(String type, String id) {
        return type + ":" + id;
    }

    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String nullSafe(String s, String fallback) {
        return (s == null || s.isEmpty()) ? fallback : s;
    }

    private record ShareMetadataRequest(String title, String description, String image) {
    }

    private record CachedShareMetadata(String title, String description, String image) {
    }

    private record ShareMetadata(String title, String description, String image) {
        private static final ShareMetadata EMPTY = new ShareMetadata("", "", "");
    }
}
