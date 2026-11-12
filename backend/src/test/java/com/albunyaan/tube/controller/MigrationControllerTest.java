package com.albunyaan.tube.controller;

import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.util.ThumbnailRepairMigration;
import com.albunyaan.tube.util.UserBackfillMigration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationControllerTest {

    @Test
    void aCompletedThumbnailRepairAnnouncesTheContentChange() throws Exception {
        ThumbnailRepairMigration repair = mock(ThumbnailRepairMigration.class);
        PublicContentCacheService caches = mock(PublicContentCacheService.class);
        when(repair.run("admin")).thenReturn(new ThumbnailRepairMigration.RunSummary(
                1, 1, 0, 0, 0, List.of(), List.of(), "start", "end"));
        MigrationController controller = new MigrationController(mock(UserBackfillMigration.class), repair, caches);
        ReflectionTestUtils.setField(controller, "thumbnailRepairEnabled", true);

        var response = controller.runThumbnailRepair(
                new FirebaseUserDetails("admin", "admin@t", "admin"), "run-thumbnail-repair");

        assertEquals(200, response.getStatusCode().value());
        verify(caches).evictPublicContentCaches();
    }
}
