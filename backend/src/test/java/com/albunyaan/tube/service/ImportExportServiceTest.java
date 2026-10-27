package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ImportRequest;
import com.albunyaan.tube.dto.ImportResponse;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §7 row 15: the JSON import reads each stored doc once, so the live guard and the write see the same doc. */
class ImportExportServiceTest {

    final CategoryRepository categories = mock(CategoryRepository.class);
    final ChannelRepository channels = mock(ChannelRepository.class);
    final PlaylistRepository playlists = mock(PlaylistRepository.class);
    final VideoRepository videos = mock(VideoRepository.class);
    final ImportExportService service = new ImportExportService(categories, channels, playlists, videos,
            mock(AuditLogService.class), mock(VideoValidationService.class), mock(LiveCategoryGuard.class));

    @Test
    void eachStoredDocIsReadOnce() throws Exception {
        Category category = new Category("C", null);
        category.setId("cat-1");
        Channel channel = new Channel("UCx");
        channel.setId("ch-1");
        Playlist playlist = new Playlist("PLx");
        playlist.setId("pl-1");
        Video video = new Video("vidx");
        video.setId("v-1");
        when(categories.findById("cat-1")).thenReturn(Optional.of(new Category("C", null)));
        when(channels.findById("ch-1")).thenReturn(Optional.of(new Channel("UCx")));
        when(playlists.findById("pl-1")).thenReturn(Optional.of(new Playlist("PLx")));
        when(videos.findById("v-1")).thenReturn(Optional.of(new Video("vidx")));
        ImportRequest request = new ImportRequest();
        request.setCategories(List.of(category));
        request.setChannels(List.of(channel));
        request.setPlaylists(List.of(playlist));
        request.setVideos(List.of(video));
        request.setMergeStrategy("MERGE");

        service.importAll(request);

        verify(categories).findById("cat-1");
        verify(channels).findById("ch-1");
        verify(playlists).findById("pl-1");
        verify(videos).findById("v-1");
        verify(channels).save(channel);
    }

    /**
     * Stage 9 B14 (Cubic r5 #4): takeDownExclusions is server-only, so an export leaves it out. An overwrite or a merge
     * of the stored doc keeps it, or a later Restore of that take-down lifts nothing.
     */
    @Test
    void anOverwriteOrAMergeKeepsTheStoredDocsTakeDownExclusions() throws Exception {
        Video stored = new Video("ilgiwqpcOC8");
        stored.setId("v-takedown");
        stored.setStatus("REJECTED");
        stored.setSource("REPORT");
        stored.setTakeDownExclusions(List.of("channel:UCfassTU6mGWIj-yb26_ADjQ"));
        when(videos.findById("v-takedown")).thenReturn(Optional.of(stored));
        for (String strategy : List.of("OVERWRITE", "MERGE")) {
            Video imported = new Video("ilgiwqpcOC8");                   // as exported: no takeDownExclusions
            imported.setId("v-takedown");
            imported.setStatus("REJECTED");
            imported.setSource("REPORT");
            ImportRequest request = new ImportRequest();
            request.setVideos(List.of(imported));
            request.setMergeStrategy(strategy);

            service.importAll(request);

            verify(videos).save(imported);
            assertThat(imported.getTakeDownExclusions()).as(strategy).containsExactly("channel:UCfassTU6mGWIj-yb26_ADjQ");
        }
    }

    /**
     * Stage 9 B21 (Cubic r9 #3): an older backup holds the video's doc from before a report took it down. Written beside
     * the take-down record (another doc id), it is the APPROVED copy findByYoutubeId prefers: served again. It is skipped
     * and named, as the simple and async imports skip an id that has a doc.
     */
    @Test
    void aVideoAReportTookDownIsSkippedAndNamed() throws Exception {
        Video record = new Video("ilgiwqpcOC8");
        record.setId("v-takedown");
        record.setStatus("REJECTED");
        record.setSource("REPORT");
        when(videos.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.of(record));
        for (String strategy : List.of("SKIP", "OVERWRITE", "MERGE")) {
            Video backedUp = new Video("ilgiwqpcOC8");
            backedUp.setId("v-before");
            backedUp.setStatus("APPROVED");
            ImportRequest request = new ImportRequest();
            request.setVideos(List.of(backedUp));
            request.setMergeStrategy(strategy);

            ImportResponse out = service.importAll(request);

            verify(videos, never()).save(backedUp);
            assertThat(out.getCounts().getVideosSkipped()).as(strategy).isEqualTo(1);
            assertThat(out.getErrors()).as(strategy).singleElement().satisfies(e -> {
                assertThat(e.getId()).isEqualTo("v-before");
                assertThat(e.getError()).contains("ilgiwqpcOC8").contains("taken down");
            });
        }
    }

