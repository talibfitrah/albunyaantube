package com.albunyaan.tube.controller;

import com.albunyaan.tube.search.InnerCrawler;
import com.albunyaan.tube.search.InnerState;
import com.albunyaan.tube.search.InnerStore;
import com.albunyaan.tube.security.FirebaseUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Spec R25 / §9: the admin surface's answers. Roles are proven over HTTP in InnerSearchIT. */
class InnerSearchAdminControllerTest {

    private final InnerCrawler crawler = mock(InnerCrawler.class);
    private final InnerStore store = mock(InnerStore.class);
    private final InnerSearchAdminController controller = new InnerSearchAdminController(crawler, store);
    private final FirebaseUserDetails admin = new FirebaseUserDetails("uid-admin", "admin@test", "admin");

    @Test
    void getAnswersTheCrawlersStatus() {
        when(crawler.status()).thenReturn(Map.of("mode", InnerState.Mode.OFF));
        assertEquals(Map.of("mode", InnerState.Mode.OFF), controller.status());
    }

    @Test
    void aPutAppliesAndAnswersTheNewStatus() {
        when(store.enabled()).thenReturn(true);
        when(crawler.status()).thenReturn(Map.of("mode", InnerState.Mode.FIRST_PASS));
        InnerCrawler.Update update = new InnerCrawler.Update(InnerState.Mode.FIRST_PASS, null, null, null, null, null);
        ResponseEntity<Map<String, Object>> answer = controller.update(update, admin);
        assertEquals(HttpStatus.OK, answer.getStatusCode());
        verify(crawler).update(update);
    }

    @Test
    void aBadCapIsABadRequest() {
        when(store.enabled()).thenReturn(true);
        doThrow(new IllegalArgumentException("capPerSource must be 1..10000")).when(crawler).update(any());
        ResponseEntity<Map<String, Object>> answer = controller.update(new InnerCrawler.Update(null, null, null, null, 0, null), admin);
        assertEquals(HttpStatus.BAD_REQUEST, answer.getStatusCode());
        assertEquals("capPerSource must be 1..10000", answer.getBody().get("error"));
    }

    @Test
    void aPutWhileTheFeatureIsOffIsAConflict() {
        when(store.enabled()).thenReturn(false);
        ResponseEntity<Map<String, Object>> answer = controller.update(new InnerCrawler.Update(InnerState.Mode.FULL, null, null, null, null, null), admin);
        assertEquals(HttpStatus.CONFLICT, answer.getStatusCode());
        verify(crawler, never()).update(any());
    }
}
