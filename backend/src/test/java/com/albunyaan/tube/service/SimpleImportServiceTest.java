package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.dto.SimpleImportResponse;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleImportServiceTest {

    @Mock
    private YouTubeService youTubeService;

    @Mock
    private CategoryMappingService categoryMappingService;

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private VideoRepository videoRepository;

    private SimpleImportService service;

    @BeforeEach
    void setUp() {
        service = new SimpleImportService(
                youTubeService,
                categoryMappingService,
                channelRepository,
                playlistRepository,
                videoRepository
        );
    }

    @Test
    void importSimpleFormat_channelUsesDtoMetadata() throws Exception {
        Map<String, String> channels = Map.of("UC123", "Fallback Title|CatA");
        List<Map<String, String>> payload = List.of(channels, Collections.emptyMap(), Collections.emptyMap());

        ChannelDetailsDto dto = new ChannelDetailsDto();
        dto.setId("UC123");
        dto.setName("DTO Channel");
        dto.setDescription("DTO Description");
        dto.setThumbnailUrl("https://img.example/channel.jpg");
        dto.setSubscriberCount(5_000L);

        when(channelRepository.findByYoutubeId("UC123")).thenReturn(Optional.empty());
        when(youTubeService.validateAndFetchChannelDto("UC123")).thenReturn(dto);
        when(categoryMappingService.mapCategoryNamesToIds("CatA")).thenReturn(List.of("cat-1"));
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SimpleImportResponse response = service.importSimpleFormat(payload, "APPROVED", "user-1", false);

        ArgumentCaptor<Channel> channelCaptor = ArgumentCaptor.forClass(Channel.class);
        verify(channelRepository).save(channelCaptor.capture());
        Channel saved = channelCaptor.getValue();

        assertEquals("DTO Channel", saved.getName());
        assertEquals("DTO Description", saved.getDescription());
        assertEquals("https://img.example/channel.jpg", saved.getThumbnailUrl());
        assertEquals(5_000L, saved.getSubscribers());
        assertEquals(List.of("cat-1"), saved.getCategoryIds());
        assertTrue(response.isSuccess());
    }

    @Test
    void importSimpleFormat_playlistMapsDtoFields() throws Exception {
        Map<String, String> playlists = Map.of("PL123", "Playlist Title|CatB");
        List<Map<String, String>> payload = List.of(Collections.emptyMap(), playlists, Collections.emptyMap());

        PlaylistDetailsDto dto = new PlaylistDetailsDto();
        dto.setId("PL123");
        dto.setName("DTO Playlist");
        dto.setDescription("Playlist description");
        dto.setThumbnailUrl("https://img.example/playlist.jpg");
        dto.setStreamCount(42L);

        when(playlistRepository.findByYoutubeId("PL123")).thenReturn(Optional.empty());
        when(youTubeService.validateAndFetchPlaylistDto("PL123")).thenReturn(dto);
        when(categoryMappingService.mapCategoryNamesToIds("CatB")).thenReturn(List.of("cat-2"));
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.importSimpleFormat(payload, "PENDING", "user-2", false);

        ArgumentCaptor<Playlist> playlistCaptor = ArgumentCaptor.forClass(Playlist.class);
        verify(playlistRepository).save(playlistCaptor.capture());
        Playlist saved = playlistCaptor.getValue();

        assertEquals("DTO Playlist", saved.getTitle());
        assertEquals("Playlist description", saved.getDescription());
        assertEquals("https://img.example/playlist.jpg", saved.getThumbnailUrl());
        assertEquals(42, saved.getItemCount());
        assertEquals(List.of("cat-2"), saved.getCategoryIds());
    }

    @Test
    void importSimpleFormat_videoMapsDtoFields() throws Exception {
        Map<String, String> videos = Map.of("VID123", "Video Title|CatC");
        List<Map<String, String>> payload = List.of(Collections.emptyMap(), Collections.emptyMap(), videos);

        StreamDetailsDto dto = new StreamDetailsDto();
        dto.setId("VID123");
        dto.setName("DTO Video");
        dto.setDescription("Video description");
        dto.setUploaderName("Uploader Name");
        dto.setUploaderUrl("https://www.youtube.com/channel/UCCHAN123");
        dto.setThumbnailUrl("https://img.example/video.jpg");
        dto.setDuration(180L);
        dto.setViewCount(1_000L);

        when(videoRepository.findByYoutubeId("VID123")).thenReturn(Optional.empty());
        when(youTubeService.validateAndFetchVideoDto("VID123")).thenReturn(dto);
        when(categoryMappingService.mapCategoryNamesToIds("CatC")).thenReturn(List.of("cat-3"));
        when(videoRepository.save(any(Video.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.importSimpleFormat(payload, "APPROVED", "user-3", false);

        ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
        verify(videoRepository).save(videoCaptor.capture());
        Video saved = videoCaptor.getValue();

        assertEquals("DTO Video", saved.getTitle());
        assertEquals("Video description", saved.getDescription());
        assertEquals("UCCHAN123", saved.getChannelId());
        assertEquals("Uploader Name", saved.getChannelTitle());
        assertEquals("https://img.example/video.jpg", saved.getThumbnailUrl());
        assertEquals(180, saved.getDurationSeconds());
        assertEquals(1_000L, saved.getViewCount());
        assertEquals(List.of("cat-3"), saved.getCategoryIds());
    }
}
