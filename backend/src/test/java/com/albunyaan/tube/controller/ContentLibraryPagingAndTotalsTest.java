package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Field bug: the Content Library stopped at 200 items per type — deeper pages came back empty and
 * {@code totalItems} reported the cap as the library size. Pins the two properties that fix it:
 * a deep page asks Firestore for enough rows to actually fill it, and the registry totals are
 * counted independently of that bound, on their own endpoint.
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

    /** A stand-in registry far larger than the old 200 ceiling. */
    private static final int LIBRARY_SIZE = 900;

    private static List<Channel> channels(int howMany) {
        List<Channel> out = new ArrayList<>(howMany);
        for (int i = 0; i < howMany; i++) {
            Channel c = new Channel("UC" + i);
            c.setId("id-" + i);
            c.setName("Channel " + i);
            c.setStatus("APPROVED");
            // Descending createdAt so the controller's newest-first sort preserves insertion order.
            c.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(LIBRARY_SIZE - i, 0));
            out.add(c);
        }
        return out;
    }

    private void stubChannelCollection() throws Exception {
        when(channelRepository.findAll(anyInt()))
                .thenAnswer(inv -> channels(Math.min(inv.getArgument(0, Integer.class), LIBRARY_SIZE)));
    }

    @Test
    void deepPageIsReachablePastTheOldTwoHundredCeiling() throws Exception {
        stubChannelCollection();

        // Page 15 at size 20 = items 300..319 — unreachable while the fetch stopped at 200.
        ResponseEntity<ContentLibraryController.ContentLibraryResponse> response =
                controller.getContent("channel", "all", null, null, "newest", 15, 20);

        ContentLibraryController.ContentLibraryResponse body = response.getBody();
        assertEquals(20, body.content.size(), "deep page came back short — the fetch bound cut it off");
        assertEquals("id-300", body.content.get(0).id);
        assertEquals("id-319", body.content.get(19).id);
    }

    @Test
    void fetchAsksForEnoughRowsToFillTheRequestedPage() throws Exception {
        stubChannelCollection();

        controller.getContent("channel", "all", null, null, "newest", 15, 20);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(channelRepository).findAll(limit.capture());
        // The invariant the 200-cap violated: the query must cover every row up to the end of the
        // requested page, or that page cannot be served.
        assertTrue(limit.getValue() > 16 * 20,
                "fetch limit " + limit.getValue() + " cannot cover page 15 at size 20");
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
    void theApprovedListingLeavesArchivedRowsToTheArchivedPage() throws Exception {
        Channel live = new Channel("UClive");
        live.setId("c-live");
        live.setStatus("APPROVED");
        Channel archived = new Channel("UCgone");
        archived.setId("c-gone");
        archived.setStatus("APPROVED");
        archived.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
        when(channelRepository.findByStatus(org.mockito.ArgumentMatchers.eq("APPROVED"), anyInt()))
                .thenReturn(List.of(live, archived));

        ContentLibraryController.ContentLibraryResponse body =
                controller.getContent("channel", "approved", null, null, "newest", 0, 20).getBody();

        // Same definition as GET /totals?status=approved, so the list and its header agree.
        assertEquals(1, body.content.size());
        assertEquals("c-live", body.content.get(0).id);
    }

    @Test
    void archivedRowsNeverLeaveAnApprovedPageShort() throws Exception {
        // 60 archived rows ahead of 40 live ones. Page 0 of 20 normally reads 70 rows: dropping the
        // archived would leave 10, and offset paging would then skip live rows 10-19 for good.
        List<Channel> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Channel c = new Channel("UC" + i);
            c.setId(String.format("c%03d", i));
            c.setStatus("APPROVED");
            if (i < 60) c.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
            rows.add(c);
        }
        when(channelRepository.findByStatus(org.mockito.ArgumentMatchers.eq("APPROVED"), anyInt()))
                .thenAnswer(inv -> rows.subList(0, Math.min(rows.size(), (int) inv.getArgument(1))));

        ContentLibraryController.ContentLibraryResponse body =
                controller.getContent("channel", "approved", null, null, "newest", 0, 20).getBody();

        assertEquals(20, body.content.size());
        assertEquals(40, body.totalItems);
        // Deepened by the rows it dropped, not by a registry-wide count on the listing's path.
        org.mockito.Mockito.verify(channelRepository, org.mockito.Mockito.never()).countApprovedArchived();
    }

    @Test
    void anApprovedPageWithNothingArchivedIsOneQuery() throws Exception {
        List<Channel> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            Channel c = new Channel("UC" + i);
            c.setId(String.format("c%03d", i));
            c.setStatus("APPROVED");
            rows.add(c);
        }
        when(channelRepository.findByStatus(org.mockito.ArgumentMatchers.eq("APPROVED"), anyInt()))
                .thenAnswer(inv -> rows.subList(0, Math.min(rows.size(), (int) inv.getArgument(1))));

        controller.getContent("channel", "approved", null, null, "newest", 0, 20);

        org.mockito.Mockito.verify(channelRepository, org.mockito.Mockito.times(1))
                .findByStatus(org.mockito.ArgumentMatchers.eq("APPROVED"), anyInt());
    }

    @Test
    void oneArchivedRowCostsOneMoreRowNotADoubledWindow() throws Exception {
        List<Channel> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            Channel c = new Channel("UC" + i);
            c.setId(String.format("c%03d", i));
            c.setStatus("APPROVED");
            if (i == 0) c.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
            rows.add(c);
        }
        when(channelRepository.findByStatus(org.mockito.ArgumentMatchers.eq("APPROVED"), anyInt()))
                .thenAnswer(inv -> rows.subList(0, Math.min(rows.size(), (int) inv.getArgument(1))));

        controller.getContent("channel", "approved", null, null, "newest", 0, 20);

        // Window 70 (+1 probe) holds one archived row, so the second ask is 71 (+1), not 140.
        org.mockito.Mockito.verify(channelRepository).findByStatus("APPROVED", 72);
    }

    @Test
    void aLongArchivedRunCostsAFewRoundsNotOnePerRow() throws Exception {
        // 1500 archived rows ahead of 100 live ones: linear deepening would re-query ~20 times.
        List<Channel> rows = new ArrayList<>();
        for (int i = 0; i < 1600; i++) {
            Channel c = new Channel("UC" + i);
            c.setId(String.format("c%04d", i));
            c.setStatus("APPROVED");
            if (i < 1500) c.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
            rows.add(c);
        }
        when(channelRepository.findByStatus(org.mockito.ArgumentMatchers.eq("APPROVED"), anyInt()))
                .thenAnswer(inv -> rows.subList(0, Math.min(rows.size(), (int) inv.getArgument(1))));

        ContentLibraryController.ContentLibraryResponse body =
                controller.getContent("channel", "approved", null, null, "newest", 0, 20).getBody();

        assertEquals(20, body.content.size());
        org.mockito.Mockito.verify(channelRepository, org.mockito.Mockito.atMost(6))
                .findByStatus(org.mockito.ArgumentMatchers.eq("APPROVED"), anyInt());
    }

    @Test
    void anApprovedSearchDropsArchivedMatchesBeforeTrimming() throws Exception {
        // 75 archived matches ahead of 30 live ones. Trimming to the 70-row window first would keep
        // only archived rows and report nothing, though the live matches were already fetched.
        List<Channel> matches = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            Channel c = new Channel("UC" + i);
            c.setId(String.format("c%03d", i));
            c.setName("term " + i);
            c.setStatus("APPROVED");
            if (i < 75) c.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
            matches.add(c);
        }
        when(channelRepository.searchByNameLower(org.mockito.ArgumentMatchers.anyString(), anyInt())).thenReturn(matches);
        when(channelRepository.searchByKeyword(org.mockito.ArgumentMatchers.anyString(), anyInt())).thenReturn(List.of());

        ContentLibraryController.ContentLibraryResponse body =
                controller.getContent("channel", "approved", null, "term", "newest", 0, 20).getBody();

        assertEquals(20, body.content.size());
        assertEquals(30, body.totalItems);
        // Archived rows go before the trim, so the search never re-runs its scans to deepen.
        org.mockito.Mockito.verify(channelRepository, org.mockito.Mockito.times(1))
                .searchByNameLower(org.mockito.ArgumentMatchers.anyString(), anyInt());
    }

    @Test
    void aBroadApprovedSearchWithFewLiveMatchesRunsItsScansOnce() throws Exception {
        // The name scan hits its 200-row bound but only 5 rows are approved, so the window is
        // short with hitLimit set. Deepening could never add a match: the scan bound is fixed.
        List<Channel> matches = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Channel c = new Channel("UC" + i);
            c.setId(String.format("c%03d", i));
            c.setName("term " + i);
            c.setStatus(i < 5 ? "APPROVED" : "PENDING");
            matches.add(c);
        }
        when(channelRepository.searchByNameLower(org.mockito.ArgumentMatchers.anyString(), anyInt())).thenReturn(matches);
        when(channelRepository.searchByKeyword(org.mockito.ArgumentMatchers.anyString(), anyInt())).thenReturn(List.of());

        controller.getContent("channel", "approved", null, "term", "newest", 0, 20);

        org.mockito.Mockito.verify(channelRepository, org.mockito.Mockito.times(1))
                .searchByNameLower(org.mockito.ArgumentMatchers.anyString(), anyInt());
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
        stubChannelCollection();

        // Browse page, scroll page and search alike: the counts live on their own endpoint, so a
        // slow or failing aggregation can never delay or fail a listing.
        controller.getContent("channel", "all", null, null, "newest", 0, 20);
        controller.getContent("channel", "all", null, null, "newest", 1, 20);
        controller.getContent("channel", "approved", null, null, "newest", 0, 20);

        org.mockito.Mockito.verify(channelRepository, org.mockito.Mockito.never()).countAll();
        org.mockito.Mockito.verify(playlistRepository, org.mockito.Mockito.never()).countAll();
        org.mockito.Mockito.verify(videoRepository, org.mockito.Mockito.never()).countAll();
        org.mockito.Mockito.verify(channelRepository, org.mockito.Mockito.never()).countApprovedArchived();
    }

    @Test
    void searchScanStaysBoundedIndependentlyOfTheBrowseCeiling() throws Exception {
        when(channelRepository.searchByKeyword(org.mockito.ArgumentMatchers.anyString(), anyInt()))
                .thenReturn(List.of());
        when(channelRepository.searchByNameLower(org.mockito.ArgumentMatchers.anyString(), anyInt()))
                .thenReturn(List.of());

        controller.getContent("channel", "all", null, "history", "newest", 0, 20);

        // Search runs on every debounced keystroke and issues two queries per type at a fixed
        // limit, so it must not inherit the (much higher) browse ceiling.
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(channelRepository)
                .searchByKeyword(org.mockito.ArgumentMatchers.anyString(), limit.capture());
        assertTrue(limit.getValue() <= 200,
                "search scan limit " + limit.getValue() + " tracks the browse ceiling");
    }

}
