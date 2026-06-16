package com.albunyaan.tube.util;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.service.ChannelOrchestrator;
import com.albunyaan.tube.service.YouTubeOEmbedClient;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.channel.ChannelInfo;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the repair wiring: scan approved content → detect-broken →
 * re-extract → targeted field update, while leaving healthy records untouched
 * and never clobbering curated channel descriptions. The Firestore CAS lock is
 * stubbed (it mirrors the integration-tested UserBackfillMigration).
 */
class ThumbnailRepairMigrationTest {

    private static final String ZAD_ID = "UCBoe29aQT-zMECFyyyO7H4Q";
    private static final String GOOD_CHANNEL_ID = "UCgoodgoodgoodgoodgood1";
    private static final String BROKEN_PL_ID = "PLlZazEh_c4nScNCvGBn8OEf6ujk-sDUpg";
    private static final String GOOD_PL_ID = "PLgoodgoodgoodgoodgoodgood";
    private static final String FRESH_AVATAR =
            "https://yt3.googleusercontent.com/freshAvatarToken=s800-c-k-c0x00ffffff-no-rj";

    private ChannelRepository channelRepository;
    private PlaylistRepository playlistRepository;
    private ChannelOrchestrator orchestrator;
    private YouTubeOEmbedClient oEmbedClient;
    private ThumbnailRepairMigration migration;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        Firestore firestore = mock(Firestore.class);
        channelRepository = mock(ChannelRepository.class);
        playlistRepository = mock(PlaylistRepository.class);
        orchestrator = mock(ChannelOrchestrator.class);
        oEmbedClient = mock(YouTubeOEmbedClient.class);
        FirestoreTimeoutProperties timeouts = mock(FirestoreTimeoutProperties.class);
        when(timeouts.getWrite()).thenReturn(5L);

        // Sensible empty defaults; each test overrides what it exercises.
        when(channelRepository.findByStatus("APPROVED")).thenReturn(List.of());
        when(playlistRepository.findAllByOrderByItemCountDesc()).thenReturn(List.of());
        when(oEmbedClient.playlistThumbnailUrl(anyString())).thenReturn(Optional.empty());

        // Stub the CAS lock: claim succeeds, release is a completed write.
        CollectionReference settings = mock(CollectionReference.class);
        DocumentReference lockRef = mock(DocumentReference.class);
        when(firestore.collection("system_settings")).thenReturn(settings);
        when(settings.document(anyString())).thenReturn(lockRef);
        doReturn(ApiFutures.immediateFuture(Boolean.TRUE)).when(firestore).runTransaction(any());
        when(lockRef.update(anyString(), any(), any(), any()))
                .thenReturn(ApiFutures.immediateFuture(mock(WriteResult.class)));

