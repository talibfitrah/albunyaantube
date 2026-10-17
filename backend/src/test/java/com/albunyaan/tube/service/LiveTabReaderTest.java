package com.albunyaan.tube.service;

import com.albunyaan.tube.config.NewPipeConfiguration;
import com.albunyaan.tube.config.ValidationProperties;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec §12 U1: NewPipe 0.26.5 reads the real (trimmed) Live-tab captures through LiveTabReader. No network. */
class LiveTabReaderTest {

    /** Serves browse POSTs from a queue; answers NewPipe's one-time client-version lookup; fails anything else. */
    static final class FixtureDownloader extends Downloader {
        interface Reply {
            Response to(String url) throws ReCaptchaException;
        }

        final Deque<Reply> browse = new ArrayDeque<>();
        final List<String> browseUrls = new ArrayList<>();

        void queue(String... fixtures) throws IOException {
            for (String name : fixtures) {
                try (InputStream in = LiveTabReaderTest.class.getResourceAsStream("/live-tab/" + name)) {
                    String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    browse.add(url -> new Response(200, "OK", Map.of(), body, url));
                }
            }
        }

        /** One synthetic answer, as the prod downloader hands it over: it follows redirects ({@code landedOn}, null =
         *  no redirect) and throws only on HTTP 429 (see {@link #initProdDownloaderAnswering429}), so never use 429
         *  here. HTTP/2 carries no reason phrase, hence "". */
        void respond(int code, String body, String landedOn, boolean html) {
            Map<String, List<String>> headers = html ? Map.of("Content-Type", List.of("text/html; charset=utf-8")) : Map.of();
            browse.add(url -> new Response(code, "", headers, body, landedOn == null ? url : landedOn));
        }

        @Override
        public Response execute(Request request) throws ReCaptchaException {
            String url = request.url();
            if (url.equals("https://www.youtube.com/sw.js")) {   // getClientVersion's first source; cached per JVM after
                return new Response(200, "OK", Map.of(),
                        "\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"2.20260120.01.00\"", url);
            }
            if (url.contains("/youtubei/v1/browse")) {
                browseUrls.add(url);
                Reply reply = browse.poll();
                if (reply == null) throw new AssertionError("no fixture left for " + url);
                return reply.to(url);
            }
            throw new AssertionError("unexpected request " + url);
        }
    }

    private FixtureDownloader downloader;
    private YouTubeGateway gateway;
    private YouTubeOEmbedClient oEmbed;
    private ChannelOrchestrator channels;
    private LiveTabReader reader;

    @BeforeEach
    void setUp() {
        downloader = new FixtureDownloader();
        NewPipe.init(downloader);
        gateway = new YouTubeGateway(ServiceList.YouTube, 1, null, null);
        oEmbed = mock(YouTubeOEmbedClient.class);
        channels = mock(ChannelOrchestrator.class);
        reader = new LiveTabReader(gateway, oEmbed, channels);
    }

    @AfterEach
    void tearDown() {
        gateway.shutdown();
    }

    private static List<String> ids(LiveTabResult r) {
        return r.lives().stream().map(LiveTabResult.LiveItem::id).toList();
    }

    @Test
    void one4kidsReadsAllThreeConcurrentLivesInTabOrderWithOneRequest() throws IOException {
        downloader.queue("one4kids.json");

        LiveTabResult r = reader.read("UC5vfWTTPnKdFp-8s0KbqJhw");

        assertThat(r.outcome()).isEqualTo(LiveTabResult.Outcome.OK);
        assertThat(ids(r)).containsExactly("J3bDNstCnzE", "yee9gj7t8nQ", "bpvNfqWTXhw");
        assertThat(r.lives()).allSatisfy(l -> assertThat(l.title()).isNotBlank());
        assertThat(r.requests()).isEqualTo(1);
        assertThat(downloader.browseUrls).hasSize(1);   // "channel/UC…" needs no resolve request (F7)
    }

    @Test
    void quranReadsItsOneLiveAndIgnoresPastStreams() throws IOException {
        downloader.queue("quran.json");

        LiveTabResult r = reader.read("UCos52azQNBgW63_9uDJoPDA");

        assertThat(r.outcome()).isEqualTo(LiveTabResult.Outcome.OK);
        assertThat(ids(r)).containsExactly("eC4LfEVxvKg");
        assertThat(r.hasLive()).isTrue();
    }

    @Test
    void anOfflineChannelIsAnEmptyOkReadNotAnError() throws IOException {
        downloader.queue("control.json");

        LiveTabResult r = reader.read("UCcjFQDQ1g993Douu7RCK1OQ");

        assertThat(r.outcome()).isEqualTo(LiveTabResult.Outcome.OK);
        assertThat(r.lives()).isEmpty();
        assertThat(r.hasLive()).isFalse();
    }

