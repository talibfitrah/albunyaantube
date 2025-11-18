package com.albunyaan.tube.service;
import com.albunyaan.tube.dto.*;
import com.albunyaan.tube.exception.PolicyViolationException;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DownloadServiceTest {
    @Mock private VideoRepository videoRepository;
    @Mock private DownloadTokenService tokenService;
    @Mock private YouTubeGateway youtubeGateway;
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
        PolicyViolationException exception = assertThrows(PolicyViolationException.class, () ->
                downloadService.generateDownloadToken("YT-video-123", "user-123", false));
        assertEquals("EULA acceptance required", exception.getMessage());
    }

    @Test
    void getDownloadManifest_shouldReturnManifest_whenTokenValid() throws Exception {
        // Setup mocks
        when(tokenService.validateToken("valid-token", "YT-video-123")).thenReturn(true);
        when(videoRepository.findByYoutubeId("YT-video-123")).thenReturn(Optional.of(approvedVideo));
        when(tokenService.getExpirationTimeFromToken("valid-token")).thenReturn(1234567890L);

        // Create mock StreamInfo with video and audio streams
        StreamInfo mockStreamInfo = mock(StreamInfo.class);
        when(mockStreamInfo.getDuration()).thenReturn(300L); // 5 minutes

        // Create mock video stream (progressive)
        VideoStream videoStream = mock(VideoStream.class);
        when(videoStream.getContent()).thenReturn("https://youtube.com/video/480p");
        when(videoStream.getResolution()).thenReturn("480p");
        when(videoStream.getFormat()).thenReturn(MediaFormat.MPEG_4);
        when(videoStream.getBitrate()).thenReturn(1500000); // 1.5 Mbps
        when(mockStreamInfo.getVideoStreams()).thenReturn(List.of(videoStream));
        when(mockStreamInfo.getVideoOnlyStreams()).thenReturn(List.of());

        // Create mock audio stream
        AudioStream audioStream = mock(AudioStream.class);
        when(audioStream.getContent()).thenReturn("https://youtube.com/audio/128k");
        when(audioStream.getBitrate()).thenReturn(128000); // 128 kbps
        when(audioStream.getFormat()).thenReturn(MediaFormat.M4A);
        when(mockStreamInfo.getAudioStreams()).thenReturn(List.of(audioStream));

        when(youtubeGateway.fetchStreamInfo("YT-video-123")).thenReturn(mockStreamInfo);

        // Execute (supportsMerging=false for this test)
        DownloadManifestDto manifest = downloadService.getDownloadManifest("YT-video-123", "valid-token", false);

        // Verify
        assertNotNull(manifest);
        assertEquals("YT-video-123", manifest.getVideoId());
        assertEquals(1234567890L, manifest.getExpiresAtMillis());
        assertFalse(manifest.getVideoStreams().isEmpty());
        assertFalse(manifest.getAudioStreams().isEmpty());

        // Verify stream structure
        DownloadManifestDto.StreamOption firstVideoStream = manifest.getVideoStreams().get(0);
        assertEquals("480p", firstVideoStream.getQualityLabel());
        assertEquals("video/mp4", firstVideoStream.getMimeType());
        assertFalse(firstVideoStream.isRequiresMerging());
        assertNotNull(firstVideoStream.getProgressiveUrl());

        // Verify audio stream
        DownloadManifestDto.StreamOption firstAudioStream = manifest.getAudioStreams().get(0);
        assertEquals("128kbps", firstAudioStream.getQualityLabel());
        assertNotNull(firstAudioStream.getProgressiveUrl());
    }

    @Test
    void trackDownloadStarted_shouldCreateEvent() {
        downloadService.trackDownloadStarted("YT-video-123", "user-123", "720p", "mobile");
        verify(firestore, times(1)).collection("download_events");
    }
}

