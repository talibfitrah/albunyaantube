package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.ValidationRunRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.CategoryMappingService;
import com.albunyaan.tube.service.ChannelOrchestrator;
import com.albunyaan.tube.service.ContentImportService;
import com.albunyaan.tube.service.ImportExportService;
import com.albunyaan.tube.service.LiveCategoryGuard;
import com.albunyaan.tube.service.LiveCategoryService;
import com.albunyaan.tube.service.LiveChannelService;
import com.albunyaan.tube.service.SimpleExportService;
import com.albunyaan.tube.service.SimpleImportService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §7 row 16, sync simple import: the live guard (here) and the writer (SimpleImportService) see one existence read. */
class ImportExportControllerTest {

    /** A LIVE row the guard saw stored is skipped unread; re-reading it would write a doc deleted in between, unchecked. */
    @Test
    void aLiveRowTheGuardSawStoredIsSkippedEvenIfDeletedSince() throws Exception {
        ChannelOrchestrator orchestrator = mock(ChannelOrchestrator.class);
        CategoryMappingService mapping = mock(CategoryMappingService.class);
        ChannelRepository channels = mock(ChannelRepository.class);
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        VideoRepository videos = mock(VideoRepository.class);
        LiveCategoryService liveCategories = mock(LiveCategoryService.class);
        when(liveCategories.liveCategoryIds()).thenReturn(Set.of("live-cat"));
        when(mapping.mapCategoryNamesToIds("Live")).thenReturn(List.of("live-cat"));
        when(playlists.findByYoutubeId("PLgone"))
                .thenReturn(Optional.of(new Playlist("PLgone")))   // the guard's read: stored, so no check
                .thenReturn(Optional.empty());                     // deleted before the writer gets there
        PlaylistDetailsDto dto = new PlaylistDetailsDto();
        dto.setId("PLgone");
        dto.setName("P");
        when(orchestrator.validateAndFetchPlaylistDto("PLgone")).thenReturn(dto);
        LiveCategoryGuard guard = new LiveCategoryGuard(liveCategories, mock(LiveChannelService.class), videos,
                channels, playlists, mapping);
        ImportExportController controller = new ImportExportController(mock(ImportExportService.class),
                new SimpleImportService(orchestrator, mapping, channels, playlists, videos), mock(SimpleExportService.class),
                mock(ContentImportService.class), mock(ValidationRunRepository.class), Runnable::run, guard);

        controller.importSimpleFormat(new MockMultipartFile("file", "import.json", "application/json",
                        "[{}, {\"PLgone\": \"P|Live\"}, {}]".getBytes(StandardCharsets.UTF_8)),
                "APPROVED", new FirebaseUserDetails("admin-uid", "admin@test.com", "ADMIN"));

        verify(playlists, never()).save(any(Playlist.class));
        verify(playlists, times(1)).findByYoutubeId("PLgone");
    }
}
