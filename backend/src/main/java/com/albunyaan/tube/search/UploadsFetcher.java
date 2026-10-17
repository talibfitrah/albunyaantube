package com.albunyaan.tube.search;

import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one admitted source's listing through NewPipe (inner-search spec §4): a channel's uploads playlist UU…, or a
 * playlist PL…, 100 items per request, with `hl` forced (R21). A first page is 2 requests (F4, F25), each next page 1.
 * Never goes through YouTubeGateway: the crawler has its own breaker and the shared one must not see its traffic
 * (R12). Every HTTP request still passes CrawlPacer inside NewPipe's global downloader.
 * NewPipe's exceptions pass through as NewPipe types them: the crawler alone decides what "gone" is (R3-18).
 */
@Component
public class UploadsFetcher {
    private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");
    /** A channel key needs a UC… id (its uploads list is UU…); a playlist key takes any id family (PL/OL/RD/LL/UU...). */
    private static final Pattern SOURCE_KEY = Pattern.compile("channel:UC([A-Za-z0-9_-]{1,62})|playlist:([A-Za-z0-9_-]{1,64})");

    /** `count`: the exact item count (first page, else -1); `listed`: what YouTube listed before R20 (R32 counts it); `next`: null at the end. */
    public record Batch(long count, List<InnerCorpus.Item> items, int listed, Page next) {}

    public Batch first(String sourceKey, String hl) throws IOException, ExtractionException {
        PlaylistExtractor x = extractor(sourceKey, hl);
        x.fetchPage();
        return batch(sourceKey, x.getInitialPage(), x.getStreamCount());
    }

    public Batch next(String sourceKey, String hl, Page page) throws IOException, ExtractionException {
        return batch(sourceKey, extractor(sourceKey, hl).getPage(page), -1);
    }

    private static PlaylistExtractor extractor(String sourceKey, String hl) throws ExtractionException {
        PlaylistExtractor x = ServiceList.YouTube.getPlaylistExtractor(url(sourceKey));
        x.forceLocalization(new Localization(hl));
        return x;
    }

    /** Rejects (before any request) a key that names no list: never a guessed UU… of someone else, never a stray URL character. */
    static String url(String sourceKey) {
        Matcher m = SOURCE_KEY.matcher(sourceKey);
        if (!m.matches()) throw new IllegalArgumentException("not a crawlable source key: " + sourceKey);
        return "https://www.youtube.com/playlist?list=" + (m.group(1) != null ? "UU" + m.group(1) : m.group(2));
    }

    static Batch batch(String sourceKey, InfoItemsPage<StreamInfoItem> page, long count) {
        boolean playlist = sourceKey.startsWith("playlist:");
        List<InnerCorpus.Item> items = new ArrayList<>();
        for (StreamInfoItem s : page.getItems()) {
            InnerCorpus.Item it = item(s, playlist);
            if (it != null) items.add(it);
        }
        return new Batch(count, items, page.getItems().size() + page.getErrors().size(), page.hasNextPage() ? page.getNextPage() : null);
    }

    /** R20 (members-only, paid, upcoming: never stored), `a` (approximate upload day), `c`/`u` for playlist items (R16). Null = skip. */
    static InnerCorpus.Item item(StreamInfoItem s, boolean playlist) {
        ContentAvailability av = s.getContentAvailability();
        if (av == ContentAvailability.MEMBERSHIP || av == ContentAvailability.PAID || av == ContentAvailability.UPCOMING) return null;
        if (s.getName() == null) return null;
        String id;
        try {
            id = ServiceList.YouTube.getStreamLHFactory().getId(s.getUrl());
        } catch (Exception e) {
            return null;
        }
        DateWrapper date = s.getUploadDate();
        Integer day = date == null ? null : (int) LocalDate.ofInstant(date.getInstant(), ZoneOffset.UTC).toEpochDay();
        String c = null, u = null;
        if (playlist) {
            c = s.getUploaderName();
            Matcher m = CHANNEL_ID.matcher(String.valueOf(s.getUploaderUrl()));
            u = m.find() ? m.group() : null;
        }
        return new InnerCorpus.Item(id, s.getName(), (int) Math.max(0, s.getDuration()), day, c, u);
    }
}
