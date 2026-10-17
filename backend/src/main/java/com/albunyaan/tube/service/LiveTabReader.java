package com.albunyaan.tube.service;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only class that asks YouTube about live channels (spec D2, D10). Every request goes through YouTubeGateway's
 * shared throttle and breaker; nothing here calls /player.
 */
@Component
public class LiveTabReader {

    /** R16: follow up to 5 continuation-only pages, as both apps do (Zad needs 2 today). */
    static final int MAX_CONTINUATIONS = 5;

    private final YouTubeGateway gateway;
    private final YouTubeOEmbedClient oEmbed;
    private final ChannelOrchestrator channels;

    public LiveTabReader(YouTubeGateway gateway, YouTubeOEmbedClient oEmbed, ChannelOrchestrator channels) {
        this.gateway = gateway;
        this.oEmbed = oEmbed;
        this.channels = channels;
    }

    /** The channel's current lives, in Live-tab order. Never throws (D3). */
    public LiveTabResult read(String ucId) {
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
            return LiveTabResult.ok(List.copyOf(lives.values()), requests);
        } catch (YouTubeGateway.CircuitOpenException e) {
            return LiveTabResult.breaker(requests - 1, e.getMessage());   // the refused request never left
        } catch (ContentNotAvailableException e) {                         // incl. AccountTerminatedException (RV M6)
            return LiveTabResult.terminated(requests);
        } catch (Exception e) {
            return LiveTabResult.error(requests, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** D10's last source: oEmbed author → handle → channel details (cached) → UC id. 2–3 requests when unknown. */
    public Optional<String> resolveChannelId(String videoId) {
        Optional<String> author = oEmbed.authorUrl(videoId);
        if (author.isEmpty()) {
            return Optional.empty();
        }
        String handleOrId = gateway.extractChannelId(author.get());   // NewPipe 0.26.5: "@handle" or "channel/UC…"
        if (handleOrId == null) {
            return Optional.empty();
        }
        if (handleOrId.startsWith("channel/UC")) {
            return Optional.of(handleOrId.substring("channel/".length()));
        }
        try {
            String id = channels.getChannelDetails(handleOrId).getId();
            return id != null && id.startsWith("UC") ? Optional.of(id) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean isLive(StreamType type) {
        return type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM;
    }
}
