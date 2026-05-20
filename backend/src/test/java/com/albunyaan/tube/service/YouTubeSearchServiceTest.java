package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.SearchHit;
import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.YouTubeSearchResponse;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link YouTubeSearchService}.
 *
 * NewPipe info items are mocked via Mockito because they have complex constructor
 * requirements.  The service only calls getName(), getUrl(), getThumbnails(), and
 * type-specific accessors — all of which are straightforward to stub.
 */
@ExtendWith(MockitoExtension.class)
class YouTubeSearchServiceTest {

    @Mock
    private NewPipeSearchClient newPipeClient;

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private YouTubeGateway gateway;

    private YouTubeSearchService svc;

    @BeforeEach
    void setUp() {
        svc = new YouTubeSearchService(
                newPipeClient, channelRepository, playlistRepository, videoRepository, gateway);
    }

    // =========================================================
    // Channel search — alreadyKnown annotation
    // =========================================================

    @Test
    void search_channels_annotatesAlreadyKnownFromChannelRepo() throws Exception {
        ChannelInfoItem item = mock(ChannelInfoItem.class);
        when(item.getUrl()).thenReturn("https://www.youtube.com/channel/UC123");
        when(item.getName()).thenReturn("Kitten Channel");
        when(item.getSubscriberCount()).thenReturn(1_200L);
        when(item.getThumbnails()).thenReturn(List.of());

        when(newPipeClient.search("kittens", YouTubeContentType.CHANNEL, null))
                .thenReturn(new NewPipeSearchClient.RawPage(List.of(item), null));
        when(gateway.extractChannelId("https://www.youtube.com/channel/UC123"))
                .thenReturn("UC123");

        Channel knownChannel = new Channel();
        knownChannel.setYoutubeId("UC123");
        knownChannel.setStatus("APPROVED");
        when(channelRepository.findByYoutubeIds(List.of("UC123")))
                .thenReturn(Map.of("UC123", knownChannel));

        YouTubeSearchResponse resp = svc.search("kittens", YouTubeContentType.CHANNEL, null);

        assertThat(resp.items()).hasSize(1);
        SearchHit hit = resp.items().get(0);
        assertThat(hit.youtubeId()).isEqualTo("UC123");
        assertThat(hit.name()).isEqualTo("Kitten Channel");
        assertThat(hit.alreadyKnown()).isTrue();
        assertThat(hit.knownStatus()).isEqualTo("APPROVED");
    }

