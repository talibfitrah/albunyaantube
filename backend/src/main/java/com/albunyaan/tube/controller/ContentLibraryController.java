package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.search.CatalogChanged;
import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.service.ContentReportService;
import com.albunyaan.tube.service.ImportGraduationService;
import com.albunyaan.tube.service.LibrarySearchSnapshot;
import com.albunyaan.tube.service.LiveCategoryGuard;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.SortOrderService;
import com.albunyaan.tube.service.TagEnrichmentService;
import com.albunyaan.tube.service.VisibilityPolicy;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Content Library Controller
 *
 * Unified endpoint for managing all approved content (channels, playlists, videos).
 * Powers the Content Library admin view with filtering, search, and pagination.
 *
 * Endpoint: /api/admin/content
 */
@RestController
@RequestMapping("/api/admin/content")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class ContentLibraryController {

    private static final Logger log = LoggerFactory.getLogger(ContentLibraryController.class);
    private static final int FIRESTORE_BATCH_LIMIT = 500;

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;
    private final PublicContentCacheService publicContentCacheService;
    private final SortOrderService sortOrderService;
    private final TagEnrichmentService tagEnrichmentService;
    private final ImportGraduationService importGraduationService;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;
    private final LiveCategoryGuard liveCategoryGuard;
    private final LibrarySearchSnapshot librarySearch;

    public ContentLibraryController(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            Firestore firestore,
            FirestoreTimeoutProperties timeoutProperties,
            PublicContentCacheService publicContentCacheService,
            SortOrderService sortOrderService,
            TagEnrichmentService tagEnrichmentService,
            ImportGraduationService importGraduationService,
            UserRepository userRepository,
            ApplicationEventPublisher events,
            LiveCategoryGuard liveCategoryGuard,
            LibrarySearchSnapshot librarySearch
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
        this.publicContentCacheService = publicContentCacheService;
        this.sortOrderService = sortOrderService;
        this.tagEnrichmentService = tagEnrichmentService;
        this.importGraduationService = importGraduationService;
        this.userRepository = userRepository;
        this.events = events;
        this.liveCategoryGuard = liveCategoryGuard;
        this.librarySearch = librarySearch;
    }

    /**
     * Registry-wide per-type counts for the Content Library header.
     *
     * <p>Deliberately its own request rather than a field on the listing response. The counts
     * follow only the status filter, never search, category or paging — piggybacking them put
     * three serialized aggregation round-trips on the listing's critical path, where they were
     * recomputed on every debounced search keystroke, discarded on every infinite-scroll page, and
     * able to fail a listing that had already been fetched and sorted. On their own endpoint a
     * slow or failing count costs nothing but an unpopulated header.</p>
     *
     * <p>{@code status} follows the listing's status filter (all, approved, pending, rejected), so
     * the header reads e.g. "approved channels" while the admin views approved content. Approved
     * means live, as in the listing: archived items keep status APPROVED, but the apps hide them
     * and the Archived page counts them. The Content Sorting picker lists the same set. Pending and rejected
     * count every doc with that status, so pending equals the Pending Approvals queue.</p>
     */
    @GetMapping("/totals")
    public ResponseEntity<RegistryTotals> getTotals(@RequestParam(defaultValue = "all") String status)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        if ("all".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(new RegistryTotals(
                    channelRepository.countAll(),
                    playlistRepository.countAll(),
                    videoRepository.countAll()
            ));
        }
        if ("approved".equalsIgnoreCase(status)) {
            // Two reads, so a concurrent status change can skew one moment by an item; never below 0.
            return ResponseEntity.ok(new RegistryTotals(
                    Math.max(0, channelRepository.countByStatus("APPROVED") - channelRepository.countApprovedArchived()),
                    Math.max(0, playlistRepository.countByStatus("APPROVED") - playlistRepository.countApprovedArchived()),
                    Math.max(0, videoRepository.countByStatus("APPROVED") - videoRepository.countApprovedArchived())
            ));
        }
        if (!"pending".equalsIgnoreCase(status) && !"rejected".equalsIgnoreCase(status)) {
            return ResponseEntity.badRequest().build();
        }
        String upper = status.toUpperCase(java.util.Locale.ROOT);
        return ResponseEntity.ok(new RegistryTotals(
                channelRepository.countByStatus(upper),
                playlistRepository.countByStatus(upper),
                videoRepository.countByStatus(upper)
        ));
    }

    /**
     * GET /api/admin/content/approved-picker
     *
     * Every approved (live) item, carrying only what the "add content to a category" picker draws,
     * one cursor page at a time: the approved Library listing (same definition as
     * {@code GET /totals?status=approved}, same merge and cursor), so the picker's total is the
     * Library's. Personal grants are included and flagged: the picker counts them in that total and
     * says why it cannot add them.
     */
    @GetMapping("/approved-picker")
    public ResponseEntity<ApprovedPickerResponse> getApprovedForPicker(@RequestParam(required = false) String cursor)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ContentLibraryResponse page = mergePage(Set.of("channel", "playlist", "video"), "approved", false,
                ListingCursor.decode(cursor), PICKER_PAGE,
                (a, id, limit) -> channelRepository.findPageAfter("APPROVED", null, false, a, id, limit),
                (a, id, limit) -> playlistRepository.findPageAfter("APPROVED", null, false, a, id, limit),
                (a, id, limit) -> videoRepository.findPageAfter("APPROVED", null, false, a, id, limit));
        List<ApprovedPickerItem> items = page.content.stream()
                .map(i -> new ApprovedPickerItem(i.type, i.id, i.title, i.thumbnailUrl, i.youtubeId, i.visibility))
                .toList();
        return ResponseEntity.ok(new ApprovedPickerResponse(items, page.nextCursor));
    }

    /** Rows per picker page: a few requests cover the whole approved library. */
    private static final int PICKER_PAGE = 500;

    /**
     * One row of the picker. {@code getThumbnailUrl()} on the client reads {@code thumbnailUrl}
     * first and otherwise builds a URL from {@code youtubeId}, so both carry the image.
     * {@code visibility} is "PUBLIC" or "PERSONAL"; a personal grant cannot go into a category.
     */
    public static class ApprovedPickerItem {
        public final String type;
        public final String id;
        public final String title;
        public final String thumbnailUrl;
        public final String youtubeId;
        public final String visibility;

        public ApprovedPickerItem(String type, String id, String title, String thumbnailUrl, String youtubeId,
                                  String visibility) {
            this.type = type;
            this.id = id;
            this.title = title;
            this.thumbnailUrl = thumbnailUrl;
            this.youtubeId = youtubeId;
            this.visibility = visibility;
        }
    }

    public static class ApprovedPickerResponse {
        public final List<ApprovedPickerItem> items;
        /** Pass back as {@code cursor} for the next page. Null when nothing is left. */
        public final String nextCursor;

        public ApprovedPickerResponse(List<ApprovedPickerItem> items, String nextCursor) {
            this.items = items;
            this.nextCursor = nextCursor;
        }
    }

    /**
     * Helper to fetch category IDs for a content item.
     */
    private List<String> getCategoryIdsForItem(String type, String id)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        switch (type.toLowerCase()) {
            case "channel":
                return channelRepository.findById(id).map(Channel::getCategoryIds).orElse(null);
            case "playlist":
                return playlistRepository.findById(id).map(Playlist::getCategoryIds).orElse(null);
            case "video":
                return videoRepository.findById(id).map(Video::getCategoryIds).orElse(null);
            default:
                return null;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper CURSOR_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Where a listing resumes, opaque to the client.
     *
     * <p>For each type, the last row already served (createdAt to the nanosecond, then document
     * id — the listing's order), and the types with nothing left. Resuming by position rather
     * than offset means a page costs only its own rows, never the prefix before it, and a row
     * deleted or added above the window shifts nothing. Browse, search and the picker share it.</p>
     */
    public static class ListingCursor {
        public Map<String, Position> after = new HashMap<>();
        public Set<String> done = new HashSet<>();

        static ListingCursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) return new ListingCursor();
            try {
                ListingCursor c = CURSOR_JSON.readValue(Base64.getUrlDecoder().decode(cursor), ListingCursor.class);
                if (c == null || c.after == null || c.done == null
                        || c.after.values().stream().anyMatch(p -> p == null || p.id == null)) {
                    throw new IllegalArgumentException("Invalid cursor");
                }
                return c;
            } catch (java.io.IOException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid cursor", e);
            }
        }

        String encode() {
            try {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(CURSOR_JSON.writeValueAsBytes(this));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** A row's place in the listing order. */
    public static class Position {
        public long s;
        public int n;
        public String id;

        Position() {
        }

        Position(com.google.cloud.Timestamp at, String id) {
            this.s = at.getSeconds();
            this.n = at.getNanos();
            this.id = id;
        }

        com.google.cloud.Timestamp timestamp() {
            return com.google.cloud.Timestamp.ofTimeSecondsAndNanos(s, n);
        }
    }

    /**
     * Get content with filtering, one page at a time.
     *
     * Query Parameters:
     * - types: Comma-separated list of content types (channel,playlist,video)
     * - status: Filter by status (all, approved, pending, rejected)
     * - category: Filter by category ID
     * - search: Substring of title, description or a keyword, case-insensitive
     * - sort: newest or oldest
     * - cursor: nextCursor of the previous page; omitted for the first page
     * - size: Page size (default 20, max 100)
     *
     * Browse and search page by cursor through every matching row, with no ceiling.
     */
    @GetMapping
    public ResponseEntity<ContentLibraryResponse> getContent(
            @RequestParam(required = false) String types,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(required = false) String cursor,
            @Min(1) @RequestParam(defaultValue = "20") int size
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        // Cap page size to prevent excessive reads
        int cappedSize = Math.min(size, 100);
        Set<String> contentTypes = parseTypes(types);
        ListingCursor at = ListingCursor.decode(cursor);
        boolean oldest = "oldest".equalsIgnoreCase(sort);
        String st = "all".equalsIgnoreCase(status) ? null : status.toUpperCase(java.util.Locale.ROOT);
        String cat = category == null || category.isBlank() ? null : category;

        log.info("Content Library request: types={}, status={}, category={}, search={}, sort={}, hasCursor={}, size={}",
                types, status, category, search, sort, cursor != null, cappedSize);

        ContentLibraryResponse response;
        if (search != null && !search.isBlank()) {
            LibrarySearchSnapshot.Matches m = librarySearch.find(search, st, cat);
            response = mergePage(contentTypes, status, oldest, at, cappedSize,
                    inOrder(m.channels(), oldest, Channel::getCreatedAt, Channel::getId),
                    inOrder(m.playlists(), oldest, Playlist::getCreatedAt, Playlist::getId),
                    inOrder(m.videos(), oldest, Video::getCreatedAt, Video::getId));
            response.content = current(response.content);
        } else {
            response = mergePage(contentTypes, status, oldest, at, cappedSize,
                    (a, id, limit) -> channelRepository.findPageAfter(st, cat, oldest, a, id, limit),
                    (a, id, limit) -> playlistRepository.findPageAfter(st, cat, oldest, a, id, limit),
                    (a, id, limit) -> videoRepository.findPageAfter(st, cat, oldest, a, id, limit));
        }
        resolveGranteeNames(response.content);

        log.info("Returning {} items (more: {})", response.content.size(), response.nextCursor != null);
        return ResponseEntity.ok(response);
    }

    /** Search matches as a {@link PageQuery}: the listing order, resuming strictly after the cursor. */
    private static <T> PageQuery<T> inOrder(List<T> rows, boolean oldest,
                                            java.util.function.Function<T, com.google.cloud.Timestamp> createdAt,
                                            java.util.function.Function<T, String> id) {
        Comparator<T> asc = Comparator.comparing(createdAt).thenComparing(id);
        List<T> sorted = rows.stream().sorted(oldest ? asc : asc.reversed()).toList();
        return (afterAt, afterId, limit) -> sorted.stream()
                .filter(r -> {
                    if (afterAt == null) return true;
                    int cmp = createdAt.apply(r).compareTo(afterAt);
                    if (cmp == 0) cmp = id.apply(r).compareTo(afterId);
                    return oldest ? cmp > 0 : cmp < 0;
                })
                .limit(limit)
                .toList();
    }

    /**
     * A search page as the documents are now. The snapshot is current for what search matches on;
     * the page is read by id so every other field (displayOrder, written without an event) is too.
     * A row deleted since is dropped.
     */
    private List<ContentItem> current(List<ContentItem> page)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Map<String, Channel> ch = channelRepository.findAllByIds(idsOf(page, "channel"));
        Map<String, Playlist> pl = playlistRepository.findAllByIds(idsOf(page, "playlist"));
        Map<String, Video> vi = videoRepository.findAllByIds(idsOf(page, "video"));
        List<ContentItem> out = new ArrayList<>();
        for (ContentItem i : page) {
            ContentItem fresh = switch (i.type) {
                case "channel" -> ch.containsKey(i.id) ? item(ch.get(i.id)) : null;
                case "playlist" -> pl.containsKey(i.id) ? item(pl.get(i.id)) : null;
                default -> vi.containsKey(i.id) ? item(vi.get(i.id)) : null;
            };
            if (fresh != null) out.add(fresh);
        }
        return out;
    }

    private static List<String> idsOf(List<ContentItem> page, String type) {
        return page.stream().filter(i -> type.equals(i.type)).map(i -> i.id).toList();
    }

    /**
     * One row of a page, with the full-precision createdAt its cursor position needs.
     * {@code skipTo}: the archived row just before it that an approved listing skipped, if any.
     */
    private record Row(String type, String id, com.google.cloud.Timestamp createdAt, ContentItem item,
                       Position skipTo) {
    }

    @FunctionalInterface
    private interface PageQuery<T> {
        List<T> fetch(com.google.cloud.Timestamp afterCreatedAt, String afterId, int limit)
                throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException;
    }

    /**
     * A k-way merge of per-type cursor pages in createdAt order.
     *
     * <p>Each type unfinished contributes its next {@code size} rows, so the first {@code size}
     * of the merge can never be preceded by a row of any type not yet read. Each type's cursor
     * then moves to the last of its rows the page served, or past the archived rows before the
     * first it did not, so an archived run is read once. A page reads about {@code size + 1} rows per
     * type, whatever its depth, plus any archived run it moves past.</p>
     */
    private ContentLibraryResponse mergePage(Set<String> contentTypes, String status, boolean oldest,
                                             ListingCursor at, int size, PageQuery<Channel> channels,
                                             PageQuery<Playlist> playlists, PageQuery<Video> videos)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // "Approved" is live content, matching GET /totals: archived rows belong to the Archived page.
        boolean liveOnly = "approved".equalsIgnoreCase(status);

        Map<String, List<Row>> windows = new LinkedHashMap<>();
        if (contentTypes.contains("channel") && !at.done.contains("channel")) {
            windows.put("channel", window("channel", at, size, liveOnly, Channel::getValidationStatus,
                    Channel::getCreatedAt, Channel::getId, ContentLibraryController::item, channels));
        }
        if (contentTypes.contains("playlist") && !at.done.contains("playlist")) {
            windows.put("playlist", window("playlist", at, size, liveOnly, Playlist::getValidationStatus,
                    Playlist::getCreatedAt, Playlist::getId, ContentLibraryController::item, playlists));
        }
        if (contentTypes.contains("video") && !at.done.contains("video")) {
            windows.put("video", window("video", at, size, liveOnly, Video::getValidationStatus,
                    Video::getCreatedAt, Video::getId, ContentLibraryController::item, videos));
        }

        Comparator<Row> order = Comparator.comparing(Row::createdAt).thenComparing(Row::id).thenComparing(Row::type);
        List<Row> merged = windows.values().stream().flatMap(List::stream)
                .sorted(oldest ? order : order.reversed())
                .limit(size)
                .toList();

        ListingCursor next = new ListingCursor();
        next.after.putAll(at.after);
        next.done.addAll(at.done);
        for (Row row : merged) {
            next.after.put(row.type(), new Position(row.createdAt(), row.id()));
        }
        windows.forEach((type, rows) -> {
            Row left = rows.stream().filter(r -> !merged.contains(r)).findFirst().orElse(null);
            if (left == null) next.done.add(type); // rows that continue past the page include one more
            else if (left.skipTo() != null) next.after.put(type, left.skipTo());
        });
        boolean finished = contentTypes.stream().allMatch(next.done::contains);
        List<ContentItem> page = merged.stream().map(Row::item).collect(Collectors.toList());
        return new ContentLibraryResponse(page, finished ? null : next.encode(), size);
    }

    /**
     * A type's next rows after its cursor: all that remain, or {@code size + 1} and more when the
     * type continues past this page. For approved (live) listings archived rows are skipped,
     * reading on past them so a page is never short while live rows remain.
     */
    private <T> List<Row> window(String type, ListingCursor at, int size, boolean liveOnly,
                              java.util.function.Function<T, ValidationStatus> validationStatus,
                              java.util.function.Function<T, com.google.cloud.Timestamp> createdAt,
                              java.util.function.Function<T, String> id,
                              java.util.function.Function<T, ContentItem> toItem,
                              PageQuery<T> query)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Position from = at.after.get(type);
        com.google.cloud.Timestamp afterAt = from == null ? null : from.timestamp();
        String afterId = from == null ? null : from.id;
        List<Row> rows = new ArrayList<>();
        Position skipped = null;
        while (true) {
            // size + 1: the extra row says whether more follow, without a count.
            List<T> raw = query.fetch(afterAt, afterId, size + 1);
            for (T r : raw) {
                if (!liveOnly || !ValidationStatus.holds(validationStatus.apply(r))) {
                    rows.add(new Row(type, id.apply(r), createdAt.apply(r), toItem.apply(r), skipped));
                    skipped = null;
                } else {
                    skipped = new Position(createdAt.apply(r), id.apply(r));
                }
            }
            if (rows.size() > size || raw.size() <= size) return rows;
            T last = raw.get(raw.size() - 1);
            afterAt = createdAt.apply(last);
            afterId = id.apply(last);
        }
    }

    private static ContentItem item(Channel ch) {
        return withVisibility(new ContentItem("channel", ch.getId(), ch.getYoutubeId(), ch.getName(),
                ch.getDescription(), ch.getThumbnailUrl(), ch.getStatus(), ch.getCategoryIds(),
                ch.getCreatedAt() != null ? ch.getCreatedAt().toDate() : null,
                ch.getSubscribers(), ch.getDisplayOrder(), ch.getKeywords()),
                ch.getVisibility(), ch.getPersonalGrants());
    }

    private static ContentItem item(Playlist pl) {
        return withVisibility(new ContentItem("playlist", pl.getId(), pl.getYoutubeId(), pl.getTitle(),
                pl.getDescription(), pl.getThumbnailUrl(), pl.getStatus(), pl.getCategoryIds(),
                pl.getCreatedAt() != null ? pl.getCreatedAt().toDate() : null,
                pl.getItemCount() != null ? Long.valueOf(pl.getItemCount()) : null, pl.getDisplayOrder(),
                pl.getKeywords()),
                pl.getVisibility(), pl.getPersonalGrants());
    }

    private static ContentItem item(Video v) {
        ContentItem item = new ContentItem("video", v.getId(), v.getYoutubeId(), v.getTitle(),
                v.getDescription(), v.getThumbnailUrl(), v.getStatus(), v.getCategoryIds(),
                v.getCreatedAt() != null ? v.getCreatedAt().toDate() : null,
                v.getViewCount(), v.getDisplayOrder(), v.getKeywords());
        item.offlineAllowed = v.allowsOffline();
        return withVisibility(item, v.getVisibility(), v.getPersonalGrants());
    }

    /**
     * Stamp the item's visibility, and its grantee uids for later resolution.
     *
     * <p>Legacy and organic docs carry no visibility field; {@link VisibilityPolicy} treats those
     * as public, so they must not surface as restricted grants.
     */
    private static ContentItem withVisibility(ContentItem item, String visibility, List<String> grantUids) {
        boolean isPublic = VisibilityPolicy.isPublic(visibility);
        item.visibility = isPublic ? "PUBLIC" : "PERSONAL";
        item.grantedTo = (!isPublic && grantUids != null)
                // Not List.copyOf: one null entry on a malformed document would fail the whole
                // listing rather than degrading that single row.
                ? grantUids.stream().filter(java.util.Objects::nonNull).toList()
                : List.of();
        return item;
    }

    /**
     * Turn grantee uids into something an admin can read, for the returned page only.
     *
     * <p>Scoped to the page — the bounded fetch can hold thousands of rows, and only the ones on
     * screen need a name. Distinct uids are resolved once per request; {@code findByUid} is cached
     * behind the repository, so a grant list shared across items costs one lookup.
     *
     * <p>An unresolvable uid falls back to itself: a deleted account must still read as a grant
     * that exists, not as a blank chip.
     */
    private void resolveGranteeNames(List<ContentItem> page) {
        Map<String, String> resolved = new HashMap<>();
        for (ContentItem item : page) {
            if (item.grantedTo.isEmpty()) {
                continue;
            }
            List<String> names = new ArrayList<>(item.grantedTo.size());
            for (String uid : item.grantedTo) {
                names.add(resolved.computeIfAbsent(uid, this::granteeLabel));
            }
            item.grantedTo = names;
        }
    }

    private String granteeLabel(String uid) {
        try {
            return userRepository.findByUid(uid)
                    .map(u -> hasText(u.getDisplayName()) ? u.getDisplayName() : u.getEmail())
                    .filter(ContentLibraryController::hasText)
                    .orElse(uid);
        } catch (Exception e) {
            log.warn("Could not resolve grantee {}: {}", uid, e.getMessage());
            return uid;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Set<String> parseTypes(String types) {
        if (types == null || types.isBlank()) {
            return Set.of("channel", "playlist", "video"); // Default: all types
        }
        return Arrays.stream(types.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(Set.of("channel", "playlist", "video")::contains) // an unknown type would never be done
                .collect(Collectors.toSet());
    }

    // DTOs

    public static class ContentLibraryResponse {
        public List<ContentItem> content;
        /** Opaque position after this page: pass it back as {@code cursor}. Null when nothing is left. */
        public String nextCursor;
        public int pageSize;

        public ContentLibraryResponse(List<ContentItem> content, String nextCursor, int pageSize) {
            this.content = content;
            this.nextCursor = nextCursor;
            this.pageSize = pageSize;
        }
    }

    /**
     * Registry-wide counts per content type, from Firestore server-side aggregation.
     *
     * <p>Filtered by status only, never by category or search: these answer "how much is in the
     * library" for that status, which is why they stay stable while the user searches, and why
     * they are counted separately from the listing rather than derived from them. {@code videos} counts only
     * individually-curated video documents — approving a channel or playlist does not create
     * Video documents, so videos reachable through a channel or playlist are not counted here.</p>
     */
    public static class RegistryTotals {
        public long channels;
        public long playlists;
        public long videos;

        public RegistryTotals(long channels, long playlists, long videos) {
            this.channels = channels;
            this.playlists = playlists;
            this.videos = videos;
        }
    }

    public static class ContentItem {
        public String type;
        public String id;
        public String youtubeId; // YouTube ID for video preview/player
        public String title;
        public String description;
        public String thumbnailUrl;
        public String status;
        public List<String> categoryIds;
        public Date createdAt;
        public Long count; // subscriber/video/view count depending on type
        public Integer displayOrder; // custom ordering position
        public List<String> keywords; // keywords/tags for search
        /**
         * "PUBLIC" or "PERSONAL". APPROVED alone does not say whether an item is in the public
         * catalogue or granted only to the people who imported it.
         */
        public String visibility;
        /** Display names (or emails, or uid) of the people a PERSONAL grant covers. Never null. */
        public List<String> grantedTo = List.of();

        /**
         * Videos only (null for channels/playlists): the EFFECTIVE offline permission
         * ({@link Video#allowsOffline()}) — true unless an admin explicitly blocked it.
         */
        public Boolean offlineAllowed;

        public ContentItem(String type, String id, String youtubeId, String title, String description, String thumbnailUrl,
                           String status, List<String> categoryIds, Date createdAt, Long count, Integer displayOrder,
                           List<String> keywords) {
            this.type = type;
            this.id = id;
            this.youtubeId = youtubeId;
            this.title = title;
            this.description = description;
            this.thumbnailUrl = thumbnailUrl;
            this.status = status;
            this.categoryIds = categoryIds != null ? categoryIds : List.of();
            this.createdAt = createdAt;
            this.count = count;
            this.displayOrder = displayOrder;
            this.keywords = keywords;
        }
    }

    // Bulk Actions

    /**
     * Functional interface for bulk delete operations.
     */
    @FunctionalInterface
    private interface EntityResolver {
        /**
         * @return the item's youtubeId if it exists, otherwise empty. The id is needed to clear
         *         the content from the phones of everyone holding it.
         */
        Optional<String> resolve(BulkActionItem item) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException;
    }

    /**
     * Helper method to execute bulk status update operations using update-only writes.
     *
     * Uses batched getAll() for existence validation (with timeout) and update() instead of set()
     * to prevent lost updates from concurrent modifications. Only modifies status and updatedAt fields.
     *
     * Firestore batches provide atomic commits within each batch (up to 500 operations).
     * Items are processed in chunks: each chunk either fully succeeds or fully fails.
     *
     * @param items List of items to process
     * @param newStatus The new status to set ("APPROVED" or "REJECTED")
     * @param operationName Name of the operation for error messages (e.g., "approving", "rejecting")
     * @return BulkActionResponse with success count and errors
     */
    /**
     * Clear deleted content from the lists of everyone holding it.
     *
     * <p>Reuses the reject fan-out: from a phone's point of view "this content is gone" is the
     * same instruction either way. Grouped by type so the cost is a handful of calls, and failures
     * are swallowed — the registry rows are already deleted and cannot be put back by failing here.
     */
    private void clearFromDevices(List<BulkActionItem> deletedItems, Map<String, String> youtubeIdByKey) {
        Map<YouTubeContentType, Set<String>> idsByType = new HashMap<>();
        for (BulkActionItem item : deletedItems) {
            String youtubeId = youtubeIdByKey.get(item.type.toLowerCase() + ":" + item.id);
            if (youtubeId == null || youtubeId.isBlank()) {
                continue;
            }
            try {
                idsByType.computeIfAbsent(YouTubeContentType.valueOf(item.type.toUpperCase()),
                        t -> new HashSet<>()).add(youtubeId);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown content type in bulk delete: {}", item.type);
            }
        }
        idsByType.forEach((type, ids) -> {
            try {
                importGraduationService.onRejectedAll(type, ids);
            } catch (Exception e) {
                log.warn("Could not clear deleted {} ({} items) from devices: {}", type, ids.size(), e.getMessage());
            }
        });
    }

    /**
     * Fan an adjudication out to the per-user Me-list rows of everyone who imported these items,
     * flipping AWAITING → APPROVED (or tombstoning on reject).
     *
     * <p>Without this, content adjudicated here stays "pending" in the app forever: the sync pull
     * returns the row's stored approvalStatus, and the server-side re-derive only runs on a client
     * push that never comes again for a clean row. {@code ApprovalService} does the same fan-out
     * for the {@code /api/admin/approvals} path.
     *
     * <p>Grouped by content type and handed over in one call each, so the cost is a handful of
     * chunked queries rather than one round-trip per item. Kept on the request thread deliberately:
     * a stranded AWAITING row is the failure this exists to prevent, and a background task with no
     * handle would be dropped on the next redeploy with nothing to correct it.
     *
     * <p>Called only after a batch has committed, so an item that failed validation or whose batch
     * rolled back is never graduated.
     */
    private void graduateImporters(List<BulkActionItem> committedItems,
                                   Map<String, String> youtubeIdByKey,
                                   String newStatus) {
        Map<YouTubeContentType, Set<String>> idsByType = new HashMap<>();
        for (BulkActionItem item : committedItems) {
            String youtubeId = youtubeIdByKey.get(item.type.toLowerCase() + ":" + item.id);
            if (youtubeId == null || youtubeId.isBlank()) {
                continue;
            }
            try {
                idsByType.computeIfAbsent(YouTubeContentType.valueOf(item.type.toUpperCase()),
                        t -> new HashSet<>()).add(youtubeId);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown content type in bulk action: {}", item.type);
            }
        }

        idsByType.forEach((type, ids) -> {
            try {
                if ("APPROVED".equals(newStatus)) {
                    importGraduationService.onApprovedAll(type, ids);
                } else if ("REJECTED".equals(newStatus)) {
                    importGraduationService.onRejectedAll(type, ids);
                }
            } catch (Exception e) {
                // A fan-out failure must not fail the admin's action — the status is already written.
                log.warn("Graduation fan-out failed for {} ({} items): {}", type, ids.size(), e.getMessage());
            }
        });
    }

    private BulkActionResponse executeBulkStatusUpdate(List<BulkActionItem> items, String newStatus, String operationName) {
        int successCount = 0;
        List<String> errors = new ArrayList<>();
        Set<String> failedKeys = new HashSet<>();

        // Process items in chunks to respect Firestore batch limit (500 operations)
        for (int i = 0; i < items.size(); i += FIRESTORE_BATCH_LIMIT) {
            int endIndex = Math.min(i + FIRESTORE_BATCH_LIMIT, items.size());
            List<BulkActionItem> batch = items.subList(i, endIndex);

            List<BulkActionItem> validatedItems = new ArrayList<>();
            // youtubeId of each validated item, read from the existence-check snapshot so the
            // post-commit graduation fan-out costs no extra Firestore reads.
            Map<String, String> youtubeIdByKey = new HashMap<>();

            // First pass: validate existence using batched getAll with timeout
            try {
                List<DocumentReference> docRefs = new ArrayList<>();
                for (BulkActionItem item : batch) {
                    try {
                        String collectionName = getCollectionName(item.type);
                        docRefs.add(firestore.collection(collectionName).document(item.id));
                    } catch (IllegalArgumentException e) {
                        failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                        errors.add(e.getMessage());
                    }
                }

                if (!docRefs.isEmpty()) {
                    List<DocumentSnapshot> snapshots = firestore.getAll(docRefs.toArray(new DocumentReference[0]))
                            .get(timeoutProperties.getRead(), TimeUnit.SECONDS);

                    // Match snapshots back to items (preserving order)
                    int snapshotIndex = 0;
                    for (BulkActionItem item : batch) {
                        try {
                            getCollectionName(item.type); // Will throw if invalid type
                            DocumentSnapshot snap = snapshotIndex < snapshots.size() ? snapshots.get(snapshotIndex) : null;
                            if (snap != null && snap.exists() && "video".equalsIgnoreCase(item.type) && !"REJECTED".equals(newStatus)
                                    && ContentReportService.isTakeDownRecord(snap.getString("status"), snap.getString("source"))) {
                                // Stage 9 B21 (Cubic r9 #14): approved, a take-down record is a blank public video; pending,
                                // the approvals queue approves it. Restore or a re-add lifts a take-down.
                                failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                                errors.add("Video " + item.id + " was taken down by a report: Restore it under Archived "
                                        + "content, or add the video again");
                            } else if (snap != null && snap.exists()) {
                                validatedItems.add(item);
                                youtubeIdByKey.put(item.type.toLowerCase() + ":" + item.id, snap.getString("youtubeId"));
                            } else {
                                failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                                errors.add(item.type + " not found: " + item.id);
                            }
                            snapshotIndex++;
                        } catch (IllegalArgumentException e) {
                            // Already added to errors above
                        }
                    }
                }
            } catch (TimeoutException e) {
                log.error("Timeout during existence check for batch: {}", e.getMessage());
                for (BulkActionItem item : batch) {
                    failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                    errors.add("Timeout checking existence of " + item.type + " " + item.id);
                }
                continue; // Skip to next batch
            } catch (Exception e) {
                log.error("Error during existence check for batch: {}", e.getMessage());
                for (BulkActionItem item : batch) {
                    failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                    errors.add("Error checking " + item.type + " " + item.id + ": " + e.getMessage());
                }
                continue; // Skip to next batch
            }

            // Second pass: UPDATE (not SET) only status and updatedAt fields
            // For channels, also update legacy boolean flags (pending, approved)
            if (!validatedItems.isEmpty()) {
                WriteBatch writeBatch = firestore.batch();
                try {
                    for (BulkActionItem item : validatedItems) {
                        String collectionName = getCollectionName(item.type);
                        DocumentReference docRef = firestore.collection(collectionName).document(item.id);

                        Map<String, Object> updates = new HashMap<>();
                        updates.put("status", newStatus);
                        updates.put("updatedAt", com.google.cloud.Timestamp.now());
                        if ("channel".equalsIgnoreCase(item.type)) {
                            // Channel has legacy boolean flags that must stay in sync with status
                            updates.put("pending", "PENDING".equals(newStatus));
                            updates.put("approved", "APPROVED".equals(newStatus));
                        }
                        if ("APPROVED".equals(newStatus)) {
                            // Approving here means publishing. Leaving a PERSONAL item restricted
                            // would count it as approved while it stayed out of the public feed,
                            // and would let the public fan-out grant it to people it was never
                            // granted to.
                            updates.put("visibility", "PUBLIC");
                        }
                        writeBatch.update(docRef, updates);
                    }

                    // Atomic commit with timeout - all items succeed or all fail
                    boolean committed = false;
                    try {
                        writeBatch.commit().get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                        committed = true;
                    } finally { // a failed ack may hide a committed batch: the index re-reads each item (F3)
                        publishCatalogChanged(validatedItems, !committed);
                    }
                    successCount += validatedItems.size();
                    log.debug("Batch committed successfully: {} items with status={}", validatedItems.size(), newStatus);

                    graduateImporters(validatedItems, youtubeIdByKey, newStatus);

                } catch (TimeoutException e) {
                    log.error("Timeout during batch commit: {}", e.getMessage());
                    for (BulkActionItem item : validatedItems) {
                        failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                        errors.add("Timeout " + operationName + " " + item.type + " " + item.id);
                    }
                } catch (Exception e) {
                    // Batch failed - all items in this batch are rolled back by Firestore
                    log.error("Batch commit failed for {} items: {}", validatedItems.size(), e.getMessage());
                    for (BulkActionItem item : validatedItems) {
                        failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                        errors.add("Batch commit failed for " + item.type + " " + item.id + ": " + e.getMessage());
                    }
                }
            }
        }

        return new BulkActionResponse(successCount, errors, failedKeys);
    }

    /**
     * Helper method to execute bulk delete operations with Firestore batch writes.
     */
    private BulkActionResponse executeBulkDeleteOperation(List<BulkActionItem> items, EntityResolver resolver, String operationName) {
        int successCount = 0;
        List<String> errors = new ArrayList<>();
        Set<String> failedKeys = new HashSet<>();

        // Process items in chunks to respect Firestore batch limit (500 operations)
        for (int i = 0; i < items.size(); i += FIRESTORE_BATCH_LIMIT) {
            int endIndex = Math.min(i + FIRESTORE_BATCH_LIMIT, items.size());
            List<BulkActionItem> batch = items.subList(i, endIndex);

            WriteBatch writeBatch = firestore.batch();
            List<BulkActionItem> itemsToDelete = new ArrayList<>();
            Map<String, String> youtubeIdByKey = new HashMap<>();

            // First pass: verify all items exist
            for (BulkActionItem item : batch) {
                try {
                    Optional<String> youtubeId = resolver.resolve(item);
                    if (youtubeId.isPresent()) {
                        itemsToDelete.add(item);
                        youtubeIdByKey.put(item.type.toLowerCase() + ":" + item.id, youtubeId.get());
                    } else {
                        failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                        errors.add(item.type + " not found: " + item.id);
                    }
                } catch (IllegalArgumentException e) {
                    failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                    errors.add(e.getMessage());
                } catch (Exception e) {
                    failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                    errors.add("Error " + operationName + " " + item.type + " " + item.id + ": " + e.getMessage());
                }
            }

            // Second pass: delete all items atomically
            if (!itemsToDelete.isEmpty()) {
                try {
                    for (BulkActionItem item : itemsToDelete) {
                        String collectionName = getCollectionName(item.type);
                        writeBatch.delete(firestore.collection(collectionName).document(item.id));
                    }

                    // Atomic commit
                    boolean committed = false;
                    try {
                        writeBatch.commit().get();
                        committed = true;
                    } finally { // a failed ack may hide a committed batch: the index re-reads each item (F3)
                        publishCatalogChanged(itemsToDelete, !committed);
                    }
                    successCount += itemsToDelete.size();
                    log.debug("Batch delete committed successfully: {} items", itemsToDelete.size());

                    // Deleted content has to leave people's lists, the same way archived content
                    // does. Without this the registry entry is gone while every phone holding it
                    // keeps a working copy: the sync pull returns the row's stored state, and the
                    // archive projection only covers entries that were archived, not absent ones.
                    clearFromDevices(itemsToDelete, youtubeIdByKey);

                } catch (Exception e) {
                    log.error("Batch delete failed for {} items: {}", itemsToDelete.size(), e.getMessage());
                    for (BulkActionItem item : itemsToDelete) {
                        failedKeys.add(item.type.toLowerCase() + ":" + item.id);
                        errors.add("Batch delete failed for " + item.type + " " + item.id + ": " + e.getMessage());
                    }
                }
            }
        }

        return new BulkActionResponse(successCount, errors, failedKeys);
    }

    /** These two WriteBatch paths bypass the repositories, so they tell the search index themselves (spec §5.2). */
    private void publishCatalogChanged(List<BulkActionItem> written, boolean writeFailed) {
        for (BulkActionItem item : written) {
            events.publishEvent(new CatalogChanged(item.type.toUpperCase(Locale.ROOT), item.id, writeFailed));
        }
    }

    /**
     * Maps content type to Firestore collection name.
     */
    private String getCollectionName(String type) {
        switch (type.toLowerCase()) {
            case "channel": return "channels";
            case "playlist": return "playlists";
            case "video": return "videos";
            default: throw new IllegalArgumentException("Invalid type: " + type);
        }
    }

    /**
     * Bulk approve content items.
     *
     * Uses Firestore batch writes for atomic commits within each batch (up to 500 items per batch).
     * Each batch either fully succeeds or fully fails, preventing partial commits.
     */
    @PostMapping("/bulk/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkActionResponse> bulkApprove(@NotNull @Valid @RequestBody BulkActionRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        String username = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        log.info("Bulk approve started: user={}, itemCount={}", username, request.items.size());

        writeVerifiedChannels(liveCategoryGuard.checkAll(true, liveChanges(request.items, null, "APPROVED")));   // row 13
        BulkActionResponse response = executeBulkStatusUpdate(request.items, "APPROVED", "approving");

        // Add newly approved items to category sort order (only for items that succeeded)
        if (response.successCount > 0) {
            for (BulkActionItem item : request.items) {
                String key = item.type.toLowerCase() + ":" + item.id;
                if (response.failedKeys.contains(key)) continue;
                try {
                    List<String> catIds = getCategoryIdsForItem(item.type, item.id);
                    if (catIds != null) {
                        for (String categoryId : catIds) {
                            sortOrderService.addContentToCategory(categoryId, item.id, item.type.toLowerCase());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to add sort order for {} {}: {}", item.type, item.id, e.getMessage());
                }
            }
        }
        publicContentCacheService.evictPublicContentCaches();

        log.info("Bulk approve completed: user={}, successCount={}, errorCount={}",
                username, response.successCount, response.errors.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Bulk reject content items.
     *
     * Uses Firestore batch writes for atomic commits within each batch (up to 500 items per batch).
     * Each batch either fully succeeds or fully fails, preventing partial commits.
     */
    @PostMapping("/bulk/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkActionResponse> bulkReject(@NotNull @Valid @RequestBody BulkActionRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        String username = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        log.info("Bulk reject started: user={}, itemCount={}", username, request.items.size());

        BulkActionResponse response = executeBulkStatusUpdate(request.items, "REJECTED", "rejecting");

        // Remove rejected items from category sort order (only for items that succeeded)
        if (response.successCount > 0) {
            for (BulkActionItem item : request.items) {
                String key = item.type.toLowerCase() + ":" + item.id;
                if (response.failedKeys.contains(key)) continue;
                try {
                    sortOrderService.removeContentFromAllCategories(item.id, item.type.toLowerCase());
                } catch (Exception e) {
                    log.warn("Failed to remove sort order for {} {}: {}", item.type, item.id, e.getMessage());
                }
            }
        }
        publicContentCacheService.evictPublicContentCaches();

        log.info("Bulk reject completed: user={}, successCount={}, errorCount={}",
                username, response.successCount, response.errors.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Send content back to the review queue.
     *
     * <p>The dashboard's "Mark as Pending" had no endpoint of its own and was wired to bulk
     * reject, so a button that says it returns an item to the queue recorded it as rejected
     * instead — and once rejecting began clearing content from people's phones, that button
     * would have taken it off every device holding it.
     *
     * <p>Deliberately fans nothing out. Sending something back for review is not a decision about
     * it, and the availability gate already withholds pending content from playback.
     */
    @PostMapping("/bulk/pending")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkActionResponse> bulkMarkPending(@NotNull @Valid @RequestBody BulkActionRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        String username = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        log.info("Bulk mark-pending started: user={}, itemCount={}", username, request.items.size());

        BulkActionResponse response = executeBulkStatusUpdate(request.items, "PENDING", "marking pending");

        // Out of the public listings while it is under review again.
        if (response.successCount > 0) {
            for (BulkActionItem item : request.items) {
                if (response.failedKeys.contains(item.type.toLowerCase() + ":" + item.id)) continue;
                try {
                    sortOrderService.removeContentFromAllCategories(item.id, item.type.toLowerCase());
                } catch (Exception e) {
                    log.warn("Failed to remove sort order for {} {}: {}", item.type, item.id, e.getMessage());
                }
            }
        }
        publicContentCacheService.evictPublicContentCaches();

        log.info("Bulk mark-pending completed: user={}, successCount={}, errorCount={}",
                username, response.successCount, response.errors.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Bulk delete content items.
     *
     * Uses Firestore batch writes for atomic commits within each batch (up to 500 items per batch).
     * Each batch either fully succeeds or fully fails, preventing partial commits.
     */
    @PostMapping("/bulk/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkActionResponse> bulkDelete(@NotNull @Valid @RequestBody BulkActionRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        String username = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        log.info("Bulk delete started: user={}, itemCount={}", username, request.items.size());

        BulkActionResponse response = executeBulkDeleteOperation(request.items, item -> {
            switch (item.type.toLowerCase()) {
                case "channel":
                    return channelRepository.findById(item.id).map(Channel::getYoutubeId);
                case "playlist":
                    return playlistRepository.findById(item.id).map(Playlist::getYoutubeId);
                case "video":
                    return videoRepository.findById(item.id).map(Video::getYoutubeId);
                default:
                    throw new IllegalArgumentException("Invalid type: " + item.type);
            }
        }, "deleting");

        // Clean up sort order entries only for items that were actually deleted
        // (i.e., items that no longer exist in the repository)
        if (response.successCount > 0) {
            for (BulkActionItem item : request.items) {
                String key = item.type.toLowerCase() + ":" + item.id;
                if (response.failedKeys.contains(key)) continue; // Skip items that failed to delete
                try {
                    sortOrderService.removeContentFromAllCategories(item.id, item.type.toLowerCase());
                } catch (Exception e) {
                    log.warn("Failed to clean up sort order for {} {}: {}", item.type, item.id, e.getMessage());
                }
            }
        }
        publicContentCacheService.evictPublicContentCaches();

        log.info("Bulk delete completed: user={}, successCount={}, errorCount={}",
                username, response.successCount, response.errors.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Bulk assign categories to content items
     */
    @PostMapping("/bulk/assign-categories")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkActionResponse> bulkAssignCategories(@NotNull @Valid @RequestBody BulkCategoryAssignmentRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        writeVerifiedChannels(liveCategoryGuard.checkAll(true, liveChanges(request.items, request.categoryIds, null))); // row 12
        int successCount = 0;
        List<String> errors = new ArrayList<>();

        for (BulkActionItem item : request.items) {
            try {
                List<String> oldCategoryIds = null;
                String contentType = item.type.toLowerCase();
                boolean isApproved = false;

                switch (contentType) {
                    case "channel":
                        Channel channel = channelRepository.findById(item.id).orElse(null);
                        if (channel != null) {
                            oldCategoryIds = channel.getCategoryIds();
                            isApproved = "APPROVED".equals(channel.getStatus());
                            channel.setCategoryIds(request.categoryIds);
                            channelRepository.save(channel);
                            successCount++;
                        } else {
                            errors.add("Channel not found: " + item.id);
                        }
                        break;
                    case "playlist":
                        Playlist playlist = playlistRepository.findById(item.id).orElse(null);
                        if (playlist != null) {
                            oldCategoryIds = playlist.getCategoryIds();
                            isApproved = "APPROVED".equals(playlist.getStatus());
                            playlist.setCategoryIds(request.categoryIds);
                            playlistRepository.save(playlist);
                            successCount++;
                        } else {
                            errors.add("Playlist not found: " + item.id);
                        }
                        break;
                    case "video":
                        Video video = videoRepository.findById(item.id).orElse(null);
                        if (video != null) {
                            oldCategoryIds = video.getCategoryIds();
                            isApproved = "APPROVED".equals(video.getStatus());
                            video.setCategoryIds(request.categoryIds);
                            videoRepository.save(video);
                            successCount++;
                        } else {
                            errors.add("Video not found: " + item.id);
                        }
                        break;
                    default:
                        errors.add("Invalid type: " + item.type);
                        continue;
                }

                // Sync sort order for approved items: remove from old categories, add to new
                if (isApproved && oldCategoryIds != null) {
                    Set<String> oldSet = new HashSet<>(oldCategoryIds);
                    Set<String> newSet = new HashSet<>(request.categoryIds != null ? request.categoryIds : List.of());
                    // Remove from categories no longer assigned
                    for (String oldCat : oldSet) {
                        if (!newSet.contains(oldCat)) {
                            try {
                                sortOrderService.removeContentFromCategory(oldCat, item.id, contentType);
                            } catch (Exception e) {
                                log.warn("Failed to remove sort order for {} {} from category {}: {}", contentType, item.id, oldCat, e.getMessage());
                            }
                        }
                    }
                    // Add to newly assigned categories
                    for (String newCat : newSet) {
                        if (!oldSet.contains(newCat)) {
                            try {
                                sortOrderService.addContentToCategory(newCat, item.id, contentType);
                            } catch (Exception e) {
                                log.warn("Failed to add sort order for {} {} to category {}: {}", contentType, item.id, newCat, e.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                errors.add("Error assigning categories to " + item.type + " " + item.id + ": " + e.getMessage());
            }
        }

        publicContentCacheService.evictPublicContentCaches();
        return ResponseEntity.ok(new BulkActionResponse(successCount, errors));
    }

    /**
     * §7 rows 12–13 (D9): one Change per library item, read from its stored doc (one batched read per type); missing
     * docs are skipped. A non-null {@code newStatus} is row 13's bulk approve, which also forces PUBLIC: a doc that
     * isn't PUBLIC is new to the public listing, so it goes in with no "before" and counts as a publish.
     */
    private List<LiveCategoryGuard.Change> liveChanges(List<BulkActionItem> items, List<String> newIds, String newStatus)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, Channel> channels = channelRepository.findAllByIds(idsOfType(items, "channel"));
        Map<String, Playlist> playlists = playlistRepository.findAllByIds(idsOfType(items, "playlist"));
        Map<String, Video> videos = videoRepository.findAllByIds(idsOfType(items, "video"));
        List<LiveCategoryGuard.Change> changes = new ArrayList<>();
        for (BulkActionItem item : items) {
            String type = item.type.toLowerCase(Locale.ROOT);
            String ref = type + ":" + item.id;
            switch (type) {
                case "channel" -> {
                    Channel c = channels.get(item.id);
                    if (c != null) changes.add(LiveCategoryGuard.Change.of(ref, c, before(c, c.getVisibility(), newStatus),
                            newIds != null ? newIds : c.getCategoryIds(), newStatus != null ? newStatus : c.getStatus()));
                }
                case "playlist" -> {
                    Playlist p = playlists.get(item.id);
                    if (p != null) changes.add(LiveCategoryGuard.Change.of(ref, p, before(p, p.getVisibility(), newStatus),
                            newIds != null ? newIds : p.getCategoryIds(), newStatus != null ? newStatus : p.getStatus()));
                }
                case "video" -> {
                    Video v = videos.get(item.id);
                    if (v != null) changes.add(LiveCategoryGuard.Change.of(ref, v, before(v, v.getVisibility(), newStatus),
                            newIds != null ? newIds : v.getCategoryIds(), newStatus != null ? newStatus : v.getStatus()));
                }
                default -> { }
            }
        }
        return changes;
    }

    private static List<String> idsOfType(List<BulkActionItem> items, String type) {
        return items.stream().filter(i -> type.equalsIgnoreCase(i.type)).map(i -> i.id).distinct().toList();
    }

    /** The doc as its own "before", or null on a status change (bulk approve) of a doc that isn't PUBLIC yet. */
    private static <T> T before(T doc, String visibility, String newStatus) {
        return newStatus != null && !VisibilityPolicy.isPublic(visibility) ? null : doc;
    }

    /** Writes the guard's verified channel ids onto video docs (D13); this path keys them "video:<docId>". */
    private void writeVerifiedChannels(Map<String, String> verified)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, String> byDocId = new LinkedHashMap<>();
        verified.forEach((ref, channel) -> {
            if (ref.startsWith("video:")) byDocId.put(ref.substring("video:".length()), channel);
        });
        liveCategoryGuard.writeVerifiedChannels(byDocId);
    }

    /**
     * Update keywords/tags for a single content item.
     *
     * Keywords are used for improved search accuracy.
     * This is an optional field - can be set to null or empty list.
     *
     * Validation:
     * - Maximum 50 keywords per item
     * - Maximum 100 characters per keyword
     */
    private static final int MAX_KEYWORDS = 50;
    private static final int MAX_KEYWORD_LENGTH = 100;

    @PutMapping("/{type}/{id}/keywords")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateKeywords(
            @PathVariable @NotBlank String type,
            @PathVariable @NotBlank String id,
            @RequestBody KeywordsUpdateRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        log.info("Updating keywords for {} {}: {}", type, id, request.keywords);

        // Normalize keywords: trim whitespace, filter blanks, dedupe, then validate bounds
        List<String> normalizedKeywords = null;
        if (request.keywords != null) {
            normalizedKeywords = request.keywords.stream()
                    .filter(k -> k != null)
                    .map(String::trim)
                    .filter(k -> !k.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());

            // Validate bounds after normalization
            if (normalizedKeywords.size() > MAX_KEYWORDS) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Too many keywords",
                        "message", "Maximum " + MAX_KEYWORDS + " keywords allowed, got " + normalizedKeywords.size() + " (after deduplication)"
                ));
            }
            for (int i = 0; i < normalizedKeywords.size(); i++) {
                String keyword = normalizedKeywords.get(i);
                if (keyword.length() > MAX_KEYWORD_LENGTH) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Keyword too long",
                            "message", "Keyword '" + keyword.substring(0, Math.min(20, keyword.length())) + "...' exceeds " + MAX_KEYWORD_LENGTH + " characters (length: " + keyword.length() + ")"
                    ));
                }
            }
        }

        try {
            switch (type.toLowerCase()) {
                case "channel":
                    Channel channel = channelRepository.findById(id).orElse(null);
                    if (channel == null) {
                        return ResponseEntity.notFound().build();
                    }
                    channel.setKeywords(normalizedKeywords);
                    channel.touch();
                    channelRepository.save(channel);
                    publicContentCacheService.evictPublicContentCaches();
                    return ResponseEntity.ok(Map.of("success", true, "keywords", normalizedKeywords != null ? normalizedKeywords : List.of()));

                case "playlist":
                    Playlist playlist = playlistRepository.findById(id).orElse(null);
                    if (playlist == null) {
                        return ResponseEntity.notFound().build();
                    }
                    playlist.setKeywords(normalizedKeywords);
                    playlist.touch();
                    playlistRepository.save(playlist);
                    publicContentCacheService.evictPublicContentCaches();
                    return ResponseEntity.ok(Map.of("success", true, "keywords", normalizedKeywords != null ? normalizedKeywords : List.of()));

                case "video":
                    Video video = videoRepository.findById(id).orElse(null);
                    if (video == null) {
                        return ResponseEntity.notFound().build();
                    }
                    video.setKeywords(normalizedKeywords);
                    video.touch();
                    videoRepository.save(video);
                    publicContentCacheService.evictPublicContentCaches();
                    return ResponseEntity.ok(Map.of("success", true, "keywords", normalizedKeywords != null ? normalizedKeywords : List.of()));

                default:
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid type: " + type));
            }
        } catch (Exception e) {
            log.error("Error updating keywords for {} {}", type, id, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to update keywords. Please try again."));
        }
    }

    public static class KeywordsUpdateRequest {
        public List<String> keywords;
    }

    /**
     * Bulk reorder content items by updating their displayOrder.
     *
     * <p>Request body should contain a list of items with their new displayOrder values.
     * Uses Firestore batch writes for atomic commits within each batch (up to 500 operations).
     * If total operations exceed 500, items are processed in multiple atomic batches.
     * Each batch either fully succeeds or fully fails, preventing partial commits within a batch.
     *
     * <h3>TOCTOU Race Condition Handling</h3>
     * <p>This endpoint uses a two-phase approach: validation then commit. A Time-Of-Check-Time-Of-Use
     * (TOCTOU) race condition exists where documents validated in Phase 1 may be deleted by another
     * process before Phase 2 commits. This is handled as follows:
     *
     * <ul>
     *   <li><b>400 Bad Request</b>: Returned when documents are confirmed missing. This includes:
     *       (1) documents not found during initial validation, and (2) documents deleted between
     *       validation and commit (detected via Firestore's "NOT_FOUND" error on update).</li>
     *   <li><b>503 Service Unavailable</b>: Returned for transient infrastructure failures
     *       (timeouts, network errors). Clients should retry with exponential backoff.</li>
     * </ul>
     *
     * <h3>Client Retry Semantics</h3>
     * <ul>
     *   <li>On 400: Do NOT retry automatically. Refresh the item list from the server and
     *       resubmit only existing items.</li>
     *   <li>On 503: Safe to retry. The operation is idempotent (setting displayOrder to the
     *       same value is a no-op).</li>
     * </ul>
     *
     * <h3>Example request:</h3>
     * <pre>
     * {
     *   "items": [
     *     {"type": "channel", "id": "abc123", "displayOrder": 0},
     *     {"type": "channel", "id": "def456", "displayOrder": 1},
     *     {"type": "playlist", "id": "ghi789", "displayOrder": 2}
     *   ]
     * }
     * </pre>
     */
    @PostMapping("/bulk/reorder")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkActionResponse> bulkReorder(@NotNull @Valid @RequestBody ReorderRequest request)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        String username = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        log.info("Bulk reorder started: user={}, itemCount={}", username, request.items.size());

        // Group items by type for efficient batch updates
        Map<String, Integer> channelOrders = new HashMap<>();
        Map<String, Integer> playlistOrders = new HashMap<>();
        Map<String, Integer> videoOrders = new HashMap<>();
        List<String> invalidTypes = new ArrayList<>();

        for (ReorderItem item : request.items) {
            switch (item.type.toLowerCase()) {
                case "channel":
                    channelOrders.put(item.id, item.displayOrder);
                    break;
                case "playlist":
                    playlistOrders.put(item.id, item.displayOrder);
                    break;
                case "video":
                    videoOrders.put(item.id, item.displayOrder);
                    break;
                default:
                    invalidTypes.add("Invalid type: " + item.type);
            }
        }

        if (!invalidTypes.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, invalidTypes));
        }

        int totalOperations = channelOrders.size() + playlistOrders.size() + videoOrders.size();
        log.debug("Bulk reorder: {} channels, {} playlists, {} videos (total: {})",
                channelOrders.size(), playlistOrders.size(), videoOrders.size(), totalOperations);

        // Collect all reorder operations into a unified list for atomic batching
        List<ReorderOperation> allOperations = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : channelOrders.entrySet()) {
            allOperations.add(new ReorderOperation("channel", entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<String, Integer> entry : playlistOrders.entrySet()) {
            allOperations.add(new ReorderOperation("playlist", entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<String, Integer> entry : videoOrders.entrySet()) {
            allOperations.add(new ReorderOperation("video", entry.getKey(), entry.getValue()));
        }

        // ATOMIC REJECTION MODEL:
        // Phase 1: Validate ALL items exist without making any mutations
        // If ANY item doesn't exist, reject the entire request with 400
        ReorderValidationResult validation;
        try {
            validation = validateAllReorderOperations(allOperations);
        } catch (TimeoutException e) {
            log.error("Timeout during reorder validation for {} items", allOperations.size());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new BulkActionResponse(0, List.of("Request timed out during validation. Please try again.")));
        }

        // If any items are invalid/missing, reject the entire request with 400
        // CRITICAL: No mutations have occurred at this point
        if (!validation.allValid) {
            log.warn("Bulk reorder rejected: {} items not found", validation.validationErrors.size());
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(0, validation.validationErrors));
        }

        // Phase 2: Execute the reorder (all items validated to exist)
        ReorderExecutionResult execResult = executeValidatedReorderBatch(validation.validOperations);

        log.info("Bulk reorder completed: user={}, successCount={}, missingDocErrors={}, transientErrors={}",
                username, execResult.successCount,
                execResult.missingDocumentErrors.size(), execResult.transientErrors.size());

        // Evict caches if any operations succeeded
        if (execResult.successCount > 0) {
            publicContentCacheService.evictPublicContentCaches();
        }

        // Handle TOCTOU race condition: documents deleted between validation and commit
        // Return 400 Bad Request - client should refresh and resubmit with existing items only
        if (execResult.hasMissingDocumentErrors()) {
            log.warn("TOCTOU race: {} documents were deleted between validation and commit",
                    execResult.missingDocumentErrors.size());
            return ResponseEntity.badRequest()
                    .body(new BulkActionResponse(execResult.successCount, execResult.getAllErrors()));
        }

        // Handle transient errors (timeouts, network issues)
        // Return 503 Service Unavailable - client can retry
        if (execResult.hasTransientErrors()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new BulkActionResponse(execResult.successCount, execResult.transientErrors));
        }

        return ResponseEntity.ok(new BulkActionResponse(execResult.successCount, List.of()));
    }

    /**
     * Internal DTO for reorder operations.
     */
    private static class ReorderOperation {
        final String type;
        final String id;
        final int displayOrder;

        ReorderOperation(String type, String id, int displayOrder) {
            this.type = type;
            this.id = id;
            this.displayOrder = displayOrder;
        }
    }

    /**
     * Result class for atomic reorder validation.
     * Separates validation from execution for atomic rejection semantics.
     */
    private static class ReorderValidationResult {
        final List<ReorderOperation> validOperations;
        final List<String> validationErrors;
        final boolean allValid;

        ReorderValidationResult(List<ReorderOperation> validOperations, List<String> validationErrors) {
            this.validOperations = validOperations;
            this.validationErrors = validationErrors;
            this.allValid = validationErrors.isEmpty();
        }
    }

    /**
     * Result class for reorder execution phase.
     * Distinguishes between document-not-found errors (TOCTOU race) and transient errors.
     */
    private static class ReorderExecutionResult {
        final int successCount;
        final List<String> missingDocumentErrors;  // 400-worthy: documents deleted between validation and commit
        final List<String> transientErrors;        // 503-worthy: timeouts, network errors

        ReorderExecutionResult(int successCount, List<String> missingDocumentErrors, List<String> transientErrors) {
            this.successCount = successCount;
            this.missingDocumentErrors = missingDocumentErrors;
            this.transientErrors = transientErrors;
        }

        boolean hasMissingDocumentErrors() {
            return !missingDocumentErrors.isEmpty();
        }

        boolean hasTransientErrors() {
            return !transientErrors.isEmpty();
        }

        boolean hasAnyErrors() {
            return hasMissingDocumentErrors() || hasTransientErrors();
        }

        List<String> getAllErrors() {
            List<String> allErrors = new ArrayList<>(missingDocumentErrors);
            allErrors.addAll(transientErrors);
            return allErrors;
        }
    }

    /**
     * Validate all reorder operations exist in Firestore and are APPROVED.
     * This is the first phase of atomic rejection: if ANY item is invalid, reject the entire request.
     *
     * <p><b>APPROVED-ONLY ENFORCEMENT:</b> Only APPROVED items can have their displayOrder modified.
     * This prevents confusion where non-visible (PENDING/REJECTED) items affect the display order
     * of approved content shown to end users.
     *
     * @param operations All operations to validate
     * @return Validation result with valid operations and any errors
     */
    private ReorderValidationResult validateAllReorderOperations(List<ReorderOperation> operations)
            throws ExecutionException, InterruptedException, TimeoutException {

        List<ReorderOperation> validOperations = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Process validation in chunks to respect Firestore limits
        for (int i = 0; i < operations.size(); i += FIRESTORE_BATCH_LIMIT) {
            int endIndex = Math.min(i + FIRESTORE_BATCH_LIMIT, operations.size());
            List<ReorderOperation> batch = operations.subList(i, endIndex);

            // Build document references for batch read
            List<DocumentReference> docRefs = new ArrayList<>();
            for (ReorderOperation op : batch) {
                String collectionName = getCollectionName(op.type);
                docRefs.add(firestore.collection(collectionName).document(op.id));
            }

            // Batched read with timeout
            List<DocumentSnapshot> snapshots = firestore.getAll(docRefs.toArray(new DocumentReference[0]))
                    .get(timeoutProperties.getRead(), TimeUnit.SECONDS);

            // Check which documents exist and are APPROVED
            for (int j = 0; j < snapshots.size(); j++) {
                ReorderOperation op = batch.get(j);
                DocumentSnapshot snapshot = snapshots.get(j);
                if (!snapshot.exists()) {
                    errors.add(op.type + " not found: " + op.id);
                } else {
                    // Enforce approved-only reordering
                    String status = snapshot.getString("status");
                    if (!"APPROVED".equals(status)) {
                        errors.add(op.type + " " + op.id + " cannot be reordered: status is " + status + " (must be APPROVED)");
                    } else {
                        validOperations.add(op);
                    }
                }
            }
        }

        return new ReorderValidationResult(validOperations, errors);
    }

    /**
     * Execute reorder operations using Firestore atomic batch writes.
     * PRECONDITION: All operations have been validated to exist.
     *
     * <p>Uses UPDATE-ONLY semantics to prevent lost updates. Detects TOCTOU race conditions
     * where documents are deleted between validation and commit, distinguishing these from
     * transient infrastructure errors.
     *
     * @param operations List of validated operations to execute
     * @return ReorderExecutionResult with success count and categorized errors
     */
    private ReorderExecutionResult executeValidatedReorderBatch(List<ReorderOperation> operations) {
        if (operations.isEmpty()) {
            return new ReorderExecutionResult(0, List.of(), List.of());
        }

        int successCount = 0;
        List<String> missingDocumentErrors = new ArrayList<>();
        List<String> transientErrors = new ArrayList<>();

        // Process in chunks to respect Firestore batch limit (500 operations)
        for (int i = 0; i < operations.size(); i += FIRESTORE_BATCH_LIMIT) {
            int endIndex = Math.min(i + FIRESTORE_BATCH_LIMIT, operations.size());
            List<ReorderOperation> batch = operations.subList(i, endIndex);

            WriteBatch writeBatch = firestore.batch();
            try {
                for (ReorderOperation op : batch) {
                    String collectionName = getCollectionName(op.type);
                    var docRef = firestore.collection(collectionName).document(op.id);
                    // UPDATE only touches specified fields, preserving all other data
                    writeBatch.update(docRef,
                            "displayOrder", op.displayOrder,
                            "updatedAt", com.google.cloud.Timestamp.now());
                }

                // Atomic commit with timeout - all items in this batch succeed or all fail
                writeBatch.commit().get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                successCount += batch.size();
                log.debug("Reorder batch committed: {} items (batch {}/{})",
                        batch.size(), (i / FIRESTORE_BATCH_LIMIT) + 1,
                        (int) Math.ceil((double) operations.size() / FIRESTORE_BATCH_LIMIT));

            } catch (TimeoutException e) {
                log.error("Timeout committing reorder batch for {} items: items={}",
                        batch.size(), formatOperationsForLog(batch));
                for (ReorderOperation op : batch) {
                    transientErrors.add("Timeout committing " + op.type + " " + op.id);
                }
            } catch (ExecutionException e) {
                // Check if the root cause is a NOT_FOUND error (TOCTOU race condition)
                Throwable cause = e.getCause();
                if (isDocumentNotFoundError(cause)) {
                    // TOCTOU race: document was deleted between validation and commit
                    log.warn("TOCTOU race detected: document(s) deleted between validation and commit. " +
                            "Batch items: {}", formatOperationsForLog(batch));
                    for (ReorderOperation op : batch) {
                        missingDocumentErrors.add("Document deleted during operation: " + op.type + " " + op.id +
                                " (was valid during validation but missing at commit time)");
                    }
                } else {
                    // Other execution errors are transient
                    log.error("Reorder batch commit failed for {} items: {}. Items: {}",
                            batch.size(), e.getMessage(), formatOperationsForLog(batch));
                    for (ReorderOperation op : batch) {
                        transientErrors.add("Batch commit failed for " + op.type + " " + op.id + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                // Batch failed - Firestore ensures no partial commits within the batch
                log.error("Reorder batch commit failed for {} items: {}. Items: {}",
                        batch.size(), e.getMessage(), formatOperationsForLog(batch));
                for (ReorderOperation op : batch) {
                    transientErrors.add("Batch commit failed for " + op.type + " " + op.id + ": " + e.getMessage());
                }
            }
        }

        return new ReorderExecutionResult(successCount, missingDocumentErrors, transientErrors);
    }

    /**
     * Check if an exception indicates a document was not found (TOCTOU race condition).
     * Firestore throws StatusRuntimeException with NOT_FOUND status when updating a non-existent document.
     */
    private boolean isDocumentNotFoundError(Throwable cause) {
        if (cause == null) {
            return false;
        }
        // Check for Firestore/gRPC NOT_FOUND status
        if (cause instanceof io.grpc.StatusRuntimeException) {
            io.grpc.StatusRuntimeException grpcEx = (io.grpc.StatusRuntimeException) cause;
            return grpcEx.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND;
        }
        // Also check the message for "NOT_FOUND" as a fallback
        String message = cause.getMessage();
        if (message != null && message.contains("NOT_FOUND")) {
            return true;
        }
        // Check nested cause
        return isDocumentNotFoundError(cause.getCause());
    }

    /**
     * Format operations for logging - includes type and ID for each operation.
     */
    private String formatOperationsForLog(List<ReorderOperation> operations) {
        return operations.stream()
                .map(op -> op.type + ":" + op.id)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    // Bulk action DTOs

    public static class ReorderRequest {
        @NotNull(message = "Items list cannot be null")
        @NotEmpty(message = "Items list cannot be empty")
        public List<@NotNull @Valid ReorderItem> items;
    }

    public static class ReorderItem {
        @NotNull(message = "Content type cannot be null")
        @NotBlank(message = "Content type cannot be blank")
        public String type; // channel, playlist, or video

        @NotNull(message = "Content ID cannot be null")
        @NotBlank(message = "Content ID cannot be blank")
        public String id;

        @NotNull(message = "Display order cannot be null")
        @Min(value = 0, message = "Display order must be >= 0")
        public Integer displayOrder;
    }

    public static class BulkActionRequest {
        @NotNull(message = "Items list cannot be null")
        @NotEmpty(message = "Items list cannot be empty")
        public List<@NotNull @Valid BulkActionItem> items;
    }

    public static class BulkActionItem {
        @NotNull(message = "Content type cannot be null")
        @NotBlank(message = "Content type cannot be blank")
        public String type; // channel, playlist, or video

        @NotNull(message = "Content ID cannot be null")
        @NotBlank(message = "Content ID cannot be blank")
        public String id;
    }

    public static class BulkCategoryAssignmentRequest {
        @NotNull(message = "Items list cannot be null")
        @NotEmpty(message = "Items list cannot be empty")
        public List<@NotNull @Valid BulkActionItem> items;

        @NotNull(message = "Category IDs list cannot be null")
        public List<String> categoryIds;
    }

    public static class BulkActionResponse {
        public int successCount;
        public List<String> errors;
        @JsonIgnore
        public Set<String> failedKeys;

        public BulkActionResponse(int successCount, List<String> errors) {
            this(successCount, errors, Set.of());
        }

        public BulkActionResponse(int successCount, List<String> errors, Set<String> failedKeys) {
            this.successCount = successCount;
            this.errors = errors;
            this.failedKeys = failedKeys != null ? failedKeys : Set.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tag Enrichment
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enrich content with multilingual search tags.
     *
     * Generates keywords from category mappings, title/description extraction,
     * YouTube tags (when real YouTube IDs exist), and cross-language translations.
     *
     * @param type          Optional: "channel", "playlist", or "video" (null = all types)
     * @param force         If true, re-enrich items that already have keywords
     * @param fetchYouTube  If true, fetch tags from YouTube for items with real YouTube IDs
     * @return Enrichment statistics
     */
    @PostMapping("/enrich-tags")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> enrichTags(
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "false") boolean force,
            @RequestParam(required = false, defaultValue = "false") boolean fetchYouTube
    ) {
        log.info("Tag enrichment requested: type={}, force={}, fetchYouTube={}", type, force, fetchYouTube);

        try {
            if (type != null && !type.isEmpty()) {
                TagEnrichmentService.EnrichmentResult result;
                switch (type.toLowerCase()) {
                    case "channel":
                        result = tagEnrichmentService.enrichChannels(force, fetchYouTube);
                        break;
                    case "playlist":
                        result = tagEnrichmentService.enrichPlaylists(force, fetchYouTube);
                        break;
                    case "video":
                        result = tagEnrichmentService.enrichVideos(force, fetchYouTube);
                        break;
                    default:
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Invalid type",
                                "message", "Type must be one of: channel, playlist, video"
                        ));
                }
                publicContentCacheService.evictPublicContentCaches();
                return ResponseEntity.ok(result.toMap());
            }

            TagEnrichmentService.EnrichmentResult result =
                    tagEnrichmentService.enrichAllContent(force, fetchYouTube);
            publicContentCacheService.evictPublicContentCaches();
            return ResponseEntity.ok(result.toMap());

        } catch (Exception e) {
            log.error("Tag enrichment failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Enrichment failed",
                    "message", "Internal server error during enrichment"
            ));
        }
    }
}