        migration = new ThumbnailRepairMigration(
                firestore, channelRepository, playlistRepository, orchestrator, oEmbedClient, timeouts);
    }

    @Test
    void run_repairsStubChannel_refreshingSyntheticMetadata_andSkipsHealthyOne() throws Exception {
        Channel broken = channel("ch-broken", ZAD_ID, "https://yt3.ggpht.com/ytc/" + ZAD_ID,
                "YouTube channel: Zad academy");
        Channel healthy = channel("ch-good", GOOD_CHANNEL_ID,
                "https://yt3.googleusercontent.com/ytc/AIdro_real=s72-c-k-c0x00ffffff-no-rj", "real desc");
        when(channelRepository.findByStatus("APPROVED")).thenReturn(List.of(broken, healthy));

        ChannelInfo info = channelInfo(FRESH_AVATAR, "Real channel description", 123_456L);
        when(orchestrator.validateAndFetchChannel(ZAD_ID)).thenReturn(info);

        ThumbnailRepairMigration.RunSummary summary = migration.run("admin-uid");

        assertEquals(2, summary.channelsScanned());
        assertEquals(1, summary.channelsRepaired());
        assertEquals(0, summary.failures());
        // Targeted update with the fresh avatar; synthetic stub description + subs refreshed.
        verify(channelRepository).updateMetadata("ch-broken", FRESH_AVATAR, "Real channel description", 123_456L);
        // Healthy channel never touched.
        verify(orchestrator, never()).validateAndFetchChannel(GOOD_CHANNEL_ID);
        verify(channelRepository, never()).updateMetadata(eq("ch-good"), anyString(), any(), any());
    }

    @Test
    void run_brokenAvatarButCuratedDescription_repairsAvatarOnly_doesNotClobberDescription() throws Exception {
        // Avatar is missing (broken) but description is real/curated — must NOT be overwritten.
        Channel broken = channel("ch-curated", ZAD_ID, null, "Hand-written curated description by an admin");
        when(channelRepository.findByStatus("APPROVED")).thenReturn(List.of(broken));
        ChannelInfo info = channelInfo(FRESH_AVATAR, "Generic NewPipe description", 999L);
        when(orchestrator.validateAndFetchChannel(ZAD_ID)).thenReturn(info);

        migration.run("admin-uid");

        // description + subscribers passed as null → repository leaves them untouched.
        verify(channelRepository).updateMetadata("ch-curated", FRESH_AVATAR, null, null);
    }

    @Test
    void run_repairsBrokenPlaylist_withOEmbedViThumbnail() throws Exception {
        Playlist broken = playlist("pl-broken", BROKEN_PL_ID,
                "https://i.ytimg.com/pl_c/" + BROKEN_PL_ID + "/studio_square_thumbnail.jpg?days_since_epoch=20603");
        Playlist healthy = playlist("pl-good", GOOD_PL_ID, "https://i.ytimg.com/vi/ABCDEFGHIJK/hqdefault.jpg");
        when(playlistRepository.findAllByOrderByItemCountDesc()).thenReturn(List.of(broken, healthy));
        when(oEmbedClient.playlistThumbnailUrl(BROKEN_PL_ID))
                .thenReturn(Optional.of("https://i.ytimg.com/vi/sQMC7fkjmOA/hqdefault.jpg"));

        ThumbnailRepairMigration.RunSummary summary = migration.run("admin-uid");

        assertEquals(2, summary.playlistsScanned());
        assertEquals(1, summary.playlistsRepaired());
        assertEquals(0, summary.failures());
        verify(playlistRepository).updateThumbnailUrl("pl-broken", "https://i.ytimg.com/vi/sQMC7fkjmOA/hqdefault.jpg");
        verify(oEmbedClient, never()).playlistThumbnailUrl(GOOD_PL_ID);
        verify(playlistRepository, never()).updateThumbnailUrl(eq("pl-good"), anyString());
    }

    @Test
    void run_recordsFailedId_whenReExtractionReturnsNull() throws Exception {
        Channel broken = channel("ch-broken", ZAD_ID, "https://yt3.ggpht.com/ytc/" + ZAD_ID, "stub");
        when(channelRepository.findByStatus("APPROVED")).thenReturn(List.of(broken));
        when(orchestrator.validateAndFetchChannel(ZAD_ID)).thenReturn(null);

        ThumbnailRepairMigration.RunSummary summary = migration.run("admin-uid");

        assertEquals(0, summary.channelsRepaired());
        assertEquals(1, summary.failures());
        assertEquals(List.of(ZAD_ID), summary.failedChannelIds());
        verify(channelRepository, never()).updateMetadata(anyString(), anyString(), any(), any());
    }

    private static ChannelInfo channelInfo(String avatarUrl, String description, long subscribers) {
        Image avatar = mock(Image.class);
        when(avatar.getUrl()).thenReturn(avatarUrl);
        when(avatar.getHeight()).thenReturn(800);
        ChannelInfo info = mock(ChannelInfo.class);
        when(info.getAvatars()).thenReturn(List.of(avatar));
        when(info.getDescription()).thenReturn(description);
        when(info.getSubscriberCount()).thenReturn(subscribers);
        return info;
    }

    private static Channel channel(String id, String youtubeId, String thumbnailUrl, String description) {
        Channel c = new Channel();
        c.setId(id);
        c.setYoutubeId(youtubeId);
        c.setThumbnailUrl(thumbnailUrl);
        c.setDescription(description);
        return c;
    }

    private static Playlist playlist(String id, String youtubeId, String thumbnailUrl) {
        Playlist p = new Playlist();
        p.setId(id);
        p.setYoutubeId(youtubeId);
        p.setThumbnailUrl(thumbnailUrl);
        return p;
    }
}
