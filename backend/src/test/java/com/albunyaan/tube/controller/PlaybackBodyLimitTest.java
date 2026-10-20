package com.albunyaan.tube.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.server.PayloadTooLargeException;

import java.io.InputStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Codex P1-2: /api/playback bodies are capped before Jackson binds them, whether or not they declare a length. */
class PlaybackBodyLimitTest {

    private final PlaybackBodyLimit limit = new PlaybackBodyLimit();

    @Test
    void theLargestValidSolveFitsAndIsPassedOnWhole() throws Exception {
        byte[] largest = new ObjectMapper().writeValueAsBytes(new PlaybackSolverController.SolveRequest("0229cd64",
                Collections.nCopies(4, "n".repeat(32)), Collections.nCopies(64, "s".repeat(160))));
        assertThat(largest.length).isLessThan(PlaybackBodyLimit.MAX_BYTES);

        MockHttpInputMessage message = new MockHttpInputMessage(largest);
        message.getHeaders().setContentLength(largest.length);
        assertThat(limit.beforeBodyRead(message, null, null, null).getBody().readAllBytes()).isEqualTo(largest);
    }

    @Test
    void aDeclaredLengthOverTheCapIsRefusedUnread() {
        MockHttpInputMessage message = new MockHttpInputMessage(new InputStream() {
            @Override public int read() { throw new AssertionError("an over-cap declared length must not be read"); }
        });
        message.getHeaders().setContentLength(PlaybackBodyLimit.MAX_BYTES + 1);
        PayloadTooLargeException refused = assertThrows(PayloadTooLargeException.class,
                () -> limit.beforeBodyRead(message, null, null, null));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    /** A chunked body has no Content-Length: the read itself stops one byte past the cap. */
    @Test
    void aBodyWithNoLengthIsReadOnlyToOnePastTheCap() {
        AtomicLong read = new AtomicLong();
        MockHttpInputMessage message = new MockHttpInputMessage(new InputStream() {
            @Override public int read() { read.incrementAndGet(); return '['; }
        });
        assertThat(message.getHeaders().getContentLength()).isEqualTo(-1);
        assertThrows(PayloadTooLargeException.class, () -> limit.beforeBodyRead(message, null, null, null));
        assertThat(read.get()).isEqualTo(PlaybackBodyLimit.MAX_BYTES + 1);
    }
}
