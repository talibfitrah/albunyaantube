package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.*;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.ApprovalService;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BACKEND-APPR-01: Approval Controller Tests
 *
 * Unit tests for approval workflow endpoints.
 */
class ApprovalControllerTest {

    @Mock
    private ApprovalService approvalService;

    @Mock
    private com.albunyaan.tube.repository.ApprovalRepository approvalRepository;

    @Mock
    private com.albunyaan.tube.service.PublicContentCacheService cacheService;

    @Mock
    private FirebaseUserDetails mockUser;

    private ApprovalController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new ApprovalController(approvalService, approvalRepository, cacheService);

        // Mock user details
        when(mockUser.getUid()).thenReturn("test_uid");
        when(mockUser.getEmail()).thenReturn("test@example.com");
    }

    @Test
    void testGetPendingApprovals_Success() throws Exception {
        // Arrange
        PendingApprovalDto approval1 = new PendingApprovalDto();
        approval1.setId("channel_1");
        approval1.setType("CHANNEL");
        approval1.setTitle("Test Channel");

        CursorPageDto<PendingApprovalDto> expectedResult = new CursorPageDto<>();
        expectedResult.setData(Arrays.asList(approval1));
        expectedResult.setPageInfo(new CursorPageDto.PageInfo("cursor_abc"));

        when(approvalService.getPendingApprovals(any(), any(), any(), any()))
                .thenReturn(expectedResult);

        // Act
        ResponseEntity<CursorPageDto<PendingApprovalDto>> response =
                controller.getPendingApprovals("CHANNEL", null, 20, null, mockUser);

        // Assert
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getData().size());
        assertEquals("channel_1", response.getBody().getData().get(0).getId());
        verify(approvalService).getPendingApprovals("CHANNEL", null, 20, null);
    }

    @Test
    void testGetPendingApprovals_WithFilters() throws Exception {
        // Arrange
        CursorPageDto<PendingApprovalDto> expectedResult = new CursorPageDto<>();
        when(approvalService.getPendingApprovals(any(), any(), any(), any()))
                .thenReturn(expectedResult);

        // Act
        controller.getPendingApprovals("PLAYLIST", "category_1", 10, "cursor_123", mockUser);

        // Assert
        verify(approvalService).getPendingApprovals("PLAYLIST", "category_1", 10, "cursor_123");
    }

    @Test
    void testApprove_Success() throws Exception {
        // Arrange
        ApprovalRequestDto request = new ApprovalRequestDto("High quality content");

        ApprovalResponseDto expectedResponse = new ApprovalResponseDto();
        expectedResponse.setStatus("APPROVED");
        expectedResponse.setReviewedAt(Timestamp.now());
        expectedResponse.setReviewedBy("test_uid");

        when(approvalService.approve(eq("channel_1"), any(), eq("test_uid"), eq("test@example.com")))
                .thenReturn(expectedResponse);

        // Act
        ResponseEntity<ApprovalResponseDto> response = controller.approve("channel_1", request, mockUser);

        // Assert
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals("APPROVED", response.getBody().getStatus());
        verify(approvalService).approve("channel_1", request, "test_uid", "test@example.com");
    }

    @Test
    void testApprove_NotFound() throws Exception {
        // Arrange
        ApprovalRequestDto request = new ApprovalRequestDto("High quality content");
        when(approvalService.approve(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Item not found"));

        // Act
        ResponseEntity<ApprovalResponseDto> response = controller.approve("invalid_id", request, mockUser);

        // Assert
        assertEquals(404, response.getStatusCodeValue());
    }

    @Test
    void testReject_Success() throws Exception {
        // Arrange
        RejectionRequestDto request = new RejectionRequestDto("LOW_QUALITY", "Poor content");

        ApprovalResponseDto expectedResponse = new ApprovalResponseDto();
        expectedResponse.setStatus("REJECTED");
        expectedResponse.setReviewedAt(Timestamp.now());
        expectedResponse.setReviewedBy("test_uid");

        when(approvalService.reject(eq("channel_1"), any(), eq("test_uid"), eq("test@example.com")))
                .thenReturn(expectedResponse);

        // Act
        ResponseEntity<ApprovalResponseDto> response = controller.reject("channel_1", request, mockUser);

        // Assert
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals("REJECTED", response.getBody().getStatus());
        verify(approvalService).reject("channel_1", request, "test_uid", "test@example.com");
    }

    @Test
    void testReject_NotFound() throws Exception {
        // Arrange
        RejectionRequestDto request = new RejectionRequestDto("LOW_QUALITY", "Poor content");
        when(approvalService.reject(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Item not found"));

        // Act
        ResponseEntity<ApprovalResponseDto> response = controller.reject("invalid_id", request, mockUser);

        // Assert
        assertEquals(404, response.getStatusCodeValue());
    }

    @Test
    void testGetMySubmissions_Success() throws Exception {
        // Arrange
        PendingApprovalDto submission = new PendingApprovalDto();
        submission.setId("channel_1");
        submission.setType("CHANNEL");
        submission.setTitle("My Channel");
        submission.setStatus("PENDING");

        CursorPageDto<PendingApprovalDto> expectedResult = new CursorPageDto<>();
        expectedResult.setData(Arrays.asList(submission));
        expectedResult.setPageInfo(new CursorPageDto.PageInfo(null));

        when(approvalService.getMySubmissions(eq("test_uid"), any(), any(), any(), any()))
                .thenReturn(expectedResult);

        // Act
        ResponseEntity<CursorPageDto<PendingApprovalDto>> response =
                controller.getMySubmissions("PENDING", "CHANNEL", 20, null, mockUser);

        // Assert
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getData().size());
        verify(approvalService).getMySubmissions("test_uid", "PENDING", "CHANNEL", 20, null);
    }

    @Test
    void testGetMySubmissions_InvalidType_Returns400() throws Exception {
        // Arrange
        when(approvalService.getMySubmissions(any(), any(), eq("INVALID"), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid type: INVALID"));

        // Act
        ResponseEntity<CursorPageDto<PendingApprovalDto>> response =
                controller.getMySubmissions(null, "INVALID", null, null, mockUser);

        // Assert
        assertEquals(400, response.getStatusCodeValue());
    }

    @Test
    void testGetMySubmissions_VideoType() throws Exception {
        // Arrange
        PendingApprovalDto videoSubmission = new PendingApprovalDto();
        videoSubmission.setId("video_1");
        videoSubmission.setType("VIDEO");
        videoSubmission.setTitle("My Video");
        videoSubmission.setStatus("APPROVED");

        CursorPageDto<PendingApprovalDto> expectedResult = new CursorPageDto<>();
        expectedResult.setData(Arrays.asList(videoSubmission));
        expectedResult.setPageInfo(new CursorPageDto.PageInfo(null));

        when(approvalService.getMySubmissions(eq("test_uid"), any(), eq("VIDEO"), any(), any()))
                .thenReturn(expectedResult);

        // Act
        ResponseEntity<CursorPageDto<PendingApprovalDto>> response =
                controller.getMySubmissions("APPROVED", "VIDEO", 20, null, mockUser);

        // Assert
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals("VIDEO", response.getBody().getData().get(0).getType());
    }

    @Test
    void testGetPendingApprovals_InvalidCursor_Returns400() throws Exception {
        // Arrange
        when(approvalService.getPendingApprovals(any(), any(), any(), eq("bad-cursor")))
                .thenThrow(new IllegalArgumentException("Invalid cursor format"));

        // Act
        ResponseEntity<CursorPageDto<PendingApprovalDto>> response =
                controller.getPendingApprovals(null, null, 20, "bad-cursor", mockUser);

        // Assert
        assertEquals(400, response.getStatusCodeValue());
    }

    @Test
    void testGetPendingApprovals_InvalidType_Returns400() throws Exception {
        // Arrange
        when(approvalService.getPendingApprovals(eq("INVALID"), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid type: INVALID"));

        // Act
        ResponseEntity<CursorPageDto<PendingApprovalDto>> response =
                controller.getPendingApprovals("INVALID", null, 20, null, mockUser);

        // Assert
        assertEquals(400, response.getStatusCodeValue());
    }

    @Test
    void testApprove_AlreadyApproved_Returns409() throws Exception {
        // Arrange
        ApprovalRequestDto request = new ApprovalRequestDto("Duplicate approval");
        when(approvalService.approve(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Cannot approve channel ch1: current status is APPROVED"));

        // Act
        ResponseEntity<ApprovalResponseDto> response = controller.approve("ch1", request, mockUser);

        // Assert
        assertEquals(409, response.getStatusCodeValue());
    }

    @Test
    void testReject_AlreadyApproved_Returns409() throws Exception {
        // Arrange
        RejectionRequestDto request = new RejectionRequestDto("LOW_QUALITY", "Changed mind");
        when(approvalService.reject(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Cannot reject channel ch1: current status is APPROVED"));

        // Act
        ResponseEntity<ApprovalResponseDto> response = controller.reject("ch1", request, mockUser);

        // Assert
        assertEquals(409, response.getStatusCodeValue());
    }

    @Test
    void testApprove_WithCategoryOverride() throws Exception {
        // Arrange
        ApprovalRequestDto request = new ApprovalRequestDto("Content fits better in another category");
        request.setCategoryOverride("new_category_id");

        ApprovalResponseDto expectedResponse = new ApprovalResponseDto();
        expectedResponse.setStatus("APPROVED");

        when(approvalService.approve(any(), any(), any(), any()))
                .thenReturn(expectedResponse);

        // Act
        ResponseEntity<ApprovalResponseDto> response = controller.approve("channel_1", request, mockUser);

        // Assert
        assertEquals(200, response.getStatusCodeValue());
        verify(approvalService).approve(eq("channel_1"), argThat(req ->
                "new_category_id".equals(req.getCategoryOverride())), any(), any());
    }

    // --- BLOCKER FIX: 503 failure tests ---

    @Test
    void testGetPendingCount_Success() throws Exception {
        // Arrange
        when(approvalRepository.countAllPending()).thenReturn(42L);

        // Act
        ResponseEntity<java.util.Map<String, Long>> response = controller.getPendingCount();

        // Assert
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals(42L, response.getBody().get("count"));
    }

    @Test
    void testGetPendingCount_ServiceUnavailable_Returns503() throws Exception {
        // Arrange - repository throws exception
        when(approvalRepository.countAllPending())
                .thenThrow(new RuntimeException("Firestore connection failed"));

        // Act
        ResponseEntity<java.util.Map<String, Long>> response = controller.getPendingCount();

        // Assert - must return 503, NOT 200 with count=0
        assertEquals(503, response.getStatusCodeValue());
        assertNull(response.getBody());
    }

    @Test
    void testGetPendingApprovals_ServiceUnavailable_Returns503() throws Exception {
        // Arrange - service throws unexpected exception
        when(approvalService.getPendingApprovals(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Firestore timeout"));

        // Act
        ResponseEntity<CursorPageDto<PendingApprovalDto>> response =
                controller.getPendingApprovals(null, null, 20, null, mockUser);

        // Assert - must return 503, NOT 200 with empty data
        assertEquals(503, response.getStatusCodeValue());
    }

    @Test
    void testGetMySubmissions_ServiceUnavailable_Returns503() throws Exception {
        // Arrange - service throws unexpected exception
        when(approvalService.getMySubmissions(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Firestore timeout"));

        // Act
        ResponseEntity<CursorPageDto<PendingApprovalDto>> response =
                controller.getMySubmissions("PENDING", null, 20, null, mockUser);

        // Assert - must return 503, NOT 200 with empty data
        assertEquals(503, response.getStatusCodeValue());
    }

    @Test
    void testApprove_UnexpectedError_Returns500() throws Exception {
        // Arrange
        ApprovalRequestDto request = new ApprovalRequestDto("notes");
        when(approvalService.approve(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Unexpected error"));

        // Act
        ResponseEntity<ApprovalResponseDto> response = controller.approve("ch1", request, mockUser);

        // Assert
        assertEquals(500, response.getStatusCodeValue());
    }

    @Test
    void testReject_UnexpectedError_Returns500() throws Exception {
        // Arrange
        RejectionRequestDto request = new RejectionRequestDto("LOW_QUALITY", "Bad content");
        when(approvalService.reject(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Unexpected error"));

        // Act
        ResponseEntity<ApprovalResponseDto> response = controller.reject("ch1", request, mockUser);

        // Assert
        assertEquals(500, response.getStatusCodeValue());
    }
}

