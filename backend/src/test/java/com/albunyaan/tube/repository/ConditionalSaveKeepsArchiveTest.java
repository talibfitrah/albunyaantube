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
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** The stored doc's status, and its validationStatus as each run of the transaction reads it (a retry runs it again). */
    private void storedDoc(String status, String... validationStatusPerRun) throws Exception {
        Deque<ApiFuture<DocumentSnapshot>> reads = new ArrayDeque<>();
        for (String validationStatus : validationStatusPerRun) {
            DocumentSnapshot snapshot = mock(DocumentSnapshot.class);
            when(snapshot.exists()).thenReturn(true);
            when(snapshot.getString("status")).thenReturn(status);
            when(snapshot.getString("validationStatus")).thenReturn(validationStatus);
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
        storedDoc("REJECTED", "ARCHIVED");
        Video video = new Video("sCQwxFEMlZ4");
        video.setId("v1");
        video.setStatus("APPROVED");                          // RegistryController.addVideo clears validationStatus

        Video saved = new VideoRepository(firestore, timeouts, events).saveIfStatus(video, "REJECTED");

        assertNull(saved.getValidationStatus());
    }

    /** Firestore re-runs a contended transaction: a Restore that lifted the archive between runs is not undone. */
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
}
