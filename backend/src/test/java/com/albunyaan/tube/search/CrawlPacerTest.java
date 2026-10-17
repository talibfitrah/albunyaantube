package com.albunyaan.tube.search;

import com.albunyaan.tube.service.YouTubeCircuitBreaker;
import com.albunyaan.tube.service.YouTubeGateway;
import com.albunyaan.tube.service.YouTubeThrottler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Spec R11–R13, R33, R37, §6.6: one case per signal shape, the interval mechanics, isolation and the outcome rules. */
class CrawlPacerTest {

    @TempDir Path tmp;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-20T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private InnerStore store;
    private YouTubeThrottler throttler;
    private CrawlPacer pacer;
    private final List<Long> slept = new ArrayList<>();

    static final String BROWSE = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false";
    static final String JSON_BODY = "{\"contents\":{\"twoColumnBrowseResultsRenderer\":{\"tabs\":[]}},\"padding\":\"…\"}";

    @BeforeEach
    void setUp() {
        store = new InnerStore(tmp.resolve("inner").toString());
        store.state().mode = InnerState.Mode.FIRST_PASS;
        throttler = mock(YouTubeThrottler.class);
        pacer = new CrawlPacer(store, throttler, clock);
        pacer.sleeper = ms -> {
            slept.add(ms);
            now.set(now.get().plusMillis(ms));
        };
        pacer.jitter = () -> 1.0;
    }

    interface Body { void run() throws Exception; }

