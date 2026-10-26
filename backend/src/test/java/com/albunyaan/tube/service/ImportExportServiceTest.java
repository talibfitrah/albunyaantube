package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ImportRequest;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
}
