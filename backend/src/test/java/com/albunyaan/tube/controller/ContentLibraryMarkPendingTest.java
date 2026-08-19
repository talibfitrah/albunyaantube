package com.albunyaan.tube.controller;

import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.ImportGraduationService;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteBatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Content Library's "Mark as Pending" sent its items to the reject endpoint, because no
 * endpoint existed for putting content back under review. So a button that says it returns an
 * item to the queue was recording it as rejected — and once rejecting started clearing content
 * from people's phones, that same button would have wiped it from every device holding it.
 *
 * <p>Sending something back for review is not a decision about it. Nothing may be fanned out.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentLibraryMarkPendingTest {

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

    private WriteBatch stubOneVideo() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@test", null, List.of()));

        CollectionReference coll = mock(CollectionReference.class);
        when(firestore.collection("videos")).thenReturn(coll);
        when(coll.document("vid-1")).thenReturn(mock(DocumentReference.class));

        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(snap.exists()).thenReturn(true);
        when(snap.getString("youtubeId")).thenReturn("yt-vid-1");
        when(firestore.getAll(any(DocumentReference[].class)))
                .thenReturn(ApiFutures.immediateFuture(List.of(snap)));

        WriteBatch batch = mock(WriteBatch.class);
        when(firestore.batch()).thenReturn(batch);
        when(batch.commit()).thenReturn(ApiFutures.immediateFuture(List.of()));
        when(timeoutProperties.getRead()).thenReturn(5L);
        when(timeoutProperties.getWrite()).thenReturn(10L);
        return batch;
    }

    private static ContentLibraryController.BulkActionRequest request() {
        ContentLibraryController.BulkActionItem item = new ContentLibraryController.BulkActionItem();
        item.type = "video";
        item.id = "vid-1";
        ContentLibraryController.BulkActionRequest req = new ContentLibraryController.BulkActionRequest();
        req.items = List.of(item);
        return req;
    }

    @Test
    void markingPendingRecordsPendingRatherThanRejected() throws Exception {
        WriteBatch batch = stubOneVideo();

        controller.bulkMarkPending(request());

        ArgumentCaptor<Map<String, Object>> updates = ArgumentCaptor.forClass(Map.class);
        verify(batch).update(any(DocumentReference.class), updates.capture());
        assertEquals("PENDING", updates.getValue().get("status"));
        // Sending something back for review says nothing about who may see it.
        assertFalse(updates.getValue().containsKey("visibility"));
    }

    @Test
    void markingPendingTakesNothingOffAnybodysPhone() throws Exception {
        stubOneVideo();

        controller.bulkMarkPending(request());

        verify(importGraduationService, never()).onRejectedAll(any(), any());
        verify(importGraduationService, never()).onApprovedAll(any(), any());
    }

    @Test
    void aChannelsLegacyFlagsFollowThePendingStatus() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@test", null, List.of()));
        CollectionReference coll = mock(CollectionReference.class);
        when(firestore.collection("channels")).thenReturn(coll);
        when(coll.document("ch-1")).thenReturn(mock(DocumentReference.class));
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(snap.exists()).thenReturn(true);
        when(firestore.getAll(any(DocumentReference[].class)))
                .thenReturn(ApiFutures.immediateFuture(List.of(snap)));
        WriteBatch batch = mock(WriteBatch.class);
        when(firestore.batch()).thenReturn(batch);
        when(batch.commit()).thenReturn(ApiFutures.immediateFuture(List.of()));
        when(timeoutProperties.getRead()).thenReturn(5L);
        when(timeoutProperties.getWrite()).thenReturn(10L);

        ContentLibraryController.BulkActionItem item = new ContentLibraryController.BulkActionItem();
        item.type = "channel";
        item.id = "ch-1";
        ContentLibraryController.BulkActionRequest req = new ContentLibraryController.BulkActionRequest();
        req.items = List.of(item);

        controller.bulkMarkPending(req);

        ArgumentCaptor<Map<String, Object>> updates = ArgumentCaptor.forClass(Map.class);
        verify(batch).update(any(DocumentReference.class), updates.capture());
        assertEquals(true, updates.getValue().get("pending"));
        assertEquals(false, updates.getValue().get("approved"));
    }
}
