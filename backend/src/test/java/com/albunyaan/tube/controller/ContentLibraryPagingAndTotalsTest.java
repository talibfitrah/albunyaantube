package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Field bugs: the Content Library stopped at a per-type ceiling (200, later 2000) and warned
 * "results may be incomplete" on every unfiltered view. Pins what fixes it: browsing follows a
 * per-type cursor to the last row with no ceiling, each page reads only its own rows, and the
 * registry totals are counted independently, on their own endpoint.
 */
@ExtendWith(MockitoExtension.class)
class ContentLibraryPagingAndTotalsTest {

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private PlaylistRepository playlistRepository;
    @Mock
    private VideoRepository videoRepository;
    @Mock
    private com.google.cloud.firestore.Firestore firestore;
    @Mock
    private com.albunyaan.tube.config.FirestoreTimeoutProperties timeoutProperties;
    @Mock
    private com.albunyaan.tube.service.PublicContentCacheService publicContentCacheService;
    @Mock
    private com.albunyaan.tube.service.SortOrderService sortOrderService;
    @Mock
    private com.albunyaan.tube.service.TagEnrichmentService tagEnrichmentService;
    @Mock
    private com.albunyaan.tube.service.LiveCategoryGuard liveCategoryGuard;

    @InjectMocks
    private ContentLibraryController controller;

    private final RegistryFake registry = new RegistryFake();
    private final List<Channel> channelRows = registry.channels;
    private final List<Playlist> playlistRows = registry.playlists;
    private final List<Video> videoRows = registry.videos;

    private void stubRegistry() throws Exception {
        registry.stub(channelRepository, playlistRepository, videoRepository);
    }

    private static Timestamp at(int i) {
        return RegistryFake.at(i);
    }

    private static <T> Comparator<T> firestoreOrder(Function<T, Timestamp> at, Function<T, String> id, boolean oldest) {
        return RegistryFake.order(at, id, oldest);
    }

    private void seed(int channels, int playlists, int videos) {
        for (int i = 0; i < channels; i++) {
            Channel c = new Channel("UC" + i);
            c.setId(String.format("c%05d", i));
            c.setStatus(i % 4 == 0 ? "PENDING" : "APPROVED");
            c.setCreatedAt(at(i));
            channelRows.add(c);
        }
        for (int i = 0; i < playlists; i++) {
            Playlist p = new Playlist("PL" + i);
            p.setId(String.format("p%05d", i));
            p.setStatus("APPROVED");
            p.setCreatedAt(at(i * 7));
            playlistRows.add(p);
        }
        for (int i = 0; i < videos; i++) {
            Video v = new Video("v" + i);
            v.setId(String.format("v%05d", i));
            v.setStatus("PENDING");
            v.setCreatedAt(at(i * 2));
            videoRows.add(v);
        }
    }

