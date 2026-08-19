package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.ImportGraduationService;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.SortOrderService;
import com.albunyaan.tube.service.TagEnrichmentService;
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
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Field bug: content approved from the Content Library kept showing as "pending" in the app.
 *
 * <p>Approving through {@code /api/admin/approvals} fans the decision out to every importer's
 * per-user Me-list row via {@link ImportGraduationService}; the Content Library's bulk endpoints
 * wrote {@code status} straight to Firestore and skipped it, stranding those rows on AWAITING
 * with nothing to ever correct them (the sync pull returns the stored value, and the
 * server-side re-derive only runs on a client push that never comes again).
 *
 * <p>Pins that both bulk paths graduate, and that an item which never committed does not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentLibraryBulkGraduationTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private Firestore firestore;
    @Mock private FirestoreTimeoutProperties timeoutProperties;
    @Mock private PublicContentCacheService publicContentCacheService;
    @Mock private SortOrderService sortOrderService;
    @Mock private TagEnrichmentService tagEnrichmentService;
    @Mock private ImportGraduationService graduationService;
    @Mock private com.albunyaan.tube.repository.UserRepository userRepository;

    @InjectMocks private ContentLibraryController controller;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Wires Firestore so a single-item bulk action sees one existing document carrying
     * {@code youtubeId}, and so its write batch commits.
     *
     * @param exists whether the document is found by the existence check
     */
    private void stubFirestore(String collection, String docId, String youtubeId, boolean exists) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@test", null, List.of()));

        DocumentReference docRef = mock(DocumentReference.class);
        CollectionReference collRef = mock(CollectionReference.class);
        when(firestore.collection(collection)).thenReturn(collRef);
        when(collRef.document(docId)).thenReturn(docRef);

        DocumentSnapshot snapshot = mock(DocumentSnapshot.class);
        when(snapshot.exists()).thenReturn(exists);
        when(snapshot.getString("youtubeId")).thenReturn(youtubeId);
        when(firestore.getAll(any(DocumentReference[].class)))
                .thenReturn(ApiFutures.immediateFuture(List.of(snapshot)));

        WriteBatch batch = mock(WriteBatch.class);
        when(firestore.batch()).thenReturn(batch);
        when(batch.commit()).thenReturn(ApiFutures.immediateFuture(List.of()));

        when(timeoutProperties.getRead()).thenReturn(5L);
        when(timeoutProperties.getWrite()).thenReturn(10L);
    }

    /** As {@link #stubFirestore} but hands back the write batch so its updates can be inspected. */
    private WriteBatch stubFirestoreReturningBatch(String collection, String docId, String youtubeId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@test", null, List.of()));

        CollectionReference collRef = mock(CollectionReference.class);
        when(firestore.collection(collection)).thenReturn(collRef);
        when(collRef.document(docId)).thenReturn(mock(DocumentReference.class));

        DocumentSnapshot snapshot = mock(DocumentSnapshot.class);
        when(snapshot.exists()).thenReturn(true);
        when(snapshot.getString("youtubeId")).thenReturn(youtubeId);
        when(snapshot.getString("visibility")).thenReturn("PERSONAL");
        when(firestore.getAll(any(DocumentReference[].class)))
                .thenReturn(ApiFutures.immediateFuture(List.of(snapshot)));

        WriteBatch batch = mock(WriteBatch.class);
        when(firestore.batch()).thenReturn(batch);
        when(batch.commit()).thenReturn(ApiFutures.immediateFuture(List.of()));
        when(timeoutProperties.getRead()).thenReturn(5L);
        when(timeoutProperties.getWrite()).thenReturn(10L);
        return batch;
    }

    /** Firestore wired so every named video exists and carries `yt-<id>`. */
    private void stubManyVideos(String... ids) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@test", null, List.of()));

        CollectionReference collRef = mock(CollectionReference.class);
        when(firestore.collection("videos")).thenReturn(collRef);
        List<DocumentSnapshot> snapshots = new java.util.ArrayList<>();
        for (String id : ids) {
            when(collRef.document(id)).thenReturn(mock(DocumentReference.class));
            DocumentSnapshot snap = mock(DocumentSnapshot.class);
            when(snap.exists()).thenReturn(true);
            when(snap.getString("youtubeId")).thenReturn("yt-" + id);
            snapshots.add(snap);
        }
        when(firestore.getAll(any(DocumentReference[].class)))
                .thenReturn(ApiFutures.immediateFuture(snapshots));

        WriteBatch batch = mock(WriteBatch.class);
        when(firestore.batch()).thenReturn(batch);
        when(batch.commit()).thenReturn(ApiFutures.immediateFuture(List.of()));
        when(timeoutProperties.getRead()).thenReturn(5L);
        when(timeoutProperties.getWrite()).thenReturn(10L);
    }

    private static ContentLibraryController.BulkActionRequest requestFor(String type, String... ids) {
        ContentLibraryController.BulkActionRequest req = new ContentLibraryController.BulkActionRequest();
        req.items = new java.util.ArrayList<>();
        for (String id : ids) {
            ContentLibraryController.BulkActionItem item = new ContentLibraryController.BulkActionItem();
            item.type = type;
            item.id = id;
            req.items.add(item);
        }
        return req;
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
    void bulkApprove_graduatesTheImportersAwaitingRows() throws Exception {
        stubFirestore("videos", "vid-1", "dQw4w9WgXcQ", true);

        controller.bulkApprove(request("video", "vid-1"));

        verify(graduationService).onApprovedAll(YouTubeContentType.VIDEO, Set.of("dQw4w9WgXcQ"));
    }

    @Test
    void bulkReject_tombstonesTheImportersAwaitingRows() throws Exception {
        stubFirestore("channels", "ch-1", "UC_rejected", true);

        controller.bulkReject(request("channel", "ch-1"));

        verify(graduationService).onRejectedAll(YouTubeContentType.CHANNEL, Set.of("UC_rejected"));
    }

    @Test
    void bulkApprove_doesNotGraduateAnItemThatWasNeverFound() throws Exception {
        stubFirestore("playlists", "pl-missing", "PLmissing", false);

        controller.bulkApprove(request("playlist", "pl-missing"));

        verify(graduationService, never()).onApprovedAll(any(), any());
    }

    @Test
    void bulkApprove_publishesAnItemThatWasOnlyGrantedToSomebody() throws Exception {
        // Bulk approve writes status. If it left visibility alone, a "Approved for Ahmed" item
        // would be counted as approved while staying out of the public feed — and the public
        // fan-out below would hand it to every waiting importer it was never granted to.
        WriteBatch batch = stubFirestoreReturningBatch("videos", "vid-personal", "yt-personal");

        controller.bulkApprove(request("video", "vid-personal"));

        ArgumentCaptor<Map<String, Object>> updates = ArgumentCaptor.forClass(Map.class);
        verify(batch).update(any(DocumentReference.class), updates.capture());
        assertEquals("APPROVED", updates.getValue().get("status"));
        assertEquals("PUBLIC", updates.getValue().get("visibility"));
    }

    @Test
    void bulkReject_leavesVisibilityAlone() throws Exception {
        // Rejecting is not a visibility decision; the row is going away either way.
        WriteBatch batch = stubFirestoreReturningBatch("videos", "vid-1", "yt-1");

        controller.bulkReject(request("video", "vid-1"));

        ArgumentCaptor<Map<String, Object>> updates = ArgumentCaptor.forClass(Map.class);
        verify(batch).update(any(DocumentReference.class), updates.capture());
        assertEquals("REJECTED", updates.getValue().get("status"));
        assertFalse(updates.getValue().containsKey("visibility"));
    }

    @Test
    void bulkApprove_asksOnceForTheWholeBatchRatherThanOncePerItem() throws Exception {
        // One collection-group query and write batch per item put 500 sequential Firestore
        // round-trips inside the admin's request, after the status change had already committed —
        // so a slow fan-out surfaced as a failed bulk action that had in fact worked.
        stubManyVideos("v1", "v2", "v3");

        controller.bulkApprove(requestFor("video", "v1", "v2", "v3"));

        verify(graduationService).onApprovedAll(YouTubeContentType.VIDEO, Set.of("yt-v1", "yt-v2", "yt-v3"));
        verify(graduationService, times(1)).onApprovedAll(any(), any());
    }
}
