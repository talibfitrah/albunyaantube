package com.albunyaan.tube.service;
import com.albunyaan.tube.dto.*;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DownloadServiceTest {
    @Mock private VideoRepository videoRepository;
    @Mock private DownloadTokenService tokenService;
    @Mock private Firestore firestore;
    @Mock private CollectionReference collectionReference;
    @InjectMocks private DownloadService downloadService;
    private Video approvedVideo;

    @BeforeEach
    void setUp() {
        approvedVideo = new Video("YT-video-123");
        approvedVideo.setId("video-123");
        approvedVideo.setTitle("Test Video");
        approvedVideo.setStatus("APPROVED");
        lenient().when(firestore.collection("download_events")).thenReturn(collectionReference);
    }

    @Test
    void checkDownloadPolicy_shouldAllowDownload_whenVideoApproved() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        when(videoRepository.findByYoutubeId("YT-video-123")).thenReturn(Optional.of(approvedVideo));
        DownloadPolicyDto policy = downloadService.checkDownloadPolicy("YT-video-123");
        assertTrue(policy.isAllowed());
        assertTrue(policy.isRequiresEula());
    }

    @Test
    void checkDownloadPolicy_shouldDenyDownload_whenVideoNotFound() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        when(videoRepository.findByYoutubeId("nonexistent")).thenReturn(Optional.empty());
        DownloadPolicyDto policy = downloadService.checkDownloadPolicy("nonexistent");
        assertFalse(policy.isAllowed());
        assertEquals("Video not found in registry", policy.getReason());
    }

    @Test
    void checkDownloadPolicy_shouldDenyDownload_whenVideoNotApproved() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        approvedVideo.setStatus("PENDING");
        when(videoRepository.findByYoutubeId("YT-video-123")).thenReturn(Optional.of(approvedVideo));
        DownloadPolicyDto policy = downloadService.checkDownloadPolicy("YT-video-123");
        assertFalse(policy.isAllowed());
        assertEquals("Video not approved for viewing", policy.getReason());
    }

    @Test
    void generateDownloadToken_shouldGenerateToken_whenEulaAccepted() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        when(videoRepository.findByYoutubeId("YT-video-123")).thenReturn(Optional.of(approvedVideo));
        when(tokenService.generateToken("YT-video-123", "user-123")).thenReturn("token-abc");
        when(tokenService.getExpirationTime("YT-video-123", "user-123")).thenReturn(1234567890L);
        DownloadTokenDto token = downloadService.generateDownloadToken("YT-video-123", "user-123", true);
        assertNotNull(token);
        assertEquals("token-abc", token.getToken());
        assertEquals("YT-video-123", token.getVideoId());
    }

    @Test
    void generateDownloadToken_shouldThrowException_whenEulaNotAccepted() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        when(videoRepository.findByYoutubeId("YT-video-123")).thenReturn(Optional.of(approvedVideo));
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                downloadService.generateDownloadToken("YT-video-123", "user-123", false));
        assertEquals("EULA acceptance required", exception.getMessage());
    }

    @Test
    void getDownloadManifest_shouldReturnManifest_whenTokenValid() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        when(tokenService.validateToken("valid-token", "YT-video-123")).thenReturn(true);
        when(videoRepository.findByYoutubeId("YT-video-123")).thenReturn(Optional.of(approvedVideo));
        when(tokenService.getExpirationTime(anyString(), anyString())).thenReturn(1234567890L);
        DownloadManifestDto manifest = downloadService.getDownloadManifest("YT-video-123", "valid-token");
        assertNotNull(manifest);
        assertEquals("YT-video-123", manifest.getVideoId());
        assertFalse(manifest.getVideoStreams().isEmpty());
    }

    @Test
    void trackDownloadStarted_shouldCreateEvent() {
        downloadService.trackDownloadStarted("YT-video-123", "user-123", "720p", "mobile");
        verify(firestore, times(1)).collection("download_events");
    }
}

