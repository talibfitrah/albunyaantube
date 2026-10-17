package com.albunyaan.tube.controller;

import com.albunyaan.tube.search.InnerCrawler;
import com.albunyaan.tube.search.InnerStore;
import com.albunyaan.tube.security.FirebaseUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Inner search's switches and status (spec R25, §9), following the circuit-breaker endpoints' precedent: no dashboard
 * card. ADMIN and MODERATOR read; only ADMIN writes. The token-free kill switch is the STOP file in search.inner.dir.
 */
@RestController
@RequestMapping("/api/admin/inner-search")
public class InnerSearchAdminController {
    private static final Logger log = LoggerFactory.getLogger(InnerSearchAdminController.class);

    private final InnerCrawler crawler;
    private final InnerStore store;

    public InnerSearchAdminController(InnerCrawler crawler, InnerStore store) {
        this.crawler = crawler;
        this.store = store;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    public Map<String, Object> status() {
        return crawler.status();
    }

    /** Any subset of {mode, serveInner, streamGate, backfillHeld, capPerSource, resetPace}. */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> update(@RequestBody InnerCrawler.Update update,
                                                      @AuthenticationPrincipal FirebaseUserDetails user) {
        if (!store.enabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "search.inner.dir is not set: inner search is off"));
        }
        try {
            crawler.update(update);
        } catch (IllegalArgumentException e) { // a bad cap, or FULL before any first pass (R25)
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        log.info("inner-search settings changed by uid={}", user == null ? "?" : user.getUid());
        return ResponseEntity.ok(crawler.status());
    }
}
