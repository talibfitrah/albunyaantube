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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    @MockBean
    com.albunyaan.tube.service.MailService mailService;

    @MockBean
    com.albunyaan.tube.service.AuthService authService;

    @MockBean(name = "passwordResetExecutor")
    java.util.concurrent.Executor passwordResetExecutor;

    ObjectMapper objectMapper;

    private static final String TEST_UID = "uid-test-1";
    private static final String TEST_EMAIL = "user@test.com";

    @BeforeEach
    void setUpPrincipal() {
        FirebaseUserDetails principal = new FirebaseUserDetails(TEST_UID, TEST_EMAIL, "user", true);
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

    /** A mail the server never handed to Graph is a 503, not a 200: both apps run Firebase's own
     *  mailer only on a non-2xx (see the controller). */
    @Test
    void sendVerificationEmailAnswers503WhenTheMailerDidNotSend() throws Exception {
        signInUnverified("uid-unsent");
        when(mailService.isEnabled()).thenReturn(true);
        when(firebaseAuth.generateEmailVerificationLink(TEST_EMAIL)).thenReturn("https://verify/link");
        when(mailService.sendEmailVerification(TEST_EMAIL, "https://verify/link")).thenReturn(false);

        mockMvc.perform(post("/api/account/send-verification-email"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MAIL_UNAVAILABLE"));

        // The cooldown is recorded whether or not the mailer sent: the apps fall back to
        // Firebase on the 503 and wait 60 s regardless, so a retry window here would only let
        // one uid loop generateEmailVerificationLink (Firebase Admin quota) while mail is down.
        when(mailService.sendEmailVerification(TEST_EMAIL, "https://verify/link")).thenReturn(true);
        mockMvc.perform(post("/api/account/send-verification-email"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void sendVerificationEmailAnswers200OnlyWhenTheMailerSent() throws Exception {
        signInUnverified("uid-sent");
        when(mailService.isEnabled()).thenReturn(true);
        when(firebaseAuth.generateEmailVerificationLink(TEST_EMAIL)).thenReturn("https://verify/link");
        when(mailService.sendEmailVerification(TEST_EMAIL, "https://verify/link")).thenReturn(true);

        mockMvc.perform(post("/api/account/send-verification-email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Verification email sent"));
    }

    /** CF-A-57 (Cubic P3): with mail off there is nothing to carry the link, so the Firebase
     *  Admin call is skipped; the cooldown is still recorded so the server-side throttle is one
     *  rule whatever the reason for the 503. */
    @Test
    void sendVerificationEmailAnswers503WithoutGeneratingALinkWhenMailIsDisabled() throws Exception {
        signInUnverified("uid-mail-off");
        when(mailService.isEnabled()).thenReturn(false);

        mockMvc.perform(post("/api/account/send-verification-email"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MAIL_UNAVAILABLE"));
        verify(firebaseAuth, never()).generateEmailVerificationLink(any());
        verify(mailService, never()).sendEmailVerification(any(), any());

        mockMvc.perform(post("/api/account/send-verification-email"))
                .andExpect(status().isTooManyRequests());
    }

    /** One uid per test: the controller's verification cooldown map is on the shared bean. */
    private void signInUnverified(String uid) {
        FirebaseUserDetails principal = new FirebaseUserDetails(uid, TEST_EMAIL, "user", false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @Test
    void postProfileHappyPath() throws Exception {
        User saved = activeUser();
        when(accountProfileService.completeProfile(eq(TEST_UID), eq("Test User"), any(LocalDate.class), any(String.class)))
                .thenReturn(saved);

        CompleteProfileRequest req = new CompleteProfileRequest();
        req.setDisplayName("Test User");
        req.setDateOfBirth(LocalDate.of(2000, 1, 1));
        req.setPhoneNumber("+31612345678");

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
        when(accountProfileService.completeProfile(eq(TEST_UID), any(), any(LocalDate.class), any(String.class)))
                .thenThrow(new AgeIneligibleException(TEST_UID, 10));

        CompleteProfileRequest req = new CompleteProfileRequest();
        req.setDisplayName("Young User");
        req.setDateOfBirth(LocalDate.of(2020, 1, 1));
        req.setPhoneNumber("+31612345678");

        mockMvc.perform(post("/api/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGE_INELIGIBLE"));
    }

    // ── Test 3: already completed → 409 ────────────────────────────────────

    @Test
    void postProfileAlreadyCompletedReturns409() throws Exception {
        when(accountProfileService.completeProfile(eq(TEST_UID), any(), any(LocalDate.class), any(String.class)))
                .thenThrow(new ProfileAlreadyCompletedException(TEST_UID));

        CompleteProfileRequest req = new CompleteProfileRequest();
        req.setDisplayName("Test User");
        req.setDateOfBirth(LocalDate.of(2000, 1, 1));
        req.setPhoneNumber("+31612345678");

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
        req.setPhoneNumber("+31612345678");

        mockMvc.perform(post("/api/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // ── Phone is optional (owner ruling 2026-09-27) ─────────────────────────

    /** No phone, or a blank one, reaches the service as null -- never a 400. */
    @Test
    void postProfileWithoutPhoneNumberSucceeds() throws Exception {
        when(accountProfileService.completeProfile(eq(TEST_UID), eq("Test User"), any(LocalDate.class), eq(null)))
                .thenReturn(activeUser());

        for (String body : java.util.List.of(
                "{\"displayName\":\"Test User\",\"dateOfBirth\":\"2000-01-01\"}",
                "{\"displayName\":\"Test User\",\"dateOfBirth\":\"2000-01-01\",\"phoneNumber\":null}",
                "{\"displayName\":\"Test User\",\"dateOfBirth\":\"2000-01-01\",\"phoneNumber\":\"  \"}")) {
            mockMvc.perform(post("/api/account/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.uid").value(TEST_UID));
        }
        verify(accountProfileService, org.mockito.Mockito.times(3))
                .completeProfile(eq(TEST_UID), eq("Test User"), eq(LocalDate.of(2000, 1, 1)), eq(null));
    }

    /** Optional is not unvalidated: a phone that IS given must still be E.164. */
    @Test
    void postProfileMalformedPhoneNumberStillReturns400() throws Exception {
        mockMvc.perform(post("/api/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Test User\",\"dateOfBirth\":\"2000-01-01\",\"phoneNumber\":\"0612345678\"}"))
                .andExpect(status().isBadRequest());
        verify(accountProfileService, never()).completeProfile(any(), any(), any(), any());
    }

    /** PUT: "" or whitespace reaches the service as-is (it means "remove"); omitted/null = no change. */
    @Test
    void putProfileBlankPhoneReachesTheServiceAndNullMeansNoChange() throws Exception {
        when(accountProfileService.updateProfile(eq(TEST_UID), any()))
                .thenReturn(com.albunyaan.tube.dto.AccountMeResponse.from(activeUser()));

        for (String phone : java.util.Arrays.asList("", "  ", null)) {
            String body = phone == null ? "{\"displayName\":\"Test User\"}"
                    : "{\"phoneNumber\":\"" + phone + "\"}";
            mockMvc.perform(put("/api/account/profile").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.phoneNumber").value(org.hamcrest.Matchers.nullValue()));
            verify(accountProfileService).updateProfile(eq(TEST_UID), eq(
                    new com.albunyaan.tube.dto.UpdateProfileRequest(phone == null ? "Test User" : null, null, phone)));
        }
        mockMvc.perform(put("/api/account/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":null}"))
                .andExpect(status().isOk());
        verify(accountProfileService).updateProfile(eq(TEST_UID),
                eq(new com.albunyaan.tube.dto.UpdateProfileRequest(null, null, null)));
    }

    /** PUT: a phone that is neither blank nor E.164 is still a 400. */
    @Test
    void putProfileMalformedPhoneStillReturns400() throws Exception {
        for (String bad : java.util.List.of("0612345678", " +31612345678", "abc")) {
            mockMvc.perform(put("/api/account/profile").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phoneNumber\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verify(accountProfileService, never()).updateProfile(any(), any());
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
        // GET /me is now backed by userRepository.getOrCreate(uid, factory)
        // for transactional lazy-create (cubic R4 P2). The mock returns the
        // existing user without invoking the factory.
        when(userRepository.getOrCreate(eq(TEST_UID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(activeUser());

        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(TEST_UID))
                .andExpect(jsonPath("$.email").value(TEST_EMAIL))
                .andExpect(jsonPath("$.displayName").value("Test User"));
    }

    /** The row mirrors Firebase's email (verifyAndChangeEmail moves it with no callback to us). */
    @Test
    void getMeHasTheRowFollowTheTokensFirebaseEmail() throws Exception {
        User stored = activeUser();
        when(userRepository.getOrCreate(eq(TEST_UID), org.mockito.ArgumentMatchers.any())).thenReturn(stored);

        mockMvc.perform(get("/api/account/me")).andExpect(status().isOk());

        verify(accountProfileService).followFirebaseEmail(eq(stored),
                org.mockito.ArgumentMatchers.argThat(p -> TEST_UID.equals(p.getUid())));
    }

    /** The email sync is best-effort: /me is every app launch, and a failed mirror write (or audit)
     *  must not turn it into a 500 outside the typed Lazy* envelopes. */
    @Test
    void getMeStillAnswers200WhenTheEmailSyncThrows() throws Exception {
        when(userRepository.getOrCreate(eq(TEST_UID), org.mockito.ArgumentMatchers.any())).thenReturn(activeUser());
        org.mockito.Mockito.doThrow(new java.util.concurrent.ExecutionException(new RuntimeException("firestore down")))
                .doThrow(new java.util.concurrent.TimeoutException("slow"))
                .doThrow(new IllegalStateException("audit store down"))
                .when(accountProfileService).followFirebaseEmail(any(), any());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/account/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.uid").value(TEST_UID));
        }
    }

    @Test
    void getMeRestoresTheInterruptFlagWhenTheEmailSyncIsInterrupted() throws Exception {
        when(userRepository.getOrCreate(eq(TEST_UID), org.mockito.ArgumentMatchers.any())).thenReturn(activeUser());
        org.mockito.Mockito.doThrow(new InterruptedException())
                .when(accountProfileService).followFirebaseEmail(any(), any());

        mockMvc.perform(get("/api/account/me")).andExpect(status().isOk());

        assertTrue(Thread.interrupted(), "the interrupt was swallowed"); // also clears it for later tests
    }

    // ── Test 7: GET /me lazy-creates doc when missing ──────────────────────

    @Test
    void getMeLazyCreatesIfMissing() throws Exception {
        // Simulate the absent-doc branch: invoke the factory to build the fresh
        // user, assert it has PENDING_PROFILE status, return it as the persisted
        // result. Mirrors the transactional getOrCreate contract.
        when(userRepository.getOrCreate(eq(TEST_UID), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Supplier<User> factory =
                            (java.util.function.Supplier<User>) inv.getArgument(1);
                    User fresh = factory.get();
                    org.junit.jupiter.api.Assertions.assertEquals(
                        UserStatus.PENDING_PROFILE, fresh.getStatusEnum());
                    return fresh;
                });

        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_profile"))
                .andExpect(jsonPath("$.uid").value(TEST_UID));

        verify(userRepository).getOrCreate(eq(TEST_UID), org.mockito.ArgumentMatchers.any());
    }

    // ── Test 8: POST /profile AgeIneligibleAborted → 500 ──────────────────

    @Test
    void postProfileAgeIneligibleAbortedReturns500() throws Exception {
        when(accountProfileService.completeProfile(eq(TEST_UID), any(), any(LocalDate.class), any(String.class)))
                .thenThrow(new AgeIneligibleAbortedException(TEST_UID,
                        new RuntimeException("revoke failed")));

        CompleteProfileRequest req = new CompleteProfileRequest();
        req.setDisplayName("Kid");
        req.setDateOfBirth(LocalDate.of(2020, 1, 1));
        req.setPhoneNumber("+31612345678");

        mockMvc.perform(post("/api/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AGE_INELIGIBLE_ABORTED"));
    }

    // ── Defense-in-depth contract tests (cubic R1 follow-up) ──────────────
    // Spring Security gates /api/account/** server-side, so principal should
    // never be null in production. The defensive `if (principal == null)`
    // guards exist as belt-and-suspenders: if a future config refactor ever
    // drops `permitAll`, misorders filter chains, or someone hits the
    // endpoint via a test harness that bypasses filters, the contract is to
    // return 401 (auth failure), not 500 (NPE on principal.getUid()). These
    // tests pin that contract.

    @Test
    void getMe_returnsUnauthorized_whenPrincipalIsNull() throws Exception {
        SecurityContextHolder.clearContext();  // override @BeforeEach
        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postProfile_returnsUnauthorized_whenPrincipalIsNull() throws Exception {
        SecurityContextHolder.clearContext();  // override @BeforeEach
        CompleteProfileRequest req = new CompleteProfileRequest();
        req.setDisplayName("X");
        req.setDateOfBirth(LocalDate.of(2000, 1, 1));
        req.setPhoneNumber("+31612345678");
        mockMvc.perform(post("/api/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // ── Test 9: email not verified → 403 EMAIL_NOT_VERIFIED ──────────────
    @Test
    void postProfile_returnsForbidden_whenEmailNotVerified() throws Exception {
        // Replace the @BeforeEach principal with one that has emailVerified=false
        FirebaseUserDetails unverified = new FirebaseUserDetails(TEST_UID, TEST_EMAIL, "user", false);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(unverified, null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        CompleteProfileRequest req = new CompleteProfileRequest();
        req.setDisplayName("Alice");
        req.setDateOfBirth(LocalDate.of(2000, 1, 1));
        req.setPhoneNumber("+31612345678");
        mockMvc.perform(post("/api/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    // ── DELETE /api/account/me — self-serve account deletion ────────────────
    // Google Play policy 13327111 requires an in-app deletion path for any app
    // that allows in-app account creation.

    @Test
    void deleteMe_returnsNoContent_andDelegatesToAuthService() throws Exception {
        mockMvc.perform(delete("/api/account/me"))
                .andExpect(status().isNoContent());

        verify(authService).deleteAccountPermanently(TEST_UID);
    }

    @Test
    void deleteMe_returnsUnauthorized_whenPrincipalIsNull() throws Exception {
        SecurityContextHolder.clearContext();  // override @BeforeEach

        mockMvc.perform(delete("/api/account/me"))
                .andExpect(status().isUnauthorized());

        // No principal ⇒ no uid ⇒ the destructive call must never be reached.
        org.mockito.Mockito.verifyNoInteractions(authService);
    }

    @Test
    void deleteMe_returnsConflict_whenCallerIsLastActiveAdmin() throws Exception {
        org.mockito.Mockito.doThrow(
                        new com.albunyaan.tube.exception.LastAdminException(
                                "Cannot delete the last active admin account."))
                .when(authService).deleteAccountPermanently(TEST_UID);

        mockMvc.perform(delete("/api/account/me"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));
    }

    @Test
    void deleteMe_secondCallIsIdempotent() throws Exception {
        // The service is idempotent on an already-deleted account (it returns
        // without throwing), so the endpoint must answer 204 both times rather
        // than 404/409 on the retry — a mobile client that lost the first
        // response must be able to retry safely.
        mockMvc.perform(delete("/api/account/me"))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(""));
        mockMvc.perform(delete("/api/account/me"))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(""));

        verify(authService, org.mockito.Mockito.times(2)).deleteAccountPermanently(TEST_UID);
    }
    // ── Forgot password: POST /api/account/send-password-reset-email (signed out) ──

    private static final String RESET_SENT = "If an account exists for that email, a reset link is on its way";

    private org.springframework.test.web.servlet.ResultActions postReset(String email, String realIp) throws Exception {
        return mockMvc.perform(post("/api/account/send-password-reset-email")
                .header("X-Real-IP", realIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    /** The send is handed to passwordResetExecutor and never run on the request thread: neither the
     *  answer nor its timing may depend on whether the address has an account. */
    @Test
    void sendPasswordResetEmailHandsTheSendOffTheRequestThread() throws Exception {
        when(mailService.isEnabled()).thenReturn(true);

        postReset("reset-a@test.com", "198.51.100.1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(RESET_SENT));

        org.mockito.ArgumentCaptor<Runnable> send = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(passwordResetExecutor).execute(send.capture());
        verify(authService, never()).sendPasswordResetEmailQuietly(any());
        org.mockito.Mockito.verifyNoInteractions(firebaseAuth);
        send.getValue().run();
        verify(authService).sendPasswordResetEmailQuietly("reset-a@test.com");
    }

    @Test
    void sendPasswordResetEmailAnswers503WhenMailIsDisabled() throws Exception {
        when(mailService.isEnabled()).thenReturn(false);

        postReset("reset-b@test.com", "198.51.100.2")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MAIL_UNAVAILABLE"));
        verify(passwordResetExecutor, never()).execute(any());
    }

    /** A per-address limit must never answer differently (that would block the victim and leak):
     *  a repeat inside the cooldown is dropped, and the caller cannot tell. */
    @Test
    void aRepeatResetForTheSameAddressIsDroppedButAnsweredIdentically() throws Exception {
        when(mailService.isEnabled()).thenReturn(true);

        postReset("reset-c@test.com", "198.51.100.10").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(RESET_SENT));
        postReset("RESET-C@test.com", "198.51.100.11").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(RESET_SENT));

        verify(passwordResetExecutor, org.mockito.Mockito.times(1)).execute(any());
    }

    @Autowired
    AccountController controller;

    @Test
    void resetMailPerAddressIsOneAMinuteAndTenADay() {
        long t = 1_000_000_000L;
        String email = "reset-window@test.com";
        assertTrue(controller.resetMailAllowed(email, t));
        assertFalse(controller.resetMailAllowed(email, t + 59_999), "inside the 60 s cooldown");
        for (int i = 1; i < 10; i++) {
            assertTrue(controller.resetMailAllowed(email, t + i * 60_000L), "send " + (i + 1));
        }
        assertFalse(controller.resetMailAllowed(email, t + 10 * 60_000L), "the 11th inside 24 h");
        assertTrue(controller.resetMailAllowed(email, t + 86_400_000L), "the first send aged out");
    }

    /** X-Real-IP (set by nginx from Cloudflare's ranges), never CF-Connecting-IP (forgeable at the
     *  public origin). The per-IP limit may answer 429: it says nothing about any address. */
    @Test
    void sendPasswordResetEmailLimitsEachIpToTwentyAnHourAndIgnoresCfConnectingIp() throws Exception {
        when(mailService.isEnabled()).thenReturn(true);

        for (int i = 0; i < 20; i++) {
            mockMvc.perform(post("/api/account/send-password-reset-email")
                            .header("X-Real-IP", "203.0.113.7")
                            .header("CF-Connecting-IP", "192.0.2." + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"reset-d" + i + "@test.com\"}"))
                    .andExpect(status().isOk());
        }
        postReset("reset-d-last@test.com", "203.0.113.7")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        postReset("reset-d-last@test.com", "203.0.113.8").andExpect(status().isOk());
    }

    @Test
    void theIpKeyGroupsIpv6By64AndIgnoresAnOverlongOrBogusHeader() {
        assertEquals(AccountController.clientIpKey("2001:db8:1:2::a", "127.0.0.1"),
                AccountController.clientIpKey("2001:db8:1:2:ffff:eeee:dddd:cccc", "127.0.0.1"));
        assertNotEquals(AccountController.clientIpKey("2001:db8:1:2::a", "127.0.0.1"),
                AccountController.clientIpKey("2001:db8:1:3::a", "127.0.0.1"));
        assertNotEquals(AccountController.clientIpKey("198.51.100.1", "127.0.0.1"),
                AccountController.clientIpKey("198.51.100.2", "127.0.0.1"));
        String overlong = "1".repeat(46);
        assertEquals(AccountController.clientIpKey(null, "192.0.2.1"),
                AccountController.clientIpKey(overlong, "192.0.2.1"));
        assertEquals(AccountController.clientIpKey(null, "192.0.2.1"),
                AccountController.clientIpKey("evil.example", "192.0.2.1"));
    }

    @Test
    void sendPasswordResetEmailRejectsAMalformedAddress() throws Exception {
        postReset("not-an-email", "198.51.100.30").andExpect(status().isBadRequest());
        verify(passwordResetExecutor, never()).execute(any());
    }

    // ── Change email: POST /api/account/send-change-email-verification ────────────

    private org.springframework.test.web.servlet.ResultActions postChangeEmail(String uid, String newEmail)
            throws Exception {
        return postChangeEmail(uid, newEmail, System.currentTimeMillis() / 1000);
    }

    private org.springframework.test.web.servlet.ResultActions postChangeEmail(String uid, String newEmail, Long authTime)
            throws Exception {
        // The token's email (TEST_EMAIL) is deliberately NOT the account's current address below:
        // the link must be minted for the uid's record, never for a possibly stale token claim.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new FirebaseUserDetails(uid, TEST_EMAIL, "user", true, authTime), null, java.util.List.of()));
        return mockMvc.perform(post("/api/account/send-change-email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newEmail\":\"" + newEmail + "\"}"));
    }

    private void currentEmailIs(String uid, String email) throws Exception {
        com.google.firebase.auth.UserRecord record = org.mockito.Mockito.mock(com.google.firebase.auth.UserRecord.class);
        when(record.getEmail()).thenReturn(email);
        when(firebaseAuth.getUser(uid)).thenReturn(record);
    }

    /** verifyBeforeUpdateEmail used to enforce Firebase's requires-recent-login; the backend must
     *  too, or any live token (stolen, or a device left signed in) could move the email. */
    @Test
    void sendChangeEmailVerificationRequiresASignInFromTheLastFiveMinutes() throws Exception {
        when(mailService.isEnabled()).thenReturn(true);

        postChangeEmail("uid-change-stale", "new@test.com", System.currentTimeMillis() / 1000 - 301)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REQUIRES_RECENT_LOGIN"));
        postChangeEmail("uid-change-stale", "new@test.com", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REQUIRES_RECENT_LOGIN"));
        verify(authService, never()).generateVerifyAndChangeEmailLink(any(), any());
        verify(mailService, never()).sendEmailChangeVerification(any(), any());
    }

    @Test
    void sendChangeEmailVerificationMailsTheNewAddressALinkForTheCallersOwnAccount() throws Exception {
        when(mailService.isEnabled()).thenReturn(true);
        currentEmailIs("uid-change-ok", "current@test.com");
        when(authService.generateVerifyAndChangeEmailLink("current@test.com", "new@test.com"))
                .thenReturn("https://change/link");
        when(mailService.sendEmailChangeVerification("new@test.com", "https://change/link")).thenReturn(true);

        postChangeEmail("uid-change-ok", "new@test.com").andExpect(status().isOk());

        verify(mailService).sendEmailChangeVerification("new@test.com", "https://change/link");
        // Per-uid cooldown, same 60 s rule as the verification mail.
        postChangeEmail("uid-change-ok", "new@test.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void sendChangeEmailVerificationAnswers503WithoutALinkWhenMailIsDisabled() throws Exception {
        when(mailService.isEnabled()).thenReturn(false);

        postChangeEmail("uid-change-off", "new@test.com")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MAIL_UNAVAILABLE"));
        verify(authService, never()).generateVerifyAndChangeEmailLink(any(), any());
        postChangeEmail("uid-change-off", "new@test.com").andExpect(status().isTooManyRequests());
    }

    @Test
    void sendChangeEmailVerificationAnswers503WhenTheMailerDidNotSend() throws Exception {
        when(mailService.isEnabled()).thenReturn(true);
        currentEmailIs("uid-change-unsent", "current@test.com");
        when(authService.generateVerifyAndChangeEmailLink("current@test.com", "new@test.com"))
                .thenReturn("https://change/link");
        when(mailService.sendEmailChangeVerification(any(), any())).thenReturn(false);

        postChangeEmail("uid-change-unsent", "new@test.com")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MAIL_UNAVAILABLE"));
    }

    /** EMAIL_IN_USE keeps the apps' existing "already in use" copy. It is an account-existence
     *  answer, so it costs the same 60 s cooldown as a send: no unlimited probing. */
    @Test
    void sendChangeEmailVerificationAnswers409AndStillCoolsDownWhenTheNewAddressIsTaken() throws Exception {
        when(mailService.isEnabled()).thenReturn(true);
        currentEmailIs("uid-change-taken", "current@test.com");
        when(authService.generateVerifyAndChangeEmailLink("current@test.com", "taken@test.com"))
                .thenThrow(new com.google.firebase.auth.FirebaseAuthException(
                        com.google.firebase.ErrorCode.ALREADY_EXISTS, "EMAIL_EXISTS", null, null,
                        com.google.firebase.auth.AuthErrorCode.EMAIL_ALREADY_EXISTS));

        postChangeEmail("uid-change-taken", "taken@test.com")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_IN_USE"));
        postChangeEmail("uid-change-taken", "other@test.com").andExpect(status().isTooManyRequests());
    }

    /** `@Email` accepts `a@b`; Identity Toolkit does not. Its INVALID_EMAIL is the caller's input,
     *  so a 400, not a 500. */
    @Test
    void sendChangeEmailVerificationAnswers400WhenFirebaseRejectsTheAddress() throws Exception {
        when(mailService.isEnabled()).thenReturn(true);
        currentEmailIs("uid-change-invalid", "current@test.com");
        when(authService.generateVerifyAndChangeEmailLink("current@test.com", "a@b"))
                .thenThrow(new com.google.firebase.auth.FirebaseAuthException(
                        com.google.firebase.ErrorCode.INVALID_ARGUMENT, "INVALID_NEW_EMAIL", null, null, null));

        postChangeEmail("uid-change-invalid", "a@b")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EMAIL"));
    }

    @Test
    void sendChangeEmailVerificationRejectsAMalformedAddress() throws Exception {
        postChangeEmail("uid-change-bad", "not-an-email").andExpect(status().isBadRequest());
        verify(authService, never()).generateVerifyAndChangeEmailLink(any(), any());
    }
}
