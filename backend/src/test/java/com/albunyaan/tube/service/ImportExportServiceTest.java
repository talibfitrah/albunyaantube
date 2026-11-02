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
        when(videos.findAllByYoutubeId("ilgiwqpcOC8")).thenReturn(List.of(record));
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
     * Cubic r14: a take-down archives the doc in place (B23), and so does validation. A backup row of the same youtubeId
     * under another doc id would be a second, VALID doc: served again. Each type skips it and names it.
     */
    @Test
    void aRowBesideAnArchivedDocOfItsIdIsSkippedAndNamed() throws Exception {
        Channel archivedChannel = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        archivedChannel.setId("ch-held");
        archivedChannel.setStatus("APPROVED");
        archivedChannel.setValidationStatus(ValidationStatus.ARCHIVED);
        Playlist archivedPlaylist = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        archivedPlaylist.setId("pl-held");
        archivedPlaylist.setStatus("APPROVED");
        archivedPlaylist.setValidationStatus(ValidationStatus.ARCHIVED);
        Video archivedVideo = new Video("ilgiwqpcOC8");
        archivedVideo.setId("v-held");
        archivedVideo.setStatus("APPROVED");
        archivedVideo.setValidationStatus(ValidationStatus.ARCHIVED);
        when(channels.findAllByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(List.of(archivedChannel));
        when(playlists.findAllByYoutubeId("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe")).thenReturn(List.of(archivedPlaylist));
        when(videos.findAllByYoutubeId("ilgiwqpcOC8")).thenReturn(List.of(archivedVideo));
        for (String strategy : List.of("SKIP", "OVERWRITE", "MERGE")) {
            Channel channel = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
            channel.setId("ch-before");
            channel.setStatus("APPROVED");
            Playlist playlist = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
            playlist.setId("pl-before");
            playlist.setStatus("APPROVED");
            Video video = new Video("ilgiwqpcOC8");
            video.setId("v-before");
            video.setStatus("APPROVED");
            ImportRequest request = new ImportRequest();
            request.setChannels(List.of(channel));
            request.setPlaylists(List.of(playlist));
            request.setVideos(List.of(video));
            request.setMergeStrategy(strategy);

            ImportResponse out = service.importAll(request);

            verify(channels, never()).save(channel);
            verify(playlists, never()).save(playlist);
            verify(videos, never()).save(video);
            assertThat(List.of(out.getCounts().getChannelsSkipped(), out.getCounts().getPlaylistsSkipped(),
                    out.getCounts().getVideosSkipped())).as(strategy).containsExactly(1, 1, 1);
            assertThat(out.getErrors()).as(strategy).extracting(e -> e.getType() + ":" + e.getId())
                    .containsExactly("CHANNEL:ch-before", "PLAYLIST:pl-before", "VIDEO:v-before");
        }
    }

    /**
     * Cubic users4: every doc of the youtubeId counts, not only the one findByYoutubeId prefers. A PENDING doc and a
     * REJECTED one archived by a take-down (in place, then rejected): the row is skipped.
     */
    @Test
    void aRowBesideAnArchivedDocTheLookupDoesNotPreferIsSkipped() throws Exception {
        Video pending = new Video("ilgiwqpcOC8");
        pending.setId("v-pending");
        pending.setStatus("PENDING");
        Video takenDown = new Video("ilgiwqpcOC8");
        takenDown.setId("v-held");
        takenDown.setStatus("REJECTED");
        takenDown.setValidationStatus(ValidationStatus.ARCHIVED);
        when(videos.findByYoutubeId("ilgiwqpcOC8")).thenReturn(Optional.of(pending));
        when(videos.findAllByYoutubeId("ilgiwqpcOC8")).thenReturn(List.of(pending, takenDown));
        Video row = new Video("ilgiwqpcOC8");
        row.setId("v-third");
        row.setStatus("APPROVED");
        ImportRequest request = new ImportRequest();
        request.setVideos(List.of(row));
        request.setMergeStrategy("OVERWRITE");

        ImportResponse out = service.importAll(request);

        verify(videos, never()).save(row);
        assertThat(out.getCounts().getVideosSkipped()).isEqualTo(1);
    }

    /**
     * Cubic users5: a row whose own doc is stored and served overwrites that doc (kept() keeps its own hold): no second
     * doc, nothing brought back, so another doc's archive does not skip it. A row whose own doc is not served (here
     * REJECTED) still is: written APPROVED, it would serve the id beside the take-down.
     */
    @Test
    void aRowOverItsOwnServedDocIsNotSkippedForAnotherDocsArchive() throws Exception {
        Video legacy = new Video("ilgiwqpcOC8");
        legacy.setId("v-legacy");
        legacy.setStatus("APPROVED");
        legacy.setValidationStatus(ValidationStatus.UNAVAILABLE);
        Video served = new Video("ilgiwqpcOC8");
        served.setId("v-served");
        served.setStatus("APPROVED");
        Video rejected = new Video("ilgiwqpcOC8");
        rejected.setId("v-rejected");
        rejected.setStatus("REJECTED");
        when(videos.findById("v-served")).thenReturn(Optional.of(served));
        when(videos.findById("v-rejected")).thenReturn(Optional.of(rejected));
        when(videos.findAllByYoutubeId("ilgiwqpcOC8")).thenReturn(List.of(legacy, served, rejected));
        Video servedRow = new Video("ilgiwqpcOC8");
        servedRow.setId("v-served");
        servedRow.setStatus("APPROVED");
        Video rejectedRow = new Video("ilgiwqpcOC8");
        rejectedRow.setId("v-rejected");
        rejectedRow.setStatus("APPROVED");
        ImportRequest request = new ImportRequest();
        request.setVideos(List.of(servedRow, rejectedRow));
        request.setMergeStrategy("OVERWRITE");

        ImportResponse out = service.importAll(request);

        verify(videos).save(servedRow);
        verify(videos, never()).save(rejectedRow);
        assertThat(out.getErrors()).extracting(e -> e.getId()).containsExactly("v-rejected");
    }

    /**
     * Cubic b26-r1: a row whose stored doc is APPROVED but which now carries another youtubeId is a new item for that
     * id: a take-down of it skips the row (record or archived doc), as for a new doc.
     */
    @Test
    void aRowThatMovesItsServedDocToATakenDownIdIsSkipped() throws Exception {
        Video storedVideo = new Video("ilgiwqpcOC8");
        storedVideo.setId("v1");
        storedVideo.setStatus("APPROVED");
        Video record = new Video("xc7keR2piUM");
        record.setId("v-takedown");
        record.setStatus("REJECTED");
        record.setSource("REPORT");
        record.setValidationStatus(ValidationStatus.ARCHIVED);
        Channel storedChannel = new Channel("UCOll3M-P7oKs5cSrQ9ytt6g");
        storedChannel.setId("ch-1");
        storedChannel.setStatus("APPROVED");
        Channel archived = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        archived.setId("ch-held");
        archived.setStatus("APPROVED");
        archived.setValidationStatus(ValidationStatus.ARCHIVED);
        when(videos.findById("v1")).thenReturn(Optional.of(storedVideo));
        when(videos.findAllByYoutubeId("xc7keR2piUM")).thenReturn(List.of(record));
        when(channels.findById("ch-1")).thenReturn(Optional.of(storedChannel));
        when(channels.findAllByYoutubeId("UCfassTU6mGWIj-yb26_ADjQ")).thenReturn(List.of(archived));
        Video videoRow = new Video("xc7keR2piUM");                // v1, now the taken-down id
        videoRow.setId("v1");
        videoRow.setStatus("APPROVED");
        Channel channelRow = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        channelRow.setId("ch-1");
        channelRow.setStatus("APPROVED");
        ImportRequest request = new ImportRequest();
        request.setVideos(List.of(videoRow));
        request.setChannels(List.of(channelRow));
        request.setMergeStrategy("OVERWRITE");

        ImportResponse out = service.importAll(request);

        verify(videos, never()).save(videoRow);
        verify(channels, never()).save(channelRow);
        assertThat(out.getErrors()).extracting(e -> e.getType() + ":" + e.getId()).containsExactly("CHANNEL:ch-1", "VIDEO:v1");
    }

    /**
     * Cubic users4: a skipped row never reaches the live guard. A LIVE-category channel row whose youtubeId an archived
     * doc holds is skipped, so nothing seeds it: no Live-tab read, no 503 for a row the import skips anyway.
     */
    @Test
    void aSkippedLiveChannelRowIsNeverSeeded() throws Exception {
        String uc = "UCROKYPep-UuODNwyipe6JMw";
        LiveTabReader reader = mock(LiveTabReader.class);
        LiveChannelService live = new LiveChannelService(reader, mock(LiveChannelStateRepository.class), Clock.systemUTC());
        LiveCategoryService liveCategories = mock(LiveCategoryService.class);
        ImportExportService importer = new ImportExportService(categories, channels, playlists, videos,
                mock(AuditLogService.class), mock(VideoValidationService.class),
                new LiveCategoryGuard(liveCategories, live, videos, channels, playlists, new CategoryMappingService(categories)));
        when(liveCategories.liveCategoryIds()).thenReturn(Set.of("live-cat"));
        when(reader.read(uc)).thenReturn(LiveTabResult.error(1, "boom"));
        live.loadStates(List.of());
        Channel takenDown = liveChannel(uc, ValidationStatus.ARCHIVED);
        takenDown.setId("ch-held");
        when(channels.findByYoutubeId(uc)).thenReturn(Optional.of(takenDown));
        when(channels.findAllByYoutubeId(uc)).thenReturn(List.of(takenDown));
        when(liveCategories.members("live-cat")).thenReturn(new LiveCategoryService.Members(List.of()));
        Channel row = liveChannel(uc, ValidationStatus.VALID);   // id ch-live: another doc id
        ImportRequest request = new ImportRequest();
        request.setChannels(List.of(row));
        request.setMergeStrategy("OVERWRITE");

        ImportResponse out = importer.importAll(request);

        verify(reader, never()).read(uc);
        verify(channels, never()).save(row);
        assertThat(out.getCounts().getChannelsSkipped()).isEqualTo(1);
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
     * Stage 9 B22 (Cubic r10 #4): a channel out of the public listing left the poller's targets, so its stored state keeps
     * the lives it had then. A file that serves it again re-enters it as an add (D15, B14): seeded, and a seed YouTube does
     * not answer serves none of those lives. Stage 9 B23 (Cubic r11 #2): an import never un-archives, so the change that
     * serves it again is a non-archive one, here PERSONAL to PUBLIC.
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
            Channel stored = liveChannel(uc, ValidationStatus.VALID);
            stored.setVisibility("PERSONAL");
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

    /**
     * Stage 9 B23 (Cubic r11 #2; r10 re-review #1): an import never un-archives, for every type and strategy. A report's
     * take-down (or validation) archived the stored doc in place; a backup made before writes VALID over it, and the item
     * was served again. The stored ARCHIVED (or a video's legacy UNAVAILABLE) is kept: un-archiving is Restore's job.
     */
    @Test
    void anImportNeverUnArchives() throws Exception {
        for (String strategy : List.of("OVERWRITE", "MERGE")) {
            Channel storedChannel = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
            storedChannel.setValidationStatus(ValidationStatus.ARCHIVED);
            Playlist storedPlaylist = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
            storedPlaylist.setValidationStatus(ValidationStatus.ARCHIVED);
            Video storedVideo = new Video("ilgiwqpcOC8");
            storedVideo.setValidationStatus(ValidationStatus.ARCHIVED);
            Video storedGone = new Video("xc7keR2piUM");
            storedGone.setValidationStatus(ValidationStatus.UNAVAILABLE);
            when(channels.findById("ch-1")).thenReturn(Optional.of(storedChannel));
            when(playlists.findById("pl-1")).thenReturn(Optional.of(storedPlaylist));
            when(videos.findById("v-1")).thenReturn(Optional.of(storedVideo));
            when(videos.findById("v-2")).thenReturn(Optional.of(storedGone));
            Channel channel = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
            channel.setId("ch-1");
            Playlist playlist = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
            playlist.setId("pl-1");
            Video video = new Video("ilgiwqpcOC8");
            video.setId("v-1");
            Video gone = new Video("xc7keR2piUM");
            gone.setId("v-2");
            for (var item : List.of(channel, playlist, video, gone)) {   // as a backup made before the archive holds them
                if (item instanceof Channel c) { c.setStatus("APPROVED"); c.setValidationStatus(ValidationStatus.VALID); }
                if (item instanceof Playlist p) { p.setStatus("APPROVED"); p.setValidationStatus(ValidationStatus.VALID); }
                if (item instanceof Video v) { v.setStatus("APPROVED"); v.setValidationStatus(ValidationStatus.VALID); }
            }
            ImportRequest request = new ImportRequest();
            request.setChannels(List.of(channel));
            request.setPlaylists(List.of(playlist));
            request.setVideos(List.of(video, gone));
            request.setMergeStrategy(strategy);

            service.importAll(request);

            verify(channels).save(channel);
            verify(playlists).save(playlist);
            verify(videos).save(video);
            verify(videos).save(gone);
            assertThat(List.of(channel.getValidationStatus(), playlist.getValidationStatus(), video.getValidationStatus(),
                    gone.getValidationStatus())).as(strategy).containsExactly(ValidationStatus.ARCHIVED,
                    ValidationStatus.ARCHIVED, ValidationStatus.ARCHIVED, ValidationStatus.UNAVAILABLE);
        }
    }

    /**
     * Stage 9 B23 (Cubic r11 #2 with B22's reenters): the import keeps an archived channel archived, so it does not
     * re-enter its LIVE category: nothing is seeded, and it serves nothing (not servable).
     */
    @Test
    void anArchivedChannelTheFileServesStaysArchivedAndIsNotSeeded() throws Exception {
        String uc = "UCROKYPep-UuODNwyipe6JMw";
        LiveTabReader reader = mock(LiveTabReader.class);
        LiveChannelService live = new LiveChannelService(reader, mock(LiveChannelStateRepository.class), Clock.systemUTC());
        LiveCategoryService liveCategories = mock(LiveCategoryService.class);
        ImportExportService importer = new ImportExportService(categories, channels, playlists, videos,
                mock(AuditLogService.class), mock(VideoValidationService.class),
                new LiveCategoryGuard(liveCategories, live, videos, channels, playlists, new CategoryMappingService(categories)));
        when(liveCategories.liveCategoryIds()).thenReturn(Set.of("live-cat"));
        when(reader.read(uc)).thenReturn(LiveTabResult.error(1, "boom"));
        live.loadStates(List.of());
        for (String strategy : List.of("OVERWRITE", "MERGE")) {
            Channel stored = liveChannel(uc, ValidationStatus.ARCHIVED);
            when(channels.findById("ch-live")).thenReturn(Optional.of(stored));
            when(liveCategories.members("live-cat")).thenReturn(new LiveCategoryService.Members(List.of(stored)));
            Channel backedUp = liveChannel(uc, ValidationStatus.VALID);
            ImportRequest request = new ImportRequest();
            request.setChannels(List.of(backedUp));
            request.setMergeStrategy(strategy);

            importer.importAll(request);

            verify(channels, atLeastOnce()).save(backedUp);
            assertThat(backedUp.getValidationStatus()).as(strategy).isEqualTo(ValidationStatus.ARCHIVED);
            assertThat(LiveCategoryService.servable(backedUp)).as(strategy).isFalse();
        }
        verify(reader, never()).read(uc);
    }

    /**
     * Stage 9 B23 (Cubic r11 #14): Firestore refuses an empty id or one holding a '/' (IllegalArgumentException, on the read
     * and on the write). The import's pre-read must not fail the whole import over it: that row is its own error, and the
     * rest import, as before the pre-read (9a980b4c). Its parent id the same: an id Firestore refuses names no LIVE parent.
     */
    @Test
    void aRowWhoseIdFirestoreRefusesIsThatRowsErrorAndTheRestImport() throws Exception {
        IllegalArgumentException refused = new IllegalArgumentException("Path should point to a Document Reference: videos/a/b");
        Category badCategory = new Category("Bad", "a/b");
        badCategory.setId("");
        Channel badChannel = new Channel("UCfassTU6mGWIj-yb26_ADjQ");
        badChannel.setId("a/b");
        Playlist badPlaylist = new Playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe");
        badPlaylist.setId("");
        Video badVideo = new Video("xc7keR2piUM");
        badVideo.setId("a/b");
        Video good = new Video("ilgiwqpcOC8");
        good.setId("v-good");
        for (String bad : List.of("", "a/b")) {
            when(categories.findById(bad)).thenThrow(refused);
            when(channels.findById(bad)).thenThrow(refused);
            when(playlists.findById(bad)).thenThrow(refused);
            when(videos.findById(bad)).thenThrow(refused);
        }
        when(categories.save(badCategory)).thenThrow(refused);
        when(channels.save(badChannel)).thenThrow(refused);
        when(playlists.save(badPlaylist)).thenThrow(refused);
        when(videos.save(badVideo)).thenThrow(refused);
        ImportRequest request = new ImportRequest();
        request.setCategories(List.of(badCategory));
        request.setChannels(List.of(badChannel));
        request.setPlaylists(List.of(badPlaylist));
        request.setVideos(List.of(badVideo, good));
        request.setMergeStrategy("SKIP");

        ImportResponse out = service.importAll(request);

        verify(videos).save(good);
        assertThat(out.getCounts().getVideosImported()).isEqualTo(1);
        assertThat(out.getErrors()).extracting(e -> e.getType() + ":" + e.getId())
                .containsExactly("CATEGORY:", "CHANNEL:a/b", "PLAYLIST:", "VIDEO:a/b");
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
