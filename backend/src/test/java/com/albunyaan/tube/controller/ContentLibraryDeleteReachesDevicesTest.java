package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.ImportGraduationService;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteBatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting content from the library removed the registry entry and told nobody. Anyone who had
 * subscribed to it, saved it or imported it kept a working copy on their phone: the sync pull
 * hands back the row's stored state, and the archive projection only covers entries that were
 * archived — a deleted one is simply absent, which the pull treats as "not gated".
 *
 * <p>Archiving already reaches devices. Deleting has to as well, or the two admin actions that
 * both mean "this is no longer part of the library" behave differently on the one screen that
 * matters.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentLibraryDeleteReachesDevicesTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private Firestore firestore;
    @Mock private com.albunyaan.tube.config.FirestoreTimeoutProperties timeoutProperties;
    @Mock private com.albunyaan.tube.service.PublicContentCacheService publicContentCacheService;
    @Mock private com.albunyaan.tube.service.SortOrderService sortOrderService;
    @Mock private com.albunyaan.tube.service.TagEnrichmentService tagEnrichmentService;
    @Mock private ImportGraduationService importGraduationService;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private ContentLibraryController controller;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void stubDeletableVideo(String id, String youtubeId) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@test", null, List.of()));

        Video v = new Video(youtubeId);
        v.setId(id);
        when(videoRepository.findById(id)).thenReturn(Optional.of(v));

        CollectionReference coll = mock(CollectionReference.class);
        when(firestore.collection("videos")).thenReturn(coll);
        when(coll.document(id)).thenReturn(mock(DocumentReference.class));

        WriteBatch batch = mock(WriteBatch.class);
        when(firestore.batch()).thenReturn(batch);
        when(batch.commit()).thenReturn(ApiFutures.immediateFuture(List.of()));
    }

    private static ContentLibraryController.BulkActionRequest request(String type, String id) {
        ContentLibraryController.BulkActionItem item = new ContentLibraryController.BulkActionItem();
        item.type = type;
        item.id = id;
        ContentLibraryController.BulkActionRequest req = new ContentLibraryController.BulkActionRequest();
        req.items = List.of(item);
        return req;
    }

    @Test
    void deletingContentClearsItFromEverybodysPhone() throws Exception {
        stubDeletableVideo("vid-1", "yt-vid-1");

        controller.bulkDelete(request("video", "vid-1"));

        verify(importGraduationService).onRejectedAll(YouTubeContentType.VIDEO, Set.of("yt-vid-1"));
    }

    @Test
    void anItemThatWasNotThereClearsNothing() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@test", null, List.of()));
        when(videoRepository.findById("missing")).thenReturn(Optional.empty());

        controller.bulkDelete(request("video", "missing"));

        verify(importGraduationService, never()).onRejectedAll(any(), any());
    }
}
