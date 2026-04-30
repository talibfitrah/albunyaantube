package com.albunyaan.tube.controller;

import com.albunyaan.tube.exception.GlobalExceptionHandler;
import com.albunyaan.tube.model.ContentReport;
import com.albunyaan.tube.model.ReportReason;
import com.albunyaan.tube.model.ReportTargetType;
import com.albunyaan.tube.service.ContentReportService;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContentReportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ContentReportControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ContentReportService reportService;

    @MockBean
    FirebaseAuth firebaseAuth;

    // Valid request body JSON
    private static final String VALID_BODY = """
            {
              "targetType": "VIDEO",
              "targetId": "abc123",
              "reasons": ["MUSIC"]
            }
            """;

    @Test
    void submitReport_validBody_returns201() throws Exception {
        ContentReport saved = new ContentReport();
        saved.setId("report-1");
        when(reportService.submitReport(any(), anyString(), anyList(), isNull(), anyString()))
                .thenReturn(saved);

        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void submitReport_emptyReasons_returns400() throws Exception {
        String body = """
                {
                  "targetType": "VIDEO",
                  "targetId": "abc123",
                  "reasons": []
                }
                """;

        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitReport_missingTargetId_returns400() throws Exception {
        String body = """
                {
                  "targetType": "VIDEO",
                  "reasons": ["MUSIC"]
                }
                """;

        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitReport_otherDescriptionTooLong_returns400() throws Exception {
        String longDesc = "x".repeat(501);
        String body = """
                {
                  "targetType": "VIDEO",
                  "targetId": "abc123",
                  "reasons": ["OTHER"],
                  "otherDescription": "%s"
                }
                """.formatted(longDesc);

        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitReport_rateLimitExceeded_returns429() throws Exception {
        when(reportService.submitReport(any(), anyString(), anyList(), any(), anyString()))
                .thenThrow(new ContentReportService.RateLimitExceededException("Too many reports"));

        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").exists());
    }
}
