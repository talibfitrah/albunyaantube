package com.albunyaan.tube.config;

import com.albunyaan.tube.search.CrawlPacer;
import com.albunyaan.tube.search.InnerState;
import com.albunyaan.tube.search.InnerStore;
import com.albunyaan.tube.service.YouTubeThrottler;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Inner-search R11/R33: NewPipe's one global downloader passes every request through CrawlPacer, before and after. */
class NewPipeDownloaderPacerTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private String url;
    private CrawlPacer pacer;
    private Downloader downloader;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            hits.incrementAndGet();
            byte[] body = "{\"ok\":true,\"padding\":\"..................................\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/ok";
        pacer = mock(CrawlPacer.class);
        NewPipeConfiguration config = new NewPipeConfiguration();
        ReflectionTestUtils.setField(config, "userAgent", "inner-search-test");
        downloader = config.newPipeDownloader(new OkHttpClient(), pacer);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void everyRequestPassesThePacerBeforeAndAfter() throws Exception {
        Response r = downloader.get(url);
        assertEquals(200, r.responseCode());
        InOrder order = inOrder(pacer);
        order.verify(pacer).before(url);
        order.verify(pacer).after(eq(url), eq(200), eq(url), startsWith("application/json"), eq(r.responseBody()));
    }

    @Test
    void aRefusalInBeforeStopsTheRequestBeforeItIsSent() throws Exception {
        doThrow(new CrawlPacer.CrawlPausedException("STOP file")).when(pacer).before(anyString());
        assertThrows(CrawlPacer.CrawlPausedException.class, () -> downloader.get(url));
        assertEquals(0, hits.get());
    }

    @Test
    void aSignalFromAfterReachesTheCaller() throws Exception {
        doThrow(new CrawlPacer.CrawlPausedException("HTTP_429")).when(pacer).after(anyString(), anyInt(), anyString(), any(), anyString());
        assertThrows(CrawlPacer.CrawlPausedException.class, () -> downloader.get(url));
        assertEquals(1, hits.get());
    }

    @Test
    void a429ReachesThePacerBeforeItBecomesAReCaptchaException() throws Exception {
        server.createContext("/limited", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(429, -1);
            ex.close();
        });
        String limited = url.replace("/ok", "/limited");
        assertThrows(org.schabi.newpipe.extractor.exceptions.ReCaptchaException.class, () -> downloader.get(limited));
        verify(pacer).after(eq(limited), eq(429), eq(limited), any(), eq(""));
    }

    /** Prod's default (search.inner.dir unset): the real pacer neither throttles nor counts another thread's request. */
    @Test
    void featureOffTheRealPacerLeavesAnotherThreadExactlyAsBefore() throws Exception {
        String limited = limited();
        InnerStore off = new InnerStore("");
        YouTubeThrottler throttler = mock(YouTubeThrottler.class);
        Downloader real = realDownloader(new CrawlPacer(off, throttler, Clock.systemUTC()));

        assertNull(thrownOn("http-nio-8080-exec-1", () -> {
            assertEquals(200, real.get(url).responseCode());
            return null;
        }));
        assertInstanceOf(ReCaptchaException.class, thrownOn("http-nio-8080-exec-1", () -> real.get(limited)));
        verifyNoInteractions(throttler);
        assertTrue(off.state().requestsByDay.isEmpty());
    }

    /** The real pacer, feature on: other threads keep hotfix #2's 429 -> ReCaptchaException, unpaced and only counted. */
    @Test
    void withTheRealPacerOnlyTheCrawlerThreadsOwn429BecomesASignal(@TempDir Path tmp) throws Exception {
        String limited = limited();
        InnerStore store = new InnerStore(tmp.toString());
        store.state().mode = InnerState.Mode.FULL;
        YouTubeThrottler throttler = mock(YouTubeThrottler.class);
        Downloader real = realDownloader(new CrawlPacer(store, throttler, Clock.systemUTC()));

        assertNull(thrownOn("http-nio-8080-exec-1", () -> {
            assertEquals(200, real.get(url).responseCode());
            return null;
        }));
        assertInstanceOf(ReCaptchaException.class, thrownOn("http-nio-8080-exec-1", () -> real.get(limited)));
        verifyNoInteractions(throttler);
        assertTrue(store.state().signals.isEmpty());

        assertInstanceOf(CrawlPacer.CrawlPausedException.class, thrownOn(CrawlPacer.CRAWLER_THREAD, () -> real.get(limited)));
        verify(throttler).throttle();
        assertEquals("HTTP_429", store.state().signals.get(0).type());
        assertEquals(Map.of("http-nio-8080-exec-", 2L, CrawlPacer.CRAWLER_THREAD, 1L), store.state().requestsByDay.lastEntry().getValue());
    }

    private String limited() {
        server.createContext("/limited", ex -> {
            ex.sendResponseHeaders(429, -1);
            ex.close();
        });
        return url.replace("/ok", "/limited");
    }

    private static Downloader realDownloader(CrawlPacer pacer) {
        NewPipeConfiguration config = new NewPipeConfiguration();
        ReflectionTestUtils.setField(config, "userAgent", "inner-search-test");
        return config.newPipeDownloader(new OkHttpClient(), pacer);
    }

    private static Throwable thrownOn(String thread, Callable<?> body) throws InterruptedException {
        Throwable[] thrown = {null};
        Thread t = new Thread(() -> {
            try {
                body.call();
            } catch (Throwable e) {
                thrown[0] = e;
            }
        }, thread);
        t.start();
        t.join(10_000);
        assertFalse(t.isAlive(), thread + " still running after 10 s");
        return thrown[0];
    }
}
