package com.albunyaan.tube.search;

import com.albunyaan.tube.service.YouTubeThrottler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * Per-request pacing and the crawler's own breaker (inner-search spec R11–R13, R33, R37). NewPipe's one global
 * downloader (NewPipeConfiguration) calls before() and after() for every request on every thread. Other threads are
 * only counted. The crawler thread is paced, refused while it must not run, and every response it gets is
 * classified. Never touches YouTubeCircuitBreaker (R12): the pause, interval and signals live in state.json, saved
 * on each change.
 */
@Component
public class CrawlPacer {
    private static final Logger log = LoggerFactory.getLogger(CrawlPacer.class);

    public static final String CRAWLER_THREAD = "inner-crawler";
    // ponytail: 15 s ±20% is the owner's ceiling (R1). Measured safe only for 56 requests at ≥3.5 s from a
    // residential IP (RS §3.2); the first pass runs at 30 s as the prod-IP canary. Go faster only after a week
    // at 15 s with zero signals.
    static final long BASE_INTERVAL_MS = 15_000, FIRST_PASS_INTERVAL_MS = 30_000, MAX_INTERVAL_MS = 120_000;
    static final long PAUSE_MS = 3_600_000;
    static final int MAX_SIGNALS = 20, DAYS_KEPT = 14, UNAVAILABLE_RUN = 3, WINDOW = 10, WINDOW_FAILURES = 3,
            TRANSPORT_PAUSES = 3;
    private static final Pattern BOT_TEXT = Pattern.compile("not a bot|unusual traffic|g-recaptcha", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_DIGITS = Pattern.compile("\\d+$");

    /**
     * Aborts the current crawl job: a rate signal after() just recorded, or STOP, OFF, a pause or the daily cap. No
     * message carries text YouTubeCircuitBreaker.isRateLimitError reads as a rate limit ("429", "too many requests"...),
     * so even one that escaped the crawler thread could not trip the shared breaker; the signal's type is in state.json.
     */
    public static class CrawlPausedException extends IOException {
        public CrawlPausedException(String why) {
            super("inner crawl paused: " + why);
        }
    }

    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final InnerStore store;
    private final YouTubeThrottler throttler;
    private final Clock clock;
    Sleeper sleeper = Thread::sleep;
    DoubleSupplier jitter = () -> ThreadLocalRandom.current().nextDouble(0.8, 1.2);
    private volatile int dailyCap = Integer.MAX_VALUE;
    // Crawler thread only:
    private long lastRequestAt;
    private boolean signalled;
    private final Deque<Boolean> window = new ArrayDeque<>();

    public CrawlPacer(InnerStore store, YouTubeThrottler throttler, Clock clock) {
        this.store = store;
        this.throttler = throttler;
        this.clock = clock;
    }

    private static boolean onCrawler() {
        return CRAWLER_THREAD.equals(Thread.currentThread().getName());
    }

    /** Before every NewPipe request, on the caller's thread (R11, R37). */
    public void before(String url) throws IOException {
        if (!store.enabled()) return;
        if (!onCrawler()) {
            count(Thread.currentThread().getName());
            return;
        }
        if (url.contains("/player") || url.contains("googlevideo.com")) {
            log.warn("inner crawl refused {}", url);
            throw new IOException("inner crawl refused: a player or media request");
        }
        InnerState s = store.state();
        refuseWhileHeld(s);
        if (today(CRAWLER_THREAD) >= dailyCap) throw new CrawlPausedException("daily cap");
        // capped: a backward clock step must not lengthen the sleep, which wakeUp() cannot end (Cubic r8 #15)
        long wait = Math.min(lastRequestAt + Math.round(s.breaker.intervalMs * jitter.getAsDouble()) - clock.millis(),
                Math.round(MAX_INTERVAL_MS * 1.2));
        if (wait > 0) {
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CrawlPausedException("interrupted");
            }
            refuseWhileHeld(s); // a `touch STOP` during the sleep holds this request too
        }
        throttler.throttle(); // the shared 3-4 s spacing: how the crawler yields to the live poller and validators (R12)
        lastRequestAt = clock.millis();
        count(CRAWLER_THREAD);
    }

