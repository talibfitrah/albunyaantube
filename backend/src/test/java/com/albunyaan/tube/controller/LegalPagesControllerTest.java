package com.albunyaan.tube.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
            "Me &rarr; menu (&#8942; or &hellip;) &rarr; Profile &rarr; Delete account";

    /** Every public page; a new page joins the shared checks by being added here. */
    private static final java.util.List<String> ALL_PAGES =
            java.util.List.of("/delete-account", "/privacy", "/terms", "/licenses", "/support");

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
        for (String path : java.util.List.of("/delete-account", "/privacy", "/terms", "/support")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(IN_APP_PATH)))
                    .andExpect(content().string(not(containsString("Settings &rarr; Account"))))
                    // iOS shows a horizontal "…", Android a vertical "⋮": name neither alone.
                    .andExpect(content().string(not(containsString("three-dot"))))
                    .andExpect(content().string(not(containsString("Me &rarr; &#8942; &rarr;"))));
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
                // The iOS app offers Sign in with Apple; the policy must name it, and its scope
                // sentence must cover iOS, not Android alone.
                .andExpect(content().string(containsString("Sign in with Apple")))
                // Scope must name the Play build AND the other builds (iOS included).
                .andExpect(content().string(containsString("the FitrahTube Android app on Google Play "
                        + "(<code>com.albunyaan.tube.play</code>), other FitrahTube app builds, and the "
                        + "service at <code>app.fitrahtube.com</code>")))
                .andExpect(content().string(containsString(CONTACT)));
    }

    /** Served HTML with every whitespace run collapsed to one space, so phrases wrapped across text-block lines still match. */
    private String page(String path) throws Exception {
        return mockMvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
    }

    private static void has(String body, String... phrases) {
        for (String p : phrases) assertTrue(body.contains(p), "missing: " + p);
    }

    private static void lacks(String body, String... phrases) {
        for (String p : phrases) assertFalse(body.contains(p), "still present: " + p);
    }

    private static final String CONTROLLER =
            "FitrahTube is published by Stichting Tarbiyah Consultancy, Almere, the Netherlands "
                    + "(the data controller).";

    @Test
    void privacyAndDeletePages_nameTheDataController() throws Exception {
        for (String path : java.util.List.of("/delete-account", "/privacy")) {
            has(page(path), CONTROLLER);
        }
    }

    @Test
    void termsPage_namesTheOperator() throws Exception {
        String terms = page("/terms");
        has(terms, "Stichting Tarbiyah Consultancy, Almere, the Netherlands, is the operator of FitrahTube.");
        lacks(terms, "data controller");
    }

    @Test
    void deleteAccountPage_namesThePlayPackage() throws Exception {
        has(page("/delete-account"), "com.albunyaan.tube.play");
    }

    /**
     * The app extracts and plays streams ON THE DEVICE, so the device talks to
     * Google directly. The old "made by our server, not from your device" claim
     * was false for every app build.
     */
    @Test
    void privacyPage_saysTheDeviceConnectsToYouTubeDirectly() throws Exception {
        String privacy = page("/privacy");
        has(privacy,
                "the app connects directly from your device to YouTube/Google servers",
                "googlevideo.com",
                "To play videos the app runs a Google-provided script in a web view on your device; "
                        + "it collects device and browser signals and sends them to Google.",
                "We do not fingerprint your device ourselves");
        lacks(privacy, "not from your device", "no device fingerprinting", "BotGuard");
    }

    /**
     * Unknown imported items go to moderators WITH the importer's name and email
     * (ApprovalService submitter label), so "only to provide the import" was false.
     */
    @Test
    void privacyPage_disclosesTheYouTubeImportAndLimitedUse() throws Exception {
        String privacy = page("/privacy");
        has(privacy,
                "youtube.readonly",
                "Limited Use",
                "https://myaccount.google.com/permissions",
                "https://www.youtube.com/t/terms",
                "https://policies.google.com/privacy",
                "sent to our moderators for catalogue review, together with your account's display name and email address",
                "We use it only for the import and catalogue-review feature you start",
                "we never use it for advertising",
                "point only to an anonymous placeholder");
        lacks(privacy, "only to provide the import you asked for");
    }

    @Test
    void termsPage_requiresSignIn_andBindsUsersToTheYouTubeTerms() throws Exception {
        String terms = page("/terms");
        has(terms, "You must sign in to use FitrahTube.",
                "you agree to be bound by the <a href=\"https://www.youtube.com/t/terms\">YouTube Terms of Service</a>");
        lacks(terms, "do not need an account");
    }

    @Test
    void privacyPage_treatsSignInAsMandatory() throws Exception {
        lacks(page("/privacy"), "whether or not you are signed in", "If you sign in, the channels");
    }

    /**
     * Search terms are logged by the Spring app (SearchOrchestrator, PublicContentService)
     * into /opt/albunyaan/logs/app.log, which has no rotation (live check 2026-09-27) —
     * not the nginx logs that rotate after 14 days.
     */
    @Test
    void privacyPage_disclosesSuggestionsSearchesAndCast() throws Exception {
        String privacy = page("/privacy");
        has(privacy,
                "suggest channels, playlists and videos for the catalogue, with an optional free-text note",
                "can appear in our application logs, without your account identifier",
                "<strong>Application logs</strong> &mdash; our backend's own log",
                "It currently has no fixed deletion date.",
                "When you cast, Google's Cast SDK sends session and usage data to Google",
                "(The app does include Google's Cast SDK for casting; see section&nbsp;5.)");
        lacks(privacy, "can appear in our server logs");
    }

    /** The Cloudflare / nginx-log facts in section 3 come from the live server config, not code. */
    @Test
    void privacyPage_section3_saysWhereItsAbsencesWereVerified() throws Exception {
        String privacy = page("/privacy");
        has(privacy, "These are absences we have verified in our own code and server configuration,");
        lacks(privacy, "verified in our own code, not merely");
    }

    @Test
    void privacyPage_statesWhoSendsEmailAndWhatGoogleSignInShares() throws Exception {
        String privacy = page("/privacy");
        has(privacy,
                // AccountController sends all three through MailService (Microsoft Graph) when mail
                // is on. Both apps' fallback to Firebase: any mail on a 503 (mail off, or Graph
                // refused a verification/change-email send); reset and change-email also when the
                // backend is unreachable. A reset is mailed after the 200, so a Graph failure there
                // sends nothing (SignInViewModel.sendPasswordReset, EmailVerificationViewModel).
                "only when our own mail service is switched on",
                "Email-verification messages, password-reset messages (the ones you request from the "
                        + "sign-in screen and the ones sent when an administrator resets your password) "
                        + "and the confirmation message sent to your new address when you change your "
                        + "email are then sent through Microsoft's mail service",
                "When our mail service is switched off, Google Firebase sends the messages you request "
                        + "instead. The app also turns to Google Firebase when it cannot reach our server to "
                        + "request a password reset or an email change, and when our server could not send "
                        + "an email-verification or email-change message.",
                "Google shares your name, email address and profile photo address with Firebase Authentication",
                "We do not copy your profile photo into our own database");
        lacks(privacy, "When we send you a password-reset", "Password-reset emails are sent by Google Firebase.",
                "cannot be reached, Google Firebase sends",
                "Password-reset emails you request from the app are sent by Google Firebase.");
    }

    /** The iOS app now requests the name scope; the policy must say where the name ends up. */
    @Test
    void privacyPage_saysAppleSharesTheNameWithPermission() throws Exception {
        String privacy = page("/privacy");
        has(privacy, "With your permission, Apple also shares your name, which becomes your display name; "
                + "you can change it.");
        lacks(privacy, "We do not ask for your name");
    }

    /** The admin-only user endpoints return the full User model, phone and date of birth included. */
    @Test
    void privacyPage_statesPhoneAndDateOfBirthUseTruthfully() throws Exception {
        String privacy = page("/privacy");
        has(privacy,
                "<strong>Phone number (optional).</strong> You can leave it out when you set up your profile.",
                "If you give one, it is stored with your profile.",
                "Used to check that you are at least 13, and kept with your profile until you delete your account.",
                "Our administrators can see it when they manage accounts.",
                "It is shown back to you in the app and to our administrators when they manage accounts;");
        lacks(privacy, "Used once, to check", "It is shown back to you in the app; we do not verify it",
                "Required when you set up your profile");
    }

    @Test
    void privacyPage_disclosesHostingAndServerLogs() throws Exception {
        String privacy = page("/privacy");
        has(privacy,
                "virtual private server operated for us",
                "<strong>Cloudflare.</strong> Our network and security provider",
                "record Cloudflare's network address, not yours",
                "deleted after 14 days");
        lacks(privacy, "All of the data described in section&nbsp;2 is stored on Google's infrastructure");
    }

    @Test
    void privacyPage_carriesTheGdprDisclosures() throws Exception {
        String privacy = page("/privacy");
        has(privacy,
                "Article 6(1)(b)", "Article 6(1)(f)", "Article 6(1)(a)",
                "https://autoriteitpersoonsgegevens.nl",
                "EU-US Data Privacy Framework",
                "You have the right to");
        lacks(privacy, "Depending on where you live");
    }

    /**
     * Staff actions keep the acting email outside audit_logs too — approval
     * metadata (ApprovalController passes user.getEmail()), content_reports.resolvedBy
     * and ValidationRun.triggeredByDisplayName — and purgeUserData touches none of them.
     */
    @Test
    void deleteAccountPage_isHonestAboutWhatStillLinksTheAccount() throws Exception {
        String del = page("/delete-account");
        has(del,
                "We keep these to investigate abuse and to prove that a deletion happened.",
                "the security and audit logs described above still connect it to your email address, "
                        + "and so do the records of moderation work described below",
                "Records of moderation work (moderator and administrator accounts).",
                "the approvals and rejections you made, the content reports you resolved and the "
                        + "validation runs you started keep the email address you acted from",
                "Content you submitted for review.",
                "no fixed deletion date");
        lacks(del, "cannot be linked back to you", "Google's own policy permits", "the only records that still connect");
        has(page("/privacy"), "records of moderation work done by moderator and administrator accounts, "
                + "which keep the email address that acted");
    }

    @Test
    void licensesPage_describesNewPipeExtractorPlainly() throws Exception {
        String lic = page("/licenses");
        has(lic, "Used to read publicly available YouTube content.");
        lacks(lic, "stream URLs");
    }

    /**
     * Cloudflare Email Address Obfuscation (in front of app.fitrahtube.com)
     * rewrites every bare address into a JavaScript-only decoder, leaving the
     * deletion page with no readable address. EVERY occurrence of the contact
     * address must sit inside Cloudflare's {@code <!--email_off-->…<!--email_on-->}
     * markers.
     */
    @Test
    void everyContactAddress_isShieldedFromCloudflareEmailObfuscation() throws Exception {
        for (String path : ALL_PAGES) {
            String body = page(path);
            assertTrue(body.contains("<!--email_off-->"), path + " has no shielded address");
            String outside = body.replaceAll("(?s)<!--email_off-->.*?<!--email_on-->", "");
            assertFalse(outside.contains(CONTACT), path + " has an unshielded contact address");
        }
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
        for (String path : ALL_PAGES) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("TODO"))));
        }
    }

    /** Store listings need a support URL: what the app is, how to reach us, and self-help. */
    @Test
    void supportPage_isHtml_andGivesContactReportSignInAndDeletionHelp() throws Exception {
        mockMvc.perform(get("/support"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString(CONTACT)));
        String support = page("/support");
        has(support,
                "<h1>FitrahTube Support</h1>",
                "FitrahTube is a curated Islamic video library. Every channel, playlist and video in it "
                        + "is reviewed by our team before it appears.",
                "tap <strong>Report</strong>",
                "<strong>Forgot password?</strong>",
                "https://app.fitrahtube.com/delete-account",
                "<a href=\"/privacy\">", "<a href=\"/terms\">",
                // A Google sign-up sets a password at profile setup on both apps; an Apple sign-up
                // (iOS only) does not, and resetting one into it is unproven, so Android goes via us.
                "Sign in with the Google button, or with your email address and the password you "
                        + "chose when you set up your profile.",
                "Sign in with the Apple button on your iPhone or iPad. To use that account on an "
                        + "Android device, email us at");
        lacks(support, "no separate FitrahTube password", "you also chose a password", "Google or Apple?");
        // Store-copy rules: never name the video platform, no ad/download/background claims.
        lacks(support.toLowerCase(java.util.Locale.ROOT),
                "youtube", "ad-free", "no ads", "download", "background play");
    }

    /** The shared footer lists every sibling page, so it must list /support too. */
    @Test
    void everyPageFooter_linksToSupport() throws Exception {
        for (String path : ALL_PAGES) {
            String footer = page(path).replaceAll("(?s).*<footer>", "");
            has(footer, "<a href=\"/support\">Support</a>", "<a href=\"/licenses\">Licences</a>");
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
