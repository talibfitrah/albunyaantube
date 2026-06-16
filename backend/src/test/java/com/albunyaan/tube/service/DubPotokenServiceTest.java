package com.albunyaan.tube.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the abuse-hardening on the public {@code /api/v1/dub-potoken} mint path: a malformed videoId
 * must be rejected BEFORE any sidecar call (so it can't pollute the cache or amplify BotGuard mints),
 * a successful mint is cached (single-flight per id), and a failed mint is negative-cached (no re-fire).
 */
@ExtendWith(MockitoExtension.class)
class DubPotokenServiceTest {

    @Mock
    private HttpClient http;

    private DubPotokenService service;

    @BeforeEach
    void setUp() {
        service = new DubPotokenService("http://localhost:4416", http);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "short",          // < 11 chars
            "abcdefghijkl",   // 12 chars
            "abc defghij",    // 11 chars but contains a space
            "abc/defghij",    // 11 chars but contains '/'
    })
    void rejectsInvalidVideoIdsWithoutCallingSidecar(String badId) throws Exception {
        assertThat(service.getPotoken(badId)).isNull();
        // The validation must short-circuit before any mint — never touch the sidecar.
        verify(http, never()).send(any(), any());
    }

    @Test
    void mintsValidIdOnceThenServesFromCache() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"poToken\":\"tok-123\"}");
        when(http.<String>send(any(), any())).thenReturn(resp);

        assertThat(service.getPotoken("DW-00ckCAPI")).isEqualTo("tok-123");
        assertThat(service.getPotoken("DW-00ckCAPI")).isEqualTo("tok-123"); // cache hit

        verify(http, times(1)).send(any(), any()); // minted exactly once
    }

    @Test
    void negativeCachesFailedMintSoItDoesNotRefire() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(500);
        when(http.<String>send(any(), any())).thenReturn(resp);

        assertThat(service.getPotoken("DW-00ckCAPI")).isNull();
        assertThat(service.getPotoken("DW-00ckCAPI")).isNull(); // negative-cached, no re-mint

        verify(http, times(1)).send(any(), any()); // failed mint attempted only once
    }
}
