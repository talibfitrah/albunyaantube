package com.albunyaan.tube.service;

import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SearchableStreamRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Finding 3 (review fix B): the public by-id detail endpoints must NOT serve PERSONAL-visibility
 * items. A PERSONAL item returns 404 (ResourceNotFoundException → Android fails open to NewPipe), so
 * a non-grantee can't fetch the curated DTO or the grantee UID list. PUBLIC and legacy
 * null-visibility items still return normally. Closes the codex-flagged by-id leak (P0).
 */
@ExtendWith(MockitoExtension.class)
class PublicContentServiceVisibilityTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryContentOrderRepository orderRepository;
    @Mock private SearchableStreamRepository searchableStreamRepository;
    @Mock private SearchTokenizer searchTokenizer;

    private PublicContentService service;

    @BeforeEach
    void setUp() {
        service = new PublicContentService(
                channelRepository, playlistRepository, videoRepository,
                categoryRepository, orderRepository,
                Runnable::run,
                searchableStreamRepository,
                searchTokenizer
        );
    }

    private Video video(String visibility) {
        Video v = new Video("vid1");
        v.setStatus("APPROVED");
        v.setVisibility(visibility);
        return v;
    }

    @Test
    void getVideoDetails_personal_throws404() throws Exception {
        when(videoRepository.findByYoutubeId("vid1")).thenReturn(Optional.of(video("PERSONAL")));
        assertThrows(ResourceNotFoundException.class, () -> service.getVideoDetails("vid1"));
    }

    @Test
    void getVideoDetails_public_returns() throws Exception {
        when(videoRepository.findByYoutubeId("vid1")).thenReturn(Optional.of(video("PUBLIC")));
        assertNotNull(service.getVideoDetails("vid1"));
    }

    @Test
    void getVideoDetails_legacyNullVisibility_treatedAsPublic() throws Exception {
        when(videoRepository.findByYoutubeId("vid1")).thenReturn(Optional.of(video(null)));
        assertNotNull(service.getVideoDetails("vid1"));
    }

    @Test
    void getChannelDetails_personal_throws404() throws Exception {
        Channel c = new Channel();
        c.setStatus("APPROVED");
        c.setVisibility("PERSONAL");
        when(channelRepository.findByYoutubeId("ch1")).thenReturn(Optional.of(c));
        assertThrows(ResourceNotFoundException.class, () -> service.getChannelDetails("ch1"));
    }

    @Test
    void getChannelDetails_public_returns() throws Exception {
        Channel c = new Channel();
        c.setStatus("APPROVED");
        c.setVisibility("PUBLIC");
        when(channelRepository.findByYoutubeId("ch1")).thenReturn(Optional.of(c));
        assertNotNull(service.getChannelDetails("ch1"));
    }

    @Test
    void getPlaylistDetails_personal_throws404() throws Exception {
        Playlist p = new Playlist();
        p.setStatus("APPROVED");
        p.setVisibility("PERSONAL");
        when(playlistRepository.findByYoutubeId("pl1")).thenReturn(Optional.of(p));
        assertThrows(ResourceNotFoundException.class, () -> service.getPlaylistDetails("pl1"));
    }
}
