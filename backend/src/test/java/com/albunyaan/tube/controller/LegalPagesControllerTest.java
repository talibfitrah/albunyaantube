package com.albunyaan.tube.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Google Play policy 13327111 requires a publicly reachable web URL where a
 * user can request account deletion without reinstalling the app, and the
 * Android About screen links to /privacy, /terms and /licenses — all four must
 * render rather than 404.
 *
 * <p>These tests pin the CONTENT contract. Anonymous reachability through the
 * real Spring Security chain is proved separately in
 * {@code SelfDeleteAccountIT#publicLegalPages_areReachableAnonymously} —
 * {@code addFilters = false} here bypasses security entirely.
 */
@WebMvcTest(LegalPagesController.class)
@AutoConfigureMockMvc(addFilters = false)
class LegalPagesControllerTest {

    @Autowired
    MockMvc mockMvc;

    // FirebaseAuthFilter is a Filter, so @WebMvcTest instantiates it and needs
    // its two collaborators — same pattern as WatchPageControllerTest.
    @MockBean
    com.google.firebase.auth.FirebaseAuth firebaseAuth;

    @MockBean
    com.albunyaan.tube.repository.UserRepository userRepository;

    private static final String CONTACT = "info@albunyaan.tv";

    @Test
    void deleteAccountPage_isHtml_namesTheApp_andGivesTheContactAddress() throws Exception {
        mockMvc.perform(get("/delete-account"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("FitrahTube")))
                // Users who already uninstalled cannot use the in-app path, so
                // the page MUST carry an off-app request channel.
                .andExpect(content().string(containsString(CONTACT)))
                // Play reviewers look for what is erased vs retained.
                .andExpect(content().string(containsString("Settings")))
                .andExpect(content().string(containsString("audit")));
    }

    @Test
    void privacyPage_isHtml_namesTheApp_andCoversTheRequiredDisclosures() throws Exception {
        mockMvc.perform(get("/privacy"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("FitrahTube")))
                // Collection / use / sharing / retention / deletion.
                .andExpect(content().string(containsString("X-Device-Id")))
                .andExpect(content().string(containsString("Firebase")))
                .andExpect(content().string(containsString("Retention")))
                .andExpect(content().string(containsString("/delete-account")))
                .andExpect(content().string(containsString(CONTACT)));
    }

    @Test
    void termsPage_isHtml_andNamesTheApp() throws Exception {
        mockMvc.perform(get("/terms"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("FitrahTube")))
                .andExpect(content().string(containsString(CONTACT)));
    }

    @Test
    void licensesPage_isHtml_namesTheApp_andDisclosesTheGplDependencies() throws Exception {
        mockMvc.perform(get("/licenses"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("FitrahTube")))
                // The two copyleft dependencies must be named explicitly — they
                // drive the project's own licensing obligation.
                .andExpect(content().string(containsString("NewPipeExtractor")))
                .andExpect(content().string(containsString("GPL")))
                .andExpect(content().string(containsString("ffmpeg-kit-min-gpl")))
                .andExpect(content().string(containsString("Apache License 2.0")));
    }
}
