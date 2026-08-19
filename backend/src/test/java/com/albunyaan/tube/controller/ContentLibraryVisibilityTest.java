package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.User;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * "Approved" in the Content Library hid a real distinction: an item approved for everyone and an
 * item approved only for the person who imported it both read as APPROVED, so an admin could not
 * tell a public catalogue entry from a personal grant.
 *
 * <p>Pins that the listing carries the visibility, and names the people a personal grant covers —
 * an admin cannot act on a uid.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentLibraryVisibilityTest {

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

    private static Video video(String id, String visibility, List<String> grants) {
        Video v = new Video("yt-" + id);
        v.setId(id);
        v.setTitle("Video " + id);
        v.setStatus("APPROVED");
        v.setVisibility(visibility);
        v.setPersonalGrants(grants);
        v.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(1_000, 0));
        return v;
    }

    private static User user(String uid, String displayName, String email) {
        User u = new User();
        u.setUid(uid);
        u.setDisplayName(displayName);
        u.setEmail(email);
        return u;
    }

    private ContentLibraryController.ContentItem firstItem() throws Exception {
        return controller.getContent("video", "all", null, null, "newest", 0, 20)
                .getBody().content.get(0);
    }

    @Test
    void aPersonallyApprovedItemNamesWhoItWasApprovedFor() throws Exception {
        when(videoRepository.findAll(anyInt()))
                .thenReturn(List.of(video("v1", "PERSONAL", List.of("uid-ahmed"))));
        when(userRepository.findByUid("uid-ahmed"))
                .thenReturn(Optional.of(user("uid-ahmed", "Ahmed", "ahmed@example.com")));

        ContentLibraryController.ContentItem item = firstItem();

        assertEquals("PERSONAL", item.visibility);
        assertEquals(List.of("Ahmed"), item.grantedTo);
    }

    @Test
    void aPubliclyApprovedItemCarriesNoGrantees() throws Exception {
        // Stale grants survive a personal→public promotion in Firestore. A public item is for
        // everyone, so listing grantees for it would state a restriction that does not exist.
        when(videoRepository.findAll(anyInt()))
                .thenReturn(List.of(video("v2", "PUBLIC", List.of("uid-stale"))));

        ContentLibraryController.ContentItem item = firstItem();

        assertEquals("PUBLIC", item.visibility);
        assertTrue(item.grantedTo.isEmpty());
    }

    @Test
    void aLegacyItemWithNoStoredVisibilityReadsAsPublic() throws Exception {
        // Docs written before personal approval existed have no visibility field. They were
        // public, and must not be presented as a restricted grant.
        when(videoRepository.findAll(anyInt()))
                .thenReturn(List.of(video("v3", null, null)));

        ContentLibraryController.ContentItem item = firstItem();

        assertEquals("PUBLIC", item.visibility);
        assertTrue(item.grantedTo.isEmpty());
    }

    @Test
    void aGranteeWhoCannotBeResolvedStillShowsSomething() throws Exception {
        // A deleted account must not render as a blank chip — fall back to the raw uid so the
        // admin can still see that a grant exists and to whom.
        when(videoRepository.findAll(anyInt()))
                .thenReturn(List.of(video("v4", "PERSONAL", List.of("uid-ghost"))));
        when(userRepository.findByUid("uid-ghost")).thenReturn(Optional.empty());

        ContentLibraryController.ContentItem item = firstItem();

        assertEquals(List.of("uid-ghost"), item.grantedTo);
    }

    @Test
    void aGranteeWithNoDisplayNameIsNamedByEmail() throws Exception {
        when(videoRepository.findAll(anyInt()))
                .thenReturn(List.of(video("v5", "PERSONAL", List.of("uid-noname"))));
        when(userRepository.findByUid("uid-noname"))
                .thenReturn(Optional.of(user("uid-noname", null, "quiet@example.com")));

        ContentLibraryController.ContentItem item = firstItem();

        assertEquals(List.of("quiet@example.com"), item.grantedTo);
    }
}
