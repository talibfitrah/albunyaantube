package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.CompleteProfileRequest;
import com.albunyaan.tube.exception.GlobalExceptionHandler;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AccountProfileService;
import com.albunyaan.tube.service.AgeIneligibleAbortedException;
import com.albunyaan.tube.service.AgeIneligibleException;
import com.albunyaan.tube.service.ProfileAlreadyCompletedException;
import com.albunyaan.tube.service.UserNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AccountControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AccountProfileService accountProfileService;

    @MockBean
    UserRepository userRepository;

    @MockBean
    FirebaseAuth firebaseAuth;

    ObjectMapper objectMapper;

    private static final String TEST_UID = "uid-test-1";
    private static final String TEST_EMAIL = "user@test.com";

    @BeforeEach
    void setUpPrincipal() {
        FirebaseUserDetails principal = new FirebaseUserDetails(TEST_UID, TEST_EMAIL, "user");
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        // Spring TestContext does NOT auto-clear SecurityContextHolder (ThreadLocal).
        // Explicit teardown so no auth state leaks across tests.
        SecurityContextHolder.clearContext();
    }

    private User activeUser() {
        User u = new User();
        u.setUid(TEST_UID);
        u.setEmail(TEST_EMAIL);
        u.setDisplayName("Test User");
        u.setStatus("active");
        u.setRole("user");
        u.setProfileCompletedAt(Timestamp.ofTimeSecondsAndNanos(1715000000L, 0));
        return u;
    }

    // ── Test 1: happy path ──────────────────────────────────────────────────

    @Test
    void postProfileHappyPath() throws Exception {
        User saved = activeUser();
        when(accountProfileService.completeProfile(eq(TEST_UID), eq("Test User"), any(LocalDate.class)))
                .thenReturn(saved);

        CompleteProfileRequest req = new CompleteProfileRequest();
        req.setDisplayName("Test User");
        req.setDateOfBirth(LocalDate.of(2000, 1, 1));

        mockMvc.perform(post("/api/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(TEST_UID))
                .andExpect(jsonPath("$.status").value("active"));
    }

    // ── Test 2: under-13 → 422 ─────────────────────────────────────────────

    @Test
    void postProfileUnder13Returns422() throws Exception {
        when(accountProfileService.completeProfile(eq(TEST_UID), any(), any(LocalDate.class)))
                .thenThrow(new AgeIneligibleException(TEST_UID, 10));

        CompleteProfileRequest req = new CompleteProfileRequest();
        req.setDisplayName("Young User");
        req.setDateOfBirth(LocalDate.of(2020, 1, 1));

        mockMvc.perform(post("/api/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGE_INELIGIBLE"));
    }

    // ── Test 3: already completed → 409 ────────────────────────────────────

    @Test
    void postProfileAlreadyCompletedReturns409() throws Exception {
        when(accountProfileService.completeProfile(eq(TEST_UID), any(), any(LocalDate.class)))
                .thenThrow(new ProfileAlreadyCompletedException(TEST_UID));

        CompleteProfileRequest req = new CompleteProfileRequest();
        req.setDisplayName("Test User");
        req.setDateOfBirth(LocalDate.of(2000, 1, 1));

        mockMvc.perform(post("/api/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_ALREADY_COMPLETED"));
    }

    // ── Test 4: blank displayName → 400 (Bean Validation) ──────────────────

    @Test
    void postProfileBlankDisplayNameReturns400() throws Exception {
        CompleteProfileRequest req = new CompleteProfileRequest();
        req.setDisplayName("   ");
        req.setDateOfBirth(LocalDate.of(2000, 1, 1));

        mockMvc.perform(post("/api/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // ── Test 5: malformed dateOfBirth → 400 (Jackson deserialization) ───────

    @Test
    void postProfileMalformedDateReturns400() throws Exception {
        String badBody = "{\"displayName\":\"Test\",\"dateOfBirth\":\"not-a-date\"}";

        mockMvc.perform(post("/api/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badBody))
                .andExpect(status().isBadRequest());
    }

    // ── Test 6: GET /me happy path ─────────────────────────────────────────

    @Test
    void getMeReturnsCallerProfile() throws Exception {
        when(userRepository.findByUid(TEST_UID)).thenReturn(Optional.of(activeUser()));

        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(TEST_UID))
                .andExpect(jsonPath("$.email").value(TEST_EMAIL))
                .andExpect(jsonPath("$.displayName").value("Test User"));
    }

    // ── Test 7: GET /me lazy-creates doc when missing ──────────────────────

    @Test
    void getMeLazyCreatesIfMissing() throws Exception {
        when(userRepository.findByUid(TEST_UID)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            // Assert the fresh user has PENDING_PROFILE status before we return it
            org.junit.jupiter.api.Assertions.assertEquals(
                UserStatus.PENDING_PROFILE, u.getStatusEnum());
            return u;
        });

        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_profile"))
                .andExpect(jsonPath("$.uid").value(TEST_UID));

        verify(userRepository).save(any(User.class));
    }

    // ── Test 8: POST /profile AgeIneligibleAborted → 500 ──────────────────

    @Test
    void postProfileAgeIneligibleAbortedReturns500() throws Exception {
        when(accountProfileService.completeProfile(eq(TEST_UID), any(), any(LocalDate.class)))
                .thenThrow(new AgeIneligibleAbortedException(TEST_UID,
                        new RuntimeException("revoke failed")));

        CompleteProfileRequest req = new CompleteProfileRequest();
        req.setDisplayName("Kid");
        req.setDateOfBirth(LocalDate.of(2020, 1, 1));

        mockMvc.perform(post("/api/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AGE_INELIGIBLE_ABORTED"));
    }
}
