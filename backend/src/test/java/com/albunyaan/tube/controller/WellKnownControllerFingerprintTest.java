package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.UniversalLinksProperties;
import com.albunyaan.tube.exception.GlobalExceptionHandler;
import com.albunyaan.tube.security.SecurityConfig;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Same slice as {@link WellKnownControllerTest}, split into its own class so
 * {@code @TestPropertySource} can supply one Android signing fingerprint —
 * covering the 200 branch of {@code GET /.well-known/assetlinks.json}
 * (requirement 3d's populated case; the empty/404 case lives in the other
 * class against the real, unmodified {@code application.yml} default).
 */
@WebMvcTest(WellKnownController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, UniversalLinksProperties.class})
@TestPropertySource(properties =
        "app.universal-links.android-sha256-fingerprints="
                + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99")
class WellKnownControllerFingerprintTest {

    private static final String FINGERPRINT =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    @Autowired
    MockMvc mockMvc;

    @MockBean
    FirebaseAuth firebaseAuth;

    @MockBean
    com.albunyaan.tube.repository.UserRepository userRepository;

    @Test
    void assetlinks_withFingerprintConfigured_returns200WithRelation() throws Exception {
        mockMvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$[0].relation[0]").value("delegate_permission/common.handle_all_urls"))
                .andExpect(jsonPath("$[0].target.namespace").value("android_app"))
                .andExpect(jsonPath("$[0].target.package_name").value("com.albunyaan.tube"))
                .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[0]").value(FINGERPRINT));
    }
}
