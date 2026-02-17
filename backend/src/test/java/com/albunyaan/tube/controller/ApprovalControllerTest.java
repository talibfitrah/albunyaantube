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
    private com.albunyaan.tube.service.PublicContentCacheService cacheService;

    @Mock
    private FirebaseUserDetails mockUser;

    private ApprovalController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new ApprovalController(approvalService, cacheService);

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
}

