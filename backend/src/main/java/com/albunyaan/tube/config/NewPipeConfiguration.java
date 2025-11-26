package com.albunyaan.tube.config;

import okhttp3.OkHttpClient;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;

/**
 * NewPipeExtractor Configuration
 * <p>
 * Initializes NewPipeExtractor for YouTube content extraction without requiring API keys.
 * This replaces the YouTube Data API v3 implementation.
 * <p>
 * NewPipeExtractor scrapes YouTube content directly, providing:
 * - Channel/playlist/video metadata
 * - Search functionality
 * - Stream URL resolution
 * - No quota limits (no API key required)
 * <p>
 * Configuration is loaded from application.yml under app.newpipe.*
 */
@Configuration
public class NewPipeConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(NewPipeConfiguration.class);

    @Value("${app.newpipe.http.connect-timeout-seconds:30}")
    private int connectTimeoutSeconds;

    @Value("${app.newpipe.http.read-timeout-seconds:30}")
    private int readTimeoutSeconds;

    @Value("${app.newpipe.http.user-agent:Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0}")
    private String userAgent;

    @Value("${app.newpipe.stream-cache-ttl-minutes:30}")
    private int streamCacheTtlMinutes;

    @Value("${app.newpipe.http.log-body-chars:400}")
    private int logBodyChars;

    @Value("${app.newpipe.http.log-body-enabled:false}")
    private boolean logBodyEnabled;

    private static final Localization DEFAULT_LOCALIZATION = Localization.fromLocale(Locale.US);
    private static final ContentCountry DEFAULT_CONTENT_COUNTRY = new ContentCountry("US");

    /**
     * Creates OkHttp client for NewPipeExtractor HTTP requests
     */
    @Bean
    public OkHttpClient newPipeOkHttpClient() {
        logger.info("Creating OkHttpClient for NewPipeExtractor with timeouts: connect={}s, read={}s",
                connectTimeoutSeconds, readTimeoutSeconds);

        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .readTimeout(Duration.ofSeconds(readTimeoutSeconds))
                .followRedirects(true)
                .build();
    }

    /**
     * Creates NewPipe downloader wrapping OkHttp client
     */
    @Bean
    public Downloader newPipeDownloader(OkHttpClient okHttpClient) {
        logger.info("Creating NewPipe downloader with user-agent: {}", userAgent);
        logger.info("HTTP body logging enabled: {} (max {} chars)", logBodyEnabled, logBodyChars);

        return new Downloader() {
            @Override
            public Response execute(Request request) throws IOException, ReCaptchaException {
                okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                        .url(request.url());

                // Add headers from request
                Map<String, java.util.List<String>> headers = request.headers();
                for (Map.Entry<String, java.util.List<String>> entry : headers.entrySet()) {
                    String headerValue = String.join(",", entry.getValue());
                    builder.header(entry.getKey(), headerValue);
                }

                // Add user-agent if not already present
                if (!headers.containsKey("User-Agent")) {
                    builder.header("User-Agent", userAgent);
                }

                // Add Accept-Language header for localization
                if (!headers.containsKey("Accept-Language")) {
                    if (request.localization() != null) {
                        builder.header("Accept-Language", buildLanguageTag(request.localization()));
                    }
                }

                // Build HTTP method
                switch (request.httpMethod().toUpperCase(Locale.US)) {
                    case "HEAD":
                        builder.head();
                        break;
                    case "GET":
                        builder.get();
                        break;
                    case "POST":
                        byte[] data = request.dataToSend() != null ? request.dataToSend() : new byte[0];
                        String contentType = headers.containsKey("Content-Type")
                                ? headers.get("Content-Type").get(0)
                                : "application/json";
                        builder.post(okhttp3.RequestBody.create(data,
                                okhttp3.MediaType.parse(contentType)));
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported HTTP method: " + request.httpMethod());
                }

                // Execute request
                try {
                    okhttp3.Request okHttpRequest = builder.build();
                    okhttp3.Response response = okHttpClient.newCall(okHttpRequest).execute();

                    try {
                        String responseBody = response.body() != null ? response.body().string() : "";

                        // Debug logging for troubleshooting
                        // Body logging is disabled by default in prod (app.newpipe.http.log-body-enabled)
                        if (response.code() != 200) {
                            if (logBodyEnabled) {
                                // Verbose logging with body preview (dev/debug only)
                                String bodyPreview = truncateBody(responseBody);
                                logger.warn(
                                        "NewPipe HTTP {} {} for {} {} (body chars: {}, retry-after: {}, rate-limit-remain: {}, rate-limit-limit: {}) body-preview: {}",
                                        response.code(),
                                        response.message(),
                                        request.httpMethod(),
                                        request.url(),
                                        responseBody.length(),
                                        headerValue(response, "Retry-After"),
                                        headerValue(response, "X-RateLimit-Remaining"),
                                        headerValue(response, "X-RateLimit-Limit"),
                                        bodyPreview
                                );

                                // Log request payloads for resolve_url calls (common for channel validation)
                                if (request.url().contains("resolve_url")) {
                                    byte[] reqBody = request.dataToSend();
                                    String reqBodyStr = reqBody != null ? new String(reqBody) : "(no body)";
                                    logger.warn("resolve_url request payload (truncated): {}",
                                            truncateBody(reqBodyStr));
                                }
                            } else {
                                // Safe logging without body content (production default)
                                logger.warn(
                                        "NewPipe HTTP {} {} for {} {} (body chars: {}, retry-after: {}, rate-limit-remain: {}, rate-limit-limit: {})",
                                        response.code(),
                                        response.message(),
                                        request.httpMethod(),
                                        request.url(),
                                        responseBody.length(),
                                        headerValue(response, "Retry-After"),
                                        headerValue(response, "X-RateLimit-Remaining"),
                                        headerValue(response, "X-RateLimit-Limit")
                                );
                            }
                        }

                        return new Response(
                                response.code(),
                                response.message(),
                                response.headers().toMultimap(),
                                responseBody,
                                response.request().url().toString()
                        );
                    } finally {
                        response.close();
                    }
                } catch (IOException ioe) {
                    logger.warn("NewPipe HTTP request failed for {} {}: {}", request.httpMethod(), request.url(), ioe.getMessage());
                    throw ioe;
                }
            }

            private String buildLanguageTag(Localization localization) {
                String language = localization.getLanguageCode();
                String country = localization.getCountryCode();
                return country == null || country.isBlank() ? language : language + "-" + country;
            }

            private String truncateBody(String body) {
                if (body == null) {
                    return "(null)";
                }
                // Cap at 10KB to prevent excessive logging and disk space exhaustion
                int limit = Math.min(Math.max(0, logBodyChars), 10000);
                if (limit == 0 || body.length() <= limit) {
                    return body;
                }
                int remaining = body.length() - limit;
                return body.substring(0, limit) + "... (" + remaining + " more chars)";
            }

            private String headerValue(okhttp3.Response response, String key) {
                return Objects.toString(response.header(key), "n/a");
            }
        };
    }

    /**
     * Initializes NewPipeExtractor on application startup
     * This bean depends on newPipeDownloader, so Spring will create it first
     */
    @Bean
    public String newPipeInitializer(Downloader newPipeDownloader) {
        try {
            logger.info("Initializing NewPipeExtractor...");

            // Initialize NewPipe with downloader and localization
            NewPipe.init(newPipeDownloader, DEFAULT_LOCALIZATION, DEFAULT_CONTENT_COUNTRY);

            // Get YouTube service
            StreamingService youtubeService = ServiceList.YouTube;
            logger.info("NewPipeExtractor initialized successfully");
            logger.info("YouTube service: {}, version: {}",
                    youtubeService.getServiceInfo().getName(),
                    youtubeService.getServiceId());
            logger.info("Stream cache TTL: {} minutes", streamCacheTtlMinutes);

            return "initialized";
        } catch (Exception e) {
            logger.error("Failed to initialize NewPipeExtractor", e);
            throw new RuntimeException("NewPipeExtractor initialization failed", e);
        }
    }

    /**
     * Provides the YouTube streaming service from NewPipe
     */
    @Bean
    public StreamingService newPipeYouTubeService() {
        return ServiceList.YouTube;
    }

    /**
     * Getter for stream cache TTL configuration
     */
    public int getStreamCacheTtlMinutes() {
        return streamCacheTtlMinutes;
    }
}
