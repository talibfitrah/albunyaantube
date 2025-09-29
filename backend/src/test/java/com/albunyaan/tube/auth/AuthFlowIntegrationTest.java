package com.albunyaan.tube.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.albunyaan.tube.admin.dto.CreateModeratorRequest;
import com.albunyaan.tube.auth.dto.LoginRequest;
import com.albunyaan.tube.auth.dto.LogoutRequest;
import com.albunyaan.tube.auth.dto.RefreshRequest;
import com.albunyaan.tube.auth.dto.TokenResponse;
import com.albunyaan.tube.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class AuthFlowIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void login_refresh_logout_flow_revokes_tokens() throws Exception {
        var loginResponse = mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new LoginRequest("admin@albunyaan.tube", "ChangeMe!123")))
            )
            .andExpect(status().isOk())
            .andReturn();

        var loginBody = loginResponse.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var initialTokens = objectMapper.readValue(loginBody, TokenResponse.class);

        assertThat(initialTokens.accessToken()).isNotBlank();
        assertThat(initialTokens.refreshToken()).isNotBlank();

        mockMvc
            .perform(
                post("/api/v1/admins/moderators")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + initialTokens.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new CreateModeratorRequest("moderator@albunyaan.tube", "StrongPass!9", "Moderator One")
                        )
                    )
            )
            .andExpect(status().isCreated());

        var refreshResponse = mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new RefreshRequest(initialTokens.refreshToken())))
            )
            .andExpect(status().isOk())
            .andReturn();

        var refreshedTokens = objectMapper.readValue(
            refreshResponse.getResponse().getContentAsString(StandardCharsets.UTF_8),
            TokenResponse.class
        );

        assertThat(refreshedTokens.accessToken()).isNotEqualTo(initialTokens.accessToken());
        assertThat(refreshedTokens.refreshToken()).isNotEqualTo(initialTokens.refreshToken());

        mockMvc
            .perform(
                get("/api/v1/admins/moderators")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshedTokens.accessToken())
            )
            .andExpect(status().isOk());

        mockMvc
            .perform(
                post("/api/v1/auth/logout")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshedTokens.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new LogoutRequest(refreshedTokens.refreshToken())))
            )
            .andExpect(status().isNoContent());

        mockMvc
            .perform(
                get("/api/v1/admins/moderators")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshedTokens.accessToken())
            )
            .andExpect(status().isUnauthorized());

        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new RefreshRequest(refreshedTokens.refreshToken())))
            )
            .andExpect(status().isUnauthorized());
    }
}
