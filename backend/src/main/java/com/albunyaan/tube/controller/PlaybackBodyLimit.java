package com.albunyaan.tube.controller;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.server.PayloadTooLargeException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * Codex P1-2: {@code /api/playback} request bodies are capped BEFORE Jackson binds them, so a signed-in caller cannot
 * put a huge array on the heap ahead of the per-uid limiter and the 4/64 validation. A declared length over the cap is
 * refused unread; any other body (a chunked one declares none) is read to at most one byte past the cap. Over: 413,
 * rendered by {@code GlobalExceptionHandler} like every other Spring 4xx.
 */
@ControllerAdvice(assignableTypes = PlaybackSolverController.class)
public class PlaybackBodyLimit extends RequestBodyAdviceAdapter {

    /** The largest valid solve (4 n of 32 and 64 s of 160 characters) is about 10.6 KB of compact JSON; half again on top. */
    static final int MAX_BYTES = 16 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        if (message.getHeaders().getContentLength() > MAX_BYTES) throw new PayloadTooLargeException(null);
        byte[] body = message.getBody().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) throw new PayloadTooLargeException(null);
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return new ByteArrayInputStream(body); }
            @Override public HttpHeaders getHeaders() { return message.getHeaders(); }
        };
    }
}
