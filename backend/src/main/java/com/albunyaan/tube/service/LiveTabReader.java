package com.albunyaan.tube.service;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The only class that asks YouTube about live channels (spec D2, D10). Live-tab reads and the channel-details lookup go
 * through YouTubeGateway's shared throttle and breaker; the oEmbed author lookup in {@link #resolveChannelId} bypasses
 * both. Nothing here calls /player.
 */
@Component
public class LiveTabReader {

    /** R16: follow up to 5 continuation-only pages, as both apps do (Zad needs 2 today). */
    static final int MAX_CONTINUATIONS = 5;

    private static final Pattern UC_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");

    /**
     * R7 beyond HTTP 429 (which the prod downloader already turns into a ReCaptchaException): the HTML a followed
     * consent or bot-check redirect lands on, whose URL NewPipe puts in its "Got HTML document" ParsingException; and
     * the bot-check alert, Got error: "Sign in to confirm you're not a bot" (stage 9 B24; sign-in wording is a rate
     * limit only there, as YouTubeCircuitBreaker reads it).
     */
    private static final Pattern RATE_LIMITED =
            Pattern.compile("consent\\.youtube\\.com|google\\.com/sorry|not a bot", Pattern.CASE_INSENSITIVE);

    private final YouTubeGateway gateway;
    private final YouTubeOEmbedClient oEmbed;
    private final ChannelOrchestrator channels;

    public LiveTabReader(YouTubeGateway gateway, YouTubeOEmbedClient oEmbed, ChannelOrchestrator channels) {
        this.gateway = gateway;
        this.oEmbed = oEmbed;
        this.channels = channels;
    }

    static boolean isChannelId(String id) {
        return id != null && UC_ID.matcher(id).matches();
    }

    /** The channel's current lives, in Live-tab order. Never throws (D3). */
    public LiveTabResult read(String ucId) {
        if (!isChannelId(ucId)) {
            return LiveTabResult.error(0, "not a channel id: " + ucId);
        }
        int requests = 0;
        try {
            ChannelTabExtractor tab = gateway.createLiveTabExtractor(ucId);
            requests++;
            gateway.fetchTabPage(tab);
            ListExtractor.InfoItemsPage<InfoItem> page = tab.getInitialPage();
            for (int continuation = 0; page.getItems().isEmpty() && page.hasNextPage()
                    && continuation < MAX_CONTINUATIONS; continuation++) {
                requests++;
                page = gateway.getTabPage(tab, page.getNextPage());
            }
            if (page.getItems().isEmpty()) {
                return LiveTabResult.error(requests, "no items after " + (requests - 1) + " continuation pages");
            }
            // ponytail: only the first page with items is scanned (spec §5); a live listed below it is missed, read one more page if that is ever seen.
            Map<String, LiveTabResult.LiveItem> lives = new LinkedHashMap<>();
            for (InfoItem item : page.getItems()) {
                if (item instanceof StreamInfoItem stream && isLive(stream.getStreamType())) {
                    String id = gateway.extractVideoId(stream.getUrl());
                    if (id != null) {
                        lives.putIfAbsent(id, new LiveTabResult.LiveItem(id, stream.getName()));
                    }
                }
            }
            return LiveTabResult.ok(lives.values(), requests);
        } catch (YouTubeGateway.CircuitOpenException e) {
            return LiveTabResult.breaker(requests - 1, e.getMessage());   // the refused call never left
        } catch (Exception e) {
            return failure(e, requests);
        }
    }

    /**
     * D3 and R7: a rate limit first (never a hide); TERMINATED only on the first request's definitive signals, the
     * channel validator's {@link ChannelOrchestrator#channelGone} (a bare HTTP 404 can be YouTube's /oops page, and a
     * continuation page says nothing about the channel); else ERROR.
     */
    private static LiveTabResult failure(Exception e, int requests) {
        String message = e.getClass().getSimpleName() + ": " + e.getMessage();
        String raw = String.valueOf(e.getMessage());
        if (e instanceof ReCaptchaException || RATE_LIMITED.matcher(raw).find()) {
            return LiveTabResult.rateLimited(requests, message);
        }
        if (requests == 1 && e instanceof ExtractionException ee && ChannelOrchestrator.channelGone(ee)) {
            return LiveTabResult.terminated(requests, message);
        }
        return LiveTabResult.error(requests, message);
    }

    /**
     * D10's last source: oEmbed author (bypasses the throttle) → handle → channel details (cached, throttled) → UC id.
     * 2–3 requests when unknown; empty when a step answers with no channel. An IOException (oEmbed's 429, 5xx or
     * network failure; a channel-details breaker, rate limit, network failure, or an extraction failure NewPipe gives
     * no definitive reason for) is no signal (R7): it reaches the caller, which must not read it as "no channel".
     * A gone channel ({@link ChannelOrchestrator#channelGone}, as {@link #read}'s TERMINATED) is definitive: empty.
     */
    public Optional<String> resolveChannelId(String videoId) throws IOException {
        try {
            Optional<String> author = oEmbed.authorUrl(videoId);
            if (author.isEmpty()) {
                return Optional.empty();
            }
            String handleOrId = gateway.extractChannelId(author.get());   // "UC…", "@handle", or null
            if (handleOrId == null) {
                return Optional.empty();
            }
            if (isChannelId(handleOrId)) {
                return Optional.of(handleOrId);
            }
            String id;
            try {
                id = channels.getChannelDetails(handleOrId).getId();
            } catch (IOException e) {                               // getChannelDetails wraps every ExtractionException
                if (e.getCause() instanceof ExtractionException ee && ChannelOrchestrator.channelGone(ee)) {
                    return Optional.empty();
                }
                throw e;
            }
            return isChannelId(id) ? Optional.of(id) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();                                // a crash, not a transient: a retry finds nothing
        }
    }

    private static boolean isLive(StreamType type) {
        return type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM;
    }
}
