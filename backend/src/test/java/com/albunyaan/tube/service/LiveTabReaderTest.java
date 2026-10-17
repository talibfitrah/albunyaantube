package com.albunyaan.tube.service;

import com.albunyaan.tube.config.ValidationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

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
        final Deque<String> browse = new ArrayDeque<>();
        final List<String> browseUrls = new ArrayList<>();

        void queue(String... fixtures) throws IOException {
            for (String name : fixtures) {
                try (InputStream in = LiveTabReaderTest.class.getResourceAsStream("/live-tab/" + name)) {
                    browse.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }

        @Override
        public Response execute(Request request) {
            String url = request.url();
            if (url.equals("https://www.youtube.com/sw.js")) {   // getClientVersion's first source; cached per JVM after
                return new Response(200, "OK", Map.of(),
                        "\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"2.20260120.01.00\"", url);
            }
            if (url.contains("/youtubei/v1/browse")) {
                browseUrls.add(url);
                String body = browse.poll();
                if (body == null) throw new AssertionError("no fixture left for " + url);
                return new Response(200, "OK", Map.of(), body, url);
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

        LiveTabResult r = new LiveTabReader(guarded, oEmbed, channels).read("UCOll3M-P7oKs5cSrQ9ytt6g");

        assertThat(r.outcome()).isEqualTo(LiveTabResult.Outcome.BREAKER);
        assertThat(r.requests()).isZero();
        assertThat(downloader.browseUrls).isEmpty();
        guarded.shutdown();
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
    void noAuthorMeansNoChannel() {
        when(oEmbed.authorUrl("eC4LfEVxvKg")).thenReturn(Optional.empty());

        assertThat(reader.resolveChannelId("eC4LfEVxvKg")).isEmpty();
    }
}
