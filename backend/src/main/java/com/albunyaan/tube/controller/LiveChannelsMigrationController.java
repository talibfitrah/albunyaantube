package com.albunyaan.tube.controller;

import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.LiveChannelsMigration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Spec §10: the live-channels migration, on MigrationController's confirm-header pattern. Deleted after use (Task 37). */
@RestController
@RequestMapping("/api/admin/migrations")
public class LiveChannelsMigrationController {

    private final LiveChannelsMigration migration;

    public LiveChannelsMigrationController(LiveChannelsMigration migration) {
        this.migration = migration;
    }

    @PostMapping("/live-channels")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> run(@AuthenticationPrincipal FirebaseUserDetails actor,
                                 @RequestHeader(value = "X-Confirm-Migration", required = false) String confirm,
                                 @RequestBody LiveChannelsMigration.Plan plan) throws Exception {
        if (!"run-live-channels".equals(confirm)) {
            return ResponseEntity.status(428).body(Map.of("code", "MIGRATION_CONFIRM_REQUIRED",
                    "hint", "Set X-Confirm-Migration: run-live-channels header."));
        }
        Map<String, Object> report = migration.run(plan, actor.getUid(), actor.getEmail());
        boolean blocked = !((List<?>) report.get("blockers")).isEmpty();
        return ResponseEntity.status("APPLY".equals(plan.mode()) && blocked ? 409 : 200).body(report);
    }
}
