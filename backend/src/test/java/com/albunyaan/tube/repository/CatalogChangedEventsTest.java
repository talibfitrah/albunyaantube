package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.search.CatalogChanged;
import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Spec §9.2 + F3: every catalog write publishes a CatalogChanged invalidation (even when its ack fails or a conditional write looks like a miss); display order does not. */
@ExtendWith(MockitoExtension.class)
class CatalogChangedEventsTest {

    // Deep stubs: every Firestore future is a mock whose get(..) returns without blocking.
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Firestore firestore;
    @Mock private FirestoreTimeoutProperties timeouts;
    @Mock private ApplicationEventPublisher events;

    private ChannelRepository channels() {
        return new ChannelRepository(firestore, timeouts, events);
    }

    private PlaylistRepository playlists() {
        return new PlaylistRepository(firestore, timeouts, events);
    }

    private VideoRepository videos() {
        return new VideoRepository(firestore, timeouts, events);
    }

    /** A transaction that commits and hands back `result`. */
    private void transactionReturns(Object result) {
        doReturn(ApiFutures.immediateFuture(result)).when(firestore).runTransaction(any(Transaction.Function.class));
    }

    // ---- channels ----

    @Test
    void channelSavePublishesAnInvalidation() throws Exception {
        Channel channel = new Channel("UCsaveTest");
        channel.setId("c1");
        channels().save(channel);
        verify(events).publishEvent(new CatalogChanged("CHANNEL", "c1", false));
    }

    @Test
    void channelSaveIfStatusPublishesAnInvalidation() throws Exception {
        Channel channel = new Channel("UCsaveTest");
        channel.setId("c1");
        transactionReturns(channel);
        channels().saveIfStatus(channel, "PENDING");
        verify(events).publishEvent(new CatalogChanged("CHANNEL", "c1", false));
    }

    @Test
    void channelDeletePublishesARereadEvent() throws Exception {
        channels().deleteById("c1");
        verify(events).publishEvent(new CatalogChanged("CHANNEL", "c1", false));
    }

    @Test
    void channelConditionalDeletePublishesARereadEvent() throws Exception {
        channels().deleteByIdIfStatusIn("c1", Set.of("PENDING"));
        verify(events).publishEvent(new CatalogChanged("CHANNEL", "c1", false));
    }

    @Test
    void channelMetadataUpdatePublishesARereadEvent() throws Exception {
        channels().updateMetadata("c1", "https://example.test/t.jpg", null, null);
        verify(events).publishEvent(new CatalogChanged("CHANNEL", "c1", false));
    }

    @Test
    void displayOrderBatchesPublishNothing() throws Exception {
        when(firestore.getAll(any(DocumentReference[].class))).thenReturn(ApiFutures.immediateFuture(List.of())); // playlists check existence first
        channels().batchUpdateDisplayOrder(Map.of("c1", 1));
        playlists().batchUpdateDisplayOrder(Map.of("p1", 1));
        videos().batchUpdateDisplayOrder(Map.of("v1", 1));
        verifyNoInteractions(events);
    }

    /** A Firestore whose every write fails at the ack: the write may or may not have committed. */
    private static Firestore ackFails() {
        Answer<Object> answer = new Answer<>() {
            @Override
            public Object answer(InvocationOnMock inv) throws Throwable {
                Class<?> type = inv.getMethod().getReturnType();
                if (type == ApiFuture.class) return ApiFutures.immediateFailedFuture(new RuntimeException("deadline exceeded"));
                if (type.getName().startsWith("com.google.cloud.firestore.")) return mock(type, this);
                return RETURNS_DEFAULTS.answer(inv);
            }
        };
        return mock(Firestore.class, answer);
    }

    @Test
    void everyWriteWhoseAckFailsStillPublishesAnInvalidation() {
        // A lost ack may hide a committed reject: the index re-reads the document and decides (F3).
        Firestore down = ackFails();
        ChannelRepository cs = new ChannelRepository(down, timeouts, events);
        PlaylistRepository ps = new PlaylistRepository(down, timeouts, events);
        VideoRepository vs = new VideoRepository(down, timeouts, events);
        Channel channel = new Channel("UCackTest");
        channel.setId("c1");
        Playlist playlist = new Playlist("PLackTest");
        playlist.setId("p1");
        Video video = new Video("vAckTest");
        video.setId("v1");
        List<Executable> writes = List.of(
                () -> cs.save(channel), () -> cs.saveIfStatus(channel, "PENDING"), () -> cs.deleteById("c1"),
                () -> cs.deleteByIdIfStatusIn("c1", Set.of("PENDING")), () -> cs.updateMetadata("c1", "t", null, null),
                () -> ps.save(playlist), () -> ps.saveIfStatus(playlist, "PENDING"), () -> ps.updateThumbnailUrl("p1", "t"),
                () -> ps.deleteById("p1"), () -> ps.deleteByIdIfStatusIn("p1", Set.of("PENDING")),
                () -> vs.save(video), () -> vs.saveIfStatus(video, "PENDING"), () -> vs.deleteById("v1"),
                () -> vs.deleteByIdIfStatusIn("v1", Set.of("PENDING")));

        for (Executable write : writes) assertThrows(ExecutionException.class, write);
        // Flagged: a failed ack can still commit later, so the writer reads it again (B2).

        verify(events, times(5)).publishEvent(new CatalogChanged("CHANNEL", "c1", true));
        verify(events, times(5)).publishEvent(new CatalogChanged("PLAYLIST", "p1", true));
        verify(events, times(2)).publishEvent(new CatalogChanged("VIDEO", "v1", true, "vAckTest")); // saves carry the id (R1)
        verify(events, times(2)).publishEvent(new CatalogChanged("VIDEO", "v1", true)); // deletes have none
    }

