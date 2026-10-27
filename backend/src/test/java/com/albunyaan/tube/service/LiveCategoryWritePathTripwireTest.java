package com.albunyaan.tube.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §7 U5: a tripwire over every call that can attach or publish a category membership. A new call site, or a
 * new file with one, fails this test: route the write through LiveCategoryGuard (check or checkAll) first, then
 * update the count here. Keyed by file plus count, not by line, so unrelated edits never trip it.
 * A second map, GUARDED, pins the guard calls themselves, so a removed call also fails. That also covers
 * ImportExportService.importAll, which saves deserialized models with no setter call and so is invisible to ALLOWED.
 */
class LiveCategoryWritePathTripwireTest {

    private static final List<String> PATTERNS = List.of(
            "setCategoryIds(", "addContentToCategory(", "addMultipleContentToCategory(", "setStatus(", "put(\"status\"");

    private static final List<String> GUARD_CALLS = List.of(
            "liveCategoryGuard.check(", "liveCategoryGuard.checkAll(", "liveCategoryGuard.checkMembersForLive(");

    /** file -> occurrences; every entry is guarded (§7 rows 1–17) or is not a content write (user/report/run status, HTTP bodies). */
    private static final Map<String, Integer> ALLOWED = Map.ofEntries(
            Map.entry("controller/ContentLibraryController.java", 6),     // rows 12–13
            Map.entry("controller/ImportExportController.java", 1),       // ValidationRun status
            Map.entry("controller/RegistryController.java", 27),          // rows 1–9
            Map.entry("controller/SortOrderController.java", 2),          // row 14
            Map.entry("exception/GlobalExceptionHandler.java", 18),       // response bodies
            Map.entry("repository/ChannelRepository.java", 2),            // re-asserts the doc's own status
            Map.entry("scheduler/LiveChannelPoller.java", 1),             // R12 rejoin: SortOrderService's guarded add (B13)
            Map.entry("security/FirebaseAuthFilter.java", 2),             // HTTP status
            Map.entry("security/ProfileUpdateRateLimitInterceptor.java", 1),
            Map.entry("security/SecurityConfig.java", 1),
            Map.entry("security/SubmissionRateLimitInterceptor.java", 1),
            Map.entry("service/ApprovalService.java", 25),                // row 11 (approve); reject/request-changes unpublish
            Map.entry("service/BulkSubmissionService.java", 1),           // row 10
            Map.entry("service/ContentImportService.java", 6),            // row 16 (async)
            Map.entry("service/ContentReportService.java", 3),            // report status (B23: a take-down keeps the doc's status)
            Map.entry("service/LiveCategoryService.java", 2),             // admin status map (Task 12), no doc write
            Map.entry("service/LiveChannelService.java", 1),              // a re-entering channel's state UNKNOWN (B14), no doc write
            Map.entry("service/LiveChannelsMigration.java", 1),           // Task 13 (deleted with it in Task 37)
            Map.entry("service/LivePollRules.java", 2),                   // state transition (Task 3), no doc write
            Map.entry("service/RegistrySubmissionWriter.java", 3),        // row 10
            Map.entry("service/SimpleImportService.java", 6),             // row 16 (sync)
            Map.entry("service/SortOrderService.java", 9),                // row 14, removals
            Map.entry("service/UserImportSubmissionService.java", 6),     // always List.of() / PENDING
            Map.entry("util/UserBackfillMigration.java", 2));             // user status

    /** file -> LiveCategoryGuard call count. */
    private static final Map<String, Integer> GUARDED = Map.ofEntries(
            Map.entry("controller/CategoryController.java", 1),
            Map.entry("controller/ImportExportController.java", 1),
            Map.entry("controller/RegistryController.java", 12),
            Map.entry("controller/ContentLibraryController.java", 2),
            Map.entry("service/ContentImportService.java", 1),
            Map.entry("service/BulkSubmissionService.java", 1),
            Map.entry("service/ApprovalService.java", 3),
            Map.entry("service/ContentValidationService.java", 1),        // Restore re-enters a channel as an add (B22)
            Map.entry("service/SortOrderService.java", 1),
            Map.entry("service/ImportExportService.java", 1));

    @Test
    void everyCategoryOrStatusWriteSiteIsKnown() throws IOException {
        Map<String, Integer> found = count(PATTERNS, true);
        assertThat(found)
                .as("A category/status write site changed. Guard it with LiveCategoryGuard, then update ALLOWED. Found: %s", found)
                .isEqualTo(new TreeMap<>(ALLOWED));
    }

    @Test
    void everyLiveCategoryGuardCallIsPinned() throws IOException {
        Map<String, Integer> found = count(GUARD_CALLS, false);
        assertThat(found)
                .as("A LiveCategoryGuard call site changed. A removed call reopens a write path into a LIVE category; an added one must be listed. Found: %s", found)
                .isEqualTo(new TreeMap<>(GUARDED));
    }

    private static Map<String, Integer> count(List<String> patterns, boolean skipModelAndDto) throws IOException {
        Path root = Path.of("src/main/java/com/albunyaan/tube");
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (skipModelAndDto && (rel.startsWith("model/") || rel.startsWith("dto/"))) continue;
                String text = Files.readString(file);
                int n = 0;
                for (String pattern : patterns) {
                    for (int i = text.indexOf(pattern); i >= 0; i = text.indexOf(pattern, i + pattern.length())) n++;
                }
                if (n > 0) found.put(rel, n);
            }
        }
        return found;
    }
}
