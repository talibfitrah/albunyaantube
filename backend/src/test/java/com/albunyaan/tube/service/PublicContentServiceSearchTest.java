package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ContentItemDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicContentServiceSearchTest {

    @Test
    void textFilter_matchesTitleCaseInsensitive() {
        PublicContentService.TextFilter filter = new PublicContentService.TextFilter("islam");

        ContentItemDto matching = ContentItemDto.video("v1", "Introduction to Islam", null,
                null, null, "Basic beliefs", null, null, null, null);
        ContentItemDto nonMatching = ContentItemDto.video("v2", "Arabic Grammar", null,
                null, null, "Language study", null, null, null, null);

        List<ContentItemDto> result = filter.apply(List.of(matching, nonMatching));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Introduction to Islam");
    }

    @Test
    void textFilter_matchesDescription() {
        PublicContentService.TextFilter filter = new PublicContentService.TextFilter("quran");

        ContentItemDto byDescription = ContentItemDto.video("v1", "Lecture 5", null,
                null, null, "Quran recitation techniques", null, null, null, null);
        ContentItemDto noMatch = ContentItemDto.video("v2", "Episode 1", null,
                null, null, "Something else entirely", null, null, null, null);

        List<ContentItemDto> result = filter.apply(List.of(byDescription, noMatch));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Lecture 5");
    }

    @Test
    void textFilter_matchesChannelName() {
        PublicContentService.TextFilter filter = new PublicContentService.TextFilter("fiqh");

        ContentItemDto channelMatch = ContentItemDto.channel("c1", "Fiqh Academy", null,
                null, "Islamic jurisprudence", null, null, null);
        ContentItemDto noMatch = ContentItemDto.channel("c2", "Other Channel", null,
                null, "Nothing relevant", null, null, null);

        List<ContentItemDto> result = filter.apply(List.of(channelMatch, noMatch));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Fiqh Academy");
    }

    @Test
    void textFilter_nullQueryReturnsAll() {
        PublicContentService.TextFilter filter = new PublicContentService.TextFilter(null);

        ContentItemDto item1 = ContentItemDto.video("v1", "Title A", null, null, null, null, null, null, null, null);
        ContentItemDto item2 = ContentItemDto.video("v2", "Title B", null, null, null, null, null, null, null, null);

        List<ContentItemDto> result = filter.apply(List.of(item1, item2));

        assertThat(result).hasSize(2);
        assertThat(filter.isActive()).isFalse();
    }

    @Test
    void textFilter_emptyQueryReturnsAll() {
        PublicContentService.TextFilter filter = new PublicContentService.TextFilter("  ");

        ContentItemDto item = ContentItemDto.video("v1", "Title", null, null, null, null, null, null, null, null);

        List<ContentItemDto> result = filter.apply(List.of(item));

        assertThat(result).hasSize(1);
        assertThat(filter.isActive()).isFalse();
    }
}