    private void refuseWhileHeld(InnerState s) throws CrawlPausedException {
        if (store.stopped()) throw new CrawlPausedException("STOP file");
        if (s.mode == InnerState.Mode.OFF) throw new CrawlPausedException("mode OFF");
        if (clock.millis() < s.breaker.pausedUntil) throw new CrawlPausedException("paused");
    }

    /** After every NewPipe response, crawler thread only (R33 items 1-5): a match is a rate signal and aborts the job. */
    public void after(String url, int status, String finalUrl, String contentType, String body) throws IOException {
        if (!store.enabled() || !onCrawler()) return;
        String type = classify(url, status, finalUrl, contentType, body);
        if (type != null) {
            rateSignal(type, path(finalUrl != null ? finalUrl : url), status);
            throw new CrawlPausedException("rate-limited");
        }
        if (status >= 500) throw new IOException("HTTP " + status); // transport (§6.6): the job window, never a signal
        clean();
    }

    /** R33 items 1-5; null = not a rate signal. Bodies are scanned only when they are not JSON: titles are user text. */
    static String classify(String url, int status, String finalUrl, String contentType, String body) {
        if (status == 429 || status == 403 || status == 503) return "HTTP_" + status;
        if (status >= 500) return null;
        URI fin = uri(finalUrl != null ? finalUrl : url);
        String host = fin == null || fin.getHost() == null ? "" : fin.getHost().toLowerCase(Locale.ROOT);
        String path = fin == null || fin.getPath() == null ? "" : fin.getPath();
        if (host.equals("consent.youtube.com")) return "CONSENT";
        if ((host.equals("google.com") || host.endsWith(".google.com")) && path.startsWith("/sorry")) return "SORRY";
        if ((host.equals("youtube.com") || host.endsWith(".youtube.com"))
                && (path.startsWith("/oops") || path.startsWith("/error"))) return "OOPS";
        boolean json = contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
        if (url.contains("/youtubei/")) {
            if (!json) return "NON_JSON";
            if (body == null || body.length() < 50) return "SHORT_BODY";
        }
        if (!json && body != null && BOT_TEXT.matcher(body).find()) return "BOT_TEXT";
        return null;
    }