    /** R16 / F25: Zad's first two pages are continuation-only; its live is the first item of the third. */
    @Test
    void zadNeedsExactlyThreeRequestsAndItsLiveComesFirst() throws IOException {
        downloader.queue("zad-1.json", "zad-2.json", "zad-3.json");

        LiveTabResult r = reader.read("UCOll3M-P7oKs5cSrQ9ytt6g");

        assertThat(r.outcome()).isEqualTo(LiveTabResult.Outcome.OK);
        assertThat(ids(r)).containsExactly("eaOtcUNHDB8");
        assertThat(r.requests()).isEqualTo(3);
        assertThat(downloader.browseUrls).hasSize(3);
    }

    @Test
    void sixContinuationOnlyPagesAreAnErrorAfterSixRequests() throws IOException {
        downloader.queue("zad-1.json", "zad-2.json", "zad-2.json", "zad-2.json", "zad-2.json", "zad-2.json");

        LiveTabResult r = reader.read("UCOll3M-P7oKs5cSrQ9ytt6g");

        assertThat(r.outcome()).isEqualTo(LiveTabResult.Outcome.ERROR);
        assertThat(r.requests()).isEqualTo(6);
        assertThat(downloader.browseUrls).hasSize(6);
    }

    /** D3 / RV M3: a breaker refusal is not a channel error and reaches no request. */
    @Test
    void anOpenBreakerIsABreakerResultWithNoRequest() {
        ValidationProperties props = new ValidationProperties();
        props.getYoutube().getCircuitBreaker().setEnabled(true);
        props.getYoutube().getCircuitBreaker().setCooldownBaseMinutes(60);
        props.getYoutube().getCircuitBreaker().setCooldownMaxMinutes(600);
        props.getYoutube().getCircuitBreaker().getRollingWindow().setErrorThreshold(1);
        props.getYoutube().getCircuitBreaker().getRollingWindow().setWindowMinutes(10);
        YouTubeCircuitBreaker breaker = new YouTubeCircuitBreaker(props, null);
        breaker.recordRateLimitError(new RuntimeException("rate limit"));
        YouTubeGateway guarded = new YouTubeGateway(ServiceList.YouTube, 1, null, breaker);
        try {
            LiveTabResult r = new LiveTabReader(guarded, oEmbed, channels).read("UCOll3M-P7oKs5cSrQ9ytt6g");

            assertThat(r.outcome()).isEqualTo(LiveTabResult.Outcome.BREAKER);
            assertThat(r.requests()).isZero();
            assertThat(downloader.browseUrls).isEmpty();
        } finally {
            guarded.shutdown();
        }
    }

