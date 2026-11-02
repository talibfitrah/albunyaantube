package com.albunyaan.tube.config;

import com.albunyaan.tube.search.CrawlPacer;
import com.albunyaan.tube.service.YouTubeCircuitBreaker;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** The shared prod downloader, served by an OkHttp interceptor (no network). */
class NewPipeConfigurationTest {

    private static final String BROWSE = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false";

    static Downloader prodDownloaderAnswering(int code) {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> new okhttp3.Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_2).code(code).message("")
                .body(ResponseBody.create("<!DOCTYPE html><html><body>status " + code + "</body></html>",
                        MediaType.get("text/html; charset=utf-8")))
                .build()).build();
        NewPipeConfiguration config = new NewPipeConfiguration();
        ReflectionTestUtils.setField(config, "userAgent", "test-agent");
        return config.newPipeDownloader(http, mock(CrawlPacer.class));
    }

    /** R7 root cause: a 429 is a rate limit for every NewPipe caller, and the shared breaker counts it as one. */
    @Test
    void a429IsAReCaptchaExceptionThatTheBreakerCountsAsARateLimit() {
        ReCaptchaException e = assertThrows(ReCaptchaException.class, () -> prodDownloaderAnswering(429).get(BROWSE));

        assertTrue(new YouTubeCircuitBreaker(new ValidationProperties(), null).isRateLimitError(e));
    }

    @Test
    void everyOtherStatusStillReachesNewPipeAsAResponse() throws Exception {
        for (int code : new int[] {200, 403, 404, 500, 503}) {
            assertEquals(code, prodDownloaderAnswering(code).get(BROWSE).responseCode());
        }
    }
}
