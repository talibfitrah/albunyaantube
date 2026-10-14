package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.search.CatalogChanged;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import com.google.cloud.firestore.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Spec §9.2: repository writes publish CatalogChanged after Firestore succeeds; display order does not. */
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
    void channelSavePublishesTheSavedEntity() throws Exception {
        Channel channel = new Channel("UCsaveTest");
        channel.setId("c1");
        channels().save(channel);
        verify(events).publishEvent(new CatalogChanged("CHANNEL", "c1", channel));
    }

    @Test
    void channelSaveIfStatusPublishesTheSavedEntity() throws Exception {
        Channel channel = new Channel("UCsaveTest");
        channel.setId("c1");
        transactionReturns(channel);
        channels().saveIfStatus(channel, "PENDING");
        verify(events).publishEvent(new CatalogChanged("CHANNEL", "c1", channel));
    }

    @Test
    void channelDeletePublishesARereadEvent() throws Exception {
        channels().deleteById("c1");
        verify(events).publishEvent(new CatalogChanged("CHANNEL", "c1", null));
    }

    @Test
    void channelConditionalDeletePublishesARereadEvent() throws Exception {
        channels().deleteByIdIfStatusIn("c1", Set.of("PENDING"));
        verify(events).publishEvent(new CatalogChanged("CHANNEL", "c1", null));
    }

    @Test
    void channelMetadataUpdatePublishesARereadEvent() throws Exception {
        channels().updateMetadata("c1", "https://example.test/t.jpg", null, null);
        verify(events).publishEvent(new CatalogChanged("CHANNEL", "c1", null));
    }

    @Test
    void displayOrderBatchesPublishNothing() throws Exception {
        when(firestore.getAll(any(DocumentReference[].class))).thenReturn(ApiFutures.immediateFuture(List.of())); // playlists check existence first
        channels().batchUpdateDisplayOrder(Map.of("c1", 1));
        playlists().batchUpdateDisplayOrder(Map.of("p1", 1));
        videos().batchUpdateDisplayOrder(Map.of("v1", 1));
        verifyNoInteractions(events);
    }

    @Test
    void aFailedWritePublishesNothing() {
        when(firestore.collection("channels").document("c1").delete())
                .thenReturn(ApiFutures.<WriteResult>immediateFailedFuture(new IllegalStateException("firestore down")));
        assertThrows(ExecutionException.class, () -> channels().deleteById("c1"));
        verifyNoInteractions(events);
    }

    // ---- playlists ----

    @Test
    void playlistSavePublishesTheSavedEntity() throws Exception {
        Playlist playlist = new Playlist("PLsaveTest");
        playlist.setId("p1");
        playlists().save(playlist);
        verify(events).publishEvent(new CatalogChanged("PLAYLIST", "p1", playlist));
    }

    @Test
    void playlistSaveIfStatusPublishesTheSavedEntity() throws Exception {
        Playlist playlist = new Playlist("PLsaveTest");
        playlist.setId("p1");
        transactionReturns(playlist);
        playlists().saveIfStatus(playlist, "PENDING");
        verify(events).publishEvent(new CatalogChanged("PLAYLIST", "p1", playlist));
    }

    @Test
    void playlistThumbnailUpdatePublishesARereadEvent() throws Exception {
        playlists().updateThumbnailUrl("p1", "https://example.test/t.jpg");
        verify(events).publishEvent(new CatalogChanged("PLAYLIST", "p1", null));
    }

    @Test
    void playlistDeletePublishesARereadEvent() throws Exception {
        playlists().deleteById("p1");
        verify(events).publishEvent(new CatalogChanged("PLAYLIST", "p1", null));
    }

    @Test
    void playlistConditionalDeletePublishesARereadEvent() throws Exception {
        playlists().deleteByIdIfStatusIn("p1", Set.of("PENDING"));
        verify(events).publishEvent(new CatalogChanged("PLAYLIST", "p1", null));
    }

    // ---- videos ----

    @Test
    void videoSavePublishesTheSavedEntity() throws Exception {
        Video video = new Video("vSaveTest");
        video.setId("v1");
        videos().save(video);
        verify(events).publishEvent(new CatalogChanged("VIDEO", "v1", video));
    }

    @Test
    void videoSaveIfStatusPublishesTheSavedEntity() throws Exception {
        Video video = new Video("vSaveTest");
        video.setId("v1");
        transactionReturns(video);
        videos().saveIfStatus(video, "PENDING");
        verify(events).publishEvent(new CatalogChanged("VIDEO", "v1", video));
    }

    @Test
    void videoDeletePublishesARereadEvent() throws Exception {
        videos().deleteById("v1");
        verify(events).publishEvent(new CatalogChanged("VIDEO", "v1", null));
    }

    @Test
    void videoConditionalDeletePublishesARereadEvent() throws Exception {
        videos().deleteByIdIfStatusIn("v1", Set.of("PENDING"));
        verify(events).publishEvent(new CatalogChanged("VIDEO", "v1", null));
    }
}
