package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ApprovalRequestDto;
import com.albunyaan.tube.dto.ApprovalResponseDto;
import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Finding 3: unit coverage for the per-user ("PERSONAL") vs public approval paths.
 * AuditLogService / SortOrderService / StreamIndexService / ImportGraduationService are
 * same-package services (no import); the repositories live in com.albunyaan.tube.repository.
 */
class ApprovalServiceTest {

    @Mock ChannelRepository channelRepository;
    @Mock PlaylistRepository playlistRepository;
    @Mock VideoRepository videoRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock ApprovalRepository approvalRepository;
    @Mock AuditLogService auditLogService;
    @Mock SortOrderService sortOrderService;
    @Mock StreamIndexService streamIndexService;
    @Mock UserRepository userRepository;
    @Mock ImportGraduationService importGraduationService;

    ApprovalService service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        service = new ApprovalService(channelRepository, playlistRepository, videoRepository,
                categoryRepository, approvalRepository, auditLogService, sortOrderService,
                streamIndexService, userRepository, importGraduationService);
    }

    @Test
    void personalApproveVideo_setsPersonalVisibilityAndGrants_skipsCategory_andDoesNotPublish() throws Exception {
        // An imported video with NO categories — personal approval must not require one.
        Video v = new Video("yt-vid");
        v.setId("yt-vid");
        v.setStatus("PENDING");
        v.setSource("USER_IMPORT");
        v.setSubmittedBy("importer-1");
        when(channelRepository.findById("yt-vid")).thenReturn(Optional.empty());
        when(playlistRepository.findById("yt-vid")).thenReturn(Optional.empty());
        when(videoRepository.findById("yt-vid")).thenReturn(Optional.of(v));
        when(videoRepository.saveIfStatus(any(Video.class), eq("PENDING"))).thenAnswer(i -> i.getArgument(0));
        // Fan-out reports the importers whose AWAITING rows were flipped.
        when(importGraduationService.onApprovedPersonal(YouTubeContentType.VIDEO, "yt-vid"))
                .thenReturn(Set.of("importer-1", "importer-2"));

        ApprovalRequestDto req = new ApprovalRequestDto();
        req.setScope("PERSONAL");

        ApprovalResponseDto resp = service.approve("yt-vid", req, "admin-1", "Admin");

        assertEquals("APPROVED", resp.getStatus());
        assertEquals("PERSONAL", v.getVisibility());
        // Grantees = fan-out uids ∪ submitter.
        assertNotNull(v.getPersonalGrants());
        assertTrue(v.getPersonalGrants().contains("importer-1"));
        assertTrue(v.getPersonalGrants().contains("importer-2"));
        // Never enters public listings, and the public fan-out is not used.
        verify(sortOrderService, never()).addContentToCategory(anyString(), anyString(), anyString());
        verify(importGraduationService, never()).onApproved(any(), anyString());
        verify(importGraduationService).onApprovedPersonal(YouTubeContentType.VIDEO, "yt-vid");
    }

    @Test
    void publicApproveVideo_withoutCategory_throwsBadRequest() throws Exception {
        // Public approval still requires a category — the guard is unchanged.
        Video v = new Video("yt-vid");
        v.setId("yt-vid");
        v.setStatus("PENDING");
        when(channelRepository.findById("yt-vid")).thenReturn(Optional.empty());
        when(playlistRepository.findById("yt-vid")).thenReturn(Optional.empty());
        when(videoRepository.findById("yt-vid")).thenReturn(Optional.of(v));

        ApprovalRequestDto req = new ApprovalRequestDto(); // scope null → PUBLIC

        assertThrows(ResponseStatusException.class,
                () -> service.approve("yt-vid", req, "admin-1", "Admin"));
        verify(videoRepository, never()).saveIfStatus(any(), anyString());
    }
}
