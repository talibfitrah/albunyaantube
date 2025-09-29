package com.albunyaan.tube.category;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@Transactional(readOnly = true)
public class CategoryService {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{2,40}$");
    private static final Pattern LOCALE_PATTERN = Pattern.compile("^[a-z]{2,8}(?:-[a-z]{2,8})?$");
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_DESCRIPTION_LENGTH = 240;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public CategoryPage listCategories(String cursor, int requestedLimit) {
        var limit = normalizeLimit(requestedLimit);
        var pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "slug"));
        var normalizedCursor = normalizeCursor(cursor);

        var slice = normalizedCursor == null
            ? categoryRepository.findAllByOrderBySlugAsc(pageable)
            : categoryRepository.findAllBySlugGreaterThanOrderBySlugAsc(normalizedCursor, pageable);

        var items = slice.getContent();
        var nextCursor = slice.hasNext() && !items.isEmpty() ? items.get(items.size() - 1).getSlug() : null;

        return new CategoryPage(items, normalizedCursor, nextCursor, slice.hasNext(), limit);
    }

    public Category getCategory(UUID id) {
        return categoryRepository
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found: " + id));
    }

    @Transactional
    public Category createCategory(String slug, Map<String, String> name, Map<String, String> description) {
        var normalizedSlug = normalizeSlug(slug);
        ensureSlugIsAvailable(normalizedSlug, null);
        var sanitizedName = sanitizeLocalizedMap(name, true, MAX_NAME_LENGTH, "name");
        var sanitizedDescription = sanitizeLocalizedMap(description, false, MAX_DESCRIPTION_LENGTH, "description");

        var category = new Category(normalizedSlug, sanitizedName, sanitizedDescription);
        return categoryRepository.save(category);
    }

    @Transactional
    public Category updateCategory(
        UUID id,
        String slug,
        Map<String, String> name,
        Map<String, String> description
    ) {
        if (slug == null && name == null && description == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one field must be provided for update");
        }

        var category = getCategory(id);

        if (slug != null) {
            var normalizedSlug = normalizeSlug(slug);
            if (!Objects.equals(normalizedSlug, category.getSlug())) {
                ensureSlugIsAvailable(normalizedSlug, category.getId());
                category.updateSlug(normalizedSlug);
            }
        }

        if (name != null) {
            var sanitizedName = sanitizeLocalizedMap(name, true, MAX_NAME_LENGTH, "name");
            category.updateName(sanitizedName);
        }

        if (description != null) {
            var sanitizedDescription = sanitizeLocalizedMap(description, false, MAX_DESCRIPTION_LENGTH, "description");
            category.updateDescription(sanitizedDescription);
        }

        return categoryRepository.save(category);
    }

    @Transactional
    public void deleteCategory(UUID id) {
        var category = getCategory(id);
        categoryRepository.delete(category);
    }

    private int normalizeLimit(int requestedLimit) {
        if (requestedLimit < MIN_LIMIT || requestedLimit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be between %d and %d".formatted(MIN_LIMIT, MAX_LIMIT));
        }
        return requestedLimit;
    }

    private String normalizeCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return null;
        }
        return normalizeSlug(cursor);
    }

    private String normalizeSlug(String slug) {
        if (!StringUtils.hasText(slug)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug is required");
        }
        var normalized = slug.trim().toLowerCase(Locale.ROOT);
        if (!SLUG_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug must match pattern " + SLUG_PATTERN.pattern());
        }
        return normalized;
    }

    private void ensureSlugIsAvailable(String slug, UUID ignoreId) {
        categoryRepository
            .findBySlug(slug)
            .filter(existing -> ignoreId == null || !existing.getId().equals(ignoreId))
            .ifPresent(existing -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Category slug already exists: " + slug);
            });
    }

    private Map<String, String> sanitizeLocalizedMap(
        Map<String, String> input,
        boolean required,
        int maxLength,
        String field
    ) {
        if (input == null) {
            if (required) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category %s is required".formatted(field));
            }
            return Map.of();
        }

        var sanitized = new LinkedHashMap<String, String>();
        for (var entry : input.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();

            if (!StringUtils.hasText(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Locale key in %s must not be blank".formatted(field));
            }

            var normalizedKey = key.trim().toLowerCase(Locale.ROOT);
            if (!LOCALE_PATTERN.matcher(normalizedKey).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid locale code '%s' in %s".formatted(normalizedKey, field));
            }

            if (!StringUtils.hasText(value)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Localized value for '%s' must not be blank".formatted(normalizedKey));
            }

            var trimmedValue = value.trim();
            if (trimmedValue.length() > maxLength) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Localized value for '%s' exceeds %d characters".formatted(normalizedKey, maxLength));
            }

            sanitized.put(normalizedKey, trimmedValue);
        }

        if (sanitized.isEmpty() && required) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category %s must include at least one locale entry".formatted(field));
        }

        return Map.copyOf(sanitized);
    }

    public record CategoryPage(
        List<Category> categories,
        String cursor,
        String nextCursor,
        boolean hasNext,
        int limit
    ) {}
}
