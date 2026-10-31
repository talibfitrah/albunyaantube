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
}
