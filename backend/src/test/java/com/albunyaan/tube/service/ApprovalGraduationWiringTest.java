package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ApprovalRequestDto;
import com.albunyaan.tube.dto.ApprovalResponseDto;
import com.albunyaan.tube.dto.RejectionRequestDto;
import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ApprovalRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BACKEND-IMPORT-09: Verify ImportGraduationService fan-out is triggered on
 * approve and reject, and that a throwing fan-out does NOT break the admin action.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalGraduationWiringTest {

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Channel pendingChannel(String id, String youtubeId) {
        Channel c = new Channel();
        c.setId(id);
        c.setYoutubeId(youtubeId);
        c.setStatus("PENDING");
        c.setCategoryIds(List.of("cat-001"));
        return c;
    }

    private Playlist pendingPlaylist(String id, String youtubeId) {
        Playlist p = new Playlist();
        p.setId(id);
        p.setYoutubeId(youtubeId);
        p.setStatus("PENDING");
        p.setCategoryIds(List.of("cat-001"));
        return p;
    }

    private Video pendingVideo(String id, String youtubeId) {
        Video v = new Video();
        v.setId(id);
        v.setYoutubeId(youtubeId);
        v.setStatus("PENDING");
        v.setCategoryIds(List.of("cat-001"));
        return v;
    }

    private ApprovalRequestDto approveRequest() {
        return new ApprovalRequestDto("looks good");
    }

    private RejectionRequestDto rejectRequest() {
        return new RejectionRequestDto("inappropriate", "review notes");
    }

    // ── Approve: fan-out called with correct type + youtubeId ─────────────────

    @Test
    void approveChannel_triggersGraduationOnApproved() throws Exception {
        Channel channel = pendingChannel("ch-1", "UC_testchannel");
        when(channelRepository.findById("ch-1")).thenReturn(Optional.of(channel));
        when(channelRepository.saveIfStatus(any(Channel.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));

        approvalService.approve("ch-1", approveRequest(), "admin-uid", "Admin");

        verify(graduationService, times(1))
                .onApproved(YouTubeContentType.CHANNEL, "UC_testchannel");
        verify(graduationService, never()).onRejected(any(), any());
    }

    @Test
    void approvePlaylist_triggersGraduationOnApproved() throws Exception {
        Playlist playlist = pendingPlaylist("pl-1", "PLtest123");
        when(playlistRepository.findById("pl-1")).thenReturn(Optional.of(playlist));
        when(playlistRepository.saveIfStatus(any(Playlist.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));
        when(channelRepository.findById("pl-1")).thenReturn(Optional.empty());

        approvalService.approve("pl-1", approveRequest(), "admin-uid", "Admin");

        verify(graduationService, times(1))
                .onApproved(YouTubeContentType.PLAYLIST, "PLtest123");
        verify(graduationService, never()).onRejected(any(), any());
    }

    @Test
    void approveVideo_triggersGraduationOnApproved() throws Exception {
        Video video = pendingVideo("vid-1", "dQw4w9WgXcQ");
        when(videoRepository.findById("vid-1")).thenReturn(Optional.of(video));
        when(videoRepository.saveIfStatus(any(Video.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));
        when(channelRepository.findById("vid-1")).thenReturn(Optional.empty());
        when(playlistRepository.findById("vid-1")).thenReturn(Optional.empty());

        approvalService.approve("vid-1", approveRequest(), "admin-uid", "Admin");

        verify(graduationService, times(1))
                .onApproved(YouTubeContentType.VIDEO, "dQw4w9WgXcQ");
        verify(graduationService, never()).onRejected(any(), any());
    }

    // ── Reject: fan-out called with correct type + youtubeId ──────────────────

    @Test
    void rejectChannel_triggersGraduationOnRejected() throws Exception {
        Channel channel = pendingChannel("ch-2", "UC_rejectme");
        when(channelRepository.findById("ch-2")).thenReturn(Optional.of(channel));
        when(channelRepository.saveIfStatus(any(Channel.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));

        approvalService.reject("ch-2", rejectRequest(), "admin-uid", "Admin");

        verify(graduationService, times(1))
                .onRejected(YouTubeContentType.CHANNEL, "UC_rejectme");
        verify(graduationService, never()).onApproved(any(), any());
    }

    @Test
    void rejectPlaylist_triggersGraduationOnRejected() throws Exception {
        Playlist playlist = pendingPlaylist("pl-2", "PLreject456");
        when(playlistRepository.findById("pl-2")).thenReturn(Optional.of(playlist));
        when(playlistRepository.saveIfStatus(any(Playlist.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));
        when(channelRepository.findById("pl-2")).thenReturn(Optional.empty());

        approvalService.reject("pl-2", rejectRequest(), "admin-uid", "Admin");

        verify(graduationService, times(1))
                .onRejected(YouTubeContentType.PLAYLIST, "PLreject456");
        verify(graduationService, never()).onApproved(any(), any());
    }

    @Test
    void rejectVideo_triggersGraduationOnRejected() throws Exception {
        Video video = pendingVideo("vid-2", "rejectVideoId");
        when(videoRepository.findById("vid-2")).thenReturn(Optional.of(video));
        when(videoRepository.saveIfStatus(any(Video.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));
        when(channelRepository.findById("vid-2")).thenReturn(Optional.empty());
        when(playlistRepository.findById("vid-2")).thenReturn(Optional.empty());

        approvalService.reject("vid-2", rejectRequest(), "admin-uid", "Admin");

        verify(graduationService, times(1))
                .onRejected(YouTubeContentType.VIDEO, "rejectVideoId");
        verify(graduationService, never()).onApproved(any(), any());
    }

    // ── Defensive: throwing fan-out must NOT break approve ────────────────────

    @Test
    void approveChannel_graduationThrows_approveStillSucceeds() throws Exception {
        Channel channel = pendingChannel("ch-3", "UC_throw");
        when(channelRepository.findById("ch-3")).thenReturn(Optional.of(channel));
        when(channelRepository.saveIfStatus(any(Channel.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));

        doThrow(new RuntimeException("simulated fan-out failure"))
                .when(graduationService).onApproved(any(), any());

        // Must NOT throw — fan-out failure is isolated
        ApprovalResponseDto response = assertDoesNotThrow(
                () -> approvalService.approve("ch-3", approveRequest(), "admin-uid", "Admin"));

        assertEquals("APPROVED", response.getStatus());
    }

    @Test
    void rejectChannel_graduationThrows_rejectStillSucceeds() throws Exception {
        Channel channel = pendingChannel("ch-4", "UC_throwreject");
        when(channelRepository.findById("ch-4")).thenReturn(Optional.of(channel));
        when(channelRepository.saveIfStatus(any(Channel.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));

        doThrow(new RuntimeException("simulated fan-out failure"))
                .when(graduationService).onRejected(any(), any());

        ApprovalResponseDto response = assertDoesNotThrow(
                () -> approvalService.reject("ch-4", rejectRequest(), "admin-uid", "Admin"));

        assertEquals("REJECTED", response.getStatus());
    }
}