    @Test
    void aConditionalWriteWhoseAckTimesOutStillPublishesAnInvalidation() throws Exception {
        // Stage 3 I1 scenario B: saveIfStatus committed server-side, then .get(timeout) threw.
        ApiFuture<Object> slow = mock(ApiFuture.class);
        when(slow.get(anyLong(), any())).thenThrow(new TimeoutException("ack lost"));
        doReturn(slow).when(firestore).runTransaction(any(Transaction.Function.class));
        Channel channel = new Channel("UCslowTest");
        channel.setId("c1");

        assertThrows(TimeoutException.class, () -> channels().saveIfStatus(channel, "PENDING"));

        verify(events).publishEvent(new CatalogChanged("CHANNEL", "c1", true));
    }

    @Test
    void aConditionalWriteThatMissesStillPublishesOneInvalidationBecauseARetriedCommitCanLookLikeAMiss() {
        // N2: Firestore retries a transaction whose commit hit a transient error; the retry can read the
        // committed status and throw, so a write that landed looks like a miss. The writer's re-read decides.
        doReturn(ApiFutures.immediateFailedFuture(new IllegalStateException("status changed")))
                .when(firestore).runTransaction(any(Transaction.Function.class));
        Channel channel = new Channel("UCmissTest");
        channel.setId("c1");
        Playlist playlist = new Playlist("PLmissTest");
        playlist.setId("p1");
        Video video = new Video("vMissTest");
        video.setId("v1");

        assertThrows(ExecutionException.class, () -> channels().saveIfStatus(channel, "PENDING"));
        assertThrows(ExecutionException.class, () -> channels().deleteByIdIfStatusIn("c1", Set.of("PENDING")));
        assertThrows(ExecutionException.class, () -> playlists().saveIfStatus(playlist, "PENDING"));
        assertThrows(ExecutionException.class, () -> playlists().deleteByIdIfStatusIn("p1", Set.of("PENDING")));
        assertThrows(ExecutionException.class, () -> videos().saveIfStatus(video, "PENDING"));
        assertThrows(ExecutionException.class, () -> videos().deleteByIdIfStatusIn("v1", Set.of("PENDING")));

        verify(events, times(2)).publishEvent(new CatalogChanged("CHANNEL", "c1", true)); // one per miss: save, delete
        verify(events, times(2)).publishEvent(new CatalogChanged("PLAYLIST", "p1", true));
        verify(events).publishEvent(new CatalogChanged("VIDEO", "v1", true, "vMissTest"));
        verify(events).publishEvent(new CatalogChanged("VIDEO", "v1", true));
        verifyNoMoreInteractions(events);
    }

    // ---- playlists ----

    @Test
    void playlistSavePublishesAnInvalidation() throws Exception {
        Playlist playlist = new Playlist("PLsaveTest");
        playlist.setId("p1");
        playlists().save(playlist);
        verify(events).publishEvent(new CatalogChanged("PLAYLIST", "p1", false));
    }

    @Test
    void playlistSaveIfStatusPublishesAnInvalidation() throws Exception {
        Playlist playlist = new Playlist("PLsaveTest");
        playlist.setId("p1");
        transactionReturns(playlist);
        playlists().saveIfStatus(playlist, "PENDING");
        verify(events).publishEvent(new CatalogChanged("PLAYLIST", "p1", false));
    }

    @Test
    void playlistThumbnailUpdatePublishesARereadEvent() throws Exception {
        playlists().updateThumbnailUrl("p1", "https://example.test/t.jpg");
        verify(events).publishEvent(new CatalogChanged("PLAYLIST", "p1", false));
    }

    @Test
    void playlistDeletePublishesARereadEvent() throws Exception {
        playlists().deleteById("p1");
        verify(events).publishEvent(new CatalogChanged("PLAYLIST", "p1", false));
    }

    @Test
    void playlistConditionalDeletePublishesARereadEvent() throws Exception {
        playlists().deleteByIdIfStatusIn("p1", Set.of("PENDING"));
        verify(events).publishEvent(new CatalogChanged("PLAYLIST", "p1", false));
    }

    // ---- videos ----

    @Test
    void videoSavePublishesAnInvalidation() throws Exception {
        Video video = new Video("vSaveTest");
        video.setId("v1");
        videos().save(video);
        verify(events).publishEvent(new CatalogChanged("VIDEO", "v1", false, "vSaveTest")); // the id travels (R1)
    }

    @Test
    void videoSaveIfStatusPublishesAnInvalidation() throws Exception {
        Video video = new Video("vSaveTest");
        video.setId("v1");
        transactionReturns(video);
        videos().saveIfStatus(video, "PENDING");
        verify(events).publishEvent(new CatalogChanged("VIDEO", "v1", false, "vSaveTest"));
    }

    @Test
    void videoDeletePublishesARereadEvent() throws Exception {
        videos().deleteById("v1");
        verify(events).publishEvent(new CatalogChanged("VIDEO", "v1", false));
    }

    @Test
    void videoConditionalDeletePublishesARereadEvent() throws Exception {
        videos().deleteByIdIfStatusIn("v1", Set.of("PENDING"));
        verify(events).publishEvent(new CatalogChanged("VIDEO", "v1", false));
    }
}
