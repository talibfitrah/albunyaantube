package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.search.CatalogChanged;
import com.albunyaan.tube.service.LibrarySearchSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Library search scanned two Firestore queries of 200 per type (a name prefix and an exact
 * keyword) and warned "may be incomplete" when either filled. It now matches every registry row
 * of every status, in memory, kept fresh by the catalog write events, and pages by cursor.
 */
@ExtendWith(MockitoExtension.class)
class LibrarySearchTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private UserRepository userRepository;

    private final RegistryFake registry = new RegistryFake();
    private LibrarySearchSnapshot snapshot;
    private ContentLibraryController controller;

    @BeforeEach
    void setUp() throws Exception {
        registry.stub(channelRepository, playlistRepository, videoRepository);
        snapshot = new LibrarySearchSnapshot(channelRepository, playlistRepository, videoRepository);
        controller = new ContentLibraryController(channelRepository, playlistRepository, videoRepository,
                null, null, null, null, null, null, userRepository, null, null, snapshot);
    }

    private Channel channel(int i, String name, String status) {
        Channel c = new Channel("UC" + i);
        c.setId(String.format("c%05d", i));
        c.setName(name);
        c.setStatus(status);
        c.setCreatedAt(RegistryFake.at(i));
        registry.channels.add(c);
        return c;
    }

    private Video video(int i, String title, String status) {
        Video v = new Video("v" + i);
        v.setId(String.format("v%05d", i));
        v.setTitle(title);
        v.setStatus(status);
        v.setCreatedAt(RegistryFake.at(i * 2));
        registry.videos.add(v);
        return v;
    }

    private List<ContentLibraryController.ContentItem> search(String status, String term) throws Exception {
        List<ContentLibraryController.ContentItem> all = new ArrayList<>();
        String cursor = null;
        do {
            ContentLibraryController.ContentLibraryResponse body =
                    controller.getContent(null, status, null, term, "newest", cursor, 25).getBody();
            assertTrue(body.content.size() <= 25);
            all.addAll(body.content);
            cursor = body.nextCursor;
        } while (cursor != null);
        return all;
    }

    @Test
    void findsEveryMatchOfEveryStatusWithNoScanBound() throws Exception {
        String[] statuses = {"APPROVED", "PENDING", "REJECTED"};
        for (int i = 0; i < 700; i++) channel(i, "Lecture " + i, statuses[i % 3]);
        for (int i = 0; i < 500; i++) video(i, "Friday lecture " + i, statuses[i % 3]);
        for (int i = 700; i < 900; i++) channel(i, "Recitation " + i, "APPROVED");

        List<ContentLibraryController.ContentItem> found = search("all", "lecture");

        // The old scans stopped at 200 per query and type.
        assertEquals(1200, found.size());
        assertEquals(1200, found.stream().map(i -> i.type + ":" + i.id).distinct().count());
        for (int i = 1; i < found.size(); i++) {
            assertFalse(found.get(i).createdAt.after(found.get(i - 1).createdAt), "out of order at " + i);
        }
    }

    @Test
    void matchesInsideTitlesDescriptionsAndKeywords() throws Exception {
        channel(1, "Seerah series", "PENDING");                       // inside the title, not a prefix
        channel(2, "Untitled", "PENDING").setDescription("A full seerah course");
        channel(3, "Untitled", "PENDING").setKeywords(List.of("history", "SEERAH of the prophet"));
        channel(4, "Unrelated", "PENDING");

        assertEquals(3, search("all", "seerah").size());
        assertEquals(3, search("pending", "SEERAH").size());
        assertEquals(0, search("approved", "seerah").size());
    }

    @Test
    void anApprovedSearchListsLiveContentOnly() throws Exception {
        for (int i = 0; i < 30; i++) channel(i, "Tafsir " + i, "APPROVED")
                .setValidationStatus(i < 10 ? ValidationStatus.ARCHIVED : null);

        // Same definition as the approved browse listing and GET /totals?status=approved.
        assertEquals(20, search("approved", "tafsir").size());
    }

    @Test
    void readsTheRegistryOnceNotPerKeystroke() throws Exception {
        channel(1, "Fiqh", "APPROVED");

        search("all", "f");
        search("all", "fi");
        search("all", "fiq");

        // One page per repository loads the snapshot (fewer than 500 rows each); then nothing.
        verify(channelRepository, times(1)).findPageAfter(any(), any(), anyBoolean(), any(), any(), anyInt());
        verify(playlistRepository, times(1)).findPageAfter(any(), any(), anyBoolean(), any(), any(), anyInt());
        verify(videoRepository, times(1)).findPageAfter(any(), any(), anyBoolean(), any(), any(), anyInt());
    }

    @Test
    void aReloadDuringALoadIsNotLost() throws Exception {
        channel(1, "Sirah", "APPROVED");
        // The six-hourly reload fires while the first search is still reading playlists.
        boolean[] fired = {false};
        when(playlistRepository.findPageAfter(any(), any(), anyBoolean(), any(), any(), anyInt())).thenAnswer(inv -> {
            if (!fired[0]) {
                fired[0] = true;
                snapshot.reload();
            }
            return List.of();
        });

        search("all", "sirah");
        search("all", "sirah");

        // The second search honours that reload: what the first one read may predate it.
        verify(playlistRepository, times(2)).findPageAfter(any(), any(), anyBoolean(), any(), any(), anyInt());
    }

    @Test
    void aWriteIsFoundByTheNextSearch() throws Exception {
        Channel c = channel(1, "Old name", "PENDING");
        assertEquals(0, search("all", "aqeedah").size());

        c.setName("Aqeedah basics");
        snapshot.onCatalogChanged(new CatalogChanged("CHANNEL", c.getId(), false));
        assertEquals(1, search("all", "aqeedah").size());

        registry.channels.remove(c);
        snapshot.onCatalogChanged(new CatalogChanged("CHANNEL", c.getId(), false));
        assertEquals(0, search("all", "aqeedah").size());
    }

    @Test
    void aNewDocumentIsFoundByTheNextSearch() throws Exception {
        channel(1, "Hadith", "APPROVED");
        assertEquals(1, search("all", "hadith").size());

        Channel added = channel(2, "Hadith sciences", "PENDING");
        snapshot.onCatalogChanged(new CatalogChanged("CHANNEL", added.getId(), false));

        assertEquals(2, search("all", "hadith").size());
    }

    @Test
    void aPageCarriesTheDocumentAsItIsNow() throws Exception {
        Channel c = channel(1, "Tajweed", "APPROVED");
        search("all", "tajweed");

        // A field the search does not match on, written by a path that publishes no event
        // (the reorder batch): the page is read from Firestore, so it is current anyway.
        c.setDisplayOrder(7);

        assertEquals(7, search("all", "tajweed").get(0).displayOrder);
    }
}
