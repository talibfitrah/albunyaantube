package com.albunyaan.tube.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

    /**
     * The one navigation path the app actually implements, verified against the
     * source rather than assumed:
     * <ul>
     *   <li>{@code res/layout/fragment_me.xml:11,16} — the Me tab owns a
     *       toolbar titled {@code @string/nav_me} ("Me")…</li>
     *   <li>…carrying {@code res/menu/menu_me_kebab.xml} whose
     *       {@code action_profile} item is titled
     *       {@code @string/me_kebab_profile} ("Profile").</li>
     *   <li>{@code ui/me/MeFragment.kt:279} navigates
     *       {@code action_me_to_profile} → {@code profileFragment}
     *       ({@code res/navigation/main_tabs_nav.xml:60,71}).</li>
     *   <li>{@code res/layout/fragment_profile.xml:257,268} holds
     *       {@code deleteAccountRow}, labelled
     *       {@code @string/profile_delete_account} ("Delete account"), wired in
     *       {@code ui/me/profile/ProfileFragment.kt:81}.</li>
     * </ul>
     *
     * <p>The pages previously published "Settings → Account → Delete account".
     * Settings DOES have an Account section, but
     * {@code res/layout/fragment_settings.xml:34-65} shows it contains only
     * {@code settings_item_signout} — no delete control, no profile fields. A
     * Play reviewer following the published path would have found nothing and
     * reported the deletion feature missing.
     */
    private static final String IN_APP_PATH =
            "Me &rarr; &#8942; &rarr; Profile &rarr; Delete account";

    @Test
    void deleteAccountPage_isHtml_namesTheApp_andGivesTheContactAddress() throws Exception {
        mockMvc.perform(get("/delete-account"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("FitrahTube")))
                // Users who already uninstalled cannot use the in-app path, so
                // the page MUST carry an off-app request channel.
                .andExpect(content().string(containsString(CONTACT)))
                // Play reviewers follow the published path literally.
                .andExpect(content().string(containsString(IN_APP_PATH)))
                .andExpect(content().string(containsString("audit")));
    }

    /**
     * Every page that names the in-app deletion route must name the SAME route.
     * Three copies drifting apart is how the wrong one gets shipped.
     */
    @Test
    void everyPageNamingTheInAppRoute_namesTheRouteThatExists() throws Exception {
        for (String path : java.util.List.of("/delete-account", "/privacy", "/terms")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(IN_APP_PATH)))
                    .andExpect(content().string(not(containsString("Settings &rarr; Account"))));
        }
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
                // The iOS app offers Sign in with Apple; the policy must name it.
                .andExpect(content().string(containsString("Sign in with Apple")))
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

    /**
     * The page bodies are Java text blocks, so an HTML comment written inside
     * one is SERVED, not stripped. Four {@code <!-- TODO(owner): ... -->} notes
     * were published this way on /privacy and /terms — the live privacy policy
     * told readers it was unreviewed and that audit retention was unbounded.
     * Internal notes belong in {@code //} Java comments above the handler.
     */
    @Test
    void noPage_leaksAnInternalTodoIntoTheServedHtml() throws Exception {
        for (String path : java.util.List.of("/delete-account", "/privacy", "/terms", "/licenses")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("TODO"))));
        }
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