    /** Follows nextCursor to the end, as the Library's Load More does. */
    private List<ContentLibraryController.ContentItem> walk(String types, String status, String sort, int size) throws Exception {
        List<ContentLibraryController.ContentItem> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ContentLibraryController.ContentLibraryResponse body =
                    controller.getContent(types, status, null, null, sort, cursor, size).getBody();
            assertTrue(body.content.size() <= size);
            all.addAll(body.content);
            cursor = body.nextCursor;
            assertTrue(++pages < 10_000, "cursor never ran out");
        } while (cursor != null);
        return all;
    }

    @Test
    void loadMoreReachesEveryRowWithNoCeiling() throws Exception {
        // 2500 channels: past the old 2000-per-type browse ceiling.
        seed(2500, 30, 700);
        stubRegistry();

        List<ContentLibraryController.ContentItem> all = walk(null, "all", "newest", 100);

        assertEquals(3230, all.size());
        assertEquals(3230, all.stream().map(i -> i.type + ":" + i.id).distinct().count(), "a row was served twice");
        for (int i = 1; i < all.size(); i++) {
            assertFalse(all.get(i).createdAt.after(all.get(i - 1).createdAt), "out of order at " + i);
        }
    }

    @Test
    void oldestFirstWalksTheSameRowsAscending() throws Exception {
        seed(250, 30, 70);
        stubRegistry();

        List<ContentLibraryController.ContentItem> all = walk(null, "all", "oldest", 25);

        assertEquals(350, all.size());
        assertEquals(350, all.stream().map(i -> i.type + ":" + i.id).distinct().count());
        for (int i = 1; i < all.size(); i++) {
            assertFalse(all.get(i).createdAt.before(all.get(i - 1).createdAt), "out of order at " + i);
        }
    }

    @Test
    void aStatusListingWalksOnlyThatStatus() throws Exception {
        seed(400, 30, 70);
        stubRegistry();

        // Pending: every fourth channel and every video, as GET /totals?status=pending counts them.
        assertEquals(100 + 70, walk(null, "pending", "newest", 25).size());
    }

    @Test
    void eachPageReadsAtMostOnePagePerType() throws Exception {
        seed(2500, 30, 700);
        stubRegistry();

        controller.getContent(null, "all", null, null, "newest", null, 25);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(channelRepository).findPageAfter(any(), any(), anyBoolean(), any(), any(), limit.capture());
        // Cursor paging: a page costs its own rows, never the prefix before it.
        assertTrue(limit.getValue() <= 26, "read " + limit.getValue() + " rows for a 25-row page");
    }

    @Test
    void aPageEdgeInsideOneInstantSkipsNothing() throws Exception {
        // One bulk import: 60 channels with the same createdAt, so page edges fall inside the run.
        for (int i = 0; i < 60; i++) {
            Channel c = new Channel("UC" + i);
            c.setId(String.format("c%05d", i));
            c.setStatus("APPROVED");
            c.setCreatedAt(at(0));
            channelRows.add(c);
        }
        stubRegistry();

        List<ContentLibraryController.ContentItem> all = walk("channel", "all", "newest", 25);

        assertEquals(60, all.size());
        assertEquals(60, all.stream().map(i -> i.id).distinct().count(), "a row was served twice");
    }

    @Test
    void aDeletedRowAtThePageEdgeSkipsNothing() throws Exception {
        seed(120, 0, 0);
        stubRegistry();
        ContentLibraryController.ContentLibraryResponse first =
                controller.getContent("channel", "all", null, null, "newest", null, 25).getBody();
        String lastId = first.content.get(24).id;
        String nextId = channelRows.stream().sorted(firestoreOrder(Channel::getCreatedAt, Channel::getId, false))
                .map(Channel::getId).toList().get(25);

        channelRows.removeIf(c -> c.getId().equals(lastId));
        ContentLibraryController.ContentLibraryResponse second =
                controller.getContent("channel", "all", null, null, "newest", first.nextCursor, 25).getBody();

        assertEquals(nextId, second.content.get(0).id);
    }

    @Test
    void theApprovedListingLeavesArchivedRowsToTheArchivedPage() throws Exception {
        // 60 archived rows ahead of 40 live ones: every live row is listed, no archived one is.
        for (int i = 0; i < 100; i++) {
            Channel c = new Channel("UC" + i);
            c.setId(String.format("c%03d", i));
            c.setStatus("APPROVED");
            c.setCreatedAt(at(1000 - i));
            if (i < 60) c.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
            channelRows.add(c);
        }
        stubRegistry();

        ContentLibraryController.ContentLibraryResponse first =
                controller.getContent("channel", "approved", null, null, "newest", null, 20).getBody();
        List<ContentLibraryController.ContentItem> all = walk("channel", "approved", "newest", 20);

        // Same definition as GET /totals?status=approved, so the list and its header agree.
        assertEquals(20, first.content.size(), "archived rows left the first page short");
        assertEquals(40, all.size());
        assertTrue(all.stream().allMatch(i -> Integer.parseInt(i.id.substring(1)) >= 60));
    }

    @Test
    void archivedRowsAreReadOnceNotOnEveryPage() throws Exception {
        // 300 archived channels, newer than every live channel, while newer videos fill the first
        // pages: the channel cursor must move past the archived run, not re-read it each page.
        for (int i = 0; i < 330; i++) {
            Channel c = new Channel("UC" + i);
            c.setId(String.format("c%05d", i));
            c.setStatus("APPROVED");
            c.setCreatedAt(at(i < 30 ? i : 470 + i));
            if (i >= 30) c.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
            channelRows.add(c);
        }
        for (int i = 0; i < 200; i++) {
            Video v = new Video("v" + i);
            v.setId(String.format("v%05d", i));
            v.setStatus("APPROVED");
            v.setCreatedAt(at(1000 + i));
            videoRows.add(v);
        }
        stubRegistry();

        assertEquals(230, walk(null, "approved", "newest", 25).size());

        // Ten pages: the archived run once, then at most one page of channels each.
        assertTrue(registry.channelRowsRead <= 300 + 10 * 26, "read " + registry.channelRowsRead + " channel rows");
    }

    @Test
    void aMalformedCursorIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.getContent(null, "all", null, null, "newest", "not-a-cursor", 20));
    }

    @Test
    void aCursorOfJsonNullIsRefused() {
        // "bnVsbA" is base64url for the JSON literal null: well-formed, but no cursor.
        assertThrows(IllegalArgumentException.class,
                () -> controller.getContent(null, "all", null, null, "newest", "bnVsbA", 20));
    }

    @Test
    void anUnknownTypeEndsTheListing() throws Exception {
        seed(30, 0, 0);
        stubRegistry();

        // Was an empty page whose cursor led back to itself, forever.
        assertNull(controller.getContent("bogus", "all", null, null, "newest", null, 20).getBody().nextCursor);
        assertEquals(30, walk("channel,bogus", "all", "newest", 20).size());
    }

    @Test
    void totalsAreCountedIndependentlyOfTheListing() throws Exception {
        when(channelRepository.countAll()).thenReturn(4321L);
        when(playlistRepository.countAll()).thenReturn(87L);
        when(videoRepository.countAll()).thenReturn(65L);

        ContentLibraryController.RegistryTotals totals = controller.getTotals("all").getBody();

        // Registry-wide, from aggregation — never derived from a bounded, filtered listing.
        assertEquals(4321L, totals.channels);
        assertEquals(87L, totals.playlists);
        assertEquals(65L, totals.videos);
    }

    @Test
    void totalsFollowTheStatusFilter() throws Exception {
        when(channelRepository.countByStatus("APPROVED")).thenReturn(900L);
        when(playlistRepository.countByStatus("APPROVED")).thenReturn(40L);
        when(videoRepository.countByStatus("APPROVED")).thenReturn(30L);
        when(channelRepository.countApprovedArchived()).thenReturn(2L);
        when(playlistRepository.countApprovedArchived()).thenReturn(8L);
        when(channelRepository.countByStatus("PENDING")).thenReturn(313L);

        ContentLibraryController.RegistryTotals totals = controller.getTotals("approved").getBody();

        // Approved = live: archived rows keep status APPROVED but are counted on the Archived page.
        assertEquals(898L, totals.channels);
        assertEquals(32L, totals.playlists);
        assertEquals(30L, totals.videos);
        // The stored status is upper-case; the listing's filter values are lower-case.
        assertEquals(313L, controller.getTotals("pending").getBody().channels);
        org.mockito.Mockito.verify(channelRepository, org.mockito.Mockito.never()).countAll();
    }

    @Test
    void totalsRejectAStatusTheFilterDoesNotOffer() throws Exception {
        assertEquals(400, controller.getTotals("archived").getStatusCode().value());
        assertEquals(400, controller.getTotals("").getStatusCode().value());
    }

    @Test
    void rejectedTotalsCountEveryRejectedDoc() throws Exception {
        when(videoRepository.countByStatus("REJECTED")).thenReturn(1L);

        assertEquals(1L, controller.getTotals("rejected").getBody().videos);
    }

    @Test
    void approvedTotalsNeverGoNegativeWhenTheTwoReadsStraddleAChange() throws Exception {
        when(channelRepository.countByStatus("APPROVED")).thenReturn(0L);
        when(channelRepository.countApprovedArchived()).thenReturn(1L);

        assertEquals(0L, controller.getTotals("approved").getBody().channels);
    }

    @Test
    void theListingNeverPaysForTheCounts() throws Exception {
        seed(100, 0, 0);
        stubRegistry();

        // First page, next page and approved page alike: the counts live on their own endpoint,
        // so a slow or failing aggregation can never delay or fail a listing.
        String next = controller.getContent("channel", "all", null, null, "newest", null, 20).getBody().nextCursor;
        controller.getContent("channel", "all", null, null, "newest", next, 20);
        controller.getContent("channel", "approved", null, null, "newest", null, 20);

        org.mockito.Mockito.verify(channelRepository, org.mockito.Mockito.never()).countAll();
        org.mockito.Mockito.verify(playlistRepository, org.mockito.Mockito.never()).countAll();
        org.mockito.Mockito.verify(videoRepository, org.mockito.Mockito.never()).countAll();
        org.mockito.Mockito.verify(channelRepository, org.mockito.Mockito.never()).countApprovedArchived();
    }

}
