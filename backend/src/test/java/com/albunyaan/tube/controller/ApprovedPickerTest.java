package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The "add content to a category" picker loaded its options by paging {@code /admin/content} in a
 * loop. That loop could not terminate on its own: the server's {@code totalPages} describes the
 * current fetch window, which grows by one page per content type each round, so the finish line
 * outran the loop and it ran to its own safety cap. Offset paging also re-read every earlier row
 * on every request, and each record carried a description and category list the picker never drew.
 *
 * <p>This endpoint answers the question the picker actually asks — "what can I add?" — in one
 * request, carrying only the fields it renders.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovedPickerTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private com.google.cloud.firestore.Firestore firestore;
    @Mock private com.albunyaan.tube.config.FirestoreTimeoutProperties timeoutProperties;
    @Mock private com.albunyaan.tube.service.PublicContentCacheService publicContentCacheService;
    @Mock private com.albunyaan.tube.service.SortOrderService sortOrderService;
    @Mock private com.albunyaan.tube.service.TagEnrichmentService tagEnrichmentService;
    @Mock private com.albunyaan.tube.service.ImportGraduationService importGraduationService;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private ContentLibraryController controller;

    private static Channel channel(String id) {
        Channel c = new Channel("UC" + id);
        c.setId(id);
        c.setName("Channel " + id);
        c.setStatus("APPROVED");
        c.setThumbnailUrl("https://img/" + id);
        c.setDescription("a long description the picker never renders");
        c.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(1000, 0));
        return c;
    }

    private static Playlist playlist(String id) {
        Playlist p = new Playlist();
        p.setId(id);
        p.setYoutubeId("PL" + id);
        p.setTitle("Playlist " + id);
        p.setStatus("APPROVED");
        p.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(1000, 0));
        return p;
    }

    private static Video video(String id) {
        Video v = new Video("yt" + id);
        v.setId(id);
        v.setTitle("Video " + id);
        v.setStatus("APPROVED");
        v.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(1000, 0));
        return v;
    }

    private ContentLibraryController.ApprovedPickerResponse fetch() throws Exception {
        return controller.getApprovedForPicker().getBody();
    }

    @Test
    void returnsEveryApprovedItemOfEveryTypeInOneRequest() throws Exception {
        when(channelRepository.findByStatus(eq("APPROVED"), anyInt())).thenReturn(List.of(channel("c1"), channel("c2")));
        when(playlistRepository.findByStatus(eq("APPROVED"), anyInt())).thenReturn(List.of(playlist("p1")));
        when(videoRepository.findByStatus(eq("APPROVED"), anyInt())).thenReturn(List.of(video("v1")));

        ContentLibraryController.ApprovedPickerResponse body = fetch();

        assertEquals(4, body.items.size());
        assertFalse(body.truncated);
    }

    @Test
    void carriesTheFieldsThePickerDraws() throws Exception {
        when(channelRepository.findByStatus(eq("APPROVED"), anyInt())).thenReturn(List.of(channel("c1")));

        ContentLibraryController.ApprovedPickerItem item = fetch().items.get(0);

        // getThumbnailUrl() reads thumbnailUrl first and otherwise builds a URL from youtubeId,
        // so both are load-bearing — a trim to id/type/title would strip every thumbnail.
        assertEquals("c1", item.id);
        assertEquals("channel", item.type);
        assertEquals("Channel c1", item.title);
        assertEquals("https://img/c1", item.thumbnailUrl);
        assertEquals("UCc1", item.youtubeId);
    }

    @Test
    void leavesOutContentThatIsNotApproved() throws Exception {
        // Belt and braces: the query filters on status, and the projection re-checks it, so a row
        // that slips through a stale index still cannot reach the picker.
        Channel pending = channel("c-pending");
        pending.setStatus("PENDING");
        when(channelRepository.findByStatus(eq("APPROVED"), anyInt())).thenReturn(List.of(channel("c-ok"), pending));

        ContentLibraryController.ApprovedPickerResponse body = fetch();

        assertEquals(1, body.items.size());
        assertEquals("c-ok", body.items.get(0).id);
    }

    @Test
    void leavesOutContentApprovedOnlyForSpecificPeople() throws Exception {
        // A PERSONAL grant is not catalogue content. Filing one into a category's sort order
        // would put an entry there that the public feed will never render.
        Channel personal = channel("c-personal");
        personal.setVisibility("PERSONAL");
        personal.setPersonalGrants(List.of("uid-ahmed"));
        when(channelRepository.findByStatus(eq("APPROVED"), anyInt())).thenReturn(List.of(channel("c-public"), personal));

        ContentLibraryController.ApprovedPickerResponse body = fetch();

        assertEquals(1, body.items.size());
        assertEquals("c-public", body.items.get(0).id);
    }

    @Test
    void saysSoWhenTheRegistryIsDeeperThanTheScan() throws Exception {
        // A silent cap would present a partial list as the whole library, which is the failure
        // the old loop's truncated flag existed to avoid.
        when(channelRepository.findByStatus(eq("APPROVED"), anyInt())).thenReturn(channels(2001));

        ContentLibraryController.ApprovedPickerResponse body = fetch();

        assertTrue(body.truncated);
        assertEquals(2000, body.items.size(), "the probe row must not be served as content");
    }

    @Test
    void doesNotCryTruncationWhenTheLibraryIsExactlyTheBound() throws Exception {
        // Warning an admin that a complete list is partial is its own bug — it sends them
        // looking for content that is already all there.
        when(channelRepository.findByStatus(eq("APPROVED"), anyInt())).thenReturn(channels(2000));

        ContentLibraryController.ApprovedPickerResponse body = fetch();

        assertFalse(body.truncated);
        assertEquals(2000, body.items.size());
    }

    private static List<Channel> channels(int howMany) {
        List<Channel> many = new ArrayList<>(howMany);
        for (int i = 0; i < howMany; i++) {
            many.add(channel("c" + i));
        }
        return many;
    }
}
