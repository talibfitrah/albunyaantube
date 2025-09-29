package com.albunyaan.tube.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RegistryQueryServiceTest {

    private final RegistryQueryService service = new RegistryQueryService();

    @Test
    void listChannelsSupportsPagination() {
        var page = service.listChannels(null, 3, null);
        assertThat(page.data()).hasSize(3);
        assertThat(page.pageInfo().nextCursor()).isEqualTo("3");
        assertThat(page.pageInfo().hasNext()).isTrue();

        var nextPage = service.listChannels(page.pageInfo().nextCursor(), 3, null);
        assertThat(nextPage.data()).isNotEmpty();
        assertThat(nextPage.pageInfo().cursor()).isEqualTo("3");
    }

    @Test
    void listPlaylistsFiltersByCategory() {
        var page = service.listPlaylists(null, 20, "seerah");
        assertThat(page.data()).allSatisfy(playlist ->
            assertThat(playlist.categories()).anyMatch(tag -> tag.id().equals("seerah"))
        );
    }

    @Test
    void listVideosValidatesCursor() {
        assertThatThrownBy(() -> service.listVideos("-1", 20, null, null, null, null, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Cursor");
    }
}

