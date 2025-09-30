package com.albunyaan.tube.registry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.albunyaan.tube.category.CategoryLocalizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RegistryQueryServiceTest {

    private RegistryQueryService service;

    @BeforeEach
    void setUp() {
        service = new RegistryQueryService(new CategoryLocalizationService());
    }

    @Test
    void listChannelsRejectsInvalidLimit() {
        assertThatThrownBy(() -> service.listChannels(null, 0, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Limit must be between");
    }

    @Test
    void listVideosRejectsInvalidCursor() {
        assertThatThrownBy(() -> service.listVideos("invalid", 10, null, null, null, null, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Invalid cursor");
    }

    @Test
    void listVideosRejectsUnsupportedFilters() {
        assertThatThrownBy(() -> service.listVideos(null, 10, null, null, "SHORT", null, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("metadata support");
    }
}
