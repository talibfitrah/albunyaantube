package com.albunyaan.tube.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.albunyaan.tube.admin.dto.CategoryPageResponse;
import com.albunyaan.tube.admin.dto.CategoryResponse;
import com.albunyaan.tube.admin.dto.CreateCategoryRequest;
import com.albunyaan.tube.admin.dto.UpdateCategoryRequest;
import com.albunyaan.tube.auth.dto.LoginRequest;
import com.albunyaan.tube.auth.dto.TokenResponse;
import com.albunyaan.tube.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class CategoryManagementIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void admin_can_crud_categories_with_cursor_pagination() throws Exception {
        var tokenBundle = authenticateAdmin();

        var createRequest = new CreateCategoryRequest(
            "stories",
            Map.of("en", "Stories"),
            Map.of("en", "Story focused lectures")
        );

        var createResult = mockMvc
            .perform(
                post("/api/v1/admins/categories")
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenBundle.accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequest))
            )
            .andExpect(status().isCreated())
            .andReturn();

        var created = objectMapper.readValue(
            createResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
            CategoryResponse.class
        );

        assertThat(created.slug()).isEqualTo("stories");
        assertThat(created.name()).containsEntry("en", "Stories");

        var listResult = mockMvc
            .perform(
                get("/api/v1/admins/categories")
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenBundle.accessToken()))
                    .param("limit", "10")
            )
            .andExpect(status().isOk())
            .andReturn();

        var page = objectMapper.readValue(
            listResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
            CategoryPageResponse.class
        );

        assertThat(page.data()).extracting(CategoryResponse::slug).contains("stories");
        assertThat(page.pageInfo().limit()).isEqualTo(10);
        assertThat(page.pageInfo().hasNext()).isFalse();

        var updateRequest = new UpdateCategoryRequest(
            "storytime",
            Map.of("en", "Story Time"),
            Map.of("en", "Updated description")
        );

        var updateResult = mockMvc
            .perform(
                put("/api/v1/admins/categories/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenBundle.accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest))
            )
            .andExpect(status().isOk())
            .andReturn();

        var updated = objectMapper.readValue(
            updateResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
            CategoryResponse.class
        );

        assertThat(updated.slug()).isEqualTo("storytime");
        assertThat(updated.name()).containsEntry("en", "Story Time");

        mockMvc
            .perform(
                delete("/api/v1/admins/categories/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenBundle.accessToken()))
            )
            .andExpect(status().isNoContent());

        mockMvc
            .perform(
                get("/api/v1/admins/categories/{id}", created.id())
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenBundle.accessToken()))
            )
            .andExpect(status().isNotFound());
    }

    private TokenResponse authenticateAdmin() throws Exception {
        var loginResult = mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new LoginRequest("admin@albunyaan.tube", "ChangeMe!123")
                        )
                    )
            )
            .andExpect(status().isOk())
            .andReturn();

        return objectMapper.readValue(
            loginResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
            TokenResponse.class
        );
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
