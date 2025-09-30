package com.albunyaan.tube.category;

import com.albunyaan.tube.registry.dto.CategoryTagDto;
import java.util.Locale;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CategoryLocalizationService {

    public CategoryTagDto toTagDto(Category category) {
        return new CategoryTagDto(category.getSlug(), resolveLabel(category));
    }

    public String resolveLabel(Category category) {
        var names = category.getName();
        if (names.isEmpty()) {
            return category.getSlug();
        }
        var locale = LocaleContextHolder.getLocale();
        if (locale != null) {
            var fullTag = locale.toLanguageTag().toLowerCase(Locale.ROOT);
            if (names.containsKey(fullTag)) {
                return names.get(fullTag);
            }
            var language = locale.getLanguage().toLowerCase(Locale.ROOT);
            if (StringUtils.hasText(language) && names.containsKey(language)) {
                return names.get(language);
            }
        }
        if (names.containsKey("en")) {
            return names.get("en");
        }
        return names.values().iterator().next();
    }
}