    /**
     * Stage 9 B22 (Cubic r10 #3): a take-down record has no title, and a full export holds it. The dry run accepts it, as
     * the import writes it; a video with no title that is no take-down record is still an error.
     */
    @Test
    void theDryRunAcceptsATakeDownRecordAsTheImportDoes() {
        Video record = new Video("ilgiwqpcOC8");
        record.setId("v-takedown");
        record.setStatus("REJECTED");
        record.setSource("REPORT");
        Video untitled = new Video("NoTitle0001");
        untitled.setId("v-untitled");
        ImportRequest request = new ImportRequest();
        request.setVideos(List.of(record, untitled));

        ImportResponse out = service.validateImport(request);

        assertThat(out.getCounts().getVideosImported()).isEqualTo(1);
        assertThat(out.getErrors()).singleElement().satisfies(e -> {
            assertThat(e.getId()).isEqualTo("v-untitled");
            assertThat(e.getError()).isEqualTo("Missing video title");
        });
    }

    /**
     * Stage 9 B22 (Cubic r10 #4): an archived channel left the poller's targets, so its stored state keeps the lives it
     * had then. A file that serves it again (VALID over the stored ARCHIVED) re-enters it as an add (D15, B14): seeded,
     * and a seed YouTube does not answer serves none of those lives.
     */
    @Test
    void aChannelTheImportServesAgainServesNoneOfItsFrozenLives() throws Exception {
        String uc = "UCROKYPep-UuODNwyipe6JMw";
        LiveTabReader reader = mock(LiveTabReader.class);
        LiveChannelService live = new LiveChannelService(reader, mock(LiveChannelStateRepository.class), Clock.systemUTC());
        LiveCategoryService liveCategories = mock(LiveCategoryService.class);
        ImportExportService importer = new ImportExportService(categories, channels, playlists, videos,
                mock(AuditLogService.class), mock(VideoValidationService.class),
                new LiveCategoryGuard(liveCategories, live, videos, channels, playlists, new CategoryMappingService(categories)));
        when(liveCategories.liveCategoryIds()).thenReturn(Set.of("live-cat"));
        when(reader.read(uc)).thenReturn(LiveTabResult.error(1, "boom"));
        for (String strategy : List.of("OVERWRITE", "MERGE")) {
            LiveChannelState frozen = LiveChannelState.unknown(uc);           // as the poller left it when archived
            frozen.setStatus(LiveChannelState.LIVE);
            frozen.getLives().add(new LiveChannelState.Live("Rs7St51oDDc", "Sunnah"));
            live.loadStates(List.of(frozen));
            Channel stored = liveChannel(uc, ValidationStatus.ARCHIVED);
            when(channels.findById("ch-live")).thenReturn(Optional.of(stored));
            when(liveCategories.members("live-cat")).thenReturn(new LiveCategoryService.Members(List.of(stored)));
            Channel backedUp = liveChannel(uc, ValidationStatus.VALID);
            ImportRequest request = new ImportRequest();
            request.setChannels(List.of(backedUp));
            request.setMergeStrategy(strategy);

            importer.importAll(request);

            verify(channels, atLeastOnce()).save(backedUp);
            assertThat(LiveCategoryService.servedLives(backedUp, live.state(uc), Map.of())).as(strategy).isEmpty();
        }
    }

    private static Channel liveChannel(String uc, ValidationStatus validation) {
        Channel c = new Channel(uc);
        c.setId("ch-live");
        c.setStatus("APPROVED");
        c.setCategoryIds(List.of("live-cat"));
        c.setValidationStatus(validation);
        return c;
    }
}