    /**
     * R7 root cause, end to end: a plain HTML 429 from www.youtube.com (no redirect, so no URL marker) goes through the
     * real prod downloader, NewPipe and the gateway; it reads RATE_LIMITED and opens the shared breaker.
     */
    /** NewPipe through the real prod downloader (NewPipeConfiguration); every browse answers HTTP 429 with this body. */
    static void initProdDownloaderAnswering429(String contentType, String body) {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            boolean swJs = chain.request().url().toString().equals("https://www.youtube.com/sw.js");
            return new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_2)
                    .code(swJs ? 200 : 429).message("")
                    .body(ResponseBody.create(swJs ? "\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"2.20260120.01.00\"" : body,
                            MediaType.get(swJs ? "text/javascript" : contentType)))
                    .build();
        }).build();
        NewPipeConfiguration config = new NewPipeConfiguration();
        ReflectionTestUtils.setField(config, "userAgent", "test-agent");
        NewPipe.init(config.newPipeDownloader(http));
    }

    @Test
    void aPlainHtml429ThroughTheProdDownloaderIsRateLimitedAndOpensTheBreaker() {
        initProdDownloaderAnswering429("text/html; charset=utf-8",
                "<!DOCTYPE html><html><head><title>Error 429 (Too Many Requests)</title></head></html>");
        ValidationProperties props = new ValidationProperties();
        props.getYoutube().getCircuitBreaker().setEnabled(true);
        props.getYoutube().getCircuitBreaker().getRollingWindow().setErrorThreshold(1);
        YouTubeCircuitBreaker breaker = new YouTubeCircuitBreaker(props, null);
        YouTubeGateway guarded = new YouTubeGateway(ServiceList.YouTube, 1, null, breaker);
        try {
            LiveTabReader guardedReader = new LiveTabReader(guarded, oEmbed, channels);

            LiveTabResult r = guardedReader.read("UCOll3M-P7oKs5cSrQ9ytt6g");

            assertThat(r.outcome()).isEqualTo(LiveTabResult.Outcome.RATE_LIMITED);
            assertThat(r.requests()).isEqualTo(1);
            assertThat(breaker.getCurrentState()).isEqualTo(YouTubeCircuitBreaker.State.OPEN);
            assertThat(guardedReader.read("UCOll3M-P7oKs5cSrQ9ytt6g").outcome()).isEqualTo(LiveTabResult.Outcome.BREAKER);
        } finally {
            guarded.shutdown();
        }
    }

    /**
     * Proof for the pattern cleanup: InnerTube's JSON 429 (status RESOURCE_EXHAUSTED) is an HTTP 429, so the prod
     * downloader turns it into a ReCaptchaException before NewPipe parses the body; its text never reaches the reader.
     */
    @Test
    void aJson429ReachesTheReaderAsReCaptchaNeverAsItsMessage() {
        initProdDownloaderAnswering429("application/json; charset=utf-8", "{\"error\":{\"code\":429,"
                + "\"message\":\"Resource has been exhausted (e.g. check quota).\",\"status\":\"RESOURCE_EXHAUSTED\"}}");

        LiveTabResult r = reader.read("UCOll3M-P7oKs5cSrQ9ytt6g");

        assertThat(r.outcome()).isEqualTo(LiveTabResult.Outcome.RATE_LIMITED);
        assertThat(r.error()).startsWith("ReCaptchaException:").doesNotContain("RESOURCE_EXHAUSTED");
    }

    /** No message heuristics beyond the redirect hosts: a stray "429" in another error is an ERROR. */
    @Test
    void aStray429InAnotherErrorIsAnErrorNotARateLimit() {
        downloader.respond(400, "{\"error\":{\"code\":400,\"message\":\"Invalid value at 'params' (429)\","
                + "\"status\":\"INVALID_ARGUMENT\"}}", null, false);

        assertThat(reader.read("UCOll3M-P7oKs5cSrQ9ytt6g").outcome()).isEqualTo(LiveTabResult.Outcome.ERROR);
    }

    /** A not-found on a continuation page says nothing about the channel: only the first request can be TERMINATED. */
    @Test
    void aNotFoundOnAContinuationPageIsAnErrorNotTerminated() throws IOException {
        downloader.queue("zad-1.json");
        downloader.respond(404, "{}", null, false);

        LiveTabResult r = reader.read("UCOll3M-P7oKs5cSrQ9ytt6g");

        assertThat(r.outcome()).isEqualTo(LiveTabResult.Outcome.ERROR);
        assertThat(r.requests()).isEqualTo(2);
    }

    @Test
    void aMalformedChannelIdIsAnErrorWithNoRequest() {
        for (String bad : new String[] {null, "UCtooShort", "channel/UCOll3M-P7oKs5cSrQ9ytt6g", "@ZadTVchannel"}) {
            LiveTabResult r = reader.read(bad);

            assertThat(r.outcome()).as(String.valueOf(bad)).isEqualTo(LiveTabResult.Outcome.ERROR);
            assertThat(r.requests()).isZero();
        }
        assertThat(downloader.browseUrls).isEmpty();
    }

    /** RV M6, narrowed to ChannelOrchestrator.channelGone: the terminated alert and the two exact not-found texts. */
    @Test
    void onlyATerminatedAlertOrNotFoundIsTerminatedAndKeepsItsMessage() {
        downloader.respond(200, "{\"alerts\":[{\"alertRenderer\":{\"type\":\"ERROR\",\"text\":{\"simpleText\":"
                + "\"This account has been terminated for a violation of YouTube's Terms of Service.\"}}}]}", null, false);
        downloader.respond(200, "{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\","
                + "\"status\":\"NOT_FOUND\"}}", null, false);
        downloader.respond(200, "{\"alerts\":[{\"alertRenderer\":{\"type\":\"ERROR\",\"text\":{\"simpleText\":"
                + "\"This channel does not exist.\"}}}]}", null, false);

        for (String expected : new String[] {"has been terminated", "This channel doesn't exist.",
                "Got error: \"This channel does not exist.\""}) {
            LiveTabResult r = reader.read("UCOll3M-P7oKs5cSrQ9ytt6g");

            assertThat(r.outcome()).as(expected).isEqualTo(LiveTabResult.Outcome.TERMINATED);
            assertThat(r.error()).contains(expected);
            assertThat(r.requests()).isEqualTo(1);
        }
    }

    /**
     * A bare HTTP 404 can be YouTube's /oops page: NewPipe checks the 404 before the final URL, so it says nothing
     * definitive about the channel (main's channelGone rule). ERROR, never TERMINATED.
     */
    @Test
    void aNotFoundEndingAtOopsIsAnErrorNotTerminated() {
        downloader.respond(404, "{}", "https://www.youtube.com/oops", false);

        LiveTabResult r = reader.read("UCOll3M-P7oKs5cSrQ9ytt6g");

        assertThat(r.outcome()).isEqualTo(LiveTabResult.Outcome.ERROR);
        assertThat(r.error()).contains("Not found (\"404");
        assertThat(r.requests()).isEqualTo(1);
    }

    /** A JSON 5xx, a plain ERROR alert and an /oops redirect all surface as ContentNotAvailableException: ERROR. */
    @Test
    void otherContentErrorsAreErrorsNotTerminated() {
        downloader.respond(500, "{\"error\":{\"code\":500,\"message\":\"Internal error encountered.\","
                + "\"status\":\"INTERNAL\"}}", null, false);
        downloader.respond(200, "{\"alerts\":[{\"alertRenderer\":{\"type\":\"ERROR\",\"text\":{\"simpleText\":"
                + "\"This channel is not available.\"}}}]}", null, false);
        downloader.respond(200, "{\"responseContext\":{},\"padding\":\"0123456789012345678901234567890123456789\"}",
                "https://www.youtube.com/oops", false);

        for (String expected : new String[] {"INTERNAL", "This channel is not available.", "Content unavailable"}) {
            LiveTabResult r = reader.read("UCOll3M-P7oKs5cSrQ9ytt6g");

            assertThat(r.outcome()).as(expected).isEqualTo(LiveTabResult.Outcome.ERROR);
            assertThat(r.error()).contains(expected);
        }
    }

    /** R7: YouTube pushing back is RATE_LIMITED (the poller stops its cycle), never ERROR or TERMINATED. */
    @Test
    void rateLimitSignalsAreRateLimited() {
        downloader.browse.add(url -> {
            throw new ReCaptchaException("reCaptcha Challenge requested", url);
        });
        String html = "<!DOCTYPE html><html><head><title>Before you continue</title></head><body></body></html>";
        downloader.respond(200, html, "https://consent.youtube.com/m?continue=https%3A%2F%2Fwww.youtube.com", true);
        downloader.respond(200, html, "https://www.google.com/sorry/index?continue=https://www.youtube.com/", true);

        for (String expected : new String[] {"reCaptcha", "consent.youtube.com", "google.com/sorry"}) {
            LiveTabResult r = reader.read("UCOll3M-P7oKs5cSrQ9ytt6g");

            assertThat(r.outcome()).as(expected).isEqualTo(LiveTabResult.Outcome.RATE_LIMITED);
            assertThat(r.error()).contains(expected);
            assertThat(r.requests()).isEqualTo(1);
            assertThat(r.hasLive()).isFalse();
        }
    }

    /** D10: an /@handle author resolves to its UC id through the (cached) channel details. */
    @Test
    void aHandleAuthorResolvesToItsChannelId() throws IOException {
        when(oEmbed.authorUrl("eaOtcUNHDB8")).thenReturn(Optional.of("https://www.youtube.com/@ZadTVchannel"));
        ChannelInfo info = mock(ChannelInfo.class);
        when(info.getId()).thenReturn("UCOll3M-P7oKs5cSrQ9ytt6g");
        when(channels.getChannelDetails("@ZadTVchannel")).thenReturn(info);

        assertThat(reader.resolveChannelId("eaOtcUNHDB8")).contains("UCOll3M-P7oKs5cSrQ9ytt6g");
    }

    @Test
    void aChannelUrlAuthorNeedsNoChannelFetch() throws IOException {
        when(oEmbed.authorUrl("Rs7St51oDDc"))
                .thenReturn(Optional.of("https://www.youtube.com/channel/UCROKYPep-UuODNwyipe6JMw"));

        assertThat(reader.resolveChannelId("Rs7St51oDDc")).contains("UCROKYPep-UuODNwyipe6JMw");
        verify(channels, never()).getChannelDetails(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void aChannelLookupCrashMeansNoChannel() throws IOException {
        when(oEmbed.authorUrl("eaOtcUNHDB8")).thenReturn(Optional.of("https://www.youtube.com/@ZadTVchannel"));
        when(channels.getChannelDetails("@ZadTVchannel")).thenThrow(new IllegalStateException("parse bug"));

        assertThat(reader.resolveChannelId("eaOtcUNHDB8")).isEmpty();
    }

    @Test
    void noAuthorMeansNoChannel() {
        when(oEmbed.authorUrl("eC4LfEVxvKg")).thenReturn(Optional.empty());

        assertThat(reader.resolveChannelId("eC4LfEVxvKg")).isEmpty();
    }
}
