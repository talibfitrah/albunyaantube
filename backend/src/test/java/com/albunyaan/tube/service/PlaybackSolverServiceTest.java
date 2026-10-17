package com.albunyaan.tube.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §4.6 / R29 / §6.3 unit tests: everything between the controller and the sidecar. */
@ExtendWith(OutputCaptureExtension.class)
class PlaybackSolverServiceTest {

    private static final String N = "QUJDREVGR0hJSktMTU5P";            // 20 chars, the challenge alphabet
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer sidecar;
    private final AtomicInteger solveHits = new AtomicInteger();
    private final AtomicInteger playerHits = new AtomicInteger();
    private final AtomicReference<String> lastSolveBody = new AtomicReference<>();
    private volatile int solveStatus = 200;
    private volatile long solveDelayMs = 0;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-17T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private static String s(int i) { return ("S" + i + "x".repeat(120)).substring(0, 108); }
    private static String n(String tag) { return (tag + N).substring(0, 20); }

    @BeforeEach
    void startSidecar() throws IOException {
        sidecar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sidecar.setExecutor(Executors.newCachedThreadPool());
        sidecar.createContext("/player", ex -> { playerHits.incrementAndGet(); reply(ex, 200, "{\"playerId\":\"0229cd64\",\"sts\":20739}"); });
        sidecar.createContext("/solve", ex -> {
            solveHits.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastSolveBody.set(body);
            try { Thread.sleep(solveDelayMs); } catch (InterruptedException ignored) { }
            if (solveStatus != 200) { reply(ex, solveStatus, "{}"); return; }
            Map<String, Object> req = JSON.readValue(body, new TypeReference<>() { });
            reply(ex, 200, JSON.writeValueAsString(Map.of("n", echo(req.get("n")), "s", echo(req.get("s")))));
        });
        sidecar.start();
    }

    @AfterEach
    void stopSidecar() { sidecar.stop(0); }

