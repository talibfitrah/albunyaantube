package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ApprovalRequestDto;
import com.albunyaan.tube.dto.ApprovalResponseDto;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BACKEND-IMPORT-07: Category required when approving uncategorized items.
 *
 * Effective categories = categoryOverride (if non-empty) else item.categoryIds.
 * If the effective set is empty → 400, no state change, no save.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalCategoryRequiredTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private ApprovalRepository approvalRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private SortOrderService sortOrderService;
    @Mock private StreamIndexService streamIndexService;
    @Mock private UserRepository userRepository;

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
                userRepository
        );
    }

    // -------------------------------------------------------------------------
    // Channel: empty categoryIds, no override → 400, no save
    // -------------------------------------------------------------------------

    @Test
    void approveChannel_emptyCategoryIds_noOverride_throws400() throws Exception {
        Channel channel = new Channel();
        channel.setStatus("PENDING");
        channel.setCategoryIds(Collections.emptyList());

        when(channelRepository.findById("ch1")).thenReturn(Optional.of(channel));

        ApprovalRequestDto request = new ApprovalRequestDto("good content");
        // no categoryOverride

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> approvalService.approve("ch1", request, "admin-uid", "Admin"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());

        // Status must NOT have been changed
        assertEquals("PENDING", channel.getStatus());
        // Repository must NOT have been called
        verify(channelRepository, never()).saveIfStatus(any(), any());
        verifyNoInteractions(sortOrderService, auditLogService);
    }

    @Test
    void approveChannel_nullCategoryIds_noOverride_throws400() throws Exception {
        Channel channel = new Channel();
        channel.setStatus("PENDING");
        channel.setCategoryIds(null);

        when(channelRepository.findById("ch1")).thenReturn(Optional.of(channel));

        ApprovalRequestDto request = new ApprovalRequestDto("good content");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> approvalService.approve("ch1", request, "admin-uid", "Admin"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("PENDING", channel.getStatus());
        verify(channelRepository, never()).saveIfStatus(any(), any());
    }

    // -------------------------------------------------------------------------
    // Channel: empty categoryIds + non-empty override → succeeds
    // -------------------------------------------------------------------------

    @Test
    void approveChannel_emptyCategoryIds_withOverride_succeeds() throws Exception {
        Channel channel = new Channel();
        channel.setStatus("PENDING");
        channel.setCategoryIds(Collections.emptyList());

        when(channelRepository.findById("ch1")).thenReturn(Optional.of(channel));
        when(channelRepository.saveIfStatus(any(Channel.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));

        ApprovalRequestDto request = new ApprovalRequestDto("good content");
        request.setCategoryOverride("cat-islam-101");

        ApprovalResponseDto response = approvalService.approve("ch1", request, "admin-uid", "Admin");

        assertEquals("APPROVED", response.getStatus());
        // Override category must have been applied to the entity
        assertEquals(List.of("cat-islam-101"), channel.getCategoryIds());
        verify(channelRepository).saveIfStatus(any(Channel.class), eq("PENDING"));
    }

    // -------------------------------------------------------------------------
    // Playlist: empty categoryIds, no override → 400, no save
    // -------------------------------------------------------------------------

    @Test
    void approvePlaylist_emptyCategoryIds_noOverride_throws400() throws Exception {
        Playlist playlist = new Playlist();
        playlist.setStatus("PENDING");
        playlist.setCategoryIds(Collections.emptyList());

        when(channelRepository.findById("pl1")).thenReturn(Optional.empty());
        when(playlistRepository.findById("pl1")).thenReturn(Optional.of(playlist));

        ApprovalRequestDto request = new ApprovalRequestDto("notes");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> approvalService.approve("pl1", request, "admin-uid", "Admin"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("PENDING", playlist.getStatus());
        verify(playlistRepository, never()).saveIfStatus(any(), any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void approvePlaylist_emptyCategoryIds_withOverride_succeeds() throws Exception {
        Playlist playlist = new Playlist();
        playlist.setStatus("PENDING");
        playlist.setCategoryIds(Collections.emptyList());

        when(channelRepository.findById("pl1")).thenReturn(Optional.empty());
        when(playlistRepository.findById("pl1")).thenReturn(Optional.of(playlist));
        when(playlistRepository.saveIfStatus(any(Playlist.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));

        ApprovalRequestDto request = new ApprovalRequestDto("notes");
        request.setCategoryOverride("cat-quran");

        ApprovalResponseDto response = approvalService.approve("pl1", request, "admin-uid", "Admin");

        assertEquals("APPROVED", response.getStatus());
        assertEquals(List.of("cat-quran"), playlist.getCategoryIds());
    }

    // -------------------------------------------------------------------------
    // Video: empty categoryIds, no override → 400, no save
    // -------------------------------------------------------------------------

    @Test
    void approveVideo_emptyCategoryIds_noOverride_throws400() throws Exception {
        Video video = new Video();
        video.setStatus("PENDING");
        video.setCategoryIds(Collections.emptyList());

        when(channelRepository.findById("v1")).thenReturn(Optional.empty());
        when(playlistRepository.findById("v1")).thenReturn(Optional.empty());
        when(videoRepository.findById("v1")).thenReturn(Optional.of(video));

        ApprovalRequestDto request = new ApprovalRequestDto("notes");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> approvalService.approve("v1", request, "admin-uid", "Admin"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("PENDING", video.getStatus());
        verify(videoRepository, never()).saveIfStatus(any(), any());
    }

    @Test
    void approveVideo_emptyCategoryIds_withOverride_succeeds() throws Exception {
        Video video = new Video();
        video.setStatus("PENDING");
        video.setCategoryIds(Collections.emptyList());

        when(channelRepository.findById("v1")).thenReturn(Optional.empty());
        when(playlistRepository.findById("v1")).thenReturn(Optional.empty());
        when(videoRepository.findById("v1")).thenReturn(Optional.of(video));
        when(videoRepository.saveIfStatus(any(Video.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));

        ApprovalRequestDto request = new ApprovalRequestDto("notes");
        request.setCategoryOverride("cat-hadith");

        ApprovalResponseDto response = approvalService.approve("v1", request, "admin-uid", "Admin");

        assertEquals("APPROVED", response.getStatus());
        assertEquals(List.of("cat-hadith"), video.getCategoryIds());
    }

    // -------------------------------------------------------------------------
    // Regression: item with existing categories + no override → still passes
    // -------------------------------------------------------------------------

    @Test
    void approveChannel_hasCategoryIds_noOverride_succeeds() throws Exception {
        Channel channel = new Channel();
        channel.setStatus("PENDING");
        channel.setCategoryIds(List.of("cat-existing"));

        when(channelRepository.findById("ch2")).thenReturn(Optional.of(channel));
        when(channelRepository.saveIfStatus(any(Channel.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));

        ApprovalRequestDto request = new ApprovalRequestDto("looks good");
        // no categoryOverride

        ApprovalResponseDto response = approvalService.approve("ch2", request, "admin-uid", "Admin");

        assertEquals("APPROVED", response.getStatus());
        // Original category must be retained
        assertTrue(channel.getCategoryIds().contains("cat-existing"));
    }

    @Test
    void approvePlaylist_hasCategoryIds_noOverride_succeeds() throws Exception {
        Playlist playlist = new Playlist();
        playlist.setStatus("PENDING");
        playlist.setCategoryIds(List.of("cat-existing"));

        when(channelRepository.findById("pl2")).thenReturn(Optional.empty());
        when(playlistRepository.findById("pl2")).thenReturn(Optional.of(playlist));
        when(playlistRepository.saveIfStatus(any(Playlist.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));

        ApprovalRequestDto request = new ApprovalRequestDto("looks good");

        ApprovalResponseDto response = approvalService.approve("pl2", request, "admin-uid", "Admin");

        assertEquals("APPROVED", response.getStatus());
        assertTrue(playlist.getCategoryIds().contains("cat-existing"));
    }

    @Test
    void approveVideo_hasCategoryIds_noOverride_succeeds() throws Exception {
        Video video = new Video();
        video.setStatus("PENDING");
        video.setCategoryIds(List.of("cat-existing"));

        when(channelRepository.findById("v2")).thenReturn(Optional.empty());
        when(playlistRepository.findById("v2")).thenReturn(Optional.empty());
        when(videoRepository.findById("v2")).thenReturn(Optional.of(video));
        when(videoRepository.saveIfStatus(any(Video.class), eq("PENDING")))
                .thenAnswer(inv -> inv.getArgument(0));

        ApprovalRequestDto request = new ApprovalRequestDto("looks good");

        ApprovalResponseDto response = approvalService.approve("v2", request, "admin-uid", "Admin");

        assertEquals("APPROVED", response.getStatus());
        assertTrue(video.getCategoryIds().contains("cat-existing"));
    }
}
