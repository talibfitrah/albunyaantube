package com.albunyaan.tube.integration;

import com.albunyaan.tube.service.PlaybackSolverService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec §6.3: /api/playback end to end through the real filter chain, with a JDK HttpServer as the sidecar.
 * The Bearer value IS the uid (the FirebaseAuth mock), so every test owns its own per-uid limiter state.
 */
@ExtendWith(OutputCaptureExtension.class)
class PlaybackSolveIT extends BaseIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger solveHits = new AtomicInteger();
    private static volatile String mode = "ok";          // ok | conflict | warming | slow
    private static final HttpServer sidecar = startSidecar();   // before the context reads the property below

    @MockBean FirebaseAuth firebaseAuth;
    @Autowired PlaybackSolverService service;

    private static HttpServer startSidecar() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/player", ex -> reply(ex, 200, "{\"playerId\":\"0229cd64\",\"sts\":20739}"));
            server.createContext("/solve", ex -> {
                solveHits.incrementAndGet();
                Map<String, Object> req = JSON.readValue(ex.getRequestBody().readAllBytes(), new TypeReference<>() { });
                switch (mode) {
                    case "conflict" -> reply(ex, 409, "{}");
                    case "warming" -> reply(ex, 503, "{\"status\":\"warming\"}");
                    default -> {
                        if (mode.equals("slow")) { try { Thread.sleep(600); } catch (InterruptedException ignored) { } }
                        reply(ex, 200, JSON.writeValueAsString(Map.of("n", echo(req.get("n")), "s", echo(req.get("s")))));
                    }
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    @DynamicPropertySource
    static void sidecarUrl(DynamicPropertyRegistry registry) {
        registry.add("playback.solver.url", () -> "http://127.0.0.1:" + sidecar.getAddress().getPort());
    }

    @AfterAll
    static void stopSidecar() { sidecar.stop(0); }

    @BeforeEach
    void bearerIsTheUid() throws Exception {
        mode = "ok";
        Mockito.when(firebaseAuth.verifyIdToken(anyString())).thenAnswer(inv -> token(inv.getArgument(0)));
        Mockito.when(firebaseAuth.verifyIdToken(anyString(), anyBoolean())).thenAnswer(inv -> token(inv.getArgument(0)));
    }

    private static FirebaseToken token(String uid) {
        FirebaseToken token = Mockito.mock(FirebaseToken.class);
        Mockito.when(token.getUid()).thenReturn(uid);
        Mockito.when(token.getEmail()).thenReturn(uid + "@test.com");
        Mockito.when(token.getClaims()).thenReturn(Map.of());     // an app user: no role claim
        return token;
    }

    private static String n(String tag) { return (tag + "QUJDREVGR0hJSktMTU5P").substring(0, 20); }
    private static String s(String tag) { return (tag + "x".repeat(120)).substring(0, 108); }

    private ResultActions solve(String uid, String n, List<String> s) throws Exception {
        return mvc.perform(post("/api/playback/solve").header("Authorization", "Bearer " + uid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("playerId", "0229cd64", "n", List.of(n), "s", s))));
    }

    /** "status Retry-After", e.g. "503 10" or "200 null". */
    private static String answer(ResultActions result) {
        var response = result.andReturn().getResponse();
        return response.getStatus() + " " + response.getHeader("Retry-After");
    }

    /** Regression pin: never open. No token: the chain's default entry point (403). A bad token: the filter's 401. */
    @Test
    void withoutATokenBothRoutesAre403AndABadTokenIs401() throws Exception {
        mvc.perform(get("/api/playback/player")).andExpect(status().isForbidden());
        mvc.perform(post("/api/playback/solve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        Mockito.doThrow(Mockito.mock(FirebaseAuthException.class)).when(firebaseAuth).verifyIdToken(eq("bad"), anyBoolean());
        mvc.perform(get("/api/playback/player").header("Authorization", "Bearer bad")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/playback/solve").header("Authorization", "Bearer bad")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void aSignedPlayerLookupIs200() throws Exception {
        mvc.perform(get("/api/playback/player").header("Authorization", "Bearer uid-it-player"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value("0229cd64"))
                .andExpect(jsonPath("$.sts").value(20739));
    }

    @Test
    void aSignedSolveRoundTripsAndItsRepeatNeverReachesTheSidecar() throws Exception {
        int before = solveHits.get();
        solve("uid-it-roundtrip", n("R"), List.of(s("R"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.n." + n("R")).value(n("R") + "A"))
                .andExpect(jsonPath("$.s." + s("R")).value(s("R") + "A"));
        solve("uid-it-roundtrip", n("R"), List.of(s("R"))).andExpect(status().isOk());
        assertThat(solveHits.get() - before).isEqualTo(1);
    }

    @Test
    void theTwentyFirstSolveInAMinuteIs429WithRetryAfter() throws Exception {
        for (int i = 0; i < 20; i++) solve("uid-it-limit", n("L"), List.of()).andExpect(status().isOk());   // 1 miss, 19 cache hits
        solve("uid-it-limit", n("L"), List.of()).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void theKillFlagIs503With120() throws Exception {
        ReflectionTestUtils.setField(service, "enabled", false);
        try {
            solve("uid-it-off", n("D"), List.of()).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "120"));
        } finally {
            ReflectionTestUtils.setField(service, "enabled", true);
        }
    }

    @Test
    void aWarmingSidecarIs503With120() throws Exception {
        mode = "warming";
        solve("uid-it-warming", n("W"), List.of()).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "120"));
    }

    @Test
    void aConflictIsPassedThrough() throws Exception {
        mode = "conflict";
        solve("uid-it-conflict", n("K"), List.of()).andExpect(status().isConflict());
    }

    @Test
    void nineConcurrentMissesLeaveAtLeastOne503With10() throws Exception {
        mode = "slow";
        ExecutorService pool = Executors.newFixedThreadPool(9);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> statuses = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int k = i;
            statuses.add(pool.submit(() -> {
                go.await();
                return answer(solve("uid-it-c" + k, n("C" + k), List.of()));
            }));
        }
        go.countDown();
        List<String> done = new ArrayList<>();
        for (Future<String> f : statuses) done.add(f.get());
        pool.shutdown();
        assertThat(done).contains("503 10");
    }

    @Test
    void overTwentyMissesInOneSecondGet503With10() throws Exception {
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));
        try {
            List<String> done = new ArrayList<>();
            for (int i = 0; i < 25; i++) done.add(answer(solve("uid-it-g" + i, n("G" + i), List.of())));
            assertThat(done).filteredOn(a -> a.startsWith("503")).containsOnly("503 10").hasSize(5);
        } finally {
            ReflectionTestUtils.setField(service, "clock", Clock.systemUTC());
        }
    }

    /** Codex P1-2: an oversize body is refused before Jackson binds it, so neither the limiter nor the sidecar sees it. */
    @Test
    void anOversizeBodyIs413BeforeBindingAndTheLargestValidOneIs200() throws Exception {
        int before = solveHits.get();
        String oversize = JSON.writeValueAsString(Map.of("playerId", "0229cd64", "n", List.of(n("O")),
                "s", Collections.nCopies(200, s("O"))));
        mvc.perform(post("/api/playback/solve").header("Authorization", "Bearer uid-it-oversize")
                        .contentType(MediaType.APPLICATION_JSON).content(oversize))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));
        // a declared length under the cap (or none, as a chunked body has) does not lift the cap on what is read
        mvc.perform(post("/api/playback/solve").header("Authorization", "Bearer uid-it-oversize")
                        .contentType(MediaType.APPLICATION_JSON).header("Content-Length", "100").content(oversize))
                .andExpect(status().isPayloadTooLarge());
        assertThat(solveHits.get()).isEqualTo(before);

        List<String> fourN = new ArrayList<>();
        List<String> sixtyFourS = new ArrayList<>();
        for (int i = 0; i < 4; i++) fourN.add((n("M" + i) + "y".repeat(12)).substring(0, 32));
        for (int i = 0; i < 64; i++) sixtyFourS.add((s("M" + i) + "z".repeat(52)).substring(0, 160));
        mvc.perform(post("/api/playback/solve").header("Authorization", "Bearer uid-it-largest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("playerId", "0229cd64", "n", fourN, "s", sixtyFourS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.s." + sixtyFourS.get(63)).value(sixtyFourS.get(63) + "A"));
    }

    @Test
    void nothingAboutTheChallengesIsLogged(CapturedOutput output) throws Exception {
        solve("uid-it-quiet", n("Q"), List.of(s("Q"))).andExpect(status().isOk());
        assertThat(output.getAll()).doesNotContain(n("Q")).doesNotContain(s("Q"));
    }
}
