package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.StreamItemDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.SearchableStream;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SearchableStreamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamIndexServiceTest {

    @Mock private SearchableStreamRepository streamRepository;
    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    private SearchTokenizer tokenizer;
    private StreamIndexService service;

    @BeforeEach
    void setUp() {
        tokenizer = new SearchTokenizer();
        service = new StreamIndexService(streamRepository, channelRepository, playlistRepository, tokenizer);
    }

    @Test
    void indexFromChannel_skipsIfChannelNotApproved() throws Exception {
        when(channelRepository.findByYoutubeId("UC123")).thenReturn(Optional.empty());
        service.indexFromChannel("UC123", List.of(makeItem("abc12345678", "Test Video")));
        verifyNoInteractions(streamRepository);
    }

    @Test
    void indexFromChannel_skipsIfChannelPending() throws Exception {
        Channel ch = new Channel("UC123");
        ch.setStatus("PENDING");
        ch.setName("Test Channel");
        when(channelRepository.findByYoutubeId("UC123")).thenReturn(Optional.of(ch));
        service.indexFromChannel("UC123", List.of(makeItem("abc12345678", "Test Video")));
        verifyNoInteractions(streamRepository);
    }

    @Test
    void indexFromChannel_skipsIfChannelPersonal() throws Exception {
        // Finding 3: PERSONAL channels must not be indexed into unauthenticated search.
        Channel ch = new Channel("UC123");
        ch.setStatus("APPROVED");
        ch.setVisibility("PERSONAL");
        ch.setName("Test Channel");
        when(channelRepository.findByYoutubeId("UC123")).thenReturn(Optional.of(ch));
        service.indexFromChannel("UC123", List.of(makeItem("abc12345678", "Test Video")));
        verifyNoInteractions(streamRepository);
    }

    @Test
    void indexFromChannel_indexesApprovedChannelItem() throws Exception {
        Channel ch = new Channel("UC123");
        ch.setStatus("APPROVED");
        ch.setName("Test Channel");
        when(channelRepository.findByYoutubeId("UC123")).thenReturn(Optional.of(ch));

        service.indexFromChannel("UC123", List.of(makeItem("abc12345678", "Quran Recitation")));

        verify(streamRepository).upsert(argThat(s ->
            s.getStreamId().equals("abc12345678") &&
            s.getTitle().equals("Quran Recitation") &&
            s.getChannelName().equals("Test Channel") &&
            s.getSearchTokens().contains("quran") &&
            s.isVisible()
        ), eq("channel:UC123"));
    }

    @Test
    void indexFromChannel_skipsExcludedVideo() throws Exception {
        Channel ch = new Channel("UC123");
        ch.setStatus("APPROVED");
        ch.setName("Test Channel");
        ch.getExcludedItems().setVideos(List.of("abc12345678"));
        when(channelRepository.findByYoutubeId("UC123")).thenReturn(Optional.of(ch));

        StreamItemDto excluded = makeItem("abc12345678", "Test");
        excluded.setStreamType("VIDEO");
        service.indexFromChannel("UC123", List.of(excluded));
        verifyNoInteractions(streamRepository);
    }

    @Test
    void indexFromChannel_skipsExcludedLiveStream() throws Exception {
        Channel ch = new Channel("UC123");
        ch.setStatus("APPROVED");
        ch.setName("Test Channel");
        ch.getExcludedItems().setLiveStreams(List.of("abc12345678"));
        when(channelRepository.findByYoutubeId("UC123")).thenReturn(Optional.of(ch));

        StreamItemDto item = makeItem("abc12345678", "Test Live");
        item.setStreamType("LIVESTREAM");
        service.indexFromChannel("UC123", List.of(item));
        verifyNoInteractions(streamRepository);
    }

    @Test
    void indexFromPlaylist_skipsIfPlaylistNotApproved() throws Exception {
        when(playlistRepository.findByYoutubeId("PL123")).thenReturn(Optional.empty());
        service.indexFromPlaylist("PL123", List.of(makeItem("abc12345678", "Test")));
        verifyNoInteractions(streamRepository);
    }

    @Test
    void indexFromPlaylist_skipsExcludedVideo() throws Exception {
        Playlist pl = new Playlist("PL123");
        pl.setStatus("APPROVED");
        pl.setExcludedVideoIds(List.of("abc12345678"));
        when(playlistRepository.findByYoutubeId("PL123")).thenReturn(Optional.of(pl));

        service.indexFromPlaylist("PL123", List.of(makeItem("abc12345678", "Test")));
        verifyNoInteractions(streamRepository);
    }

    @Test
    void markStreamArchived_callsRepositoryMarkInvisible() throws Exception {
        service.markStreamArchived("dQw4w9WgXcQ");
        verify(streamRepository).markInvisible("dQw4w9WgXcQ");
    }

    @Test
    void removeSource_callsRepositoryForEachStream() throws Exception {
        SearchableStream s1 = new SearchableStream(); s1.setStreamId("aaa11111111");
        SearchableStream s2 = new SearchableStream(); s2.setStreamId("bbb22222222");
        when(streamRepository.findBySourceKey("channel:UC123", 500)).thenReturn(List.of(s1, s2));

        service.removeSource("CHANNEL", "UC123");

        verify(streamRepository).removeSource("aaa11111111", "channel:UC123");
        verify(streamRepository).removeSource("bbb22222222", "channel:UC123");
    }

    private StreamItemDto makeItem(String id, String name) {
        StreamItemDto item = new StreamItemDto();
        item.setId(id);
        item.setName(name);
        item.setStreamType("VIDEO");
        item.setThumbnailUrl("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg");
        return item;
    }
}
