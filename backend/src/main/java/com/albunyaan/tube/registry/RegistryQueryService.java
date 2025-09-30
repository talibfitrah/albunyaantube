package com.albunyaan.tube.registry;

import com.albunyaan.tube.category.Category;
import com.albunyaan.tube.category.CategoryLocalizationService;
import com.albunyaan.tube.common.AuditableEntity;
import com.albunyaan.tube.registry.dto.CategoryTagDto;
import com.albunyaan.tube.registry.dto.ChannelSummaryDto;
import com.albunyaan.tube.registry.dto.CursorPage;
import com.albunyaan.tube.registry.dto.CursorPageInfo;
import com.albunyaan.tube.registry.dto.PlaylistSummaryDto;
import com.albunyaan.tube.registry.dto.VideoSummaryDto;
import com.albunyaan.tube.registry.dto.admin.AdminSearchChannelResultDto;
import com.albunyaan.tube.registry.dto.admin.AdminSearchPlaylistResultDto;
import com.albunyaan.tube.registry.dto.admin.AdminSearchResponseDto;
import com.albunyaan.tube.registry.dto.admin.AdminSearchVideoResultDto;
import com.albunyaan.tube.registry.dto.admin.ChannelSummarySnapshotDto;
import com.albunyaan.tube.registry.dto.admin.ExcludedItemCountsDto;
import com.albunyaan.tube.registry.dto.admin.IncludeState;
import com.albunyaan.tube.registry.model.ChannelRegistry;
import com.albunyaan.tube.registry.model.PlaylistRegistry;
import com.albunyaan.tube.registry.model.VideoRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.SetJoin;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class RegistryQueryService {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;
    private static final OffsetDateTime DEFAULT_PUBLISHED_AT = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);

    @PersistenceContext
    private EntityManager entityManager;

    private final CategoryLocalizationService categoryLocalizationService;

    public RegistryQueryService(CategoryLocalizationService categoryLocalizationService) {
        this.categoryLocalizationService = categoryLocalizationService;
    }

    public CursorPage<ChannelSummaryDto> listChannels(String cursor, int requestedLimit, String categorySlug) {
        var limit = normalizeLimit(requestedLimit);
        var decodedCursor = decodeCursor(cursor);

        var cb = entityManager.getCriteriaBuilder();
        var cq = cb.createQuery(ChannelRegistry.class);
        var root = cq.from(ChannelRegistry.class);
        root.fetch("categories", JoinType.LEFT);
        root.fetch("excludedVideoIds", JoinType.LEFT);
        root.fetch("excludedPlaylistIds", JoinType.LEFT);
        cq.select(root).distinct(true);

        var predicates = new ArrayList<Predicate>();

        if (StringUtils.hasText(categorySlug)) {
            var normalized = normalizeCategorySlug(categorySlug);
            SetJoin<ChannelRegistry, Category> join = root.joinSet("categories", JoinType.INNER);
            predicates.add(cb.equal(cb.lower(join.get("slug")), normalized));
        }

        addCursorPredicate(decodedCursor, cb, root, predicates);

        if (!predicates.isEmpty()) {
            cq.where(predicates.toArray(Predicate[]::new));
        }

        cq.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));

        var query = entityManager.createQuery(cq);
        query.setMaxResults(limit + 1);
        var slice = executeSlice(query, limit, entity -> Cursor.from(entity.getCreatedAt(), entity.getId()));

        var data = slice.items()
            .stream()
            .map(this::toChannelSummary)
            .toList();

        var pageInfo = buildPageInfo(cursor, slice.nextCursor(), slice.hasNext(), limit);
        return new CursorPage<>(data, pageInfo);
    }

    public CursorPage<PlaylistSummaryDto> listPlaylists(String cursor, int requestedLimit, String categorySlug) {
        var limit = normalizeLimit(requestedLimit);
        var decodedCursor = decodeCursor(cursor);

        var cb = entityManager.getCriteriaBuilder();
        var cq = cb.createQuery(PlaylistRegistry.class);
        var root = cq.from(PlaylistRegistry.class);
        root.fetch("channel", JoinType.INNER);
        root.fetch("categories", JoinType.LEFT);
        root.fetch("excludedVideoIds", JoinType.LEFT);
        cq.select(root).distinct(true);

        var predicates = new ArrayList<Predicate>();

        if (StringUtils.hasText(categorySlug)) {
            var normalized = normalizeCategorySlug(categorySlug);
            SetJoin<PlaylistRegistry, Category> join = root.joinSet("categories", JoinType.INNER);
            predicates.add(cb.equal(cb.lower(join.get("slug")), normalized));
        }

        addCursorPredicate(decodedCursor, cb, root, predicates);

        if (!predicates.isEmpty()) {
            cq.where(predicates.toArray(Predicate[]::new));
        }

        cq.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));

        var query = entityManager.createQuery(cq);
        query.setMaxResults(limit + 1);
        var slice = executeSlice(query, limit, entity -> Cursor.from(entity.getCreatedAt(), entity.getId()));

        var data = slice.items()
            .stream()
            .map(this::toPlaylistSummary)
            .toList();

        var pageInfo = buildPageInfo(cursor, slice.nextCursor(), slice.hasNext(), limit);
        return new CursorPage<>(data, pageInfo);
    }

    public CursorPage<VideoSummaryDto> listVideos(
        String cursor,
        int requestedLimit,
        String categorySlug,
        String query,
        String length,
        String date,
        String sort
    ) {
        rejectUnsupportedFilters(length, date, sort);

        var limit = normalizeLimit(requestedLimit);
        var decodedCursor = decodeCursor(cursor);

        var cb = entityManager.getCriteriaBuilder();
        var cq = cb.createQuery(VideoRegistry.class);
        var root = cq.from(VideoRegistry.class);
        root.fetch("channel", JoinType.INNER);
        root.fetch("playlist", JoinType.LEFT);
        root.fetch("categories", JoinType.LEFT);
        cq.select(root).distinct(true);

        var predicates = new ArrayList<Predicate>();

        if (StringUtils.hasText(categorySlug)) {
            var normalized = normalizeCategorySlug(categorySlug);
            SetJoin<VideoRegistry, Category> join = root.joinSet("categories", JoinType.INNER);
            predicates.add(cb.equal(cb.lower(join.get("slug")), normalized));
        }

        if (StringUtils.hasText(query)) {
            var normalized = query.trim().toLowerCase(Locale.ROOT);
            predicates.add(cb.like(cb.lower(root.get("ytVideoId")), "%" + normalized + "%"));
        }

        addCursorPredicate(decodedCursor, cb, root, predicates);

        if (!predicates.isEmpty()) {
            cq.where(predicates.toArray(Predicate[]::new));
        }

        cq.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));

        var jpaQuery = entityManager.createQuery(cq);
        jpaQuery.setMaxResults(limit + 1);
        var slice = executeSlice(jpaQuery, limit, entity -> Cursor.from(entity.getCreatedAt(), entity.getId()));

        var data = slice.items()
            .stream()
            .map(this::toVideoSummary)
            .toList();

        var pageInfo = buildPageInfo(cursor, slice.nextCursor(), slice.hasNext(), limit);
        return new CursorPage<>(data, pageInfo);
    }

    public AdminSearchResponseDto searchAdminRegistry(String query, String categorySlug, int requestedLimit) {
        var limit = normalizeLimit(requestedLimit);
        var trimmedQuery = StringUtils.hasText(query) ? query.trim() : "";

        var channels = findChannels(categorySlug, trimmedQuery, limit);
        var playlists = findPlaylists(categorySlug, trimmedQuery, limit);
        var videos = findVideos(categorySlug, trimmedQuery, limit);

        var channelResults = channels.stream().map(this::toAdminChannelResult).toList();
        var playlistResults = playlists.stream().map(this::toAdminPlaylistResult).toList();
        var videoResults = videos.stream().map(this::toAdminVideoResult).toList();

        return new AdminSearchResponseDto(trimmedQuery, channelResults, playlistResults, videoResults);
    }

    private List<ChannelRegistry> findChannels(String categorySlug, String query, int limit) {
        var cb = entityManager.getCriteriaBuilder();
        var cq = cb.createQuery(ChannelRegistry.class);
        var root = cq.from(ChannelRegistry.class);
        root.fetch("categories", JoinType.LEFT);
        root.fetch("excludedVideoIds", JoinType.LEFT);
        root.fetch("excludedPlaylistIds", JoinType.LEFT);
        cq.select(root).distinct(true);

        var predicates = new ArrayList<Predicate>();

        if (StringUtils.hasText(categorySlug)) {
            var normalized = normalizeCategorySlug(categorySlug);
            SetJoin<ChannelRegistry, Category> join = root.joinSet("categories", JoinType.INNER);
            predicates.add(cb.equal(cb.lower(join.get("slug")), normalized));
        }

        if (StringUtils.hasText(query)) {
            var normalized = query.trim().toLowerCase(Locale.ROOT);
            predicates.add(cb.like(cb.lower(root.get("ytChannelId")), "%" + normalized + "%"));
        }

        if (!predicates.isEmpty()) {
            cq.where(predicates.toArray(Predicate[]::new));
        }

        cq.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));

        var typedQuery = entityManager.createQuery(cq);
        typedQuery.setMaxResults(limit);
        return typedQuery.getResultList();
    }

    private List<PlaylistRegistry> findPlaylists(String categorySlug, String query, int limit) {
        var cb = entityManager.getCriteriaBuilder();
        var cq = cb.createQuery(PlaylistRegistry.class);
        var root = cq.from(PlaylistRegistry.class);
        root.fetch("channel", JoinType.INNER);
        root.fetch("categories", JoinType.LEFT);
        root.fetch("excludedVideoIds", JoinType.LEFT);
        cq.select(root).distinct(true);

        var predicates = new ArrayList<Predicate>();

        if (StringUtils.hasText(categorySlug)) {
            var normalized = normalizeCategorySlug(categorySlug);
            SetJoin<PlaylistRegistry, Category> join = root.joinSet("categories", JoinType.INNER);
            predicates.add(cb.equal(cb.lower(join.get("slug")), normalized));
        }

        if (StringUtils.hasText(query)) {
            var normalized = query.trim().toLowerCase(Locale.ROOT);
            predicates.add(cb.like(cb.lower(root.get("ytPlaylistId")), "%" + normalized + "%"));
        }

        if (!predicates.isEmpty()) {
            cq.where(predicates.toArray(Predicate[]::new));
        }

        cq.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));

        var typedQuery = entityManager.createQuery(cq);
        typedQuery.setMaxResults(limit);
        return typedQuery.getResultList();
    }

    private List<VideoRegistry> findVideos(String categorySlug, String query, int limit) {
        var cb = entityManager.getCriteriaBuilder();
        var cq = cb.createQuery(VideoRegistry.class);
        var root = cq.from(VideoRegistry.class);
        root.fetch("channel", JoinType.INNER);
        root.fetch("playlist", JoinType.LEFT);
        root.fetch("categories", JoinType.LEFT);
        cq.select(root).distinct(true);

        var predicates = new ArrayList<Predicate>();

        if (StringUtils.hasText(categorySlug)) {
            var normalized = normalizeCategorySlug(categorySlug);
            SetJoin<VideoRegistry, Category> join = root.joinSet("categories", JoinType.INNER);
            predicates.add(cb.equal(cb.lower(join.get("slug")), normalized));
        }

        if (StringUtils.hasText(query)) {
            var normalized = query.trim().toLowerCase(Locale.ROOT);
            predicates.add(cb.like(cb.lower(root.get("ytVideoId")), "%" + normalized + "%"));
        }

        if (!predicates.isEmpty()) {
            cq.where(predicates.toArray(Predicate[]::new));
        }

        cq.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));

        var typedQuery = entityManager.createQuery(cq);
        typedQuery.setMaxResults(limit);
        return typedQuery.getResultList();
    }

    private AdminSearchChannelResultDto toAdminChannelResult(ChannelRegistry entity) {
        var categories = entity
            .getCategories()
            .stream()
            .sorted(Comparator.comparing(categoryLocalizationService::resolveLabel))
            .map(categoryLocalizationService::toTagDto)
            .toList();
        var excludedVideoIds = entity
            .getExcludedVideoIds()
            .stream()
            .sorted()
            .toList();
        var excludedPlaylistIds = entity
            .getExcludedPlaylistIds()
            .stream()
            .sorted()
            .toList();
        var excludedCounts = new ExcludedItemCountsDto(excludedVideoIds.size(), excludedPlaylistIds.size());
        return new AdminSearchChannelResultDto(
            entity.getId(),
            entity.getYtChannelId(),
            null,
            null,
            0L,
            categories,
            IncludeState.INCLUDED,
            excludedCounts,
            excludedPlaylistIds,
            excludedVideoIds,
            true
        );
    }

    private ChannelSummarySnapshotDto toChannelSnapshot(ChannelRegistry entity) {
        var categories = entity
            .getCategories()
            .stream()
            .sorted(Comparator.comparing(categoryLocalizationService::resolveLabel))
            .map(categoryLocalizationService::toTagDto)
            .toList();
        return new ChannelSummarySnapshotDto(entity.getId(), entity.getYtChannelId(), null, null, 0L, categories);
    }

    private AdminSearchPlaylistResultDto toAdminPlaylistResult(PlaylistRegistry entity) {
        var categories = entity
            .getCategories()
            .stream()
            .sorted(Comparator.comparing(categoryLocalizationService::resolveLabel))
            .map(categoryLocalizationService::toTagDto)
            .toList();

        var channel = entity.getChannel();
        var excludedVideoIds = entity
            .getExcludedVideoIds()
            .stream()
            .sorted()
            .toList();

        return new AdminSearchPlaylistResultDto(
            entity.getId(),
            entity.getYtPlaylistId(),
            null,
            null,
            0,
            toChannelSnapshot(channel),
            categories,
            true,
            IncludeState.INCLUDED,
            channel.getId(),
            excludedVideoIds.size(),
            excludedVideoIds,
            true
        );
    }

    private AdminSearchVideoResultDto toAdminVideoResult(VideoRegistry entity) {
        var categories = entity
            .getCategories()
            .stream()
            .sorted(Comparator.comparing(categoryLocalizationService::resolveLabel))
            .map(categoryLocalizationService::toTagDto)
            .toList();

        var channel = entity.getChannel();
        var playlist = entity.getPlaylist();
        var ytId = entity.getYtVideoId();
        var channelExcluded = channel.getExcludedVideoIds().contains(ytId);
        var playlistExcluded = playlist != null && playlist.getExcludedVideoIds().contains(ytId);
        var includeState = (channelExcluded || playlistExcluded) ? IncludeState.EXCLUDED : IncludeState.INCLUDED;

        var parentPlaylists = playlist != null ? List.of(playlist.getYtPlaylistId()) : List.<String>of();

        return new AdminSearchVideoResultDto(
            entity.getId(),
            ytId,
            null,
            null,
            0,
            DEFAULT_PUBLISHED_AT,
            0L,
            toChannelSnapshot(channel),
            categories,
            Boolean.FALSE,
            Boolean.FALSE,
            includeState,
            channel.getId(),
            parentPlaylists
        );
    }

    private ChannelSummaryDto toChannelSummary(ChannelRegistry entity) {
        var categories = entity
            .getCategories()
            .stream()
            .sorted(Comparator.comparing(categoryLocalizationService::resolveLabel))
            .map(categoryLocalizationService::toTagDto)
            .toList();

        var excludedPlaylists = entity
            .getExcludedPlaylistIds()
            .stream()
            .sorted()
            .toList();

        var excludedVideos = entity
            .getExcludedVideoIds()
            .stream()
            .sorted()
            .toList();

        return new ChannelSummaryDto(entity.getId(), entity.getYtChannelId(), categories, excludedPlaylists, excludedVideos);
    }

    private PlaylistSummaryDto toPlaylistSummary(PlaylistRegistry entity) {
        var categories = entity
            .getCategories()
            .stream()
            .sorted(Comparator.comparing(categoryLocalizationService::resolveLabel))
            .map(categoryLocalizationService::toTagDto)
            .toList();

        var excludedVideos = entity
            .getExcludedVideoIds()
            .stream()
            .sorted()
            .toList();

        var channel = entity.getChannel();

        return new PlaylistSummaryDto(
            entity.getId(),
            entity.getYtPlaylistId(),
            channel.getId(),
            channel.getYtChannelId(),
            categories,
            excludedVideos
        );
    }

    private VideoSummaryDto toVideoSummary(VideoRegistry entity) {
        var categories = entity
            .getCategories()
            .stream()
            .sorted(Comparator.comparing(categoryLocalizationService::resolveLabel))
            .map(categoryLocalizationService::toTagDto)
            .toList();

        var channel = entity.getChannel();
        var playlist = entity.getPlaylist();
        var ytId = entity.getYtVideoId();

        var channelExcluded = channel.getExcludedVideoIds().contains(ytId);
        var playlistExcluded = playlist != null && playlist.getExcludedVideoIds().contains(ytId);

        return new VideoSummaryDto(
            entity.getId(),
            ytId,
            channel.getId(),
            channel.getYtChannelId(),
            playlist != null ? playlist.getId() : null,
            playlist != null ? playlist.getYtPlaylistId() : null,
            categories,
            channelExcluded,
            playlistExcluded
        );
    }

    private void rejectUnsupportedFilters(String length, String date, String sort) {
        if (StringUtils.hasText(length) || StringUtils.hasText(date) || StringUtils.hasText(sort)) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Length, date, and sort filters require runtime metadata support");
        }
    }

    private void addCursorPredicate(
        Cursor cursor,
        CriteriaBuilder cb,
        Root<? extends AuditableEntity> root,
        List<Predicate> predicates
    ) {
        if (cursor == null) {
            return;
        }
        var createdAtPath = root.<OffsetDateTime>get("createdAt");
        var idPath = root.<UUID>get("id");
        predicates.add(cb.or(
            cb.lessThan(createdAtPath, cursor.createdAt()),
            cb.and(cb.equal(createdAtPath, cursor.createdAt()), cb.lessThan(idPath, cursor.id()))
        ));
    }

    private static String normalizeCategorySlug(String slug) {
        return slug.trim().toLowerCase(Locale.ROOT);
    }

    private int normalizeLimit(int requestedLimit) {
        if (requestedLimit < MIN_LIMIT || requestedLimit > MAX_LIMIT) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Limit must be between %d and %d".formatted(MIN_LIMIT, MAX_LIMIT)
            );
        }
        return requestedLimit;
    }

    private Cursor decodeCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return null;
        }
        var trimmed = cursor.trim();
        var parts = trimmed.split("\\|", 2);
        if (parts.length != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }
        try {
            return new Cursor(trimmed, OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor", ex);
        }
    }

    private CursorPageInfo buildPageInfo(String currentCursor, Cursor nextCursor, boolean hasNext, int limit) {
        return new CursorPageInfo(currentCursor, nextCursor != null ? nextCursor.raw() : null, hasNext, limit);
    }

    private <T> SliceResult<T> executeSlice(TypedQuery<T> query, int limit, java.util.function.Function<T, Cursor> cursorFn) {
        var results = query.getResultList();
        var hasNext = results.size() > limit;
        if (hasNext) {
            results = results.subList(0, limit);
        }
        var items = List.copyOf(results);
        var nextCursor = hasNext ? cursorFn.apply(items.get(items.size() - 1)) : null;
        return new SliceResult<>(items, nextCursor, hasNext);
    }

    private record Cursor(String raw, OffsetDateTime createdAt, UUID id) {
        static Cursor from(OffsetDateTime createdAt, UUID id) {
            return new Cursor(createdAt.toString() + "|" + id, createdAt, id);
        }
    }

    private record SliceResult<T>(List<T> items, Cursor nextCursor, boolean hasNext) {}
}