    @Test
    void search_unknownChannel_returnsAlreadyKnownFalse() throws Exception {
        ChannelInfoItem item = mock(ChannelInfoItem.class);
        when(item.getUrl()).thenReturn("https://www.youtube.com/channel/UC999");
        when(item.getName()).thenReturn("New Channel");
        when(item.getSubscriberCount()).thenReturn(-1L);
        when(item.getThumbnails()).thenReturn(List.of());

        when(newPipeClient.search("new", YouTubeContentType.CHANNEL, null))
                .thenReturn(new NewPipeSearchClient.RawPage(List.of(item), null));
        when(gateway.extractChannelId("https://www.youtube.com/channel/UC999"))
                .thenReturn("UC999");
        when(channelRepository.findByYoutubeIds(List.of("UC999")))
                .thenReturn(Map.of());

        YouTubeSearchResponse resp = svc.search("new", YouTubeContentType.CHANNEL, null);

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).alreadyKnown()).isFalse();
        assertThat(resp.items().get(0).knownStatus()).isNull();
    }

    // =========================================================
    // Empty results — repo must NOT be called
    // =========================================================

    @Test
    void search_emptyResults_returnsEmptyResponseAndSkipsRepoLookup() throws Exception {
        when(newPipeClient.search("nothingmatches", YouTubeContentType.CHANNEL, null))
                .thenReturn(new NewPipeSearchClient.RawPage(List.of(), null));

        YouTubeSearchResponse resp = svc.search("nothingmatches", YouTubeContentType.CHANNEL, null);

        assertThat(resp.items()).isEmpty();
        assertThat(resp.nextPageToken()).isNull();
        verifyNoInteractions(channelRepository);
    }

    // =========================================================
    // Subscriber count formatting
    // =========================================================

    @Test
    void search_channel_formatsSubscriberCount_millions() throws Exception {
        ChannelInfoItem item = mock(ChannelInfoItem.class);
        when(item.getUrl()).thenReturn("https://www.youtube.com/channel/UCbig");
        when(item.getName()).thenReturn("Big Channel");
        when(item.getSubscriberCount()).thenReturn(3_500_000L);
        when(item.getThumbnails()).thenReturn(List.of());

        when(newPipeClient.search("big", YouTubeContentType.CHANNEL, null))
                .thenReturn(new NewPipeSearchClient.RawPage(List.of(item), null));
        when(gateway.extractChannelId("https://www.youtube.com/channel/UCbig"))
                .thenReturn("UCbig");
        when(channelRepository.findByYoutubeIds(anyList())).thenReturn(Map.of());

        YouTubeSearchResponse resp = svc.search("big", YouTubeContentType.CHANNEL, null);

        assertThat(resp.items().get(0).secondary()).isEqualTo("3M");
    }

    @Test
    void search_channel_formatsSubscriberCount_thousands() throws Exception {
        ChannelInfoItem item = mock(ChannelInfoItem.class);
        when(item.getUrl()).thenReturn("https://www.youtube.com/channel/UCsmall");
        when(item.getName()).thenReturn("Small Channel");
        when(item.getSubscriberCount()).thenReturn(42_000L);
        when(item.getThumbnails()).thenReturn(List.of());

        when(newPipeClient.search("small", YouTubeContentType.CHANNEL, null))
                .thenReturn(new NewPipeSearchClient.RawPage(List.of(item), null));
        when(gateway.extractChannelId("https://www.youtube.com/channel/UCsmall"))
                .thenReturn("UCsmall");
        when(channelRepository.findByYoutubeIds(anyList())).thenReturn(Map.of());

        YouTubeSearchResponse resp = svc.search("small", YouTubeContentType.CHANNEL, null);

        assertThat(resp.items().get(0).secondary()).isEqualTo("42K");
    }

    // =========================================================
    // Playlist search
    // =========================================================

    @Test
    void search_playlists_annotatesKnownFromPlaylistRepo() throws Exception {
        PlaylistInfoItem item = mock(PlaylistInfoItem.class);
        when(item.getUrl()).thenReturn("https://www.youtube.com/playlist?list=PL123");
        when(item.getName()).thenReturn("Cool Playlist");
        when(item.getUploaderName()).thenReturn("Some Channel");
        when(item.getThumbnails()).thenReturn(List.of());

        when(newPipeClient.search("cool", YouTubeContentType.PLAYLIST, null))
                .thenReturn(new NewPipeSearchClient.RawPage(List.of(item), null));
        when(gateway.extractPlaylistId("https://www.youtube.com/playlist?list=PL123"))
                .thenReturn("PL123");

        Playlist known = new Playlist();
        known.setYoutubeId("PL123");
        known.setStatus("PENDING");
        when(playlistRepository.findByYoutubeIds(List.of("PL123")))
                .thenReturn(Map.of("PL123", known));

        YouTubeSearchResponse resp = svc.search("cool", YouTubeContentType.PLAYLIST, null);

        assertThat(resp.items()).hasSize(1);
        SearchHit hit = resp.items().get(0);
        assertThat(hit.youtubeId()).isEqualTo("PL123");
        assertThat(hit.alreadyKnown()).isTrue();
        assertThat(hit.knownStatus()).isEqualTo("PENDING");
        assertThat(hit.secondary()).isEqualTo("Some Channel");
    }

    // =========================================================
    // Video search
    // =========================================================

    @Test
    void search_videos_annotatesKnownFromVideoRepo() throws Exception {
        StreamInfoItem item = mock(StreamInfoItem.class);
        when(item.getUrl()).thenReturn("https://www.youtube.com/watch?v=abc123");
        when(item.getName()).thenReturn("Great Video");
        when(item.getUploaderName()).thenReturn("Uploader");
        when(item.getThumbnails()).thenReturn(List.of());

        when(newPipeClient.search("great", YouTubeContentType.VIDEO, null))
                .thenReturn(new NewPipeSearchClient.RawPage(List.of(item), null));
        when(gateway.extractVideoId("https://www.youtube.com/watch?v=abc123"))
                .thenReturn("abc123");

        Video known = new Video();
        known.setYoutubeId("abc123");
        known.setStatus("REJECTED");
        when(videoRepository.findByYoutubeIds(List.of("abc123")))
                .thenReturn(Map.of("abc123", known));

        YouTubeSearchResponse resp = svc.search("great", YouTubeContentType.VIDEO, null);

        assertThat(resp.items()).hasSize(1);
        SearchHit hit = resp.items().get(0);
        assertThat(hit.youtubeId()).isEqualTo("abc123");
        assertThat(hit.alreadyKnown()).isTrue();
        assertThat(hit.knownStatus()).isEqualTo("REJECTED");
        assertThat(hit.secondary()).isEqualTo("Uploader");
    }

    // =========================================================
    // Items whose ID cannot be extracted are silently skipped
    // =========================================================

    @Test
    void search_itemWithNullId_isSkipped() throws Exception {
        ChannelInfoItem badItem = mock(ChannelInfoItem.class);
        when(badItem.getUrl()).thenReturn("https://www.youtube.com/user/somehandle");
        // Gateway fails to extract ID for this item — no further stubs needed as service skips it immediately
        when(gateway.extractChannelId("https://www.youtube.com/user/somehandle")).thenReturn(null);

        ChannelInfoItem goodItem = mock(ChannelInfoItem.class);
        when(goodItem.getUrl()).thenReturn("https://www.youtube.com/channel/UCgood");
        when(goodItem.getName()).thenReturn("Good Channel");
        when(goodItem.getSubscriberCount()).thenReturn(500L);
        when(goodItem.getThumbnails()).thenReturn(List.of());
        when(gateway.extractChannelId("https://www.youtube.com/channel/UCgood"))
                .thenReturn("UCgood");

        when(newPipeClient.search("test", YouTubeContentType.CHANNEL, null))
                .thenReturn(new NewPipeSearchClient.RawPage(List.of(badItem, goodItem), null));
        when(channelRepository.findByYoutubeIds(List.of("UCgood"))).thenReturn(Map.of());

        YouTubeSearchResponse resp = svc.search("test", YouTubeContentType.CHANNEL, null);

        // badItem is skipped; only goodItem survives
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).youtubeId()).isEqualTo("UCgood");
    }

    // =========================================================
    // Extraction failure → YouTubeSearchException
    // =========================================================

    @Test
    void search_onExtractionError_throwsYouTubeSearchException() throws Exception {
        when(newPipeClient.search(any(), any(), any()))
                .thenThrow(new RuntimeException("NewPipe down"));

        assertThatThrownBy(() -> svc.search("fail", YouTubeContentType.CHANNEL, null))
                .isInstanceOf(YouTubeSearchException.class)
                .hasMessageContaining("NewPipe down");
    }

    // =========================================================
    // Next page token is forwarded
    // =========================================================

    @Test
    void search_propagatesNextPageToken() throws Exception {
        when(newPipeClient.search("q", YouTubeContentType.VIDEO, null))
                .thenReturn(new NewPipeSearchClient.RawPage(List.of(), "https://page2token"));

        YouTubeSearchResponse resp = svc.search("q", YouTubeContentType.VIDEO, null);

        assertThat(resp.nextPageToken()).isEqualTo("https://page2token");
    }

    // =========================================================
    // ALL type — fans out annotation to all three repos
    // =========================================================

    @Test
    void search_allType_fansOutAnnotationToAllThreeRepos() throws Exception {
        ChannelInfoItem chItem = mock(ChannelInfoItem.class);
        when(chItem.getUrl()).thenReturn("https://www.youtube.com/channel/UCmix");
        when(chItem.getName()).thenReturn("Mix Channel");
        when(chItem.getSubscriberCount()).thenReturn(0L);
        when(chItem.getThumbnails()).thenReturn(List.of());

        PlaylistInfoItem plItem = mock(PlaylistInfoItem.class);
        when(plItem.getUrl()).thenReturn("https://www.youtube.com/playlist?list=PLmix");
        when(plItem.getName()).thenReturn("Mix Playlist");
        when(plItem.getUploaderName()).thenReturn("Mix Channel");
        when(plItem.getThumbnails()).thenReturn(List.of());

        StreamInfoItem vidItem = mock(StreamInfoItem.class);
        when(vidItem.getUrl()).thenReturn("https://www.youtube.com/watch?v=mixvid1");
        when(vidItem.getName()).thenReturn("Mix Video");
        when(vidItem.getUploaderName()).thenReturn("Mix Channel");
        when(vidItem.getThumbnails()).thenReturn(List.of());

        when(newPipeClient.search("mix", YouTubeContentType.ALL, null))
                .thenReturn(new NewPipeSearchClient.RawPage(List.of(chItem, plItem, vidItem), null));
        when(gateway.extractChannelId("https://www.youtube.com/channel/UCmix")).thenReturn("UCmix");
        when(gateway.extractPlaylistId("https://www.youtube.com/playlist?list=PLmix")).thenReturn("PLmix");
        when(gateway.extractVideoId("https://www.youtube.com/watch?v=mixvid1")).thenReturn("mixvid1");

        Channel knownChannel = new Channel();
        knownChannel.setYoutubeId("UCmix");
        knownChannel.setStatus("APPROVED");
        when(channelRepository.findByYoutubeIds(List.of("UCmix"))).thenReturn(Map.of("UCmix", knownChannel));
        when(playlistRepository.findByYoutubeIds(List.of("PLmix"))).thenReturn(Map.of());
        when(videoRepository.findByYoutubeIds(List.of("mixvid1"))).thenReturn(Map.of());

        YouTubeSearchResponse resp = svc.search("mix", YouTubeContentType.ALL, null);

        assertThat(resp.items()).hasSize(3);

        SearchHit chHit = resp.items().stream().filter(h -> "UCmix".equals(h.youtubeId())).findFirst().orElseThrow();
        assertThat(chHit.alreadyKnown()).isTrue();
        assertThat(chHit.knownStatus()).isEqualTo("APPROVED");
        assertThat(chHit.contentType()).isEqualTo("CHANNEL");

        SearchHit plHit = resp.items().stream().filter(h -> "PLmix".equals(h.youtubeId())).findFirst().orElseThrow();
        assertThat(plHit.alreadyKnown()).isFalse();
        assertThat(plHit.contentType()).isEqualTo("PLAYLIST");

        SearchHit vidHit = resp.items().stream().filter(h -> "mixvid1".equals(h.youtubeId())).findFirst().orElseThrow();
        assertThat(vidHit.alreadyKnown()).isFalse();
        assertThat(vidHit.contentType()).isEqualTo("VIDEO");

        verify(channelRepository).findByYoutubeIds(List.of("UCmix"));
        verify(playlistRepository).findByYoutubeIds(List.of("PLmix"));
        verify(videoRepository).findByYoutubeIds(List.of("mixvid1"));
    }

    // =========================================================
    // ALL type — only one content type present → other repos skipped
    // =========================================================

    @Test
    void search_allType_withOnlyChannels_doesNotQueryPlaylistOrVideoRepos() throws Exception {
        ChannelInfoItem chItem = mock(ChannelInfoItem.class);
        when(chItem.getUrl()).thenReturn("https://www.youtube.com/channel/UConly");
        when(chItem.getName()).thenReturn("Only Channel");
        when(chItem.getSubscriberCount()).thenReturn(0L);
        when(chItem.getThumbnails()).thenReturn(List.of());

        when(newPipeClient.search("only", YouTubeContentType.ALL, null))
                .thenReturn(new NewPipeSearchClient.RawPage(List.of(chItem), null));
        when(gateway.extractChannelId("https://www.youtube.com/channel/UConly")).thenReturn("UConly");
        when(channelRepository.findByYoutubeIds(List.of("UConly"))).thenReturn(Map.of());

        YouTubeSearchResponse resp = svc.search("only", YouTubeContentType.ALL, null);

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).contentType()).isEqualTo("CHANNEL");
        verify(channelRepository).findByYoutubeIds(List.of("UConly"));
        verify(playlistRepository, never()).findByYoutubeIds(any());
        verify(videoRepository, never()).findByYoutubeIds(any());
    }

    // =========================================================
    // ReCaptchaException → YouTubeSearchRateLimitedException
    // =========================================================

    @Test
    void search_reCaptchaException_throwsRateLimitedWithRetryAfter60() throws Exception {
        when(newPipeClient.search(any(), any(), any()))
                .thenThrow(new ReCaptchaException("rate limited", "https://youtube.com"));

        assertThatThrownBy(() -> svc.search("q", YouTubeContentType.CHANNEL, null))
                .isInstanceOf(YouTubeSearchRateLimitedException.class)
                .satisfies(ex -> assertThat(((YouTubeSearchRateLimitedException) ex).getRetryAfterSec())
                        .isEqualTo(60L));
    }

    // =========================================================
    // Thumbnail: null list returns null URL
    // =========================================================

    @Test
    void search_nullThumbnails_thumbnailUrlIsNull() throws Exception {
        ChannelInfoItem item = mock(ChannelInfoItem.class);
        when(item.getUrl()).thenReturn("https://www.youtube.com/channel/UCthumb");
        when(item.getName()).thenReturn("No Thumb");
        when(item.getSubscriberCount()).thenReturn(0L);
        when(item.getThumbnails()).thenReturn(null);

        when(newPipeClient.search("thumb", YouTubeContentType.CHANNEL, null))
                .thenReturn(new NewPipeSearchClient.RawPage(List.of(item), null));
        when(gateway.extractChannelId("https://www.youtube.com/channel/UCthumb"))
                .thenReturn("UCthumb");
        when(channelRepository.findByYoutubeIds(anyList())).thenReturn(Map.of());

        YouTubeSearchResponse resp = svc.search("thumb", YouTubeContentType.CHANNEL, null);

        assertThat(resp.items().get(0).thumbnailUrl()).isNull();
    }
}
