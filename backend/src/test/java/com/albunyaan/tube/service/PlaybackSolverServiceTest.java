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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
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
    private volatile String solveRaw;                                     // non-null: a 200 with this body
    private volatile CountDownLatch solveGate = new CountDownLatch(0);    // open unless a test holds it
    private volatile int playerStatus = 200;
    private volatile String playerReply = "{\"playerId\":\"0229cd64\",\"sts\":20739}";
    private volatile CountDownLatch playerGate = new CountDownLatch(0);
    private static final Map<String, Object> PLAYER = Map.of("playerId", "0229cd64", "sts", 20739);
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
        sidecar.createContext("/player", ex -> { playerHits.incrementAndGet(); pass(playerGate); reply(ex, playerStatus, playerReply); });
        sidecar.createContext("/solve", ex -> {
            solveHits.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastSolveBody.set(body);
            pass(solveGate);
            try { Thread.sleep(solveDelayMs); } catch (InterruptedException ignored) { }
            if (solveStatus != 200) { reply(ex, solveStatus, "{}"); return; }
            if (solveRaw != null) { reply(ex, 200, solveRaw); return; }
            Map<String, Object> req = JSON.readValue(body, new TypeReference<>() { });
            reply(ex, 200, JSON.writeValueAsString(Map.of("n", echo(req.get("n")), "s", echo(req.get("s")))));
        });
        sidecar.start();
    }

    @AfterEach
    void stopSidecar() {
        solveGate.countDown();
        playerGate.countDown();
        sidecar.stop(0);
    }

    private static void pass(CountDownLatch gate) {
        try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    private static void until(BooleanSupplier done) throws InterruptedException {
        while (!done.getAsBoolean()) Thread.sleep(1);
    }

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
                Arguments.of("0229cd64", List.of(N, N, N, N, N), List.of()),
                Arguments.of("0229cd64", List.of("short"), List.of()),            // n is 8-32 chars
                Arguments.of("0229cd64", List.of("x".repeat(7)), List.of()),
                Arguments.of("0229cd64", List.of("x".repeat(33)), List.of()),
                Arguments.of("0229cd64", List.of(N), List.of("S".repeat(63))),    // s is 64-160 chars
                Arguments.of("0229cd64", List.of(N), List.of("S".repeat(161))),
                Arguments.of("0229cd64", List.of("QUJDREVGR0hJ SktM"), List.of()),  // a space
                Arguments.of("0229cd64", List.of(N), List.of("S".repeat(63) + "!")),  // outside the alphabet
                Arguments.of("0229cd64", List.of(N), Collections.nCopies(65, s)), // s holds 0-64
                Arguments.of("0229cd64", null, List.of()),
                Arguments.of("0229cd64", List.of(N), null));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void malformedInputIs400AndNeverReachesTheSidecar(String playerId, List<String> n, List<String> s) {
        assertThat(service(true).solve(playerId, n, s).status()).isEqualTo(400);
        assertThat(solveHits.get()).isZero();
    }

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of(List.of("x".repeat(8)), List.of()),
                Arguments.of(List.of("x".repeat(32)), List.of()),
                Arguments.of(List.of(N), List.of("S".repeat(64))),
                Arguments.of(List.of(N), List.of("S".repeat(160))),
                Arguments.of(List.of(n("A"), n("B"), n("C"), n("D")), List.of()),
                Arguments.of(List.of(N), IntStream.range(0, 64).mapToObj(PlaybackSolverServiceTest::s).toList()));
    }

    @ParameterizedTest
    @MethodSource("boundaries")
    void theShapeBoundariesAreAccepted(List<String> n, List<String> s) {
        assertThat(service(true).solve("0229cd64", n, s).status()).isEqualTo(200);
        assertThat(solveHits.get()).isEqualTo(1);
    }

    /** Stage 9 Cubic r2 P3: n holds 0-4. iOS sends n: [] when no selected format carries one; its s challenges still solve. */
    @Test
    void anSOnlySolveAnswersItsSChallenges() {
        PlaybackSolverService.Answer answer = service(true).solve("0229cd64", List.of(), List.of(s(1)));
        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo(Map.of("n", Map.of(), "s", Map.of(s(1), s(1) + "A")));
        assertThat(lastSolveBody.get()).contains("\"n\":[]");
    }

    @Test
    void theCacheIsKeyedByPlayer() {
        PlaybackSolverService service = service(true);
        service.solve("0229cd64", List.of(N), List.of(s(1)));
        service.solve("1234abcd", List.of(N), List.of(s(1)));
        assertThat(solveHits.get()).isEqualTo(2);
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
        solveGate = new CountDownLatch(1);                                // the stub holds all 8 until released
        PlaybackSolverService service = service(true);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<PlaybackSolverService.Answer>> held = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String challenge = n("C" + i);
            held.add(pool.submit(() -> service.solve("0229cd64", List.of(challenge), List.of())));
        }
        until(() -> solveHits.get() == 8);
        assertThat(service.solve("0229cd64", List.of(n("C8")), List.of())).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 10));
        solveGate.countDown();
        for (Future<PlaybackSolverService.Answer> a : held) a.get();
        pool.shutdown();
        assertThat(solveHits.get()).isEqualTo(8);
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

    /** Fix round 1: one /player call at a time; the rest get the stale copy, or 503 + 10 when there is none. */
    @Test
    void aPlayerRefreshIsSingleFlight() throws Exception {
        PlaybackSolverService service = service(true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        playerGate = new CountDownLatch(1);
        Future<PlaybackSolverService.Answer> first = pool.submit(service::player);
        until(() -> playerHits.get() == 1);
        for (int i = 0; i < 5; i++) assertThat(service.player()).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 10));
        playerGate.countDown();
        assertThat(first.get()).isEqualTo(new PlaybackSolverService.Answer(200, PLAYER, null));

        now.set(now.get().plusSeconds(61));                               // the copy expires; a refresh is in flight
        playerGate = new CountDownLatch(1);
        Future<PlaybackSolverService.Answer> second = pool.submit(service::player);
        until(() -> playerHits.get() == 2);
        for (int i = 0; i < 5; i++) assertThat(service.player()).isEqualTo(new PlaybackSolverService.Answer(200, PLAYER, null));
        playerGate.countDown();
        assertThat(second.get().status()).isEqualTo(200);
        pool.shutdown();
        assertThat(playerHits.get()).isEqualTo(2);
    }

    /** Fix round 1: a warming (503) or hung (timeout) sidecar sees at most one /player call per 10 s. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedPlayerRefreshIsRememberedForTenSeconds(boolean hung) {
        if (hung) playerGate = new CountDownLatch(1); else playerStatus = 503;
        PlaybackSolverService service = service(true);
        assertThat(service.player()).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 120));
        now.set(now.get().plusSeconds(9));
        assertThat(service.player()).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 120));
        assertThat(playerHits.get()).isEqualTo(1);
        playerGate.countDown();
        playerStatus = 200;
        now.set(now.get().plusSeconds(1));
        assertThat(service.player()).isEqualTo(new PlaybackSolverService.Answer(200, PLAYER, null));
        assertThat(playerHits.get()).isEqualTo(2);
    }

    /** Fix round 1: the 200 body is rebuilt from playerId and sts; anything else from the sidecar is a failure. */
    @Test
    void playerAnswersOnlyAValidPlayerIdAndSts() {
        playerReply = "{\"playerId\":\"0229cd64\",\"sts\":20739,\"held\":[\"0229cd64\"],\"rssMiB\":270}";
        assertThat(service(true).player()).isEqualTo(new PlaybackSolverService.Answer(200, PLAYER, null));
        playerReply = "{\"playerId\":\"../../etc\",\"sts\":20739}";
        assertThat(service(true).player()).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 120));
        playerReply = "{\"playerId\":\"0229cd64\"}";
        assertThat(service(true).player()).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 120));
    }

    /** Fix round 1: a 409 or 500 for the copied player means the sidecar no longer holds it, so the next /player asks again. */
    @ParameterizedTest
    @ValueSource(ints = {409, 500})
    void aSolveThatFindsTheCopiedPlayerGoneDropsTheCopy(int status) {
        PlaybackSolverService service = service(true);
        service.player();
        solveStatus = status;
        service.solve("1234abcd", List.of(N), List.of());                  // another player: the copy stays
        service.player();
        assertThat(playerHits.get()).isEqualTo(1);
        service.solve("0229cd64", List.of(N), List.of());                  // the copied player is gone
        service.player();
        assertThat(playerHits.get()).isEqualTo(2);
    }

    @Test
    void aSidecarConflictIsPassedThrough() {
        solveStatus = 409;
        assertThat(service(true).solve("0229cd64", List.of(N), List.of()).status()).isEqualTo(409);
    }

    /** Any other sidecar status (a 503 here) is a failure: 503 + 120. */
    @Test
    void aFailedSidecarAnswers503With120() {
        solveStatus = 503;
        assertThat(service(true).solve("0229cd64", List.of(N), List.of())).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 120));
    }

    /**
     * B18 (Cubic r7 #8): a sidecar 500 is a solve that threw, and the sidecar evicted that player (server.mjs solve()), so
     * the app gets the unknown-player 409: iOS drops its player id with no window, instead of a 120 s window spent on it.
     */
    @Test
    void aSolveThatEvictedThePlayerAnswersTheUnknownPlayer409() {
        solveStatus = 500;
        assertThat(service(true).solve("0229cd64", List.of(N), List.of())).isEqualTo(new PlaybackSolverService.Answer(409, Map.of(), null));
    }

    @Test
    void aSidecarSlowerThanOneSecondAnswers503With120() {
        solveDelayMs = 3000;
        long t0 = System.nanoTime();
        PlaybackSolverService.Answer answer = service(true).solve("0229cd64", List.of(N), List.of());
        assertThat(answer).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 120));
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    }

    /** Fix round 1 (nit): a sidecar that answers but not with JSON is logged as that, once an hour, never as unreachable. */
    @Test
    void anUnparseableAnswerIsLoggedAsSuchOnceAnHour(CapturedOutput output) {
        solveRaw = "not json";
        PlaybackSolverService service = service(true);
        assertThat(service.solve("0229cd64", List.of(n("P1")), List.of())).isEqualTo(new PlaybackSolverService.Answer(503, Map.of(), 120));
        assertThat(service.solve("0229cd64", List.of(n("P2")), List.of()).status()).isEqualTo(503);
        assertThat(output.getAll().split("playback solver answer unparseable", -1).length - 1).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("unreachable").doesNotContain(n("P1"));
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
