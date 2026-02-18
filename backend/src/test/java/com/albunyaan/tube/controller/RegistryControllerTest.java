package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BACKEND-REG-01: Unit tests for RegistryController
 */
@ExtendWith(MockitoExtension.class)
class RegistryControllerTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private com.albunyaan.tube.repository.VideoRepository videoRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private com.albunyaan.tube.service.PublicContentCacheService publicContentCacheService;

    @Mock
    private com.albunyaan.tube.service.SortOrderService sortOrderService;

    @Mock
    private com.github.benmanes.caffeine.cache.Cache<String, Object> workspaceExclusionsCache;

    @InjectMocks
    private RegistryController registryController;

    private FirebaseUserDetails adminUser;
    private FirebaseUserDetails moderatorUser;
    private Channel testChannel;
    private Playlist testPlaylist;

    @BeforeEach
    void setUp() {
        // Create admin user
        adminUser = new FirebaseUserDetails("admin-uid", "admin@test.com", "admin");

        // Create moderator user
        moderatorUser = new FirebaseUserDetails("mod-uid", "mod@test.com", "moderator");

        // Create test channel
        testChannel = new Channel("UC-test-channel");
        testChannel.setId("channel-123");
        testChannel.setName("Test Channel");
        testChannel.setStatus("APPROVED");
        testChannel.setSubscribers(1000L);

        // Create test playlist
        testPlaylist = new Playlist("PL-test-playlist");
        testPlaylist.setId("playlist-123");
        testPlaylist.setTitle("Test Playlist");
        testPlaylist.setStatus("APPROVED");
        testPlaylist.setItemCount(10);
    }

    // ===== CHANNEL TESTS =====

    @Test
    void getAllChannels_shouldReturnAllChannels() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        List<Channel> channels = Arrays.asList(testChannel);
        when(channelRepository.findAll(anyInt())).thenReturn(channels);

        // Act
        ResponseEntity<List<Channel>> response = registryController.getAllChannels(100);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("Test Channel", response.getBody().get(0).getName());
        verify(channelRepository).findAll(100);
    }

    @Test
    void getChannelsByStatus_shouldReturnChannelsWithStatus() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        List<Channel> channels = Arrays.asList(testChannel);
        when(channelRepository.findByStatus(eq("APPROVED"), anyInt())).thenReturn(channels);

        // Act
        ResponseEntity<List<Channel>> response = registryController.getChannelsByStatus("approved", 100);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verify(channelRepository).findByStatus("APPROVED", 100);
    }

    @Test
    void getChannelById_shouldReturnChannel_whenExists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(channelRepository.findById("channel-123")).thenReturn(Optional.of(testChannel));

        // Act
        ResponseEntity<Channel> response = registryController.getChannelById("channel-123");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Test Channel", response.getBody().getName());
    }

    @Test
    void getChannelById_shouldReturn404_whenNotFound() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(channelRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // Act
        ResponseEntity<Channel> response = registryController.getChannelById("nonexistent");

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void addChannel_shouldAutoApprove_whenSubmittedByAdmin() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Channel newChannel = new Channel("UC-new-channel");
        newChannel.setName("New Channel");
        // Set status to null to trigger auto-approval logic for admins
        newChannel.setStatus(null);
        when(channelRepository.findByYoutubeId("UC-new-channel")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(newChannel);

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(newChannel, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("APPROVED", newChannel.getStatus());
        assertEquals("admin-uid", newChannel.getSubmittedBy());
        assertEquals("admin-uid", newChannel.getApprovedBy());
        verify(channelRepository).save(newChannel);
        verify(auditLogService).log(eq("channel_added_to_registry"), eq("channel"), any(), eq(adminUser));
    }

    @Test
    void addChannel_shouldPendApproval_whenSubmittedByModerator() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Channel newChannel = new Channel("UC-new-channel");
        newChannel.setName("New Channel");
        when(channelRepository.findByYoutubeId("UC-new-channel")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(newChannel);

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(newChannel, moderatorUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newChannel.getStatus());
        assertEquals("mod-uid", newChannel.getSubmittedBy());
        assertNull(newChannel.getApprovedBy());
    }

    @Test
    void addChannel_moderatorCannotSelfApprove_statusForcedToPending() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange - moderator sends status: APPROVED in request body
        Channel newChannel = new Channel("UC-new-channel");
        newChannel.setName("New Channel");
        newChannel.setStatus("APPROVED");
        newChannel.setApprovedBy("fake-admin-uid");
        when(channelRepository.findByYoutubeId("UC-new-channel")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(newChannel);

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(newChannel, moderatorUser);

        // Assert - status must be PENDING, approvedBy must be cleared
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newChannel.getStatus());
        assertNull(newChannel.getApprovedBy());
        assertEquals("mod-uid", newChannel.getSubmittedBy());
    }

    @Test
    void addChannel_adminCanExplicitlySetStatus() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange - admin sends status: PENDING explicitly
        Channel newChannel = new Channel("UC-new-channel");
        newChannel.setName("New Channel");
        newChannel.setStatus("PENDING");
        when(channelRepository.findByYoutubeId("UC-new-channel")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(newChannel);

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(newChannel, adminUser);

        // Assert - admin's explicit PENDING status is respected, approvedBy cleared
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newChannel.getStatus());
        assertEquals("admin-uid", newChannel.getSubmittedBy());
        assertNull(newChannel.getApprovedBy());
    }

    @Test
    void addChannel_shouldReturnConflict_whenChannelAlreadyExists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(channelRepository.findByYoutubeId("UC-test-channel")).thenReturn(Optional.of(testChannel));

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(testChannel, adminUser);

        // Assert
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(channelRepository, never()).save(any());
    }

    @Test
    void updateChannel_shouldUpdateExistingChannel() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Channel updates = new Channel("UC-test-channel");
        updates.setName("Updated Name");
        updates.setDescription("Updated Description");
        when(channelRepository.findById("channel-123")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);

        // Act
        ResponseEntity<Channel> response = registryController.updateChannel("channel-123", updates, adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Updated Name", testChannel.getName());
        assertEquals("Updated Description", testChannel.getDescription());
        verify(channelRepository).save(testChannel);
    }

    @Test
    void toggleChannelStatus_shouldToggleFromApprovedToPending() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        testChannel.setStatus("APPROVED");
        when(channelRepository.findById("channel-123")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);

        // Act
        ResponseEntity<Channel> response = registryController.toggleChannelStatus("channel-123", adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PENDING", testChannel.getStatus());
    }

    @Test
    void toggleChannelStatus_shouldToggleFromPendingToApproved() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        testChannel.setStatus("PENDING");
        when(channelRepository.findById("channel-123")).thenReturn(Optional.of(testChannel));
        when(channelRepository.save(any(Channel.class))).thenReturn(testChannel);

        // Act
        ResponseEntity<Channel> response = registryController.toggleChannelStatus("channel-123", adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("APPROVED", testChannel.getStatus());
        assertEquals("admin-uid", testChannel.getApprovedBy());
    }

    @Test
    void deleteChannel_shouldDeleteChannel_whenExists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(channelRepository.findById("channel-123")).thenReturn(Optional.of(testChannel));

        // Act
        ResponseEntity<Void> response = registryController.deleteChannel("channel-123", adminUser);

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(channelRepository).deleteById("channel-123");
        verify(auditLogService).log(eq("channel_deleted_from_registry"), eq("channel"), eq("channel-123"), eq(adminUser));
    }

    // ===== PLAYLIST TESTS =====

    @Test
    void getAllPlaylists_shouldReturnAllPlaylists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        List<Playlist> playlists = Arrays.asList(testPlaylist);
        when(playlistRepository.findAll(anyInt())).thenReturn(playlists);

        // Act
        ResponseEntity<List<Playlist>> response = registryController.getAllPlaylists(100);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("Test Playlist", response.getBody().get(0).getTitle());
    }

    @Test
    void getPlaylistsByStatus_shouldReturnPlaylistsWithStatus() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        List<Playlist> playlists = Arrays.asList(testPlaylist);
        when(playlistRepository.findByStatus(eq("APPROVED"), anyInt())).thenReturn(playlists);

        // Act
        ResponseEntity<List<Playlist>> response = registryController.getPlaylistsByStatus("approved", 100);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verify(playlistRepository).findByStatus("APPROVED", 100);
    }

    @Test
    void addPlaylist_shouldAutoApprove_whenSubmittedByAdmin() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Playlist newPlaylist = new Playlist("PL-new-playlist");
        newPlaylist.setTitle("New Playlist");
        // Set status to null to trigger auto-approval logic for admins
        newPlaylist.setStatus(null);
        when(playlistRepository.findByYoutubeId("PL-new-playlist")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenReturn(newPlaylist);

        // Act
        ResponseEntity<Playlist> response = registryController.addPlaylist(newPlaylist, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("APPROVED", newPlaylist.getStatus());
        assertEquals("admin-uid", newPlaylist.getSubmittedBy());
        assertEquals("admin-uid", newPlaylist.getApprovedBy());
    }

    @Test
    void addPlaylist_moderatorCannotSelfApprove_statusForcedToPending() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange - moderator sends status: APPROVED in request body
        Playlist newPlaylist = new Playlist("PL-new-playlist");
        newPlaylist.setTitle("New Playlist");
        newPlaylist.setStatus("APPROVED");
        newPlaylist.setApprovedBy("fake-admin-uid");
        when(playlistRepository.findByYoutubeId("PL-new-playlist")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenReturn(newPlaylist);

        // Act
        ResponseEntity<Playlist> response = registryController.addPlaylist(newPlaylist, moderatorUser);

        // Assert - status must be PENDING, approvedBy must be cleared
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newPlaylist.getStatus());
        assertNull(newPlaylist.getApprovedBy());
        assertEquals("mod-uid", newPlaylist.getSubmittedBy());
    }

    @Test
    void addPlaylist_shouldReturnConflict_whenPlaylistAlreadyExists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(playlistRepository.findByYoutubeId("PL-test-playlist")).thenReturn(Optional.of(testPlaylist));

        // Act
        ResponseEntity<Playlist> response = registryController.addPlaylist(testPlaylist, adminUser);

        // Assert
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(playlistRepository, never()).save(any());
    }

    @Test
    void updatePlaylist_shouldUpdateExistingPlaylist() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Playlist updates = new Playlist("PL-test-playlist");
        updates.setTitle("Updated Title");
        updates.setDescription("Updated Description");
        when(playlistRepository.findById("playlist-123")).thenReturn(Optional.of(testPlaylist));
        when(playlistRepository.save(any(Playlist.class))).thenReturn(testPlaylist);

        // Act
        ResponseEntity<Playlist> response = registryController.updatePlaylist("playlist-123", updates, adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Updated Title", testPlaylist.getTitle());
        assertEquals("Updated Description", testPlaylist.getDescription());
        verify(playlistRepository).save(testPlaylist);
    }

    @Test
    void togglePlaylistStatus_shouldToggleBetweenApprovedAndPending() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        testPlaylist.setStatus("APPROVED");
        when(playlistRepository.findById("playlist-123")).thenReturn(Optional.of(testPlaylist));
        when(playlistRepository.save(any(Playlist.class))).thenReturn(testPlaylist);

        // Act
        ResponseEntity<Playlist> response = registryController.togglePlaylistStatus("playlist-123", adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PENDING", testPlaylist.getStatus());
    }

    @Test
    void deletePlaylist_shouldDeletePlaylist_whenExists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(playlistRepository.findById("playlist-123")).thenReturn(Optional.of(testPlaylist));

        // Act
        ResponseEntity<Void> response = registryController.deletePlaylist("playlist-123", adminUser);

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(playlistRepository).deleteById("playlist-123");
    }

    // ===== VIDEO TESTS =====

    @Test
    void addVideo_shouldAutoApprove_whenSubmittedByAdmin() throws Exception {
        // Arrange
        com.albunyaan.tube.model.Video newVideo = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        newVideo.setTitle("Test Video");
        newVideo.setStatus(null);
        when(videoRepository.findByYoutubeId("dQw4w9WgXcQ")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(newVideo);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.addVideo(newVideo, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("APPROVED", newVideo.getStatus());
        assertEquals("admin-uid", newVideo.getSubmittedBy());
        assertEquals("admin-uid", newVideo.getApprovedBy());
        verify(videoRepository).save(newVideo);
    }

    @Test
    void addVideo_shouldPendApproval_whenSubmittedByModerator() throws Exception {
        // Arrange
        com.albunyaan.tube.model.Video newVideo = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        newVideo.setTitle("Test Video");
        when(videoRepository.findByYoutubeId("dQw4w9WgXcQ")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(newVideo);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.addVideo(newVideo, moderatorUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newVideo.getStatus());
        assertEquals("mod-uid", newVideo.getSubmittedBy());
        assertNull(newVideo.getApprovedBy());
    }

    @Test
    void addVideo_moderatorCannotSelfApprove_statusForcedToPending() throws Exception {
        // Arrange - moderator sends status: APPROVED in request body
        com.albunyaan.tube.model.Video newVideo = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        newVideo.setTitle("Test Video");
        newVideo.setStatus("APPROVED");
        newVideo.setApprovedBy("fake-admin-uid");
        when(videoRepository.findByYoutubeId("dQw4w9WgXcQ")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(newVideo);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.addVideo(newVideo, moderatorUser);

        // Assert - status must be PENDING, approvedBy must be cleared
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newVideo.getStatus());
        assertNull(newVideo.getApprovedBy());
        assertEquals("mod-uid", newVideo.getSubmittedBy());
    }

    @Test
    void addVideo_adminCanExplicitlySetStatus() throws Exception {
        // Arrange - admin sends status: PENDING explicitly
        com.albunyaan.tube.model.Video newVideo = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        newVideo.setTitle("Test Video");
        newVideo.setStatus("PENDING");
        when(videoRepository.findByYoutubeId("dQw4w9WgXcQ")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(newVideo);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.addVideo(newVideo, adminUser);

        // Assert - admin's explicit PENDING status is respected, approvedBy cleared
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newVideo.getStatus());
        assertEquals("admin-uid", newVideo.getSubmittedBy());
        assertNull(newVideo.getApprovedBy());
    }

    @Test
    void addVideo_adminExplicitApproved_setsApprovedBy() throws Exception {
        // Arrange - admin explicitly sends APPROVED
        com.albunyaan.tube.model.Video newVideo = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        newVideo.setTitle("Test Video");
        newVideo.setStatus("APPROVED");
        when(videoRepository.findByYoutubeId("dQw4w9WgXcQ")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(newVideo);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.addVideo(newVideo, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("APPROVED", newVideo.getStatus());
        assertEquals("admin-uid", newVideo.getApprovedBy());
    }

    @Test
    void addVideo_shouldReturnConflict_whenVideoAlreadyExists() throws Exception {
        // Arrange
        com.albunyaan.tube.model.Video existingVideo = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        when(videoRepository.findByYoutubeId("dQw4w9WgXcQ")).thenReturn(Optional.of(existingVideo));

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.addVideo(existingVideo, adminUser);

        // Assert
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(videoRepository, never()).save(any());
    }

    @Test
    void toggleVideoStatus_shouldToggleFromApprovedToPending() throws Exception {
        // Arrange
        com.albunyaan.tube.model.Video video = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        video.setId("video-123");
        video.setStatus("APPROVED");
        when(videoRepository.findById("video-123")).thenReturn(Optional.of(video));
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(video);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.toggleVideoStatus("video-123", adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PENDING", video.getStatus());
    }

    @Test
    void toggleVideoStatus_shouldToggleFromPendingToApproved() throws Exception {
        // Arrange
        com.albunyaan.tube.model.Video video = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        video.setId("video-123");
        video.setStatus("PENDING");
        when(videoRepository.findById("video-123")).thenReturn(Optional.of(video));
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(video);

        // Act
        ResponseEntity<com.albunyaan.tube.model.Video> response = registryController.toggleVideoStatus("video-123", adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("APPROVED", video.getStatus());
        assertEquals("admin-uid", video.getApprovedBy());
    }

    @Test
    void deleteVideo_shouldDeleteVideo_whenExists() throws Exception {
        // Arrange
        com.albunyaan.tube.model.Video video = new com.albunyaan.tube.model.Video("dQw4w9WgXcQ");
        video.setId("video-123");
        when(videoRepository.findById("video-123")).thenReturn(Optional.of(video));

        // Act
        ResponseEntity<Void> response = registryController.deleteVideo("video-123", adminUser);

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(videoRepository).deleteById("video-123");
    }

    @Test
    void deleteVideo_shouldReturn404_whenNotFound() throws Exception {
        // Arrange
        when(videoRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // Act
        ResponseEntity<Void> response = registryController.deleteVideo("nonexistent", adminUser);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(videoRepository, never()).deleteById(any());
    }

    // ===== PLAYLIST ADDITIONAL SECURITY TESTS =====

    @Test
    void addPlaylist_adminCanExplicitlySetStatus() throws Exception {
        // Arrange - admin sends status: PENDING explicitly
        Playlist newPlaylist = new Playlist("PL-new-playlist");
        newPlaylist.setTitle("New Playlist");
        newPlaylist.setStatus("PENDING");
        when(playlistRepository.findByYoutubeId("PL-new-playlist")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenReturn(newPlaylist);

        // Act
        ResponseEntity<Playlist> response = registryController.addPlaylist(newPlaylist, adminUser);

        // Assert - admin's explicit PENDING status is respected, approvedBy cleared
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PENDING", newPlaylist.getStatus());
        assertEquals("admin-uid", newPlaylist.getSubmittedBy());
        assertNull(newPlaylist.getApprovedBy());
    }

    @Test
    void addPlaylist_adminExplicitApproved_setsApprovedBy() throws Exception {
        // Arrange - admin explicitly sends APPROVED
        Playlist newPlaylist = new Playlist("PL-new-playlist");
        newPlaylist.setTitle("New Playlist");
        newPlaylist.setStatus("APPROVED");
        when(playlistRepository.findByYoutubeId("PL-new-playlist")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenReturn(newPlaylist);

        // Act
        ResponseEntity<Playlist> response = registryController.addPlaylist(newPlaylist, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("APPROVED", newPlaylist.getStatus());
        assertEquals("admin-uid", newPlaylist.getApprovedBy());
    }

    // ===== CHANNEL ADDITIONAL SECURITY TESTS =====

    @Test
    void addChannel_adminExplicitApproved_setsApprovedBy() throws Exception {
        // Arrange - admin explicitly sends APPROVED
        Channel newChannel = new Channel("UC-new-channel");
        newChannel.setName("New Channel");
        newChannel.setStatus("APPROVED");
        when(channelRepository.findByYoutubeId("UC-new-channel")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(newChannel);

        // Act
        ResponseEntity<Channel> response = registryController.addChannel(newChannel, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("APPROVED", newChannel.getStatus());
        assertEquals("admin-uid", newChannel.getApprovedBy());
    }

    // ===== CROSS-ROLE CONSISTENCY TESTS =====

    @Test
    void allEntityTypes_moderatorAlwaysGetsPending() throws Exception {
        // Verify consistent RBAC behavior across all 3 entity types

        // Channel
        Channel ch = new Channel("UC-ch");
        ch.setName("Ch");
        ch.setStatus("APPROVED");
        when(channelRepository.findByYoutubeId("UC-ch")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(ch);
        registryController.addChannel(ch, moderatorUser);
        assertEquals("PENDING", ch.getStatus());
        assertNull(ch.getApprovedBy());

        // Playlist
        Playlist pl = new Playlist("PL-pl");
        pl.setTitle("Pl");
        pl.setStatus("APPROVED");
        when(playlistRepository.findByYoutubeId("PL-pl")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenReturn(pl);
        registryController.addPlaylist(pl, moderatorUser);
        assertEquals("PENDING", pl.getStatus());
        assertNull(pl.getApprovedBy());

        // Video
        com.albunyaan.tube.model.Video vid = new com.albunyaan.tube.model.Video("vid-1");
        vid.setTitle("Vid");
        vid.setStatus("APPROVED");
        when(videoRepository.findByYoutubeId("vid-1")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(vid);
        registryController.addVideo(vid, moderatorUser);
        assertEquals("PENDING", vid.getStatus());
        assertNull(vid.getApprovedBy());
    }

    @Test
    void allEntityTypes_adminAutoApprovesWhenNoStatus() throws Exception {
        // Verify consistent auto-approve behavior across all 3 entity types

        // Channel
        Channel ch = new Channel("UC-ch");
        ch.setName("Ch");
        ch.setStatus(null);
        when(channelRepository.findByYoutubeId("UC-ch")).thenReturn(Optional.empty());
        when(channelRepository.save(any(Channel.class))).thenReturn(ch);
        registryController.addChannel(ch, adminUser);
        assertEquals("APPROVED", ch.getStatus());
        assertEquals("admin-uid", ch.getApprovedBy());

        // Playlist
        Playlist pl = new Playlist("PL-pl");
        pl.setTitle("Pl");
        pl.setStatus(null);
        when(playlistRepository.findByYoutubeId("PL-pl")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenReturn(pl);
        registryController.addPlaylist(pl, adminUser);
        assertEquals("APPROVED", pl.getStatus());
        assertEquals("admin-uid", pl.getApprovedBy());

        // Video
        com.albunyaan.tube.model.Video vid = new com.albunyaan.tube.model.Video("vid-1");
        vid.setTitle("Vid");
        vid.setStatus(null);
        when(videoRepository.findByYoutubeId("vid-1")).thenReturn(Optional.empty());
        when(videoRepository.save(any(com.albunyaan.tube.model.Video.class))).thenReturn(vid);
        registryController.addVideo(vid, adminUser);
        assertEquals("APPROVED", vid.getStatus());
        assertEquals("admin-uid", vid.getApprovedBy());
    }
}

