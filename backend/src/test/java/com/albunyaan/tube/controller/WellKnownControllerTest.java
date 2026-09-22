package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.AndroidProperties;
import com.albunyaan.tube.config.IosProperties;
import com.albunyaan.tube.exception.GlobalExceptionHandler;
import com.albunyaan.tube.security.SecurityConfig;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec {@code docs/superpowers/specs/2026-08-23-ios-app-design.md} §12 —
 * {@code WellKnownController} serves the AASA/assetlinks files iOS Universal
 * Links and Android App Links verify against.
 *
 * <p>Unlike {@link PublicContentControllerTest} / {@link LegalPagesControllerTest},
 * this slice does NOT set {@code addFilters = false}: the whole point of
 * requirement (b) is proving the endpoint is reachable with no
 * {@code Authorization} header through the REAL {@link SecurityConfig} chain
 * (the bug this ticket fixes is that chain's default {@code anyRequest().authenticated()}
 * 403-ing an unmapped path — {@code curl -sI …/apple-app-site-association} → 403).
 * {@code FirebaseAuthFilter} is part of that real chain, so it needs its two
 * collaborators mocked, same as {@code LegalPagesControllerTest}.
 *
 * <p>Default {@code application.yml} is on the classpath (no
 * {@code @ActiveProfiles("test")}, matching the other controller slice
 * tests), so {@code android-sha256-fingerprints} binds empty here — the
 * populated-fingerprint 200 case is covered separately in
 * {@link WellKnownControllerFingerprintTest}, which overrides that one
 * property via {@code @TestPropertySource}.
 */
@WebMvcTest(WellKnownController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, IosProperties.class, AndroidProperties.class})
class WellKnownControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    FirebaseAuth firebaseAuth;

    @MockBean
    com.albunyaan.tube.repository.UserRepository userRepository;

    @Test
    @DisplayName("AASA: 200, application/json, exact appID, every derived path, Cache-Control")
    void appleAppSiteAssociation_returnsExactBody() throws Exception {
        mockMvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.applinks.apps").isArray())
                .andExpect(jsonPath("$.applinks.apps").isEmpty())
                .andExpect(jsonPath("$.applinks.details[0].appID").value("72PF8SBQR6.com.albunyaan.tube"))
                .andExpect(jsonPath("$.applinks.details[0].paths",
                        containsInAnyOrder("/watch/*", "/channel/*", "/playlist/*",
                                "/api/watch/*", "/api/channel/*", "/api/playlist/*")))
                .andExpect(header().string("Cache-Control", containsString("max-age=3600")))
                .andExpect(header().string("Cache-Control", containsString("public")));
    }

    @Test
    @DisplayName("AASA is reachable with no Authorization header through the real security chain")
    void appleAppSiteAssociation_reachableWithoutAuthentication() throws Exception {
        // No .header("Authorization", ...) anywhere in this request — that is the assertion.
        mockMvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Root alias /apple-app-site-association serves the same body, also anonymously")
    void rootAlias_servesSameBody() throws Exception {
        mockMvc.perform(get("/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.applinks.details[0].appID").value("72PF8SBQR6.com.albunyaan.tube"));
    }

    @Test
    @DisplayName("assetlinks.json: no fingerprints configured -> 404 standard error envelope")
    void assetlinks_noFingerprints_returns404Envelope() throws Exception {
        mockMvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("/.well-known/other is NOT permitted -- proves the matcher stays narrow, not a /.well-known/** blanket")
    void otherWellKnownPath_isNotPermitted() throws Exception {
        mockMvc.perform(get("/.well-known/other"))
                .andExpect(status().isForbidden());
    }
}
