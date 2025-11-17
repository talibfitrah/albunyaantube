package com.albunyaan.tube.controller;
import com.albunyaan.tube.dto.*;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.DownloadService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DownloadControllerTest {
    @Mock private DownloadService downloadService;
    @InjectMocks private DownloadController downloadController;
    private FirebaseUserDetails testUser;

    @BeforeEach
    void setUp() {
        testUser = new FirebaseUserDetails("user-123", "user@test.com", "user");
    }

    @Test
    void checkPolicy_shouldReturnPolicy() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        DownloadPolicyDto policy = DownloadPolicyDto.allowed();
        when(downloadService.checkDownloadPolicy("video-123")).thenReturn(policy);
        ResponseEntity<DownloadPolicyDto> response = downloadController.checkPolicy("video-123");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isAllowed());
    }

    @Test
    void generateToken_shouldReturnToken_whenEulaAccepted() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Map<String, Boolean> request = new HashMap<>();
        request.put("eulaAccepted", true);
        DownloadTokenDto tokenDto = new DownloadTokenDto("token-abc", 1234567890L, "video-123");
        when(downloadService.generateDownloadToken(eq("video-123"), eq("user-123"), eq(true))).thenReturn(tokenDto);
        ResponseEntity<?> response = downloadController.generateToken("video-123", request, testUser);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof DownloadTokenDto);
        assertEquals("token-abc", ((DownloadTokenDto) response.getBody()).getToken());
    }

    @Test
    void generateToken_shouldReturnForbidden_whenEulaNotAccepted() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Map<String, Boolean> request = new HashMap<>();
        request.put("eulaAccepted", false);
        ResponseEntity<?> response = downloadController.generateToken("video-123", request, testUser);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody() instanceof ErrorResponse);
    }

    @Test
    void getManifest_shouldReturnManifest_whenTokenValid() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        DownloadManifestDto manifest = new DownloadManifestDto("video-123", "Test Video", 1234567890L);
        manifest.getVideoStreams().add(new DownloadManifestDto.StreamOption("720p", "url", "mp4", 50000000L, 2500));
        when(downloadService.getDownloadManifest("video-123", "valid-token")).thenReturn(manifest);
        ResponseEntity<?> response = downloadController.getManifest("video-123", "valid-token");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof DownloadManifestDto);
        assertEquals("video-123", ((DownloadManifestDto) response.getBody()).getVideoId());
    }

    @Test
    void trackDownloadStarted_shouldTrackEvent() {
        Map<String, String> request = new HashMap<>();
        request.put("videoId", "video-123");
        request.put("quality", "720p");
        request.put("deviceType", "mobile");
        ResponseEntity<Void> response = downloadController.trackDownloadStarted(request, testUser);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(downloadService).trackDownloadStarted("video-123", "user-123", "720p", "mobile");
    }
}