    /** Runs `body` on a thread named like the crawler's: R11 paces and classifies only that thread. */
    static void onThread(String name, Body body) throws Exception {
        Throwable[] failure = {null};
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, name);
        t.start();
        t.join(10_000);
        if (failure[0] instanceof Exception e) throw e;
        if (failure[0] instanceof Error e) throw e;
    }

    static void onCrawler(Body body) throws Exception {
        onThread(CrawlPacer.CRAWLER_THREAD, body);
    }

    /** R12 by construction: none of the crawler's classes can reach the shared breaker or the gateway. */
    static void neverHoldsTheSharedBreaker(Class<?> c) {
        List<Class<?>> forbidden = List.of(YouTubeCircuitBreaker.class, YouTubeGateway.class);
        for (Field f : c.getDeclaredFields()) assertFalse(forbidden.contains(f.getType()), c + "." + f.getName());
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            assertTrue(Arrays.stream(k.getParameterTypes()).noneMatch(forbidden::contains), k.toString());
        }
    }

    private String signalOnCrawler(String url, int status, String finalUrl, String type, String body) throws Exception {
        String[] seen = {null};
        onCrawler(() -> {
            pacer.jobStart();
            try {
                pacer.after(url, status, finalUrl, type, body);
            } catch (CrawlPacer.CrawlPausedException e) {
                assertTrue(pacer.signalled());
                seen[0] = e.getMessage();
            }
        });
        return seen[0];
    }

    // ---- R33 items 1-5: every shape is a signal -------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "HTTP_429   | 429 | https://www.youtube.com/youtubei/v1/browse | application/json |",
            "HTTP_403   | 403 | https://www.youtube.com/youtubei/v1/browse | application/json |",
            "HTTP_503   | 503 | https://www.youtube.com/youtubei/v1/browse | text/html        |",
            "CONSENT    | 200 | https://consent.youtube.com/m?continue=x    | text/html        | <html>consent</html>",
            "SORRY      | 200 | https://www.google.com/sorry/index?q=x      | text/html        | <html>sorry</html>",
            "OOPS       | 200 | https://www.youtube.com/oops                | text/html        | <html>oops</html>",
            "OOPS       | 200 | https://www.youtube.com/error?src=404       | text/html        | <html>error</html>",
            "NON_JSON   | 200 | https://www.youtube.com/youtubei/v1/browse | text/html; charset=utf-8 | <html>a page</html>",
            "SHORT_BODY | 200 | https://www.youtube.com/youtubei/v1/browse | application/json | {}"})
    void eachSoftBlockShapeIsARateSignal(String type, int status, String finalUrl, String contentType, String body) throws Exception {
        String message = signalOnCrawler(BROWSE, status, finalUrl, contentType, body);
        assertNotNull(message, type);
        assertTrue(message.contains(type), message);
        InnerState s = store.state();
        assertEquals(type, s.signals.get(s.signals.size() - 1).type());
        assertEquals(60_000, s.breaker.intervalMs);
        assertEquals(now.get().toEpochMilli() + CrawlPacer.PAUSE_MS, s.breaker.pausedUntil);
        assertEquals(type, new InnerStore(tmp.resolve("inner").toString()).state().signals.get(0).type(), "saved on the signal");
    }

    @Test
    void aBodyOf49CharsIsASignalAnd50IsNot() throws Exception {
        assertNotNull(signalOnCrawler(BROWSE, 200, BROWSE, "application/json", "x".repeat(49)));
        assertNull(signalOnCrawler(BROWSE, 200, BROWSE, "application/json", "x".repeat(50)));
    }

    @Test
    void botTextInAPageIsASignalButInsideJsonTitlesItIsNot() throws Exception {
        assertNotNull(signalOnCrawler("https://www.youtube.com/sw.js", 200, "https://www.youtube.com/sw.js", "text/html",
                "<html>Sign in to confirm you're not a bot</html>"));
        store.state().breaker.pausedUntil = 0;
        assertNull(signalOnCrawler(BROWSE, 200, BROWSE, "application/json",
                "{\"title\":\"Sign in to confirm you're not a bot — a lecture on unusual traffic\",\"x\":1}"));
    }

    @Test
    void anAgeGatePageIsNotBotText() throws Exception {
        assertNull(signalOnCrawler("https://www.youtube.com/sw.js", 200, "https://www.youtube.com/sw.js", "text/html",
                "<html>Sign in to confirm your age</html>"));
    }

    @Test
    void anotherServerErrorIsTransportNeverASignal() throws Exception {
        IOException[] thrown = {null};
        onCrawler(() -> {
            try {
                pacer.after(BROWSE, 500, BROWSE, "text/html", "<html>500</html>");
            } catch (IOException e) {
                thrown[0] = e;
            }
        });
        assertNotNull(thrown[0]);
        assertFalse(thrown[0] instanceof CrawlPacer.CrawlPausedException);
        assertTrue(store.state().signals.isEmpty());
    }

    // ---- R13: the interval ----------------------------------------------------------------------

    @Test
    void fromThirtySecondsTheThirdSignalTurnsTheCrawlerOff() {
        pacer.rateSignal("HTTP_429", "x", 429);
        assertEquals(60_000, store.state().breaker.intervalMs);
        pacer.rateSignal("HTTP_429", "x", 429);
        assertEquals(120_000, store.state().breaker.intervalMs);
        assertEquals(InnerState.Mode.FIRST_PASS, store.state().mode);
        pacer.rateSignal("HTTP_429", "x", 429);
        assertEquals(InnerState.Mode.OFF, store.state().mode);
        assertEquals(InnerState.Mode.OFF, new InnerStore(tmp.resolve("inner").toString()).state().mode, "saved");
    }

    @Test
    void fromFifteenSecondsTheFourthSignalTurnsTheCrawlerOff() {
        store.state().breaker.intervalMs = CrawlPacer.BASE_INTERVAL_MS;
        for (int i = 0; i < 3; i++) pacer.rateSignal("NON_JSON", "x", 200);
        assertEquals(120_000, store.state().breaker.intervalMs);
        assertEquals(InnerState.Mode.FIRST_PASS, store.state().mode);
        pacer.rateSignal("NON_JSON", "x", 200);
        assertEquals(InnerState.Mode.OFF, store.state().mode);
    }

    private void clean(int n) throws Exception {
        onCrawler(() -> {
            for (int i = 0; i < n; i++) pacer.after(BROWSE, 200, BROWSE, "application/json", JSON_BODY);
        });
    }

    @Test
    void aDayOfCleanRequestsInFullModeHalvesTheIntervalButNeverBelowFifteenSeconds() throws Exception {
        store.state().mode = InnerState.Mode.FULL;
        clean(2_879);
        assertEquals(30_000, store.state().breaker.intervalMs);
        clean(1);
        assertEquals(15_000, store.state().breaker.intervalMs);
        clean(5_760);
        assertEquals(15_000, store.state().breaker.intervalMs);
    }

    @Test
    void theFirstPassNeverStepsUp() throws Exception {
        clean(3_000);
        assertEquals(30_000, store.state().breaker.intervalMs);
    }

    @Test
    void idleTimeNeitherCountsNorResets() throws Exception {
        store.state().mode = InnerState.Mode.FULL;
        clean(1_000);
        now.set(now.get().plusSeconds(2 * 86_400));
        clean(1_879);
        assertEquals(30_000, store.state().breaker.intervalMs);
        clean(1);
        assertEquals(15_000, store.state().breaker.intervalMs);
    }

    @Test
    void aSignalResetsTheCleanCount() throws Exception {
        store.state().mode = InnerState.Mode.FULL;
        clean(2_000);
        pacer.rateSignal("HTTP_429", "x", 429);
        assertEquals(0, store.state().breaker.cleanRequestsAtInterval);
    }

    @Test
    void resetPaceGoesToFifteenSecondsAndClearsThePause() {
        pacer.rateSignal("HTTP_429", "x", 429);
        pacer.resetPace();
        assertEquals(15_000, store.state().breaker.intervalMs);
        assertEquals(0, store.state().breaker.pausedUntil);
    }

    // ---- R11 pacing and refusals ----------------------------------------------------------------

    @Test
    void theCrawlerWaitsIntervalTimesJitterThenTheSharedThrottle() throws Exception {
        pacer.jitter = () -> 0.8;
        onCrawler(() -> {
            pacer.before(BROWSE);
            pacer.before(BROWSE);
        });
        assertEquals(List.of(24_000L), slept);
        verify(throttler, times(2)).throttle();
        assertEquals(2, pacer.today(CrawlPacer.CRAWLER_THREAD));
    }

    @Test
    void otherThreadsAreOnlyCountedNeverPacedOrClassified() throws Exception {
        onThread("http-nio-8080-exec-7", () -> {
            pacer.before(BROWSE);
            pacer.after(BROWSE, 429, BROWSE, "application/json", null);
        });
        onThread("validation-sched-2", () -> pacer.before(BROWSE));
        assertTrue(slept.isEmpty());
        verifyNoInteractions(throttler);
        assertTrue(store.state().signals.isEmpty());
        assertEquals(1, pacer.today("http-nio-8080-exec-"));
        assertEquals(1, pacer.today("validation-sched-"));
        assertEquals("inner-crawler", CrawlPacer.prefix("inner-crawler"));
    }

    @Test
    void playerAndGooglevideoAreRefused() throws Exception {
        for (String url : List.of("https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                "https://rr3---sn-x.googlevideo.com/videoplayback?id=1")) {
            onCrawler(() -> assertTrue(assertThrows(IOException.class, () -> pacer.before(url)).getMessage().contains("refused")));
        }
        verifyNoInteractions(throttler);
        assertEquals(0, pacer.today(CrawlPacer.CRAWLER_THREAD));
    }

    @Test
    void theStopFileOffAPauseAndTheDailyCapEachBlockTheNextRequest() throws Exception {
        Files.createFile(tmp.resolve("inner/STOP"));
        onCrawler(() -> assertThrows(CrawlPacer.CrawlPausedException.class, () -> pacer.before(BROWSE)));
        Files.delete(tmp.resolve("inner/STOP"));
        store.state().mode = InnerState.Mode.OFF;
        onCrawler(() -> assertThrows(CrawlPacer.CrawlPausedException.class, () -> pacer.before(BROWSE)));
        store.state().mode = InnerState.Mode.FULL;
        store.state().breaker.pausedUntil = now.get().toEpochMilli() + 1;
        onCrawler(() -> assertThrows(CrawlPacer.CrawlPausedException.class, () -> pacer.before(BROWSE)));
        store.state().breaker.pausedUntil = 0;
        pacer.dailyCap(1);
        onCrawler(() -> {
            pacer.before(BROWSE);
            assertThrows(CrawlPacer.CrawlPausedException.class, () -> pacer.before(BROWSE));
        });
        verify(throttler, times(1)).throttle();
        assertTrue(store.state().signals.isEmpty(), "a refusal is never a signal");
    }

    @Test
    void requestCountsKeepFourteenDays() throws Exception {
        for (int d = 0; d < 16; d++) {
            onThread("validation-sched-1", () -> pacer.before(BROWSE));
            now.set(now.get().plusSeconds(86_400));
        }
        TreeMap<String, TreeMap<String, Long>> days = store.state().requestsByDay;
        assertEquals(14, days.size());
        assertEquals("2026-10-22", days.firstKey());
    }

    // ---- §6.6 outcomes --------------------------------------------------------------------------

    @Test
    void threeUnavailableSourcesInARowAreOneSignalAndKeepTheirState() {
        assertFalse(pacer.unavailable("channel:UCa"));
        assertFalse(pacer.unavailable("channel:UCb"));
        assertTrue(pacer.unavailable("playlist:PLc"));
        assertEquals("UNAVAILABLE_RUN", store.state().signals.get(0).type());
        assertEquals(List.of(), pacer.jobSucceeded(), "the run was consumed by the signal; nothing is confirmed");
    }

    @Test
    void twoUnavailableThenASuccessConfirmsBoth() {
        pacer.unavailable("channel:UCa");
        pacer.unavailable("channel:UCb");
        assertEquals(List.of("channel:UCa", "channel:UCb"), pacer.jobSucceeded());
        assertTrue(store.state().signals.isEmpty());
        assertEquals(List.of(), store.state().breaker.unavailableRun);
    }

    @Test
    void threeTransportFailuresInTenJobsPauseWithoutDoubling() {
        for (int i = 0; i < 3; i++) pacer.transportFailure();
        InnerState.Breaker b = store.state().breaker;
        assertEquals(now.get().toEpochMilli() + CrawlPacer.PAUSE_MS, b.pausedUntil);
        assertEquals(30_000, b.intervalMs);
        assertEquals(1, b.transportPauses);
        assertTrue(store.state().signals.isEmpty());
    }

    @Test
    void theThirdTransportPauseWithoutASuccessIsASignal() {
        for (int i = 0; i < 9; i++) pacer.transportFailure();
        assertEquals("TRANSPORT", store.state().signals.get(0).type());
        assertEquals(60_000, store.state().breaker.intervalMs);
    }

    @Test
    void aSuccessBetweenTransportPausesResetsTheEscalation() {
        for (int i = 0; i < 6; i++) pacer.transportFailure();
        pacer.jobSucceeded();
        for (int i = 0; i < 3; i++) pacer.transportFailure();
        assertEquals(1, store.state().breaker.transportPauses);
        assertTrue(store.state().signals.isEmpty());
    }

    @Test
    void featureOffIsANoOpOnEveryThread() throws Exception {
        CrawlPacer off = new CrawlPacer(new InnerStore(""), throttler, clock);
        onCrawler(() -> {
            off.before(BROWSE);
            off.after(BROWSE, 429, BROWSE, "application/json", null);
        });
        verifyNoInteractions(throttler);
    }

    @Test
    void thePacerNeverHoldsTheSharedBreakerOrGateway() {
        neverHoldsTheSharedBreaker(CrawlPacer.class);
        assertEquals(CrawlPacer.FIRST_PASS_INTERVAL_MS, new InnerState().breaker.intervalMs);
    }
}