    private static URI uri(String s) {
        try {
            return s == null ? null : URI.create(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String path(String url) {
        URI u = uri(url);
        return u == null || u.getHost() == null ? "?" : u.getHost() + u.getPath();
    }

    /** R13 step-up: 86,400 / interval clean requests at this interval (24 crawl-hours), in FULL only, halve it. */
    private void clean() {
        InnerState s = store.state();
        long stepped = 0;
        synchronized (s) {
            s.breaker.cleanRequestsAtInterval++;
            if (s.mode == InnerState.Mode.FULL && s.breaker.intervalMs > BASE_INTERVAL_MS
                    && s.breaker.cleanRequestsAtInterval >= 86_400_000L / s.breaker.intervalMs) {
                s.breaker.intervalMs = Math.max(BASE_INTERVAL_MS, s.breaker.intervalMs / 2);
                s.breaker.cleanRequestsAtInterval = 0;
                stepped = s.breaker.intervalMs;
            }
        }
        if (stepped > 0) {
            log.info("inner crawl: 24 clean crawl-hours; interval now {} s (R13)", stepped / 1000);
            store.save();
        }
    }

    void jobStart() {
        signalled = false;
    }

    /** True when this job's responses already recorded a signal: the job's exception adds none of its own. */
    boolean signalled() {
        return signalled;
    }

    /** R13: pause 1 h and double the interval; OFF (ERROR) when the next interval would pass 120 s. Saved at once (R15). */
    void rateSignal(String type, String path, int status) {
        InnerState s = store.state();
        long now = clock.millis();
        boolean off;
        synchronized (s) {
            s.signals.add(new InnerState.Signal(now, type, path, status));
            while (s.signals.size() > MAX_SIGNALS) s.signals.remove(0);
            s.breaker.pausedUntil = now + PAUSE_MS;
            s.breaker.cleanRequestsAtInterval = 0;
            off = s.breaker.intervalMs * 2 > MAX_INTERVAL_MS;
            if (off) s.mode = InnerState.Mode.OFF;
            else s.breaker.intervalMs *= 2;
        }
        signalled = true;
        if (off) {
            log.error("inner crawl auto-OFF: rate signal {} on {} (HTTP {}); the next interval would pass {} s",
                    type, path, status, MAX_INTERVAL_MS / 1000);
        } else {
            log.warn("inner crawl rate signal {} on {} (HTTP {}): interval {} s, paused until {}",
                    type, path, status, s.breaker.intervalMs / 1000, Instant.ofEpochMilli(now + PAUSE_MS));
        }
        store.save();
    }

    /** §6.6: ≥ 3 failed jobs in the last 10 pause 1 h without doubling; the third such pause with no success between is a signal. */
    void transportFailure() {
        window(false);
        if (window.stream().filter(ok -> !ok).count() < WINDOW_FAILURES) return;
        window.clear();
        InnerState s = store.state();
        boolean escalate;
        synchronized (s) {
            s.breaker.pausedUntil = clock.millis() + PAUSE_MS;
            escalate = ++s.breaker.transportPauses >= TRANSPORT_PAUSES;
            if (escalate) s.breaker.transportPauses = 0;
        }
        log.warn("inner crawl: transport failures in 3 of the last 10 jobs; paused 1 h");
        if (escalate) rateSignal("TRANSPORT", "-", 0);
        else store.save();
    }

    /** R33.7: K = 3 "unavailable" results in a row are a rate signal, and the run's sources keep their state. */
    boolean unavailable(String sourceKey) {
        InnerState s = store.state();
        boolean run;
        synchronized (s) {
            s.breaker.unavailableRun.add(sourceKey);
            run = s.breaker.unavailableRun.size() >= UNAVAILABLE_RUN;
            if (run) s.breaker.unavailableRun.clear();
        }
        if (run) rateSignal("UNAVAILABLE_RUN", sourceKey, 0);
        else store.save();
        return run;
    }

    /** A successful job: the unavailable run before it is confirmed (returned), and the transport escalation resets. */
    List<String> jobSucceeded() {
        window(true);
        InnerState s = store.state();
        synchronized (s) {
            List<String> confirmed = List.copyOf(s.breaker.unavailableRun);
            s.breaker.unavailableRun.clear();
            s.breaker.transportPauses = 0;
            return confirmed;
        }
    }

    /** Admin `resetPace` (§9): the 15 s base and no pause. */
    void resetPace() {
        InnerState s = store.state();
        synchronized (s) {
            s.breaker.intervalMs = BASE_INTERVAL_MS;
            s.breaker.pausedUntil = 0;
            s.breaker.cleanRequestsAtInterval = 0;
        }
        store.save();
    }

    /** R23: set by the crawler before each job; before() refuses the request that would pass it. */
    void dailyCap(int cap) {
        dailyCap = cap;
    }

    private void window(boolean ok) {
        window.addLast(ok);
        while (window.size() > WINDOW) window.removeFirst();
    }

    /** Today's (UTC) NewPipe requests by thread-name prefix (R37). */
    long today(String prefix) {
        InnerState s = store.state();
        synchronized (s) {
            TreeMap<String, Long> day = s.requestsByDay.get(day(clock.millis()));
            return day == null ? 0 : day.getOrDefault(prefix, 0L);
        }
    }

    /** "http-nio-8080-exec-7" -> "http-nio-8080-exec-": one key per thread pool. */
    static String prefix(String threadName) {
        return TRAILING_DIGITS.matcher(threadName).replaceAll("");
    }

    private static String day(long ms) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).toString();
    }

    // ponytail: every thread's request takes the state monitor, which save() holds only while serialising (never
    // through the disk write); per-prefix LongAdders folded in at save time if that wait ever shows in request latency.
    private void count(String threadName) {
        String day = day(clock.millis()), key = prefix(threadName);
        InnerState s = store.state();
        synchronized (s) {
            s.requestsByDay.computeIfAbsent(day, d -> new TreeMap<>()).merge(key, 1L, Long::sum);
            while (s.requestsByDay.size() > DAYS_KEPT) s.requestsByDay.pollFirstEntry();
        }
    }
}
