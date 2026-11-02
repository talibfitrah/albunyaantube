package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Stage 9 B11: a youtubeId with a stale second doc answers by the doc that counts, whatever the doc-id order. */
class VideoRepositoryPreferredTest {

    private static Video doc(String id, String status) {
        Video v = new Video("TwoDocs0001");
        v.setId(id);
        v.setStatus(status);
        return v;
    }

    @Test
    void approvedWinsThenAnyNotRejectedThenTheFirst() {
        assertEquals("b", VideoRepository.preferred(List.of(doc("a", "REJECTED"), doc("b", "APPROVED"))).get().getId());
        assertEquals("b", VideoRepository.preferred(List.of(doc("a", "REJECTED"), doc("b", "PENDING"))).get().getId());
        assertEquals("a", VideoRepository.preferred(List.of(doc("a", "REJECTED"), doc("b", "REJECTED"))).get().getId());
        assertTrue(VideoRepository.preferred(List.of()).isEmpty());
    }

    /**
     * Cubic r14: a channel or playlist re-added after a rejection has a second doc (bulk writes a new one). A report's
     * take-down archives the doc findByYoutubeId answers, so it must be the served APPROVED one, not whichever doc id
     * sorts first (limit(1)): the video rule.
     */
    @Test
    void aChannelOrPlaylistWithTwoDocsAnswersByTheApprovedOne() throws Exception {
        Channel rejectedChannel = new Channel("UCOll3M-P7oKs5cSrQ9ytt6g");
        rejectedChannel.setId("a");
        rejectedChannel.setStatus("REJECTED");
        Channel approvedChannel = new Channel("UCOll3M-P7oKs5cSrQ9ytt6g");
        approvedChannel.setId("b");
        approvedChannel.setStatus("APPROVED");
        Playlist rejectedPlaylist = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        rejectedPlaylist.setId("a");
        rejectedPlaylist.setStatus("REJECTED");
        Playlist approvedPlaylist = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        approvedPlaylist.setId("b");
        approvedPlaylist.setStatus("APPROVED");

        Firestore firestore = byYoutubeId(Channel.class, rejectedChannel, approvedChannel);
        assertEquals("b", new ChannelRepository(firestore, mock(FirestoreTimeoutProperties.class),
                mock(ApplicationEventPublisher.class)).findByYoutubeId("UCOll3M-P7oKs5cSrQ9ytt6g").get().getId());
        firestore = byYoutubeId(Playlist.class, rejectedPlaylist, approvedPlaylist);
        assertEquals("b", new PlaylistRepository(firestore, mock(FirestoreTimeoutProperties.class),
                mock(ApplicationEventPublisher.class)).findByYoutubeId("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe").get().getId());
    }

    /** A youtubeId query that answers both docs in doc-id order, and the first alone under limit(1), as Firestore does. */
    @SafeVarargs
    private static <T> Firestore byYoutubeId(Class<T> type, T... docs) {
        Firestore firestore = mock(Firestore.class);
        CollectionReference collection = mock(CollectionReference.class);
        Query query = mock(Query.class);
        Query first = mock(Query.class);
        QuerySnapshot all = mock(QuerySnapshot.class);
        QuerySnapshot one = mock(QuerySnapshot.class);
        when(firestore.collection(anyString())).thenReturn(collection);
        when(collection.whereEqualTo(eq("youtubeId"), anyString())).thenReturn(query);
        when(query.limit(anyInt())).thenReturn(first);
        when(query.get()).thenReturn(ApiFutures.immediateFuture(all));
        when(first.get()).thenReturn(ApiFutures.immediateFuture(one));
        when(all.toObjects(type)).thenReturn(List.of(docs));
        when(one.toObjects(type)).thenReturn(List.of(docs[0]));
        return firestore;
    }
}
