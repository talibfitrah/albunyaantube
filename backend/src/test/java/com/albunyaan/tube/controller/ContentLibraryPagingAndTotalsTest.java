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

        ContentLibraryController.RegistryTotals totals = controller.getTotals().getBody();

        // Registry-wide, from aggregation — never derived from a bounded, filtered listing.
        assertEquals(4321L, totals.channels);
        assertEquals(87L, totals.playlists);
        assertEquals(65L, totals.videos);
    }

    @Test
    void theListingNeverPaysForTheCounts() throws Exception {
        stubChannelCollection();

        // Browse page, scroll page and search alike: the counts live on their own endpoint, so a
        // slow or failing aggregation can never delay or fail a listing.
        controller.getContent("channel", "all", null, null, "newest", 0, 20);
        controller.getContent("channel", "all", null, null, "newest", 1, 20);

        org.mockito.Mockito.verify(channelRepository, org.mockito.Mockito.never()).countAll();
        org.mockito.Mockito.verify(playlistRepository, org.mockito.Mockito.never()).countAll();
        org.mockito.Mockito.verify(videoRepository, org.mockito.Mockito.never()).countAll();
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
