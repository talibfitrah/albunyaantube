package com.albunyaan.tube.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.albunyaan.tube.auth.dto.LoginRequest;
import com.albunyaan.tube.auth.dto.TokenResponse;
import com.albunyaan.tube.moderation.ModerationProposalKind;
import com.albunyaan.tube.moderation.ModerationProposalStatus;
import com.albunyaan.tube.moderation.dto.ModerationProposalCreateRequest;
import com.albunyaan.tube.moderation.dto.ModerationProposalPageResponse;
import com.albunyaan.tube.moderation.dto.ModerationProposalRejectRequest;
import com.albunyaan.tube.moderation.dto.ModerationProposalResponse;
import com.albunyaan.tube.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ModerationProposalIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void admin_can_create_list_and_decide_on_moderation_proposals() throws Exception {
        var tokenBundle = authenticateAdmin();

        var createChannelRequest = new ModerationProposalCreateRequest(
            ModerationProposalKind.CHANNEL,
            "UC12345",
            List.of("quran", "lectures"),
            "Allow-list this channel"
        );

        var createdChannel = performCreateProposal(tokenBundle, createChannelRequest);

        assertThat(createdChannel.status()).isEqualTo(ModerationProposalStatus.PENDING);
        assertThat(createdChannel.suggestedCategories()).extracting(tag -> tag.id()).containsExactly("quran", "lectures");

        var createVideoRequest = new ModerationProposalCreateRequest(
            ModerationProposalKind.VIDEO,
            "dQw4w9WgXcQ",
            List.of("kids"),
            null
        );

        var createdVideo = performCreateProposal(tokenBundle, createVideoRequest);
        assertThat(createdVideo.status()).isEqualTo(ModerationProposalStatus.PENDING);

        var firstPage = listProposals(tokenBundle, 1, null, null);
        assertThat(firstPage.pageInfo().limit()).isEqualTo(1);
        assertThat(firstPage.pageInfo().hasNext()).isTrue();
        assertThat(firstPage.pageInfo().cursor()).isNull();
        assertThat(firstPage.data()).hasSize(1);

        var firstPageProposal = firstPage.data().get(0);

        var secondPage = listProposals(tokenBundle, 1, firstPage.pageInfo().nextCursor(), null);
        assertThat(secondPage.pageInfo().cursor()).isEqualTo(firstPage.pageInfo().nextCursor());
        assertThat(secondPage.pageInfo().hasNext()).isFalse();
        assertThat(secondPage.data()).hasSize(1);

        var secondPageProposal = secondPage.data().get(0);
        assertThat(secondPageProposal.id()).isNotEqualTo(firstPageProposal.id());

        var approved = approveProposal(tokenBundle, firstPageProposal.id());
        assertThat(approved.status()).isEqualTo(ModerationProposalStatus.APPROVED);
        assertThat(approved.decidedBy()).isNotNull();
        assertThat(approved.decidedAt()).isNotNull();

        var rejected = rejectProposal(tokenBundle, secondPageProposal.id(), "Insufficient context");
        assertThat(rejected.status()).isEqualTo(ModerationProposalStatus.REJECTED);
        assertThat(rejected.decisionReason()).isEqualTo("Insufficient context");

        var pendingPage = listProposals(tokenBundle, 10, null, ModerationProposalStatus.PENDING);
        assertThat(pendingPage.data()).isEmpty();

        var approvedPage = listProposals(tokenBundle, 10, null, ModerationProposalStatus.APPROVED);
        assertThat(approvedPage.data()).extracting(ModerationProposalResponse::id).containsExactly(approved.id());

        var rejectedPage = listProposals(tokenBundle, 10, null, ModerationProposalStatus.REJECTED);
        assertThat(rejectedPage.data()).extracting(ModerationProposalResponse::id).containsExactly(rejected.id());
        assertThat(rejectedPage.data()).extracting(ModerationProposalResponse::decisionReason).contains("Insufficient context");
    }

    private ModerationProposalResponse performCreateProposal(
        TokenResponse tokenBundle,
        ModerationProposalCreateRequest request
    ) throws Exception {
        var result = mockMvc
            .perform(
                post("/api/v1/moderation/proposals")
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenBundle.accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isCreated())
            .andReturn();

        return objectMapper.readValue(
            result.getResponse().getContentAsString(StandardCharsets.UTF_8),
            ModerationProposalResponse.class
        );
    }

    private ModerationProposalPageResponse listProposals(
        TokenResponse tokenBundle,
        int limit,
        String cursor,
        ModerationProposalStatus status
    ) throws Exception {
        var requestBuilder = get("/api/v1/moderation/proposals")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenBundle.accessToken()))
            .param("limit", Integer.toString(limit));

        if (cursor != null) {
            requestBuilder = requestBuilder.param("cursor", cursor);
        }
        if (status != null) {
            requestBuilder = requestBuilder.param("status", status.name());
        }

        var result = mockMvc.perform(requestBuilder).andExpect(status().isOk()).andReturn();

        return objectMapper.readValue(
            result.getResponse().getContentAsString(StandardCharsets.UTF_8),
            ModerationProposalPageResponse.class
        );
    }

    private ModerationProposalResponse approveProposal(TokenResponse tokenBundle, UUID id) throws Exception {
        var result = mockMvc
            .perform(
                post("/api/v1/moderation/proposals/{id}/approve", id)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenBundle.accessToken()))
            )
            .andExpect(status().isOk())
            .andReturn();

        return objectMapper.readValue(
            result.getResponse().getContentAsString(StandardCharsets.UTF_8),
            ModerationProposalResponse.class
        );
    }

    private ModerationProposalResponse rejectProposal(TokenResponse tokenBundle, UUID id, String reason) throws Exception {
        var result = mockMvc
            .perform(
                post("/api/v1/moderation/proposals/{id}/reject", id)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenBundle.accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new ModerationProposalRejectRequest(reason)))
            )
            .andExpect(status().isOk())
            .andReturn();

        return objectMapper.readValue(
            result.getResponse().getContentAsString(StandardCharsets.UTF_8),
            ModerationProposalResponse.class
        );
    }

    private TokenResponse authenticateAdmin() throws Exception {
        var result = mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new LoginRequest("admin@albunyaan.tube", "ChangeMe!123")))
            )
            .andExpect(status().isOk())
            .andReturn();

        return objectMapper.readValue(
            result.getResponse().getContentAsString(StandardCharsets.UTF_8),
            TokenResponse.class
        );
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
