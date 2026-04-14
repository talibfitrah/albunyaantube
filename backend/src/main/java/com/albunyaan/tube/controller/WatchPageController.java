package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.service.PublicContentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * ANDROID-MULTI-01 Issue 4: Public "watch" landing pages that link unfurlers
 * (WhatsApp, Telegram, Slack, Skype) can crawl to render rich previews with
 * thumbnail + title + description.
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

    /**
     * Allowed shape for the {@code videoId} path variable. Firestore document IDs and
     * YouTube IDs both fit inside this charset; anything outside it is guaranteed to be
     * a malformed or attacker-crafted value and is rejected before any rendering occurs.
     */
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final PublicContentService contentService;

    public WatchPageController(PublicContentService contentService) {
        this.contentService = contentService;
    }

    @GetMapping(value = "/watch/{videoId}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> watch(
            @PathVariable String videoId,
            HttpServletRequest request
    ) {
        // Reject malformed IDs outright. Defense-in-depth: even though the response
        // escapes the value, keeping obvious attack payloads out of the rendering path
        // eliminates a whole class of bugs (XSS via </script> injection, log spoofing,
        // etc.) before any handler runs.
        if (videoId == null || !VIDEO_ID_PATTERN.matcher(videoId).matches()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .body(buildFallbackHtml("", ""));
        }
        String canonicalUrl = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .build()
                .toUriString();
        try {
            Video video = contentService.getVideoDetails(videoId);
            if (video == null) {
                return notFound(videoId, canonicalUrl);
            }
            String html = buildHtml(video, canonicalUrl);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (Exception e) {
            log.warn("Watch page fallback for videoId={}: {}", videoId, e.getMessage());
            return notFound(videoId, canonicalUrl);
        }
    }

    private ResponseEntity<String> notFound(String videoId, String canonicalUrl) {
        String html = buildFallbackHtml(videoId, canonicalUrl);
        return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(html);
    }

    private String buildHtml(Video video, String canonicalUrl) {
        String title = escapeHtml(nullSafe(video.getTitle(), "FitrahTube"));
        String channel = nullSafe(video.getChannelTitle(), "");
        String description = escapeHtml(truncate(nullSafe(video.getDescription(), ""), DESCRIPTION_MAX_CHARS));
        String thumbnail = escapeAttr(nullSafe(video.getThumbnailUrl(), ""));
        String youtubeId = nullSafe(video.getYoutubeId(), video.getId());
        String deepLink = "albunyaantube://video/" + youtubeId;

        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n<html lang=\"en\"><head>\n");
        html.append("<meta charset=\"utf-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n");
        html.append("<title>").append(title).append(" — FitrahTube</title>\n");
        html.append("<meta name=\"description\" content=\"").append(description).append("\">\n");
        // OpenGraph
        html.append("<meta property=\"og:type\" content=\"video.other\">\n");
        html.append("<meta property=\"og:title\" content=\"").append(title).append("\">\n");
        html.append("<meta property=\"og:description\" content=\"").append(description).append("\">\n");
        html.append("<meta property=\"og:url\" content=\"").append(escapeAttr(canonicalUrl)).append("\">\n");
        html.append("<meta property=\"og:site_name\" content=\"FitrahTube\">\n");
        if (!thumbnail.isEmpty()) {
            html.append("<meta property=\"og:image\" content=\"").append(thumbnail).append("\">\n");
            html.append("<meta property=\"og:image:alt\" content=\"").append(title).append("\">\n");
        }
        // Twitter cards — used by Slack, Skype and a few others in addition to OG
        html.append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n");
        html.append("<meta name=\"twitter:title\" content=\"").append(title).append("\">\n");
        html.append("<meta name=\"twitter:description\" content=\"").append(description).append("\">\n");
        if (!thumbnail.isEmpty()) {
            html.append("<meta name=\"twitter:image\" content=\"").append(thumbnail).append("\">\n");
        }
        html.append("<style>\n");
        html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;");
        html.append("margin:0;background:#0b0d12;color:#e4e7eb;min-height:100vh;");
        html.append("display:flex;flex-direction:column;align-items:center;justify-content:center;padding:24px}\n");
        html.append(".card{max-width:640px;width:100%;background:#13161d;border-radius:12px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.4)}\n");
        html.append(".thumb{width:100%;aspect-ratio:16/9;object-fit:cover;background:#000;display:block}\n");
        html.append(".body{padding:20px}\n");
        html.append("h1{margin:0 0 8px;font-size:20px;line-height:1.3}\n");
        html.append(".channel{color:#9ca3af;font-size:14px;margin:0 0 16px}\n");
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
        if (!channel.isEmpty()) {
            html.append("<p class=\"channel\">").append(escapeHtml(channel)).append("</p>\n");
        }
        if (!description.isEmpty()) {
            html.append("<p class=\"desc\">").append(description).append("</p>\n");
        }
        html.append("<a id=\"openAppLink\" class=\"cta\" data-href=\"")
                .append(escapeAttr(deepLink))
                .append("\" href=\"")
                .append(escapeAttr(deepLink))
                .append("\">Open in FitrahTube</a>\n");
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

    private String buildFallbackHtml(String videoId, String canonicalUrl) {
        String deepLink = "albunyaantube://video/" + escapeAttr(videoId);
        return "<!DOCTYPE html><html lang=\"en\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>FitrahTube</title>"
                + "<meta property=\"og:title\" content=\"FitrahTube\">"
                + "<meta property=\"og:description\" content=\"This video is not available.\">"
                + "<meta property=\"og:url\" content=\"" + escapeAttr(canonicalUrl) + "\">"
                + "<style>body{font-family:sans-serif;padding:40px;background:#0b0d12;color:#e4e7eb;text-align:center}"
                + "a{color:#3b82f6}</style>"
                + "</head><body><h1>Video not found</h1>"
                + "<p>This video is not available on FitrahTube.</p>"
                + "<p><a href=\"" + deepLink + "\">Open FitrahTube</a></p>"
                + "</body></html>";
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

    private static String nullSafe(String s, String fallback) {
        return (s == null || s.isEmpty()) ? fallback : s;
    }
}
