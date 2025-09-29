package com.albunyaan.tube.moderation;

import com.albunyaan.tube.category.Category;
import com.albunyaan.tube.category.CategoryRepository;
import com.albunyaan.tube.user.User;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class ModerationProposalService {

    private static final Pattern CATEGORY_SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{2,40}$");
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private final ModerationProposalRepository moderationProposalRepository;
    private final CategoryRepository categoryRepository;

    public ModerationProposalService(
        ModerationProposalRepository moderationProposalRepository,
        CategoryRepository categoryRepository
    ) {
        this.moderationProposalRepository = moderationProposalRepository;
        this.categoryRepository = categoryRepository;
    }

    public ModerationProposal createProposal(
        User proposer,
        ModerationProposalKind kind,
        String ytId,
        List<String> suggestedCategorySlugs,
        String notes
    ) {
        Objects.requireNonNull(proposer, "proposer must not be null");
        var normalizedYtId = normalizeYtId(ytId);
        var categories = resolveCategories(suggestedCategorySlugs);
        var sanitizedNotes = sanitizeNotes(notes);

        var proposal = new ModerationProposal(kind, normalizedYtId, categories, sanitizedNotes, proposer);
        proposal.markPending();
        var saved = moderationProposalRepository.save(proposal);
        saved.getSuggestedCategories().size();
        return saved;
    }

    @Transactional(readOnly = true)
    public ProposalPage listProposals(String cursor, int requestedLimit, ModerationProposalStatus status) {
        var limit = normalizeLimit(requestedLimit);
        var normalizedCursor = normalizeCursor(cursor);
        var pageable = PageRequest.of(0, limit + 1);

        var proposals = moderationProposalRepository.findPage(
            status,
            normalizedCursor != null ? normalizedCursor.createdAt() : null,
            normalizedCursor != null ? normalizedCursor.id() : null,
            pageable
        );

        proposals.forEach(proposal -> proposal.getSuggestedCategories().size());

        var items = new ArrayList<>(proposals);
        var hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }

        var nextCursor = hasNext ? formatCursor(items.get(items.size() - 1)) : null;
        return new ProposalPage(items, normalizedCursor != null ? normalizedCursor.raw() : null, nextCursor, hasNext, limit);
    }

    public ModerationProposal approveProposal(UUID id, User actor) {
        var proposal = getProposal(id);
        if (proposal.getStatus() != ModerationProposalStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Proposal has already been decided");
        }
        proposal.approve(actor);
        var saved = moderationProposalRepository.save(proposal);
        saved.getSuggestedCategories().size();
        return saved;
    }

    public ModerationProposal rejectProposal(UUID id, User actor, String reason) {
        var proposal = getProposal(id);
        if (proposal.getStatus() != ModerationProposalStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Proposal has already been decided");
        }
        proposal.reject(actor, sanitizeDecisionReason(reason));
        var saved = moderationProposalRepository.save(proposal);
        saved.getSuggestedCategories().size();
        return saved;
    }

    private ModerationProposal getProposal(UUID id) {
        return moderationProposalRepository
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found: " + id));
    }

    private int normalizeLimit(int requestedLimit) {
        if (requestedLimit < MIN_LIMIT || requestedLimit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be between %d and %d".formatted(MIN_LIMIT, MAX_LIMIT));
        }
        return requestedLimit;
    }

    private Cursor normalizeCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return null;
        }
        var trimmed = cursor.trim();
        var parts = trimmed.split("\\|", 2);
        if (parts.length != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }
        try {
            var createdAt = OffsetDateTime.parse(parts[0]);
            var id = UUID.fromString(parts[1]);
            return new Cursor(trimmed, createdAt, id);
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }
    }

    private String formatCursor(ModerationProposal proposal) {
        return proposal.getCreatedAt().toString() + "|" + proposal.getId();
    }

    private List<Category> resolveCategories(List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one suggested category is required");
        }
        var seen = new LinkedHashSet<String>();
        var categories = new ArrayList<Category>();
        for (var slug : slugs) {
            var normalized = normalizeCategorySlug(slug);
            if (!seen.add(normalized)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate category slug: " + normalized);
            }
            var category = categoryRepository
                .findBySlug(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category slug: " + normalized));
            categories.add(category);
        }
        return categories;
    }

    private String normalizeCategorySlug(String slug) {
        if (!StringUtils.hasText(slug)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category slug must not be blank");
        }
        var normalized = slug.trim().toLowerCase(Locale.ROOT);
        if (!CATEGORY_SLUG_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid category slug: " + slug);
        }
        return normalized;
    }

    private String normalizeYtId(String ytId) {
        if (!StringUtils.hasText(ytId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ytId is required");
        }
        var trimmed = ytId.trim();
        if (trimmed.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ytId must not exceed 64 characters");
        }
        return trimmed;
    }

    private String sanitizeNotes(String notes) {
        if (!StringUtils.hasText(notes)) {
            return null;
        }
        var trimmed = notes.trim();
        if (trimmed.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notes must not exceed 1000 characters");
        }
        return trimmed;
    }

    private String sanitizeDecisionReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return null;
        }
        var trimmed = reason.trim();
        if (trimmed.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason must not exceed 500 characters");
        }
        return trimmed;
    }

    private String resolveCategoryLabel(Category category) {
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

    public record ProposalPage(
        List<ModerationProposal> proposals,
        String cursor,
        String nextCursor,
        boolean hasNext,
        int limit
    ) {}

    private record Cursor(String raw, OffsetDateTime createdAt, UUID id) {}

    public List<CategoryTag> toCategoryTags(List<Category> categories) {
        return categories
            .stream()
            .map(category -> new CategoryTag(category.getSlug(), resolveCategoryLabel(category)))
            .toList();
    }

    public CategoryTag toCategoryTag(Category category) {
        return new CategoryTag(category.getSlug(), resolveCategoryLabel(category));
    }

    public record CategoryTag(String id, String label) {}
}
