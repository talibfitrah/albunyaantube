package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "add content to a category" picker. It loaded at most 2000 approved items per type in one
 * request and dropped personal grants, so its total could not equal the Library's approved total.
 * It now pages the approved listing by cursor with no ceiling, and flags personal grants instead
 * of hiding them.
 */
@ExtendWith(MockitoExtension.class)
class ApprovedPickerTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private UserRepository userRepository;

    private final RegistryFake registry = new RegistryFake();
    private ContentLibraryController controller;

    @BeforeEach
    void setUp() throws Exception {
        registry.stub(channelRepository, playlistRepository, videoRepository);
        controller = new ContentLibraryController(channelRepository, playlistRepository, videoRepository,
                null, null, null, null, null, null, userRepository, null, null, null);
    }

    private Channel channel(int i, String status) {
        Channel c = new Channel("UC" + i);
        c.setId("c" + i);
        c.setName("Channel " + i);
        c.setStatus(status);
        c.setThumbnailUrl("https://img/c" + i);
        c.setCreatedAt(RegistryFake.at(i));
        registry.channels.add(c);
        return c;
    }

    private Playlist playlist(int i) {
        Playlist p = new Playlist();
        p.setId("p" + i);
        p.setYoutubeId("PL" + i);
        p.setTitle("Playlist " + i);
        p.setStatus("APPROVED");
        p.setCreatedAt(RegistryFake.at(i));
        registry.playlists.add(p);
        return p;
    }

    private Video video(int i) {
        Video v = new Video("yt" + i);
        v.setId("v" + i);
        v.setTitle("Video " + i);
        v.setStatus("APPROVED");
        v.setCreatedAt(RegistryFake.at(i));
        registry.videos.add(v);
        return v;
    }

    /** Follows nextCursor to the end, as the picker does. */
    private List<ContentLibraryController.ApprovedPickerItem> all() throws Exception {
        List<ContentLibraryController.ApprovedPickerItem> items = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ContentLibraryController.ApprovedPickerResponse body = controller.getApprovedForPicker(cursor).getBody();
            items.addAll(body.items);
            cursor = body.nextCursor;
            assertTrue(++pages < 1_000, "cursor never ran out");
        } while (cursor != null);
        return items;
    }

    @Test
    void listsEveryApprovedItemWithNoCeiling() throws Exception {
        for (int i = 0; i < 2600; i++) channel(i, "APPROVED");   // the old bound was 2000 per type
        for (int i = 0; i < 40; i++) channel(5000 + i, "PENDING");
        for (int i = 0; i < 7; i++) playlist(i);
        for (int i = 0; i < 1300; i++) video(i);

        List<ContentLibraryController.ApprovedPickerItem> items = all();

        assertEquals(2600 + 7 + 1300, items.size());
        assertEquals(items.size(), items.stream().map(i -> i.type + ":" + i.id).distinct().count());
    }

    @Test
    void countsWhatTheLibraryCountsAsApproved() throws Exception {
        // Live only, as GET /totals?status=approved: archived rows belong to the Archived page.
        for (int i = 0; i < 10; i++) channel(i, "APPROVED");
        channel(20, "APPROVED").setValidationStatus(ValidationStatus.ARCHIVED);
        channel(21, "APPROVED").setValidationStatus(ValidationStatus.UNAVAILABLE);
        channel(22, "REJECTED");

        assertEquals(10, all().size());
    }

    @Test
    void flagsPersonalGrantsInsteadOfHidingThem() throws Exception {
        // In the total, so it matches the Library; the picker says why it cannot add them.
        channel(1, "APPROVED");
        Channel personal = channel(2, "APPROVED");
        personal.setVisibility("PERSONAL");
        personal.setPersonalGrants(List.of("uid-ahmed"));

        Map<String, String> visibility = all().stream().collect(Collectors.toMap(i -> i.id, i -> i.visibility));

        assertEquals(Map.of("c1", "PUBLIC", "c2", "PERSONAL"), visibility);
    }

    @Test
    void carriesTheFieldsThePickerDraws() throws Exception {
        channel(1, "APPROVED");

        ContentLibraryController.ApprovedPickerItem item = all().get(0);

        // getThumbnailUrl() reads thumbnailUrl first and otherwise builds a URL from youtubeId,
        // so both are load-bearing.
        assertEquals("c1", item.id);
        assertEquals("channel", item.type);
        assertEquals("Channel 1", item.title);
        assertEquals("https://img/c1", item.thumbnailUrl);
        assertEquals("UC1", item.youtubeId);
    }
}
