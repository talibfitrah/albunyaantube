package com.albunyaan.tube.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §4.2: every guard refusal renders {timestamp, status, error, code, message, path, details}. */
class GlobalExceptionHandlerLiveCategoryTest {

    @Test
    void aRefusalRendersItsStatusCodeMessageAndDetails() {
        LiveCategoryException ex = new LiveCategoryException(HttpStatus.BAD_REQUEST, LiveCategoryException.NOT_LIVE,
                "Video yee9gj7t8nQ is not live on channel UC5vfWTTPnKdFp-8s0KbqJhw now",
                Map.of("youtubeId", "yee9gj7t8nQ", "channelId", "UC5vfWTTPnKdFp-8s0KbqJhw"));
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest("POST", "/api/admin/registry/videos"));

        ResponseEntity<Object> response = new GlobalExceptionHandler().handleLiveCategoryException(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("status", 400).containsEntry("error", "Bad Request")
                .containsEntry("code", "LIVE_LINK_NOT_LIVE").containsEntry("path", "/api/admin/registry/videos")
                .containsEntry("message", "Video yee9gj7t8nQ is not live on channel UC5vfWTTPnKdFp-8s0KbqJhw now")
                .containsKey("timestamp");
        assertThat((Map<String, Object>) body.get("details")).containsEntry("youtubeId", "yee9gj7t8nQ");
    }
}
