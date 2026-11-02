package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 9 B24 (Cubic r12 #1): a take-down's hold is its doc's ARCHIVED (B23), and saveIfStatus writes the whole doc its
 * caller read. So an approve, a request-changes or a submitter-note edit that read the doc before a take-down landed keeps
 * the stored ARCHIVED, in the transaction. A REJECTED doc (a take-down record a re-add replaces) keeps nothing.
 */
class ConditionalSaveKeepsArchiveTest {

    private final Firestore firestore = mock(Firestore.class, RETURNS_DEEP_STUBS);
    private final FirestoreTimeoutProperties timeouts = mock(FirestoreTimeoutProperties.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Transaction tx = mock(Transaction.class);
    private String storedSource;   // the stored doc's source: "REPORT" on a take-down record

    /** The stored doc's status, and its validationStatus as each run of the transaction reads it (a retry runs it again). */
    private void storedDoc(String status, String... validationStatusPerRun) throws Exception {
        Deque<ApiFuture<DocumentSnapshot>> reads = new ArrayDeque<>();
        for (String validationStatus : validationStatusPerRun) {
            DocumentSnapshot snapshot = mock(DocumentSnapshot.class);
            when(snapshot.exists()).thenReturn(true);
            when(snapshot.getString("status")).thenReturn(status);
            when(snapshot.getString("validationStatus")).thenReturn(validationStatus);
            when(snapshot.getString("source")).thenReturn(storedSource);
            reads.add(ApiFutures.immediateFuture(snapshot));
        }
        when(tx.get(any(DocumentReference.class))).thenAnswer(inv -> reads.poll());
        doAnswer(inv -> {
            Transaction.Function<?> function = inv.getArgument(0);
            Object result = null;
            for (int run = 0; run < validationStatusPerRun.length; run++) result = function.updateCallback(tx);
            return ApiFutures.immediateFuture(result);
        }).when(firestore).runTransaction(any(Transaction.Function.class));
    }

    @Test
    void aVideoApproveReadBeforeATakeDownKeepsItsArchive() throws Exception {
        storedDoc("PENDING", "ARCHIVED");
        Video video = new Video("sCQwxFEMlZ4");
        video.setId("v1");
        video.setStatus("APPROVED");
        video.setValidationStatus(ValidationStatus.VALID);   // as read before the take-down

        Video saved = new VideoRepository(firestore, timeouts, events).saveIfStatus(video, "PENDING");

        verify(tx).set(any(DocumentReference.class), same(video));
        assertEquals(ValidationStatus.ARCHIVED, saved.getValidationStatus());
        assertEquals("APPROVED", saved.getStatus());
    }

    @Test
    void aChannelRequestChangesReadBeforeATakeDownKeepsItsArchive() throws Exception {
        storedDoc("PENDING", "ARCHIVED");
        Channel channel = new Channel("UCOll3M-P7oKs5cSrQ9ytt6g");
        channel.setId("c1");
        channel.setStatus("REQUEST_CHANGES");

        Channel saved = new ChannelRepository(firestore, timeouts, events).saveIfStatus(channel, "PENDING");

        verify(tx).set(any(DocumentReference.class), same(channel));
        assertEquals(ValidationStatus.ARCHIVED, saved.getValidationStatus());
    }

    @Test
    void aPlaylistNoteEditReadBeforeATakeDownKeepsItsArchive() throws Exception {
        storedDoc("REQUEST_CHANGES", "ARCHIVED");
        Playlist playlist = new Playlist("PLtakedown000000000000000000000000b");
        playlist.setId("p1");
        playlist.setStatus("REQUEST_CHANGES");
        playlist.setValidationStatus(ValidationStatus.VALID);

        Playlist saved = new PlaylistRepository(firestore, timeouts, events).saveIfStatus(playlist, "REQUEST_CHANGES");

        verify(tx).set(any(DocumentReference.class), same(playlist));
        assertEquals(ValidationStatus.ARCHIVED, saved.getValidationStatus());
    }

    /** ContentReportService.reAdd: the take-down record is REJECTED, and the re-add's lift of it stands. */
    @Test
    void aReAddOverATakeDownRecordStillLiftsIt() throws Exception {
        storedSource = "REPORT";
        storedDoc("REJECTED", "ARCHIVED");
        Video video = new Video("sCQwxFEMlZ4");
        video.setId("v1");
        video.setStatus("APPROVED");                          // RegistryController.addVideo clears validationStatus

        Video saved = new VideoRepository(firestore, timeouts, events).saveIfStatus(video, "REJECTED");

        assertNull(saved.getValidationStatus());
    }

    /** Firestore re-runs a contended transaction: a Restore that lifted the archive between runs is not undone. */
    /**
     * Cubic r14: a report archived a PENDING video in place (B23), then an admin rejected it, which keeps the ARCHIVED.
     * That doc is REJECTED but no take-down record, so a re-add (addVideo, a bulk row) keeps the hold: only Restore lifts it.
     */
    @Test
    void aReAddOverARejectedInPlaceTakeDownKeepsItsArchive() throws Exception {
        storedDoc("REJECTED", "ARCHIVED");
        Video video = new Video("sCQwxFEMlZ4");
        video.setId("v1");
        video.setStatus("APPROVED");                          // RegistryController.addVideo clears validationStatus

        Video saved = new VideoRepository(firestore, timeouts, events).saveIfStatus(video, "REJECTED");

        assertEquals(ValidationStatus.ARCHIVED, saved.getValidationStatus());
    }

    /** The same through save(): an edit of that rejected doc read before the take-down landed. */
    @Test
    void aSaveOverARejectedInPlaceTakeDownKeepsItsArchive() throws Exception {
        storedDoc("REJECTED", "ARCHIVED");
        Video video = new Video("sCQwxFEMlZ4");
        video.setId("v1");
        video.setStatus("APPROVED");
        video.setValidationStatus(ValidationStatus.VALID);

        assertEquals(ValidationStatus.ARCHIVED, new VideoRepository(firestore, timeouts, events).save(video).getValidationStatus());
    }

    @Test
    void aRetriedTransactionDecidesOnTheDocItReadsLast() throws Exception {
        storedDoc("PENDING", "ARCHIVED", "VALID");
        Video video = new Video("sCQwxFEMlZ4");
        video.setId("v1");
        video.setStatus("APPROVED");
        video.setValidationStatus(ValidationStatus.VALID);

        Video saved = new VideoRepository(firestore, timeouts, events).saveIfStatus(video, "PENDING");

        assertEquals(ValidationStatus.VALID, saved.getValidationStatus());
    }
    // ---- Stage 9 B25 (Cubic r13 #2-#4): the plain save() every read-modify-write path uses keeps it too ----

    /** ContentValidationService's run: it read the video minutes before a take-down, then writes VALID back. */
    @Test
    void aValidationRunSaveReadBeforeATakeDownKeepsItsArchive() throws Exception {
        storedDoc("APPROVED", "ARCHIVED");
        Video video = new Video("sCQwxFEMlZ4");
        video.setId("v1");
        video.setStatus("APPROVED");
        video.setValidationStatus(ValidationStatus.VALID);

        Video saved = new VideoRepository(firestore, timeouts, events).save(video);

        verify(tx).set(any(DocumentReference.class), same(video));
        assertEquals(ValidationStatus.ARCHIVED, saved.getValidationStatus());
    }

    /** RegistryController.updateChannel / toggleChannelStatus: a stale ERROR is not written over the take-down. */
    @Test
    void aChannelUpdateSaveReadBeforeATakeDownKeepsItsArchive() throws Exception {
        storedDoc("APPROVED", "ARCHIVED");
        Channel channel = new Channel("UCOll3M-P7oKs5cSrQ9ytt6g");
        channel.setId("c1");
        channel.setStatus("APPROVED");
        channel.setValidationStatus(ValidationStatus.ERROR);

        Channel saved = new ChannelRepository(firestore, timeouts, events).save(channel);

        verify(tx).set(any(DocumentReference.class), same(channel));
        assertEquals(ValidationStatus.ARCHIVED, saved.getValidationStatus());
    }

    /** ContentReportService.excludeFromParent: a parent playlist's exclusion edit keeps its own take-down. */
    @Test
    void aPlaylistExclusionSaveReadBeforeATakeDownKeepsItsArchive() throws Exception {
        storedDoc("APPROVED", "ARCHIVED");
        Playlist playlist = new Playlist("PLtakedown000000000000000000000000b");
        playlist.setId("p1");
        playlist.setStatus("APPROVED");

        Playlist saved = new PlaylistRepository(firestore, timeouts, events).save(playlist);

        verify(tx).set(any(DocumentReference.class), same(playlist));
        assertEquals(ValidationStatus.ARCHIVED, saved.getValidationStatus());
    }

    /** A take-down record is REJECTED: a save over it keeps nothing (B23's and B24's rule). */
    @Test
    void aSaveOverATakeDownRecordKeepsNothing() throws Exception {
        storedSource = "REPORT";
        storedDoc("REJECTED", "ARCHIVED");
        Video video = new Video("sCQwxFEMlZ4");
        video.setId("v1");
        video.setStatus("REJECTED");

        assertNull(new VideoRepository(firestore, timeouts, events).save(video).getValidationStatus());
    }

    @Test
    void aRetriedSaveDecidesOnTheDocItReadsLast() throws Exception {
        storedDoc("APPROVED", "ARCHIVED", "VALID");
        Video video = new Video("sCQwxFEMlZ4");
        video.setId("v1");
        video.setStatus("APPROVED");
        video.setValidationStatus(ValidationStatus.ERROR);

        assertEquals(ValidationStatus.ERROR, new VideoRepository(firestore, timeouts, events).save(video).getValidationStatus());
    }

    /** Restore and the live poller's restore un-archive on purpose: the lifting save writes the caller's value. */
    @Test
    void theLiftingSaveWritesTheCallersValueOverAnArchive() throws Exception {
        storedDoc("APPROVED", "ARCHIVED");
        Video video = new Video("sCQwxFEMlZ4");
        video.setId("v1");
        video.setValidationStatus(ValidationStatus.VALID);
        Channel channel = new Channel("UCOll3M-P7oKs5cSrQ9ytt6g");
        channel.setId("c1");
        channel.setValidationStatus(ValidationStatus.VALID);
        Playlist playlist = new Playlist("PLtakedown000000000000000000000000b");
        playlist.setId("p1");
        playlist.setValidationStatus(ValidationStatus.VALID);

        assertEquals(ValidationStatus.VALID, new VideoRepository(firestore, timeouts, events).saveLiftingArchive(video).getValidationStatus());
        assertEquals(ValidationStatus.VALID, new ChannelRepository(firestore, timeouts, events).saveLiftingArchive(channel).getValidationStatus());
        assertEquals(ValidationStatus.VALID, new PlaylistRepository(firestore, timeouts, events).saveLiftingArchive(playlist).getValidationStatus());
        verify(firestore, never()).runTransaction(any(Transaction.Function.class));
    }

    // ---- Cubic users3: update() changes the doc as stored now, not the caller's copy ----

    /** The stored doc as the transaction reads it (null: gone). */
    private <T> void storedObject(Class<T> type, T doc) {
        DocumentSnapshot snapshot = mock(DocumentSnapshot.class);
        when(snapshot.toObject(type)).thenReturn(doc);
        when(tx.get(any(DocumentReference.class))).thenReturn(ApiFutures.immediateFuture(snapshot));
        doAnswer(inv -> ApiFutures.immediateFuture(((Transaction.Function<?>) inv.getArgument(0)).updateCallback(tx)))
                .when(firestore).runTransaction(any(Transaction.Function.class));
    }

    /** A validation run read the channel minutes before a report excluded a video on it: its write keeps the exclusion. */
    @Test
    void aChannelUpdateKeepsAnExclusionWrittenSinceTheRead() throws Exception {
        Channel stored = new Channel("UCOll3M-P7oKs5cSrQ9ytt6g");
        stored.setId("c1");
        stored.setStatus("APPROVED");
        Channel.ExcludedItems excluded = new Channel.ExcludedItems();
        excluded.setVideos(new ArrayList<>(List.of("sCQwxFEMlZ4")));
        stored.setExcludedItems(excluded);
        storedObject(Channel.class, stored);
        Channel read = new Channel("UCOll3M-P7oKs5cSrQ9ytt6g");   // as the run read it: no exclusion yet
        read.setId("c1");
        read.setStatus("APPROVED");

        Channel written = new ChannelRepository(firestore, timeouts, events).update(read, c -> c.setValidationStatus(ValidationStatus.VALID));

        verify(tx).set(any(DocumentReference.class), same(written));
        assertEquals(List.of("sCQwxFEMlZ4"), written.getExcludedItems().getVideos());
        assertEquals(1, written.getExcludedItems().getTotalExcludedCount());
        assertEquals(ValidationStatus.VALID, written.getValidationStatus());
    }

    /** The same for a playlist; and save()'s rule: a stored ARCHIVED (a take-down) stays. */
    @Test
    void aPlaylistUpdateKeepsAnExclusionAndATakeDown() throws Exception {
        Playlist stored = new Playlist("PLtakedown000000000000000000000000b");
        stored.setId("p1");
        stored.setStatus("APPROVED");
        stored.setValidationStatus(ValidationStatus.ARCHIVED);
        stored.setExcludedVideoIds(new ArrayList<>(List.of("sCQwxFEMlZ4")));
        storedObject(Playlist.class, stored);
        Playlist read = new Playlist("PLtakedown000000000000000000000000b");
        read.setId("p1");

        Playlist written = new PlaylistRepository(firestore, timeouts, events).update(read, p -> p.setValidationStatus(ValidationStatus.ERROR));

        verify(tx).set(any(DocumentReference.class), same(written));
        assertEquals(List.of("sCQwxFEMlZ4"), written.getExcludedVideoIds());
        assertEquals(ValidationStatus.ARCHIVED, written.getValidationStatus());
    }

    /** A doc deleted since the read is not written back. */
    @Test
    void anUpdateOfADeletedDocWritesNothing() throws Exception {
        storedObject(Channel.class, null);
        Channel read = new Channel("UCOll3M-P7oKs5cSrQ9ytt6g");
        read.setId("c1");

        assertThrows(IllegalArgumentException.class,
                () -> new ChannelRepository(firestore, timeouts, events).update(read, c -> c.setValidationStatus(ValidationStatus.VALID)));
        verify(tx, never()).set(any(DocumentReference.class), any());
    }

    // ---- Cubic users4: a channel or playlist has no take-down record, so a REJECTED one keeps its ARCHIVED too ----

    /** A validation run read the channel; a report archived it in place; an admin rejected it. The run's save keeps the hold. */
    @Test
    void aChannelSaveOverARejectedTakeDownKeepsItsArchive() throws Exception {
        storedDoc("REJECTED", "ARCHIVED");
        Channel channel = new Channel("UCOll3M-P7oKs5cSrQ9ytt6g");
        channel.setId("c1");
        channel.setStatus("REJECTED");
        channel.setValidationStatus(ValidationStatus.VALID);

        assertEquals(ValidationStatus.ARCHIVED, new ChannelRepository(firestore, timeouts, events).save(channel).getValidationStatus());
    }

    @Test
    void aPlaylistSaveOverARejectedTakeDownKeepsItsArchive() throws Exception {
        storedDoc("REJECTED", "ARCHIVED");
        Playlist playlist = new Playlist("PLtakedown000000000000000000000000b");
        playlist.setId("p1");
        playlist.setStatus("REJECTED");
        playlist.setValidationStatus(ValidationStatus.VALID);

        assertEquals(ValidationStatus.ARCHIVED, new PlaylistRepository(firestore, timeouts, events).save(playlist).getValidationStatus());
    }

    @Test
    void aConditionalSaveOverARejectedChannelOrPlaylistTakeDownKeepsItsArchive() throws Exception {
        storedDoc("REJECTED", "ARCHIVED");
        Channel channel = new Channel("UCOll3M-P7oKs5cSrQ9ytt6g");
        channel.setId("c1");
        channel.setStatus("REJECTED");
        channel.setValidationStatus(ValidationStatus.VALID);
        Playlist playlist = new Playlist("PLtakedown000000000000000000000000b");
        playlist.setId("p1");
        playlist.setStatus("REJECTED");
        playlist.setValidationStatus(ValidationStatus.VALID);

        assertEquals(ValidationStatus.ARCHIVED, new ChannelRepository(firestore, timeouts, events).saveIfStatus(channel, "REJECTED").getValidationStatus());
        storedDoc("REJECTED", "ARCHIVED");
        assertEquals(ValidationStatus.ARCHIVED, new PlaylistRepository(firestore, timeouts, events).saveIfStatus(playlist, "REJECTED").getValidationStatus());
    }

    /** The validation run's update() on the doc as stored now: a REJECTED channel or playlist keeps its take-down. */
    @Test
    void anUpdateOfARejectedTakeDownKeepsItsArchive() throws Exception {
        Channel stored = new Channel("UCOll3M-P7oKs5cSrQ9ytt6g");
        stored.setId("c1");
        stored.setStatus("REJECTED");
        stored.setValidationStatus(ValidationStatus.ARCHIVED);
        storedObject(Channel.class, stored);
        assertEquals(ValidationStatus.ARCHIVED, new ChannelRepository(firestore, timeouts, events)
                .update(stored, c -> c.setValidationStatus(ValidationStatus.VALID)).getValidationStatus());

        Playlist storedPlaylist = new Playlist("PLtakedown000000000000000000000000b");
        storedPlaylist.setId("p1");
        storedPlaylist.setStatus("REJECTED");
        storedPlaylist.setValidationStatus(ValidationStatus.ARCHIVED);
        storedObject(Playlist.class, storedPlaylist);
        assertEquals(ValidationStatus.ARCHIVED, new PlaylistRepository(firestore, timeouts, events)
                .update(storedPlaylist, p -> p.setValidationStatus(ValidationStatus.VALID)).getValidationStatus());
    }
}
