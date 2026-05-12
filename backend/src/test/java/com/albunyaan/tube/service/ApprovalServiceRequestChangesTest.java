package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ApprovalResponseDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ApprovalRepository;
import com.albunyaan.tube.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ApprovalService.requestChanges (E-T2).
 */
@ExtendWith(MockitoExtension.class)
class ApprovalServiceRequestChangesTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ApprovalRepository approvalRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private SortOrderService sortOrderService;

    @Mock
    private StreamIndexService streamIndexService;

    @Mock
    private UserRepository userRepository;

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

    @Test
    void requestChanges_setsStatusAndNote_returnsResponseDto() throws Exception {
        // Arrange: a channel currently PENDING
        Channel channel = new Channel();
        channel.setStatus("PENDING");

        when(channelRepository.findById("ch1")).thenReturn(Optional.of(channel));
        when(channelRepository.saveIfStatus(any(Channel.class), eq("PENDING")))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ApprovalResponseDto dto = approvalService.requestChanges(
                "ch1", "wrong category", "channel", "uid-admin", "Admin Name");

        // Assert: response shape
        assertEquals("REQUEST_CHANGES", dto.getStatus());
        assertEquals("wrong category", dto.getReviewNotes());
        assertEquals("uid-admin", dto.getReviewedBy());
        assertNotNull(dto.getReviewedAt());

        // Assert: entity mutation
        assertEquals("REQUEST_CHANGES", channel.getStatus());
        assertNotNull(channel.getApprovalMetadata());
        assertEquals("wrong category", channel.getApprovalMetadata().getReviewNotes());

        // saveIfStatus must have been called with PENDING CAS guard
        verify(channelRepository).saveIfStatus(any(Channel.class), eq("PENDING"));
    }

    @Test
    void requestChanges_blankNote_throws() {
        // blank note must be rejected before any repo lookup
        assertThrows(IllegalArgumentException.class,
                () -> approvalService.requestChanges("ch1", "", "channel", "uid-admin", "Admin Name"));

        assertThrows(IllegalArgumentException.class,
                () -> approvalService.requestChanges("ch1", "   ", "channel", "uid-admin", "Admin Name"));

        assertThrows(IllegalArgumentException.class,
                () -> approvalService.requestChanges("ch1", null, "channel", "uid-admin", "Admin Name"));

        verifyNoInteractions(channelRepository, playlistRepository, videoRepository);
    }

    @Test
    void requestChanges_notFound_throws() throws Exception {
        // All three repositories return empty — should throw IllegalArgumentException
        when(channelRepository.findById("unknown")).thenReturn(Optional.empty());
        when(playlistRepository.findById("unknown")).thenReturn(Optional.empty());
        when(videoRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> approvalService.requestChanges(
                        "unknown", "some note", "channel", "uid-admin", "Admin Name"));
    }
}
