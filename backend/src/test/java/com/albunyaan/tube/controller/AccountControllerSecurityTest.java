package com.albunyaan.tube.controller;

import com.albunyaan.tube.exception.GlobalExceptionHandler;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.security.SecurityConfig;
import com.albunyaan.tube.service.AccountProfileService;
import com.albunyaan.tube.service.AuthService;
import com.albunyaan.tube.service.MailService;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REAL {@link SecurityConfig} chain (no {@code addFilters = false}): forgot-password is used
 * signed out, so its one POST must be anonymous -- and nothing else under /api/account may be.
 */
@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AccountControllerSecurityTest {

    @Autowired MockMvc mockMvc;
    @MockBean FirebaseAuth firebaseAuth;
    @MockBean UserRepository userRepository;
    @MockBean AccountProfileService accountProfileService;
    @MockBean MailService mailService;
    @MockBean AuthService authService;
    @MockBean(name = "passwordResetExecutor") java.util.concurrent.Executor passwordResetExecutor;

    @Test
    void forgotPasswordIsReachableWithNoToken() throws Exception {
        when(mailService.isEnabled()).thenReturn(true);

        mockMvc.perform(post("/api/account/send-password-reset-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"signed-out@test.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void nothingElseUnderAccountOpensWithIt() throws Exception {
        mockMvc.perform(get("/api/account/send-password-reset-email")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/account/send-change-email-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"new@test.com\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/account/send-verification-email")).andExpect(status().isForbidden());
    }
}
