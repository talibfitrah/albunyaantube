package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.AndroidProperties;
import com.albunyaan.tube.config.IosProperties;
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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review finding (Important #2, {@code phase5-wellknown-review.md}): the
 * original fingerprint test supplied a single value with no comma, so
 * multi-value comma-separated {@code List<String>} binding of
 * {@code app.android.sha256-fingerprints} was never actually exercised even
 * though the phase 5 report's prose claimed it was. This class closes that
 * gap: TWO comma-separated fingerprints via {@code @TestPropertySource},
 * asserting both survive the binder, in order -- Android Digital Asset
 * Links configs commonly carry two (upload key + Play App Signing key).
 */
@WebMvcTest(WellKnownController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, IosProperties.class, AndroidProperties.class})
@TestPropertySource(properties =
        "app.android.sha256-fingerprints="
                + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99,"
                + "11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00")
class WellKnownControllerMultiFingerprintTest {

    private static final String FP_1 =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";
    private static final String FP_2 =
            "11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00";

    @Autowired
    MockMvc mockMvc;

    @MockBean
    FirebaseAuth firebaseAuth;

    @MockBean
    com.albunyaan.tube.repository.UserRepository userRepository;

    @Test
    void assetlinks_withTwoCommaSeparatedFingerprints_bothSurviveInOrder() throws Exception {
        mockMvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints", hasSize(2)))
                .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[0]").value(FP_1))
                .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[1]").value(FP_2));
    }
}
