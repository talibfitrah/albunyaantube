package com.albunyaan.tube.search;

import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Spec §4, R16, R20: the source URL, the item mapping and the R32 listed count; no network. */
class UploadsFetcherTest {

    private static StreamInfoItem stream(String id, String title, long seconds) {
        StreamInfoItem s = new StreamInfoItem(0, "https://www.youtube.com/watch?v=" + id, title, StreamType.VIDEO_STREAM);
        s.setDuration(seconds);
        return s;
    }

    @Test
    void aChannelIsReadThroughItsUploadsPlaylistAndAPlaylistDirectly() {
        assertEquals("https://www.youtube.com/playlist?list=UUfassTU6mGWIj-yb26_ADjQ",
                UploadsFetcher.url("channel:UCfassTU6mGWIj-yb26_ADjQ"));
        assertEquals("https://www.youtube.com/playlist?list=PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe",
                UploadsFetcher.url("playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe"));
    }

    @Test
    void membersOnlyPaidAndUpcomingAreNeverStored() {
        for (ContentAvailability a : List.of(ContentAvailability.MEMBERSHIP, ContentAvailability.PAID, ContentAvailability.UPCOMING)) {
            StreamInfoItem s = stream("ilgiwqpcOC8", "Lezing", 2712);
            s.setContentAvailability(a);
            assertNull(UploadsFetcher.item(s, false), a.toString());
        }
        for (ContentAvailability a : List.of(ContentAvailability.AVAILABLE, ContentAvailability.UNKNOWN)) {
            StreamInfoItem s = stream("ilgiwqpcOC8", "Lezing", 2712);
            s.setContentAvailability(a);
            assertNotNull(UploadsFetcher.item(s, false), a.toString());
        }
    }

    @Test
    void anItemCarriesItsIdTitleDurationAndUploadDay() {
        StreamInfoItem s = stream("ilgiwqpcOC8", "Lezing: 'De rechtvaardige kalief ʿOmar ibn ʿAbdoel-ʿAziez'", 2712);
        s.setUploadDate(new DateWrapper(OffsetDateTime.parse("2025-04-14T10:00:00Z"), true));
        assertEquals(new InnerCorpus.Item("ilgiwqpcOC8", "Lezing: 'De rechtvaardige kalief ʿOmar ibn ʿAbdoel-ʿAziez'", 2712,
                (int) LocalDate.of(2025, 4, 14).toEpochDay(), null, null), UploadsFetcher.item(s, false));
    }

    @Test
    void aLiveOrUnknownDurationIsZeroAndAMissingDateIsLeftOut() {
        InnerCorpus.Item it = UploadsFetcher.item(stream("kRNck4MdV2g", "Live", -1), false);
        assertEquals(0, it.d());
        assertNull(it.a());
    }

    @Test
    void aPlaylistItemKeepsItsUploaderNameAndChannelIdAChannelItemDoesNot() {
        StreamInfoItem s = stream("cBjnzYHSV4M", "Hoe voed ik mijn kind op in Nederland?", 1800);
        s.setUploaderName("Dawah Nederland");
        s.setUploaderUrl("https://www.youtube.com/channel/UCfassTU6mGWIj-yb26_ADjQ");
        InnerCorpus.Item inPlaylist = UploadsFetcher.item(s, true);
        assertEquals("Dawah Nederland", inPlaylist.c());
        assertEquals("UCfassTU6mGWIj-yb26_ADjQ", inPlaylist.u());
        InnerCorpus.Item inChannel = UploadsFetcher.item(s, false);
        assertNull(inChannel.c());
        assertNull(inChannel.u());
    }

    @Test
    void anItemWithoutAParsableIdOrATitleIsSkipped() {
        assertNull(UploadsFetcher.item(new StreamInfoItem(0, "https://example.com/x", "t", StreamType.VIDEO_STREAM), false));
        assertNull(UploadsFetcher.item(stream("ilgiwqpcOC8", null, 10), false));
    }

    @Test
    void aBatchCountsEveryListedItemIncludingTheOnesItSkips() {
        StreamInfoItem paid = stream("cBjnzYHSV4M", "b", 10);
        paid.setContentAvailability(ContentAvailability.PAID);
        List<StreamInfoItem> items = List.of(stream("ilgiwqpcOC8", "a", 10), paid);
        UploadsFetcher.Batch last = UploadsFetcher.batch("channel:UCx",
                new InfoItemsPage<>(items, null, List.of(new ParsingException("one bad item"))), 150);
        assertEquals(150, last.count());
        assertEquals(1, last.items().size());
        assertEquals(3, last.listed());
        assertNull(last.next());
        UploadsFetcher.Batch more = UploadsFetcher.batch("channel:UCx",
                new InfoItemsPage<>(items, new Page("https://www.youtube.com/youtubei/v1/browse"), List.of()), -1);
        assertNotNull(more.next());
    }

    @Test
    void theFetcherNeverHoldsTheSharedBreakerOrGateway() {
        CrawlPacerTest.neverHoldsTheSharedBreaker(UploadsFetcher.class);
    }

    @Test
    void everyPlaylistIdFamilyIsReadAsIsAndAKeyThatNamesNoListIsRejected() {
        for (String id : new String[]{"OLAK5uy_kXZ0lAeLq1Bd3", "RDCMUCfassTU6mGWIj-yb26_ADjQ", "LLfassTU6mGWIj-yb26_ADjQ", "UUfassTU6mGWIj-yb26_ADjQ"}) {
            assertEquals("https://www.youtube.com/playlist?list=" + id, UploadsFetcher.url("playlist:" + id), id);
        }
        // a channel id that is not UC… has no uploads playlist; the brief's substring(2) threw or built a stranger's UU…
        for (String bad : new String[]{"channel:X", "channel:", "channel:UC", "channel:XXfassTU6mGWIj-yb26_ADjQ",
                "playlist:", "playlist:PLx&v=y", "video:ilgiwqpcOC8", "UCfassTU6mGWIj-yb26_ADjQ"}) {
            assertThrows(IllegalArgumentException.class, () -> UploadsFetcher.url(bad), bad);
        }
    }

    @Test
    void theSourceLanguageIsForcedOnTheWireAndNewPipesExceptionsPassThroughTyped() throws Exception {
        List<Request> browse = new ArrayList<>();
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(Request r) {
                if (r.url().equals("https://www.youtube.com/sw.js")) { // getClientVersion's first source; cached per JVM after
                    return new Response(200, "OK", Map.of(), "\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"2.20260120.01.00\"", r.url());
                }
                if (!r.url().contains("/youtubei/v1/browse")) throw new AssertionError("unexpected request " + r.url());
                browse.add(r);
                return new Response(404, "", Map.of(), "", r.url()); // NewPipe 0.26.5: a plain CNA, never proof of "gone"
            }
        });
        UploadsFetcher fetcher = new UploadsFetcher();

        assertThrows(ContentNotAvailableException.class, () -> fetcher.first("channel:UCfassTU6mGWIj-yb26_ADjQ", "nl"));
        assertEquals(1, browse.size());
        String body = new String(browse.get(0).dataToSend(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"browseId\":\"VLUUfassTU6mGWIj-yb26_ADjQ\""), body);
        assertTrue(body.contains("\"hl\":\"nl\""), body);

        browse.clear();
        Page page = new Page("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(ContentNotAvailableException.class, () -> fetcher.next("playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe", "ar", page));
        assertEquals(1, browse.size());
        assertEquals("ar", browse.get(0).localization().getLanguageCode());
    }
}
