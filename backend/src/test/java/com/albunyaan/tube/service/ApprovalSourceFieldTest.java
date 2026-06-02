package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.PendingApprovalDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ApprovalRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * [ADMIN-IMPORT-01] Unit tests verifying that PendingApprovalDto carries the
 * {@code source} field populated from the underlying model.
 *
 * Covers all three content types (CHANNEL, PLAYLIST, VIDEO) and verifies:
 * <ul>
 *   <li>USER_IMPORT source is surfaced correctly.</li>
 *   <li>null source (legacy / admin-submitted) is preserved as null (no default injection).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ApprovalSourceFieldTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private ApprovalRepository approvalRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private SortOrderService sortOrderService;
    @Mock private StreamIndexService streamIndexService;
    @Mock private UserRepository userRepository;
    @Mock private ImportGraduationService graduationService;

    private ApprovalService approvalService;

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService(
                channelRepository,
                playlistRepository,
                videoRepository,
                categoryRepository,
                approvalRepository,
                auditLogService,
                sortOrderService,
                streamIndexService,
                userRepository,
                graduationService
        );
    }

    // ------------------------------------------------------------------
    // CHANNEL: source surfaced in DTO
    // ------------------------------------------------------------------

    @Test
    void channelDto_carriesUserImportSource() throws Exception {
        Channel channel = pendingChannel("ch-ui-1", "USER_IMPORT");

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(List.of(channel), null, false);
        when(approvalRepository.findPendingChannelsWithCursor(anyInt(), isNull()))
                .thenReturn(channelResult);

        CursorPageDto<PendingApprovalDto> page = approvalService.getPendingApprovals(
                "CHANNEL", null, 20, null);

        assertEquals(1, page.getData().size());
        PendingApprovalDto dto = page.getData().get(0);
        assertEquals("CHANNEL", dto.getType());
        assertEquals("USER_IMPORT", dto.getSource());
    }

    @Test
    void channelDto_nullSourceIsPreserved() throws Exception {
        Channel channel = pendingChannel("ch-admin-1", null);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(List.of(channel), null, false);
        when(approvalRepository.findPendingChannelsWithCursor(anyInt(), isNull()))
                .thenReturn(channelResult);

        CursorPageDto<PendingApprovalDto> page = approvalService.getPendingApprovals(
                "CHANNEL", null, 20, null);

        assertEquals(1, page.getData().size());
        assertNull(page.getData().get(0).getSource(),
                "Legacy/admin items with no source must not be defaulted");
    }

    // ------------------------------------------------------------------
    // PLAYLIST: source surfaced in DTO
    // ------------------------------------------------------------------

    @Test
    void playlistDto_carriesUserImportSource() throws Exception {
        Playlist playlist = pendingPlaylist("pl-ui-1", "USER_IMPORT");

        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(List.of(playlist), null, false);
        when(approvalRepository.findPendingPlaylistsWithCursor(anyInt(), isNull()))
                .thenReturn(playlistResult);

        CursorPageDto<PendingApprovalDto> page = approvalService.getPendingApprovals(
                "PLAYLIST", null, 20, null);

        assertEquals(1, page.getData().size());
        PendingApprovalDto dto = page.getData().get(0);
        assertEquals("PLAYLIST", dto.getType());
        assertEquals("USER_IMPORT", dto.getSource());
    }

    // ------------------------------------------------------------------
    // VIDEO: source surfaced in DTO
    // ------------------------------------------------------------------

    @Test
    void videoDto_carriesUserImportSource() throws Exception {
        Video video = pendingVideo("vid-ui-1", "USER_IMPORT");

        ApprovalRepository.PaginatedResult<Video> videoResult =
                new ApprovalRepository.PaginatedResult<>(List.of(video), null, false);
        when(approvalRepository.findPendingVideosWithCursor(anyInt(), isNull()))
                .thenReturn(videoResult);

        CursorPageDto<PendingApprovalDto> page = approvalService.getPendingApprovals(
                "VIDEO", null, 20, null);

        assertEquals(1, page.getData().size());
        PendingApprovalDto dto = page.getData().get(0);
        assertEquals("VIDEO", dto.getType());
        assertEquals("USER_IMPORT", dto.getSource());
    }

    // ------------------------------------------------------------------
    // MIXED: source survives the merge-sort across all three types
    // ------------------------------------------------------------------

    @Test
    void mixedDto_sourcePreservedForAllTypes() throws Exception {
        Channel channel = pendingChannel("ch-mix", "USER_IMPORT");
        Playlist playlist = pendingPlaylist("pl-mix", "USER_IMPORT");
        Video video = pendingVideo("vid-mix", null); // legacy item, no source

        when(approvalRepository.findPendingChannelsWithCursor(anyInt(), isNull()))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(List.of(channel), null, false));
        when(approvalRepository.findPendingPlaylistsWithCursor(anyInt(), isNull()))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(List.of(playlist), null, false));
        when(approvalRepository.findPendingVideosWithCursor(anyInt(), isNull()))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(List.of(video), null, false));

        CursorPageDto<PendingApprovalDto> page = approvalService.getPendingApprovals(
                null, null, 20, null);

        assertEquals(3, page.getData().size());

        PendingApprovalDto channelDto = page.getData().stream()
                .filter(d -> "CHANNEL".equals(d.getType())).findFirst().orElseThrow();
        PendingApprovalDto playlistDto = page.getData().stream()
                .filter(d -> "PLAYLIST".equals(d.getType())).findFirst().orElseThrow();
        PendingApprovalDto videoDto = page.getData().stream()
                .filter(d -> "VIDEO".equals(d.getType())).findFirst().orElseThrow();

        assertEquals("USER_IMPORT", channelDto.getSource());
        assertEquals("USER_IMPORT", playlistDto.getSource());
        assertNull(videoDto.getSource());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Channel pendingChannel(String id, String source) {
        Channel c = new Channel();
        c.setId(id);
        c.setStatus("PENDING");
        c.setSource(source);
        c.setCreatedAt(Timestamp.now());
        c.setName("Test Channel");
        c.setCategoryIds(List.of("cat-1"));
        return c;
    }

    private Playlist pendingPlaylist(String id, String source) {
        Playlist p = new Playlist();
        p.setId(id);
        p.setStatus("PENDING");
        p.setSource(source);
        p.setCreatedAt(Timestamp.now());
        p.setTitle("Test Playlist");
        p.setCategoryIds(List.of("cat-1"));
        return p;
    }

    private Video pendingVideo(String id, String source) {
        Video v = new Video();
        v.setId(id);
        v.setStatus("PENDING");
        v.setSource(source);
        v.setCreatedAt(Timestamp.now());
        v.setTitle("Test Video");
        v.setCategoryIds(List.of("cat-1"));
        return v;
    }
}
