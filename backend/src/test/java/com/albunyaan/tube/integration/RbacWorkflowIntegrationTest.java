package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.ApprovalRequestDto;
import com.albunyaan.tube.dto.ApprovalResponseDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.PendingApprovalDto;
import com.albunyaan.tube.dto.RejectionRequestDto;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.ApprovalService;
import com.albunyaan.tube.util.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BACKEND-RBAC-01: RBAC Workflow Integration Tests
 *
 * E2E-style tests that verify the full moderator approval workflow:
 * moderator submit → pending queue → admin approve/reject → final state.
 *
 * Uses real Firestore emulator via BaseIntegrationTest.
 */
public class RbacWorkflowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private Category testCategory;

    @BeforeEach
    public void setUp() throws Exception {
        testCategory = TestDataBuilder.createCategory("Islamic Education");
        testCategory = categoryRepository.save(testCategory);
    }

    // ===== FULL WORKFLOW: SUBMIT → PENDING → APPROVE =====

    @Test
    public void channelWorkflow_moderatorSubmit_adminApprove() throws Exception {
        // Step 1: Moderator submits a channel (status forced to PENDING)
        Channel channel = TestDataBuilder.createChannelWithCategory("UC-workflow-1", "Workflow Channel", testCategory.getId());
        channel.setStatus("PENDING");
        channel.setSubmittedBy("mod-uid-1");
        channel = channelRepository.save(channel);

        // Step 2: Verify it appears in pending queue
        CursorPageDto<PendingApprovalDto> pending = approvalService.getPendingApprovals("CHANNEL", null, 20, null);
        assertEquals(1, pending.getData().size());
        assertEquals("CHANNEL", pending.getData().get(0).getType());

        // Step 3: Admin approves
        ApprovalResponseDto response = approvalService.approve(
                channel.getId(), new ApprovalRequestDto("Good content"), "admin-uid-1", "admin@test.com");
        assertEquals("APPROVED", response.getStatus());
        assertEquals("admin-uid-1", response.getReviewedBy());

        // Step 4: Verify channel is now approved in Firestore
        Channel approved = channelRepository.findById(channel.getId()).orElse(null);
        assertNotNull(approved);
        assertEquals("APPROVED", approved.getStatus());
        assertEquals("admin-uid-1", approved.getApprovedBy());
        assertEquals("mod-uid-1", approved.getSubmittedBy());

        // Step 5: Verify it no longer appears in pending queue
        CursorPageDto<PendingApprovalDto> afterApproval = approvalService.getPendingApprovals("CHANNEL", null, 20, null);
        assertEquals(0, afterApproval.getData().size());
    }

    @Test
    public void playlistWorkflow_moderatorSubmit_adminApprove() throws Exception {
        // Step 1: Moderator submits a playlist
        Playlist playlist = TestDataBuilder.createPlaylistWithCategory("PL-workflow-1", "Workflow Playlist", testCategory.getId());
        playlist.setStatus("PENDING");
        playlist.setSubmittedBy("mod-uid-2");
        playlist = playlistRepository.save(playlist);

        // Step 2: Verify pending
        CursorPageDto<PendingApprovalDto> pending = approvalService.getPendingApprovals("PLAYLIST", null, 20, null);
        assertEquals(1, pending.getData().size());

        // Step 3: Admin approves
        ApprovalResponseDto response = approvalService.approve(
                playlist.getId(), new ApprovalRequestDto("Well curated"), "admin-uid-1", "admin@test.com");
        assertEquals("APPROVED", response.getStatus());

        // Step 4: Verify approved
        Playlist approved = playlistRepository.findById(playlist.getId()).orElse(null);
        assertNotNull(approved);
        assertEquals("APPROVED", approved.getStatus());
        assertEquals("admin-uid-1", approved.getApprovedBy());
        assertEquals("mod-uid-2", approved.getSubmittedBy());
    }

    @Test
    public void videoWorkflow_moderatorSubmit_adminApprove() throws Exception {
        // Step 1: Moderator submits a video
        Video video = TestDataBuilder.createVideoWithCategory("VID-workflow-1", "Workflow Video", testCategory.getId());
        video.setStatus("PENDING");
        video.setSubmittedBy("mod-uid-3");
        video = videoRepository.save(video);

        // Step 2: Verify pending
        CursorPageDto<PendingApprovalDto> pending = approvalService.getPendingApprovals("VIDEO", null, 20, null);
        assertEquals(1, pending.getData().size());

        // Step 3: Admin approves
        ApprovalResponseDto response = approvalService.approve(
                video.getId(), new ApprovalRequestDto("Educational content"), "admin-uid-1", "admin@test.com");
        assertEquals("APPROVED", response.getStatus());

        // Step 4: Verify approved
        Video approved = videoRepository.findById(video.getId()).orElse(null);
        assertNotNull(approved);
        assertEquals("APPROVED", approved.getStatus());
        assertEquals("admin-uid-1", approved.getApprovedBy());
        assertEquals("mod-uid-3", approved.getSubmittedBy());
    }

    // ===== FULL WORKFLOW: SUBMIT → PENDING → REJECT =====

    @Test
    public void channelWorkflow_moderatorSubmit_adminReject() throws Exception {
        // Step 1: Moderator submits
        Channel channel = TestDataBuilder.createChannelWithCategory("UC-reject-1", "Reject Channel", testCategory.getId());
        channel.setStatus("PENDING");
        channel.setSubmittedBy("mod-uid-1");
        channel = channelRepository.save(channel);

        // Step 2: Admin rejects
        RejectionRequestDto rejection = new RejectionRequestDto("LOW_QUALITY", "Does not meet standards");
        ApprovalResponseDto response = approvalService.reject(
                channel.getId(), rejection, "admin-uid-1", "admin@test.com");
        assertEquals("REJECTED", response.getStatus());

        // Step 3: Verify rejected in Firestore
        Channel rejected = channelRepository.findById(channel.getId()).orElse(null);
        assertNotNull(rejected);
        assertEquals("REJECTED", rejected.getStatus());
        assertNotNull(rejected.getApprovalMetadata());
        assertEquals("LOW_QUALITY", rejected.getApprovalMetadata().getRejectionReason());
        assertEquals("Does not meet standards", rejected.getApprovalMetadata().getReviewNotes());

        // Step 4: Verify it no longer appears in pending queue
        CursorPageDto<PendingApprovalDto> afterReject = approvalService.getPendingApprovals("CHANNEL", null, 20, null);
        assertEquals(0, afterReject.getData().size());
    }

    // ===== MY SUBMISSIONS WORKFLOW =====

    @Test
    public void mySubmissions_moderatorCanSeeOwnSubmissions() throws Exception {
        // Step 1: Moderator 1 submits a channel
        Channel ch1 = TestDataBuilder.createChannelWithCategory("UC-sub-1", "Sub Channel 1", testCategory.getId());
        ch1.setStatus("PENDING");
        ch1.setSubmittedBy("mod-uid-A");
        channelRepository.save(ch1);

        // Step 2: Moderator 2 submits a channel
        Channel ch2 = TestDataBuilder.createChannelWithCategory("UC-sub-2", "Sub Channel 2", testCategory.getId());
        ch2.setStatus("PENDING");
        ch2.setSubmittedBy("mod-uid-B");
        channelRepository.save(ch2);

        // Step 3: Moderator A should only see their own submission
        CursorPageDto<PendingApprovalDto> modASubmissions = approvalService.getMySubmissions(
                "mod-uid-A", null, null, 20, null);
        assertEquals(1, modASubmissions.getData().size());
        assertEquals("Sub Channel 1", modASubmissions.getData().get(0).getTitle());

        // Step 4: Moderator B should only see their own submission
        CursorPageDto<PendingApprovalDto> modBSubmissions = approvalService.getMySubmissions(
                "mod-uid-B", null, null, 20, null);
        assertEquals(1, modBSubmissions.getData().size());
        assertEquals("Sub Channel 2", modBSubmissions.getData().get(0).getTitle());
    }

    @Test
    public void mySubmissions_statusFilter_showsCorrectSubmissions() throws Exception {
        // Moderator submits a channel
        Channel channel = TestDataBuilder.createChannelWithCategory("UC-filter-1", "Filter Channel", testCategory.getId());
        channel.setStatus("PENDING");
        channel.setSubmittedBy("mod-uid-filter");
        channel = channelRepository.save(channel);

        // Admin approves it
        approvalService.approve(channel.getId(), new ApprovalRequestDto("Good"), "admin-uid-1", "admin@test.com");

        // Moderator checks PENDING submissions - should be empty
        CursorPageDto<PendingApprovalDto> pendingList = approvalService.getMySubmissions(
                "mod-uid-filter", "PENDING", null, 20, null);
        assertEquals(0, pendingList.getData().size());

        // Moderator checks APPROVED submissions - should see the channel
        CursorPageDto<PendingApprovalDto> approvedList = approvalService.getMySubmissions(
                "mod-uid-filter", "APPROVED", null, 20, null);
        assertEquals(1, approvedList.getData().size());
        assertEquals("Filter Channel", approvedList.getData().get(0).getTitle());
    }

    // ===== MIXED TYPE WORKFLOW =====

    @Test
    public void mixedTypeWorkflow_allTypesInPendingQueue() throws Exception {
        // Submit one of each type
        Channel channel = TestDataBuilder.createChannelWithCategory("UC-mix-1", "Mixed Channel", testCategory.getId());
        channel.setStatus("PENDING");
        channel.setSubmittedBy("mod-uid-mix");
        channelRepository.save(channel);

        Playlist playlist = TestDataBuilder.createPlaylistWithCategory("PL-mix-1", "Mixed Playlist", testCategory.getId());
        playlist.setStatus("PENDING");
        playlist.setSubmittedBy("mod-uid-mix");
        playlistRepository.save(playlist);

        Video video = TestDataBuilder.createVideoWithCategory("VID-mix-1", "Mixed Video", testCategory.getId());
        video.setStatus("PENDING");
        video.setSubmittedBy("mod-uid-mix");
        videoRepository.save(video);

        // All 3 types should appear in unfiltered pending queue
        CursorPageDto<PendingApprovalDto> all = approvalService.getPendingApprovals(null, null, 20, null);
        assertEquals(3, all.getData().size());

        // Type filters should work
        CursorPageDto<PendingApprovalDto> channels = approvalService.getPendingApprovals("CHANNEL", null, 20, null);
        assertEquals(1, channels.getData().size());

        CursorPageDto<PendingApprovalDto> playlists = approvalService.getPendingApprovals("PLAYLIST", null, 20, null);
        assertEquals(1, playlists.getData().size());

        CursorPageDto<PendingApprovalDto> videos = approvalService.getPendingApprovals("VIDEO", null, 20, null);
        assertEquals(1, videos.getData().size());
    }

    // ===== IDEMPOTENCY: DOUBLE APPROVE/REJECT =====

    @Test
    public void doubleApprove_shouldFail() throws Exception {
        Channel channel = TestDataBuilder.createChannelWithCategory("UC-double-1", "Double Channel", testCategory.getId());
        channel.setStatus("PENDING");
        channel.setSubmittedBy("mod-uid-1");
        channel = channelRepository.save(channel);

        // First approve should succeed
        approvalService.approve(channel.getId(), new ApprovalRequestDto("Good"), "admin-uid-1", "admin@test.com");

        // Second approve should fail (already approved)
        String channelId = channel.getId();
        assertThrows(IllegalStateException.class, () ->
                approvalService.approve(channelId, new ApprovalRequestDto("Again"), "admin-uid-2", "admin2@test.com"));
    }

    @Test
    public void approveWithCategoryOverride_updatesCategory() throws Exception {
        // Create a second category
        Category newCategory = TestDataBuilder.createCategory("Hadith Studies");
        newCategory = categoryRepository.save(newCategory);

        // Submit channel with original category
        Channel channel = TestDataBuilder.createChannelWithCategory("UC-cat-1", "Category Channel", testCategory.getId());
        channel.setStatus("PENDING");
        channel.setSubmittedBy("mod-uid-1");
        channel = channelRepository.save(channel);

        // Approve with category override
        ApprovalRequestDto request = new ApprovalRequestDto("Better fit in Hadith");
        request.setCategoryOverride(newCategory.getId());
        approvalService.approve(channel.getId(), request, "admin-uid-1", "admin@test.com");

        // Verify category was changed
        Channel updated = channelRepository.findById(channel.getId()).orElse(null);
        assertNotNull(updated);
        assertEquals("APPROVED", updated.getStatus());
        assertEquals(1, updated.getCategoryIds().size());
        assertEquals(newCategory.getId(), updated.getCategoryIds().get(0));
    }
}