    @SuppressWarnings("unchecked")
    private static Map<String, String> echo(Object challenges) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String c : (List<String>) challenges) out.put(c, c + "A");
        return out;
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private PlaybackSolverService service(boolean enabled) {
        return new PlaybackSolverService("http://127.0.0.1:" + sidecar.getAddress().getPort(), enabled, clock);
    }

    static Stream<Arguments> malformed() {
        String s = s(0);
        return Stream.of(
                Arguments.of("0229CD64", List.of(N), List.of()),                  // upper-case hex
                Arguments.of("0229cd6", List.of(N), List.of()),                   // 7 chars
                Arguments.of(null, List.of(N), List.of()),
                Arguments.of("0229cd64", List.of(), List.of()),                   // n needs 1-4
                Arguments.of("0229cd64", List.of(N, N, N, N, N), List.of()),
                Arguments.of("0229cd64", List.of("short"), List.of()),            // n is 8-32 chars
                Arguments.of("0229cd64", List.of("x".repeat(33)), List.of()),
                Arguments.of("0229cd64", List.of(N), List.of("S".repeat(63))),    // s is 64-160 chars
                Arguments.of("0229cd64", List.of(N), List.of("S".repeat(161))),
                Arguments.of("0229cd64", List.of("QUJDREVGR0hJ SktM"), List.of()),  // a space
                Arguments.of("0229cd64", List.of(N), Collections.nCopies(65, s)), // s holds 0-64
                Arguments.of("0229cd64", null, List.of()));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void malformedInputIs400AndNeverReachesTheSidecar(String playerId, List<String> n, List<String> s) {
        assertThat(service(true).solve(playerId, n, s).status()).isEqualTo(400);
        assertThat(solveHits.get()).isZero();
    }

    @Test
    void theKillFlagAnswers503With120AndNeverCallsTheSidecar() {
        PlaybackSolverService off = service(false);
        assertThat(off.solve("0229cd64", List.of(N), List.of())).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 120));
        assertThat(off.player()).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 120));
        assertThat(solveHits.get() + playerHits.get()).isZero();
    }

    @Test
    void aSolveRoundTripsAndTheSameChallengesNeverReachTheSidecarTwice() {
        PlaybackSolverService service = service(true);
        PlaybackSolverService.Answer first = service.solve("0229cd64", List.of(N), List.of(s(1)));
        PlaybackSolverService.Answer second = service.solve("0229cd64", List.of(N), List.of(s(1)));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).isEqualTo(Map.of("n", Map.of(N, N + "A"), "s", Map.of(s(1), s(1) + "A")));
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(solveHits.get()).isEqualTo(1);
    }

    @Test
    void onlyTheMissingChallengesGoToTheSidecar() throws Exception {
        PlaybackSolverService service = service(true);
        service.solve("0229cd64", List.of(N), List.of(s(1)));
        service.solve("0229cd64", List.of(N), List.of(s(1), s(2)));
        Map<String, Object> sent = JSON.readValue(lastSolveBody.get(), new TypeReference<>() { });
        assertThat(sent).isEqualTo(Map.of("playerId", "0229cd64", "n", List.of(), "s", List.of(s(2))));
    }

    @Test
    void aFullSemaphoreAnswers503With10() throws Exception {
        solveDelayMs = 500;
        PlaybackSolverService service = service(true);
        ExecutorService pool = Executors.newFixedThreadPool(9);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PlaybackSolverService.Answer>> answers = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            String challenge = n("C" + i);
            answers.add(pool.submit(() -> { go.await(); return service.solve("0229cd64", List.of(challenge), List.of()); }));
        }
        go.countDown();
        List<PlaybackSolverService.Answer> done = new ArrayList<>();
        for (Future<PlaybackSolverService.Answer> a : answers) done.add(a.get());
        pool.shutdown();
        assertThat(done).filteredOn(a -> a.status() == 503).isNotEmpty().allMatch(a -> a.retryAfter() == 10);
        assertThat(done).filteredOn(a -> a.status() == 200).hasSizeLessThanOrEqualTo(8);
    }

    @Test
    void theTwentyFirstMissInOneSecondAnswers503With10() {
        PlaybackSolverService service = service(true);
        for (int i = 0; i < 20; i++) assertThat(service.solve("0229cd64", List.of(n("G" + i)), List.of()).status()).isEqualTo(200);
        assertThat(service.solve("0229cd64", List.of(n("G20")), List.of())).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 10));
        now.set(now.get().plusSeconds(1));
        assertThat(service.solve("0229cd64", List.of(n("G21")), List.of()).status()).isEqualTo(200);
    }

    @Test
    void playerIsServedFromASixtySecondCopy() {
        PlaybackSolverService service = service(true);
        assertThat(service.player().body()).isEqualTo(Map.of("playerId", "0229cd64", "sts", 20739));
        service.player();
        assertThat(playerHits.get()).isEqualTo(1);
        now.set(now.get().plusSeconds(61));
        service.player();
        assertThat(playerHits.get()).isEqualTo(2);
    }

    @Test
    void aSidecarConflictIsPassedThrough() {
        solveStatus = 409;
        assertThat(service(true).solve("0229cd64", List.of(N), List.of()).status()).isEqualTo(409);
    }

    /** 503 = warming; 500 = a solve that timed out or threw in the sidecar, which evicted that player (Task 3 as built). */
    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void aFailedOrWarmingSidecarAnswers503With120(int status) {
        solveStatus = status;
        assertThat(service(true).solve("0229cd64", List.of(N), List.of())).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 120));
    }

    @Test
    void aSidecarSlowerThanOneSecondAnswers503With120() {
        solveDelayMs = 1500;
        long t0 = System.nanoTime();
        PlaybackSolverService.Answer answer = service(true).solve("0229cd64", List.of(N), List.of());
        assertThat(answer).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 120));
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1400));
    }

    @Test
    void anUnreachableSidecarLogsOneErrorLineAnHour(CapturedOutput output) {
        PlaybackSolverService service = service(true);
        sidecar.stop(0);
        assertThat(service.solve("0229cd64", List.of(n("U1")), List.of()).status()).isEqualTo(503);
        assertThat(service.solve("0229cd64", List.of(n("U2")), List.of()).status()).isEqualTo(503);
        assertThat(output.getAll().split("playback solver unreachable", -1).length - 1).isEqualTo(1);
        now.set(now.get().plus(Duration.ofHours(1)));
        service.solve("0229cd64", List.of(n("U3")), List.of());
        assertThat(output.getAll().split("playback solver unreachable", -1).length - 1).isEqualTo(2);
        assertThat(output.getAll()).doesNotContain(n("U1"));      // no challenge in any log line
    }
}
